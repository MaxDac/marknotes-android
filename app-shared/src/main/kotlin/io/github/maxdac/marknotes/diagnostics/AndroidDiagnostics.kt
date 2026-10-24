package io.github.maxdac.marknotes.diagnostics

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCrashHandler
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticEvent
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReport
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticStore
import io.github.maxdac.marknotes.core.diagnostics.IssueDraft
import java.io.File
import java.io.IOException
import java.util.IdentityHashMap
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Android adapter; all ordinary disk work is bounded and off the caller's thread. */
class AndroidDiagnostics(context: Context, private val issueRepository: String) : DiagnosticReporter {
    private val context = context.applicationContext
    private val store = DiagnosticStore(
        File(context.noBackupFilesDir, "diagnostics"),
        File(context.cacheDir, "diagnostic-exports")
    )
    private val worker = ThreadPoolExecutor(
        1,
        1,
        0,
        TimeUnit.SECONDS,
        ArrayBlockingQueue(64),
        { runnable -> Thread(runnable, "diagnostics").apply { isDaemon = true } }
    )
    private val _storageFailed = MutableStateFlow(false)
    val storageFailed: StateFlow<Boolean> = _storageFailed
    private val recentFailures = IdentityHashMap<Throwable, Boolean>()

    init {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous != null) {
            Thread.setDefaultUncaughtExceptionHandler(DiagnosticCrashHandler(::recordCrash, previous))
        } else {
            _storageFailed.value = true
        }
        enqueue { store.prune() }
    }

    override fun report(code: DiagnosticCode, failure: Throwable?) {
        val event = DiagnosticEvent.from(System.currentTimeMillis(), code, failure) ?: return
        if (failure != null) {
            synchronized(recentFailures) {
                if (recentFailures.containsKey(failure)) return
                if (recentFailures.size >= 64) recentFailures.clear()
                recentFailures[failure] = true
            }
        }
        val generation = store.generation()
        enqueue { store.append(event, generation) }
    }

    private fun recordCrash(failure: Throwable) {
        // Only this fatal path does synchronous IO. Nothing from the Throwable is rendered.
        try {
            DiagnosticEvent.from(System.currentTimeMillis(), DiagnosticCode.CRASH, failure)?.let { store.append(it) }
        } catch (_: Throwable) {
            // Android's prior fatal handler is always called, even if storage or the heap is exhausted.
            _storageFailed.value = true
        }
    }

    private fun enqueue(action: () -> Unit) {
        try {
            worker.execute {
                try {
                    action()
                } catch (_: IOException) {
                    _storageFailed.value = true
                } catch (_: SecurityException) {
                    _storageFailed.value = true
                }
            }
        } catch (_: RejectedExecutionException) {
            _storageFailed.value = true
        }
    }

    suspend fun draft(): IssueDraft = withContext(Dispatchers.IO) {
        awaitPending()
        @Suppress("DEPRECATION")
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        DiagnosticReport.issueDraft(issueRepository, store.events(), info.longVersionCode, Build.VERSION.SDK_INT)
    }

    suspend fun shareIntent(): Intent = withContext(Dispatchers.IO) {
        awaitPending()
        val file = store.export()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri("Sanitized error logs", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    suspend fun clear() = withContext(Dispatchers.IO) {
        store.clear()
        synchronized(recentFailures) { recentFailures.clear() }
        _storageFailed.value = false
    }

    private fun awaitPending() {
        val barrier = CountDownLatch(1)
        try {
            worker.execute { barrier.countDown() }
        } catch (e: RejectedExecutionException) {
            throw IOException("Diagnostics are busy; try again", e)
        }
        if (!barrier.await(5, TimeUnit.SECONDS)) throw IOException("Diagnostics are busy; try again")
    }
}
