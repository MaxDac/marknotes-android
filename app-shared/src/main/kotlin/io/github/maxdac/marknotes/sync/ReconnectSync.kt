package io.github.maxdac.marknotes.sync

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What one automatic reconnect sync achieved. */
internal sealed interface ReconnectResult {
    /** Synced: nothing left to do. */
    data object Complete : ReconnectResult

    /** Failed for a reason retrying won't fix (sign-in, no drive, a file error): the user syncs by hand. */
    data object Stop : ReconnectResult

    /** A temporary network or server failure; [afterMillis] is the back-off the drive asked for, if any. */
    data class Retry(val afterMillis: Long = 0) : ReconnectResult
}

/**
 * The optional "Sync when reconnected" setting: when the network comes back, sync [RECONNECT_DEBOUNCE_MILLIS] later,
 * retrying temporary failures after [RECONNECT_RETRY_MILLIS], but only while the app is in the foreground, online
 * and unlocked. A reconnect seen while the app is in the background or locked waits until it is open and unlocked
 * again. A port of marknotes-web's `assets/js/reconnect.ts`.
 *
 * It only schedules foreground work: it owns no notes, credentials or persistent queue, and nothing runs once the
 * process is gone. [update] feeds it the conditions; the first network report is a baseline, never a reconnect.
 *
 * @param clock monotonic milliseconds (`SystemClock.elapsedRealtime`).
 */
class ReconnectSync internal constructor(
    private val scope: CoroutineScope,
    private val clock: () -> Long,
    private val attempt: suspend () -> ReconnectResult
) {
    private val lock = Any()
    private var enabled = false
    private var online: Boolean? = null
    private var foreground = false
    private var unlocked = false
    private var pending = false
    private var running = false
    private var attempts = 0
    private var due = 0L
    private var generation = 0
    private var timer: Job? = null

    private val _status = MutableStateFlow<String?>(null)

    /** What the reconnect sync is doing or why it stopped, for Settings; null when there is nothing to say. */
    val status: StateFlow<String?> = _status.asStateFlow()

    /** Updates the conditions given; null leaves one unchanged. */
    fun update(
        enabled: Boolean? = null,
        online: Boolean? = null,
        foreground: Boolean? = null,
        unlocked: Boolean? = null
    ): Unit = synchronized(lock) {
        val wasOnline = this.online
        enabled?.let { this.enabled = it }
        online?.let { this.online = it }
        foreground?.let { this.foreground = it }
        unlocked?.let { this.unlocked = it }
        if (!this.enabled) {
            cancelLocked()
            return
        }
        val now = clock()
        if (wasOnline == false && this.online == true) {
            if (!pending) {
                pending = true
                attempts = 0
                due = now + RECONNECT_DEBOUNCE_MILLIS
            } else {
                due = maxOf(due, now + RECONNECT_DEBOUNCE_MILLIS)
            }
        }
        // A flapping network postpones the attempt until it has been back for the debounce.
        if (wasOnline == true && this.online == false) due = maxOf(due, now + RECONNECT_DEBOUNCE_MILLIS)
        scheduleLocked()
    }

    /** Drops the pending reconnect sync ("Sync now" replaces it); a sync already running finishes. */
    fun cancel(): Unit = synchronized(lock) { cancelLocked() }

    /** The app closed: cancels pending work and forgets the network, so reopening it is not a reconnect. */
    fun reset(): Unit = synchronized(lock) {
        cancelLocked()
        online = null
        foreground = false
    }

    private fun cancelLocked() {
        generation++
        pending = false
        timer?.cancel()
        timer = null
        _status.value = null
    }

    private fun scheduleLocked() {
        timer?.cancel()
        timer = null
        if (!pending || running) return
        if (online != true || !foreground || !unlocked) {
            _status.value = WAITING_TEXT
            return
        }
        _status.value = PENDING_TEXT
        val wait = (due - clock()).coerceAtLeast(0)
        // Started after being stored, so the job can tell whether it is still the current timer.
        timer = scope.launch(start = CoroutineStart.LAZY) {
            delay(wait)
            run()
        }.also { it.start() }
    }

    private suspend fun run() {
        val started = synchronized(lock) {
            if (timer !== coroutineContext[Job]) return
            // From here on, nothing cancels the attempt: a sync is never interrupted halfway.
            timer = null
            running = true
            attempts++
            _status.value = RUNNING_TEXT
            generation
        }
        var result: ReconnectResult = ReconnectResult.Stop
        try {
            result = attempt()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            result = ReconnectResult.Stop
        } finally {
            synchronized(lock) {
                running = false
                if (started == generation) finishLocked(result) else scheduleLocked()
            }
        }
    }

    private fun finishLocked(result: ReconnectResult) {
        when (result) {
            ReconnectResult.Complete -> {
                pending = false
                _status.value = null
            }

            ReconnectResult.Stop -> {
                pending = false
                _status.value = STOPPED_TEXT
            }

            is ReconnectResult.Retry -> {
                val wait = RECONNECT_RETRY_MILLIS.getOrNull(attempts - 1)
                if (wait == null) {
                    pending = false
                    _status.value = EXHAUSTED_TEXT
                    return
                }
                due = clock() + maxOf(wait, result.afterMillis)
                scheduleLocked()
            }
        }
    }

    internal companion object {
        const val RECONNECT_DEBOUNCE_MILLIS = 2_000L
        val RECONNECT_RETRY_MILLIS = listOf(5_000L, 15_000L, 30_000L)

        const val WAITING_TEXT = "Reconnect sync waiting until the app is open, online and unlocked."
        const val PENDING_TEXT = "Reconnect sync pending. Not yet synced."
        const val RUNNING_TEXT = "Automatic reconnect sync in progress."
        const val STOPPED_TEXT = "Automatic sync stopped. Check the drive errors and use Sync now."
        const val EXHAUSTED_TEXT =
            "Automatic sync attempts exhausted. Changes remain local until a successful sync; use Sync now."
    }
}
