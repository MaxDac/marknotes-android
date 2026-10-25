package io.github.maxdac.marknotes.sync

import io.github.maxdac.marknotes.sync.ReconnectSync.Companion.EXHAUSTED_TEXT
import io.github.maxdac.marknotes.sync.ReconnectSync.Companion.PENDING_TEXT
import io.github.maxdac.marknotes.sync.ReconnectSync.Companion.RUNNING_TEXT
import io.github.maxdac.marknotes.sync.ReconnectSync.Companion.STOPPED_TEXT
import io.github.maxdac.marknotes.sync.ReconnectSync.Companion.WAITING_TEXT
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReconnectSyncTest {
    private class Harness(val test: TestScope, results: List<ReconnectResult> = emptyList()) {
        private val queue = ArrayDeque(results)
        val attempts = mutableListOf<Long>()
        var gate: CompletableDeferred<Unit>? = null
        val sync = ReconnectSync(test.backgroundScope, { test.testScheduler.currentTime }) {
            attempts += test.testScheduler.currentTime
            gate?.await()
            queue.removeFirstOrNull() ?: ReconnectResult.Complete
        }

        /** Enabled, open, unlocked and online (the baseline). */
        fun ready() = sync.update(enabled = true, online = true, foreground = true, unlocked = true)

        fun reconnect() {
            sync.update(online = false)
            sync.update(online = true)
        }

        fun advance(millis: Long) {
            test.advanceTimeBy(millis)
            test.runCurrent()
        }
    }

    @Test
    fun theFirstNetworkReportIsOnlyABaseline() = runTest {
        val h = Harness(this)
        h.ready()
        h.advance(60_000)
        assertEquals(emptyList<Long>(), h.attempts)
        assertNull(h.sync.status.value)
    }

    @Test
    fun syncsTwoSecondsAfterTheNetworkComesBack() = runTest {
        val h = Harness(this)
        h.ready()
        h.reconnect()
        assertEquals(PENDING_TEXT, h.sync.status.value)
        h.advance(1_999)
        assertEquals(emptyList<Long>(), h.attempts)
        h.advance(1)
        assertEquals(listOf(2_000L), h.attempts)
        assertNull(h.sync.status.value)
        h.advance(60_000)
        assertEquals(1, h.attempts.size)
    }

    @Test
    fun retriesTemporaryFailuresAfter5_15And30SecondsThenGivesUp() = runTest {
        val h = Harness(this, List(4) { ReconnectResult.Retry() })
        h.ready()
        h.reconnect()
        h.advance(120_000)
        assertEquals(listOf(2_000L, 7_000L, 22_000L, 52_000L), h.attempts)
        assertEquals(EXHAUSTED_TEXT, h.sync.status.value)
    }

    @Test
    fun aRetryAfterSucceedsStopsRetrying() = runTest {
        val h = Harness(this, listOf(ReconnectResult.Retry(), ReconnectResult.Complete))
        h.ready()
        h.reconnect()
        h.advance(120_000)
        assertEquals(listOf(2_000L, 7_000L), h.attempts)
        assertNull(h.sync.status.value)
    }

    @Test
    fun honoursALongerBackOffAskedForByTheDrive() = runTest {
        val h = Harness(this, listOf(ReconnectResult.Retry(afterMillis = 20_000)))
        h.ready()
        h.reconnect()
        h.advance(120_000)
        assertEquals(listOf(2_000L, 22_000L), h.attempts)
    }

    @Test
    fun aPermanentFailureStops() = runTest {
        val h = Harness(this, listOf(ReconnectResult.Stop))
        h.ready()
        h.reconnect()
        h.advance(120_000)
        assertEquals(listOf(2_000L), h.attempts)
        assertEquals(STOPPED_TEXT, h.sync.status.value)
    }

    @Test
    fun anUnexpectedExceptionStops() = runTest {
        val sync = ReconnectSync(backgroundScope, { testScheduler.currentTime }) { error("boom") }
        sync.update(enabled = true, online = true, foreground = true, unlocked = true)
        sync.update(online = false)
        sync.update(online = true)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(STOPPED_TEXT, sync.status.value)
    }

    @Test
    fun aReconnectInTheBackgroundWaitsUntilTheAppIsOpenAgain() = runTest {
        val h = Harness(this)
        h.ready()
        h.sync.update(foreground = false)
        h.reconnect()
        assertEquals(WAITING_TEXT, h.sync.status.value)
        h.advance(30_000)
        assertEquals(emptyList<Long>(), h.attempts)
        h.sync.update(foreground = true)
        h.advance(0)
        assertEquals(listOf(30_000L), h.attempts)
    }

    @Test
    fun aReconnectWhileLockedWaitsUntilUnlocked() = runTest {
        val h = Harness(this)
        h.ready()
        h.sync.update(unlocked = false)
        h.reconnect()
        h.advance(30_000)
        assertEquals(emptyList<Long>(), h.attempts)
        assertEquals(WAITING_TEXT, h.sync.status.value)
        h.sync.update(unlocked = true)
        h.advance(0)
        assertEquals(listOf(30_000L), h.attempts)
    }

    @Test
    fun goingToTheBackgroundBeforeTheDelayPostponesTheSync() = runTest {
        val h = Harness(this)
        h.ready()
        h.reconnect()
        h.advance(1_000)
        h.sync.update(foreground = false)
        h.advance(10_000)
        assertEquals(emptyList<Long>(), h.attempts)
        h.sync.update(foreground = true)
        h.advance(0)
        assertEquals(listOf(11_000L), h.attempts)
    }

    @Test
    fun aFlappingNetworkWaitsUntilItHasBeenBackForTheDelay() = runTest {
        val h = Harness(this)
        h.ready()
        h.reconnect()
        h.advance(1_500)
        h.sync.update(online = false)
        h.advance(300)
        h.sync.update(online = true)
        h.advance(1_999)
        assertEquals(emptyList<Long>(), h.attempts)
        h.advance(1)
        assertEquals(listOf(3_800L), h.attempts)
    }

    @Test
    fun turningTheSettingOffCancelsThePendingSync() = runTest {
        val h = Harness(this)
        h.ready()
        h.reconnect()
        h.sync.update(enabled = false)
        assertNull(h.sync.status.value)
        h.advance(60_000)
        h.sync.update(enabled = true)
        h.advance(60_000)
        assertEquals(emptyList<Long>(), h.attempts)
    }

    @Test
    fun nothingHappensWhileTheSettingIsOff() = runTest {
        val h = Harness(this)
        h.sync.update(online = true, foreground = true, unlocked = true)
        h.reconnect()
        h.advance(60_000)
        assertEquals(emptyList<Long>(), h.attempts)
        assertNull(h.sync.status.value)
    }

    @Test
    fun syncNowCancelsThePendingSync() = runTest {
        val h = Harness(this)
        h.ready()
        h.reconnect()
        h.sync.cancel()
        h.advance(60_000)
        assertEquals(emptyList<Long>(), h.attempts)
        assertNull(h.sync.status.value)
    }

    @Test
    fun cancellingDoesNotInterruptARunningSyncAndDropsItsRetries() = runTest {
        val h = Harness(this, listOf(ReconnectResult.Retry()))
        h.gate = CompletableDeferred()
        h.ready()
        h.reconnect()
        h.advance(2_000)
        assertEquals(RUNNING_TEXT, h.sync.status.value)
        h.sync.cancel()
        h.gate!!.complete(Unit)
        h.advance(60_000)
        assertEquals(listOf(2_000L), h.attempts)
        assertNull(h.sync.status.value)
    }

    @Test
    fun closingTheAppForgetsTheNetworkSoReopeningIsNotAReconnect() = runTest {
        val h = Harness(this)
        h.ready()
        h.sync.update(online = false)
        h.sync.reset()
        h.sync.update(online = true, foreground = true)
        h.advance(60_000)
        assertEquals(emptyList<Long>(), h.attempts)
    }
}
