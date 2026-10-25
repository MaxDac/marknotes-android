package io.github.maxdac.marknotes.ui.help

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GettingStartedPolicyTest {
    @Test
    fun unseenInstallationsAndExplicitReplayAreEligible() {
        assertTrue(visible())
        assertTrue(visible(consumed = true, replay = true))
        assertFalse(visible(consumed = true))
    }

    @Test
    fun interruptionsDeferBothAutomaticGuideAndReplay() {
        for (replay in listOf(false, true)) {
            assertFalse(visible(replay = replay, unlocked = false))
            assertFalse(visible(replay = replay, resumed = false))
            assertFalse(visible(replay = replay, safeDestination = false))
            assertFalse(visible(replay = replay, pendingShare = true))
            assertTrue(visible(replay = replay))
        }
    }

    private fun visible(
        consumed: Boolean = false,
        replay: Boolean = false,
        unlocked: Boolean = true,
        resumed: Boolean = true,
        safeDestination: Boolean = true,
        pendingShare: Boolean = false
    ) = GettingStartedPolicy.shouldShow(consumed, replay, unlocked, resumed, safeDestination, pendingShare)
}
