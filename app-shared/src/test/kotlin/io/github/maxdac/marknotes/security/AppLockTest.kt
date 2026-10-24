package io.github.maxdac.marknotes.security

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {
    private var now = 1_000L
    private var enabled = true
    private var timeout = 5 * 60_000L

    private fun appLock() = AppLock(enabled = { enabled }, timeoutMillis = { timeout }, clock = { now })

    private fun AppLock.unlock() {
        assertTrue(beginAuthentication())
        endAuthentication(success = true)
    }

    @Test
    fun startsLockedOnlyWhenEnabled() {
        assertTrue(appLock().locked.value)
        enabled = false
        assertFalse(appLock().locked.value)
    }

    @Test
    fun coldStartAsksForTheUnlockPromptOnce() {
        val lock = appLock()
        assertTrue(lock.consumePromptRequest())
        assertFalse(lock.consumePromptRequest())
    }

    @Test
    fun locksAfterTheIdleTimeout() {
        val lock = appLock()
        lock.unlock()
        now += timeout - 1
        assertFalse(lock.checkIdle())
        assertEquals(1L, lock.millisUntilIdleLock())
        now += 1
        assertTrue(lock.checkIdle())
    }

    @Test
    fun interactionRestartsTheIdleTimer() {
        val lock = appLock()
        lock.unlock()
        now += timeout - 1
        lock.onUserInteraction()
        now += timeout - 1
        assertFalse(lock.checkIdle())
        now += 1
        assertTrue(lock.checkIdle())
    }

    @Test
    fun timeInTheBackgroundCountsAndLocksOnStart() {
        val lock = appLock()
        lock.unlock()
        lock.onStop()
        assertFalse(lock.locked.value)
        now += timeout
        lock.onStart()
        assertTrue(lock.locked.value)
        assertTrue(lock.consumePromptRequest())
    }

    @Test
    fun shortTripToTheBackgroundStaysUnlocked() {
        val lock = appLock()
        lock.unlock()
        lock.onStop()
        now += 10_000
        lock.onStart()
        assertFalse(lock.locked.value)
    }

    @Test
    fun immediatelyLocksWhenLeavingTheAppButNotWhileInUse() {
        timeout = 0
        val lock = appLock()
        lock.unlock()
        now += 60 * 60_000L
        assertFalse(lock.checkIdle())
        lock.onStop()
        assertTrue(lock.locked.value)
    }

    @Test
    fun thePromptLeavingTheAppDoesNotLockIt() {
        timeout = 0
        val lock = appLock()
        lock.unlock()
        lock.lock()
        assertTrue(lock.beginAuthentication())
        assertFalse(lock.beginAuthentication())
        lock.onStop()
        lock.onStart()
        lock.endAuthentication(success = true)
        assertFalse(lock.locked.value)
    }

    @Test
    fun cancelledPromptStaysLockedAndIsNotShownAgainByItself() {
        val lock = appLock()
        assertTrue(lock.consumePromptRequest())
        assertTrue(lock.beginAuthentication())
        lock.endAuthentication(success = false)
        assertTrue(lock.locked.value)
        assertFalse(lock.authenticating)
        assertFalse(lock.consumePromptRequest())
    }

    @Test
    fun screenOffLocks() {
        val lock = appLock()
        lock.unlock()
        lock.onScreenOff()
        assertTrue(lock.locked.value)
    }

    @Test
    fun interactionWhileLockedDoesNotUnlockOrRestartTheTimer() {
        val lock = appLock()
        lock.unlock()
        lock.lock()
        now += timeout
        lock.onUserInteraction()
        assertTrue(lock.locked.value)
    }

    @Test
    fun disabledLockNeverLocks() {
        enabled = false
        val lock = appLock()
        now += 60 * 60_000L
        lock.onStart()
        lock.onStop()
        lock.onScreenOff()
        lock.lock()
        assertFalse(lock.locked.value)
        assertFalse(lock.consumePromptRequest())
    }

    @Test
    fun turningTheLockOnOrOffKeepsTheAppOpen() {
        enabled = false
        val lock = appLock()
        now += 60 * 60_000L
        enabled = true
        lock.onEnabledChanged()
        assertFalse(lock.locked.value)
        assertFalse(lock.checkIdle())

        lock.lock()
        enabled = false
        lock.onEnabledChanged()
        assertFalse(lock.locked.value)
    }
}
