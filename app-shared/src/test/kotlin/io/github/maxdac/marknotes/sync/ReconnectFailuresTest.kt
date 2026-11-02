package io.github.maxdac.marknotes.sync

import io.github.maxdac.marknotes.core.sync.DriveSyncOutcome
import io.github.maxdac.marknotes.core.sync.MultiDriveSyncResult
import io.github.maxdac.marknotes.core.sync.SyncResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectFailuresTest {
    /** A provider-specific temporary failure, as a connector would recognise it. */
    private class ProviderBusy(val backoff: Long = 0) : IOException("busy")

    private val classify: FailureClassifier = { (it as? ProviderBusy)?.backoff }

    @Test
    fun onlyTemporaryTransportAndProviderFailuresAreRetryable() {
        assertTrue(UnknownHostException("api.example.com").isReconnectRetryable())
        assertTrue(SocketTimeoutException().isReconnectRetryable())
        assertTrue(ProviderBusy().isReconnectRetryable(classify))

        assertFalse(ProviderBusy().isReconnectRetryable())
        assertFalse(IOException("disk full").isReconnectRetryable(classify))
        assertFalse(IllegalStateException("bug").isReconnectRetryable(classify))
    }

    @Test
    fun aRateLimitKeepsTheBackOffTheDriveAskedFor() {
        assertEquals(ReconnectResult.Retry(afterMillis = 60_000), ProviderBusy(60_000).reconnectResult(classify))
        assertEquals(ReconnectResult.Stop, IOException("disk full").reconnectResult(classify))
    }

    @Test
    fun aSyncRetriesOnlyWhenEveryFailureIsTemporaryAndTyped() {
        assertEquals(ReconnectResult.Complete, result(outcome(sync())).reconnectResult(emptyList(), classify))

        val offline = UnknownHostException("offline")
        assertEquals(ReconnectResult.Retry(), result(outcome(sync(offline))).reconnectResult(emptyList(), classify))
        assertEquals(
            ReconnectResult.Retry(),
            result(DriveSyncOutcome("a", null, "offline", offline)).reconnectResult(emptyList(), classify)
        )
        assertEquals(ReconnectResult.Retry(), result(outcome(sync())).reconnectResult(listOf(offline), classify))
        assertEquals(
            ReconnectResult.Retry(30_000),
            result(outcome(sync(offline, ProviderBusy(30_000)))).reconnectResult(emptyList(), classify)
        )

        val denied = IllegalStateException("expired")
        assertEquals(
            ReconnectResult.Stop,
            result(outcome(sync(offline, denied))).reconnectResult(emptyList(), classify)
        )
        assertEquals(ReconnectResult.Stop, result(outcome(sync())).reconnectResult(listOf(denied), classify))

        val untypedDrive = DriveSyncOutcome("a", null, "planning failed")
        assertEquals(ReconnectResult.Stop, result(untypedDrive).reconnectResult(emptyList(), classify))
        val untypedFile = outcome(sync().copy(errors = listOf("planning failed")))
        assertEquals(ReconnectResult.Stop, result(untypedFile).reconnectResult(emptyList(), classify))
    }

    private fun sync(vararg failures: Exception) = SyncResult(
        uploaded = 0,
        downloaded = 0,
        deletedLocal = 0,
        deletedRemote = 0,
        conflicts = emptyList(),
        errors = failures.map { it.message.orEmpty() },
        failures = failures.toList()
    )

    private fun outcome(result: SyncResult) = DriveSyncOutcome("a", result, null)

    private fun result(vararg outcomes: DriveSyncOutcome) = MultiDriveSyncResult(outcomes.toList(), emptyList())
}
