package io.github.maxdac.marknotes.sync

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Whether the default network is connected and validated (it reached the internet), while collected. Emits the
 * current state first, then every change. Needs `ACCESS_NETWORK_STATE`.
 */
internal fun ConnectivityManager.connections(): Flow<Boolean> = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
        private var current: Network? = activeNetwork

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            current = network
            trySend(capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED))
        }

        override fun onLost(network: Network) {
            // A late loss of the previous default network after switching (Wi-Fi to mobile) is not going offline.
            if (current == network) trySend(false)
        }
    }
    val validated = getNetworkCapabilities(activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    trySend(validated == true)
    registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
    awaitClose { unregisterNetworkCallback(callback) }
}.distinctUntilChanged()
