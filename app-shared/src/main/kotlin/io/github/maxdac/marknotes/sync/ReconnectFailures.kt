package io.github.maxdac.marknotes.sync

import com.dropbox.core.NetworkIOException
import com.dropbox.core.RetryException
import com.dropbox.core.ServerException
import io.github.maxdac.marknotes.core.sync.MultiDriveSyncResult
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Whether an automatic reconnect sync may retry after this failure. Only known temporary transport and server
 * failures qualify; sign-in, permission, file and unknown errors stop, so a broken drive is never hammered.
 */
internal fun Exception.isReconnectRetryable(): Boolean = when (this) {
    is NetworkIOException -> cause !is SSLException
    is RetryException, is ServerException -> true
    is ConnectException, is SocketTimeoutException, is UnknownHostException, is SocketException -> true
    else -> false
}

/** The back-off a drive asked for, in milliseconds (Dropbox rate limits and 503s), or 0. */
private fun Exception.backoffMillis(): Long = (this as? RetryException)?.backoffMillis ?: 0L

internal fun Exception.reconnectResult(): ReconnectResult =
    if (isReconnectRetryable()) ReconnectResult.Retry(backoffMillis()) else ReconnectResult.Stop

/**
 * Retry only when every error has a typed, temporary cause; an error without its exception (a planning error)
 * stops. [preparationFailures] are the drives that could not even be opened.
 */
internal fun MultiDriveSyncResult.reconnectResult(preparationFailures: List<Exception>): ReconnectResult {
    val untyped = outcomes.any {
        (it.error != null && it.failure == null) || (it.result?.errors?.size ?: 0) != (it.result?.failures?.size ?: 0)
    }
    val failures = preparationFailures + outcomes.flatMap { listOfNotNull(it.failure) + it.result?.failures.orEmpty() }
    return when {
        untyped -> ReconnectResult.Stop
        failures.isEmpty() -> ReconnectResult.Complete
        failures.all { it.isReconnectRetryable() } -> ReconnectResult.Retry(failures.maxOf { it.backoffMillis() })
        else -> ReconnectResult.Stop
    }
}
