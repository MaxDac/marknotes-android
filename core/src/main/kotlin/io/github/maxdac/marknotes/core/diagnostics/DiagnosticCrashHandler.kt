package io.github.maxdac.marknotes.core.diagnostics

/** The synchronous emergency path must never prevent Android's existing fatal-exception handling. */
class DiagnosticCrashHandler(
    private val record: (Throwable) -> Unit,
    private val previous: Thread.UncaughtExceptionHandler
) : Thread.UncaughtExceptionHandler {
    override fun uncaughtException(thread: Thread, failure: Throwable) {
        try {
            record(failure)
        } finally {
            previous.uncaughtException(thread, failure)
        }
    }
}
