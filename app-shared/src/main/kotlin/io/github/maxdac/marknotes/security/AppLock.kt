package io.github.maxdac.marknotes.security

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * When the optional app lock hides the notes. Pure logic, without Android APIs: `MainActivity` feeds it the
 * lifecycle, the user's interactions and screen-off events, and shows the lock screen while [locked].
 *
 * - The app starts locked (cold start) when the lock is enabled.
 * - It locks after [timeoutMillis] without interaction, checked when the app comes back to the foreground and by a
 *   timer while it is in the foreground. A timeout of 0 ("Immediately") locks as soon as the app leaves the
 *   foreground instead.
 * - It locks when the screen turns off.
 * - Leaving the app for the biometric or device-credential prompt does not lock it ([beginAuthentication]).
 *
 * @param clock monotonic milliseconds that keep counting while the device sleeps (`SystemClock.elapsedRealtime`).
 */
class AppLock(
    private val enabled: () -> Boolean,
    private val timeoutMillis: () -> Long,
    private val clock: () -> Long
) {
    private val _locked = MutableStateFlow(enabled())
    val locked: StateFlow<Boolean> = _locked.asStateFlow()

    private var lastActiveAt = clock()
    private var promptPending = _locked.value

    /** Whether a biometric or device-credential prompt is showing. */
    var authenticating = false
        private set

    /** The user touched or typed in the app. */
    fun onUserInteraction() {
        if (!_locked.value) lastActiveAt = clock()
    }

    /** The app comes to the foreground: locks it if it was left alone for longer than the timeout. */
    fun onStart() {
        checkIdle()
    }

    /** The app leaves the foreground (not for a configuration change). */
    fun onStop() {
        if (!authenticating && timeoutMillis() <= 0) lock()
    }

    fun onScreenOff() {
        lock()
    }

    /** Locks the app if it has been idle for the timeout; returns whether it is locked. */
    fun checkIdle(): Boolean {
        val timeout = timeoutMillis()
        if (!authenticating && timeout > 0 && clock() - lastActiveAt >= timeout) lock()
        return _locked.value
    }

    /** Milliseconds until the app locks for inactivity, if nothing happens in between. */
    fun millisUntilIdleLock(): Long = timeoutMillis() - (clock() - lastActiveAt)

    /** Locks now ("Lock now", screen off, timeout); does nothing while the lock is disabled. */
    fun lock() {
        if (!enabled() || _locked.value) return
        _locked.value = true
        promptPending = true
    }

    /**
     * Whether the unlock prompt should be shown by itself, once per locking (on cold start or when coming back to a
     * locked app). Later prompts are opened from the lock screen's Unlock button, so cancelling one never loops.
     */
    fun consumePromptRequest(): Boolean {
        val pending = promptPending && _locked.value
        promptPending = false
        return pending
    }

    /** Marks a prompt as showing; returns false if one already is. */
    fun beginAuthentication(): Boolean {
        if (authenticating) return false
        authenticating = true
        return true
    }

    /** The prompt closed; a successful one unlocks the app. */
    fun endAuthentication(success: Boolean) {
        authenticating = false
        if (success) {
            _locked.value = false
            promptPending = false
            lastActiveAt = clock()
        }
    }

    /** The lock was turned on or off in the settings, after a successful prompt: the app stays open. */
    fun onEnabledChanged() {
        _locked.value = false
        promptPending = false
        lastActiveAt = clock()
    }
}
