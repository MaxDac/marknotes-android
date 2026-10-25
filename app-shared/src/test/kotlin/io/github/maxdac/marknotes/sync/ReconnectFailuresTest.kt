package io.github.maxdac.marknotes.sync

import com.dropbox.core.InvalidAccessTokenException
import com.dropbox.core.NetworkIOException
import com.dropbox.core.RetryException
import com.dropbox.core.ServerException
import io.github.maxdac.marknotes.core.sync.DriveSyncOutcome
import io.github.maxdac.marknotes.core.sync.MultiDriveSyncResult
import io.github.maxdac.marknotes.core.sync.SyncResult
import java.io.IOException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLHandshakeException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReconnectFailuresTest {
    @Test
    fun onlyTemporaryTransportAndServerFailuresAreRetryable() {
        assertTrue(NetworkIOException(IOException("reset")).isReconnectRetryable())
        assertTrue(ServerException("req", "500").isReconnectRetryable())
        assertTrue(RetryException("req", "503").isReconnectRetryable())
        assertTrue(UnknownHostException("api.dropboxapi.com").isReconnectRetryable())
        assertTrue(SocketTimeoutException().isReconnectRetryable())

        assertFalse(NetworkIOException(SSLHandshakeException("bad cert")).isReconnectRetryable())
        assertFalse(InvalidAccessTokenException("req", "expired", null).isReconnectRetryable())
        assertFalse(IOException("disk full").isReconnectRetryable())
        assertFalse(IllegalStateException("bug").isReconnectRetryable())
    }

    @Test
    fun aRateLimitKeepsTheBackOffTheDriveAskedFor() {
        assertEquals(
            ReconnectResult.Retry(afterMillis = 60_000),
            RetryException("req", "429", 60, TimeUnit.SECONDS).reconnectResult()
        )
        assertEquals(ReconnectResult.Stop, IOException("disk full").reconnectResult())
    }

    @Test
    fun aSyncRetriesOnlyWhenEveryFailureIsTemporaryAndTyped() {
        assertEquals(ReconnectResult.Complete, result(outcome(sync())).reconnectResult(emptyList()))

        val offline = UnknownHostException("offline")
        assertEquals(ReconnectResult.Retry(), result(outcome(sync(offline))).reconnectResult(emptyList()))
        assertEquals(
            ReconnectResult.Retry(),
            result(DriveSyncOutcome("a", null, "offline", offline)).reconnectResult(emptyList())
        )
        assertEquals(ReconnectResult.Retry(), result(outcome(sync())).reconnectResult(listOf(offline)))

        val denied = InvalidAccessTokenException("req", "expired", null)
        assertEquals(ReconnectResult.Stop, result(outcome(sync(offline, denied))).reconnectResult(emptyList()))
        assertEquals(ReconnectResult.Stop, result(outcome(sync())).reconnectResult(listOf(denied)))

        val untypedDrive = DriveSyncOutcome("a", null, "planning failed")
        assertEquals(ReconnectResult.Stop, result(untypedDrive).reconnectResult(emptyList()))
        val untypedFile = outcome(sync().copy(errors = listOf("planning failed")))
        assertEquals(ReconnectResult.Stop, result(untypedFile).reconnectResult(emptyList()))
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
