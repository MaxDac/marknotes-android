package io.github.maxdac.marknotes.ui.help

internal object GettingStartedPolicy {
    const val LAST_STEP = 4

    fun shouldShow(
        consumed: Boolean,
        replay: Boolean,
        unlocked: Boolean,
        resumed: Boolean,
        safeDestination: Boolean,
        pendingShare: Boolean
    ): Boolean = (!consumed || replay) && unlocked && resumed && safeDestination && !pendingShare
}
