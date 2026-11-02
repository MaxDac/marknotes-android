package io.github.maxdac.marknotes.sync

import io.github.maxdac.marknotes.core.sync.MultiDriveSyncResult
import java.net.ConnectException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * How a provider classifies a failure: its back-off in milliseconds when the failure is temporary (0 for none),
 * or null when the provider does not recognise it. See [DriveConnector.retryBackoffMillis].
 */
internal typealias FailureClassifier = (Exception) -> Long?

/** Failures every provider shares: the device has no usable connection. */
private fun Exception.isTemporaryNetworkFailure(): Boolean =
    this is ConnectException || this is SocketTimeoutException || this is UnknownHostException ||
        this is SocketException

/**
 * Whether an automatic reconnect sync may retry after this failure. Only known temporary transport and server
 * failures qualify; sign-in, permission, file and unknown errors stop, so a broken drive is never hammered.
 */
internal fun Exception.isReconnectRetryable(classify: FailureClassifier = { null }): Boolean =
    isTemporaryNetworkFailure() || classify(this) != null

/** The back-off a drive asked for, in milliseconds (rate limits and 503s), or 0. */
private fun Exception.backoffMillis(classify: FailureClassifier): Long = classify(this) ?: 0L

internal fun Exception.reconnectResult(classify: FailureClassifier = { null }): ReconnectResult =
    if (isReconnectRetryable(classify)) ReconnectResult.Retry(backoffMillis(classify)) else ReconnectResult.Stop

/**
 * Retry only when every error has a typed, temporary cause; an error without its exception (a planning error)
 * stops. [preparationFailures] are the drives that could not even be opened.
 */
internal fun MultiDriveSyncResult.reconnectResult(
    preparationFailures: List<Exception>,
    classify: FailureClassifier = { null }
): ReconnectResult {
    val untyped = outcomes.any {
        (it.error != null && it.failure == null) || (it.result?.errors?.size ?: 0) != (it.result?.failures?.size ?: 0)
    }
    val failures = preparationFailures + outcomes.flatMap { listOfNotNull(it.failure) + it.result?.failures.orEmpty() }
    return when {
        untyped -> ReconnectResult.Stop

        failures.isEmpty() -> ReconnectResult.Complete

        failures.all { it.isReconnectRetryable(classify) } ->
            ReconnectResult.Retry(failures.maxOf { it.backoffMillis(classify) })

        else -> ReconnectResult.Stop
    }
}
