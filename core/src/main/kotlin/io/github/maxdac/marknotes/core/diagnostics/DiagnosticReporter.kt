package io.github.maxdac.marknotes.core.diagnostics

import java.io.IOException
import java.util.concurrent.CancellationException

/** Only fixed, content-free identifiers may cross the diagnostic boundary. */
enum class DiagnosticCode {
    CRASH,
    STARTUP,
    NOTE_READ,
    NOTE_WRITE,
    NOTE_OPERATION,
    IMAGE_IMPORT,
    CONTENT_IMPORT,
    SIGN_IN,
    FOLDER_LIST,
    SYNC,
    SYNC_STATE,
    SIGN_OUT,
    LOCAL_DATA_DELETE,
    AUTHENTICATION,
    SDK_ERROR
}

enum class FailureKind {
    NONE,
    IO,
    SECURITY,
    INVALID_ARGUMENT,
    INVALID_STATE,
    MEMORY,
    OTHER
}

fun interface DiagnosticReporter {
    fun report(code: DiagnosticCode, failure: Throwable?)

    companion object {
        val NONE = DiagnosticReporter { _, _ -> }
    }
}

fun DiagnosticReporter.report(code: DiagnosticCode) = report(code, null)

/** Never serializes Throwable messages, class names, frames, causes, or other caller-controlled strings. */
data class DiagnosticEvent(val timestamp: Long, val code: DiagnosticCode, val kind: FailureKind) {
    fun line(): String = "$timestamp ${code.name} ${kind.name}"

    companion object {
        fun from(timestamp: Long, code: DiagnosticCode, failure: Throwable?): DiagnosticEvent? {
            if (failure is CancellationException) return null
            val kind = when (failure) {
                null -> FailureKind.NONE
                is IOException -> FailureKind.IO
                is SecurityException -> FailureKind.SECURITY
                is IllegalArgumentException -> FailureKind.INVALID_ARGUMENT
                is IllegalStateException -> FailureKind.INVALID_STATE
                is OutOfMemoryError -> FailureKind.MEMORY
                else -> FailureKind.OTHER
            }
            return DiagnosticEvent(timestamp, code, kind)
        }

        fun parse(line: String): DiagnosticEvent {
            val parts = line.split(' ')
            require(parts.size == 3)
            return DiagnosticEvent(parts[0].toLong(), DiagnosticCode.valueOf(parts[1]), FailureKind.valueOf(parts[2]))
        }
    }
}
