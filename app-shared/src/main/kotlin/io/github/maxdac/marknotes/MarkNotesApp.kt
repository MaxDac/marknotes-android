package io.github.maxdac.marknotes

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.maxdac.marknotes.about.MarkNotesEdition
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.auth.KeystoreCrypto
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.diagnostics.AndroidDiagnostics
import io.github.maxdac.marknotes.security.AppLock
import io.github.maxdac.marknotes.sync.NoSyncHooks
import io.github.maxdac.marknotes.sync.PendingSaves
import io.github.maxdac.marknotes.sync.SyncHooks
import io.github.maxdac.marknotes.sync.SyncHost
import io.github.maxdac.marknotes.ui.editor.ImageImporter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * The Application of both builds: the libre app (offline only, no sync) and MarkNotes Plus, which adds its sync
 * through [createSyncHooks].
 */
abstract class MarkNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    protected open val issueRepository: String = "MaxDac/marknotes-android"

    /** Which build this is; Settings → About links to the other MarkNotes apps, never this one. */
    protected abstract val edition: MarkNotesEdition

    /** Available before extraConnectors is called, unlike container which is still being constructed. */
    protected lateinit var diagnosticReporter: DiagnosticReporter
        private set

    internal lateinit var diagnosticBackend: AndroidDiagnostics
        private set

    /**
     * The sync of this build; none by default (notes stay on the device). An app that syncs returns its own
     * implementation, built from what [host] gives it.
     */
    protected open fun createSyncHooks(host: SyncHost): SyncHooks = NoSyncHooks(host)

    /**
     * Deletes the data this build keeps on the device besides the notes, the sync state and what its sync clears
     * itself, when the user deletes all local data. Drives are already disconnected; nothing may be deleted from them.
     */
    protected open suspend fun deleteExtraLocalData(context: Context) {}

    override fun onCreate() {
        super.onCreate()
        val diagnostics = AndroidDiagnostics(this, issueRepository)
        diagnosticBackend = diagnostics
        diagnosticReporter = diagnostics
        container = AppContainer(this, edition, ::createSyncHooks) { deleteExtraLocalData(this) }
    }
}

/** Manual dependency container shared by the whole app. */
class AppContainer(
    context: Context,
    val edition: MarkNotesEdition,
    createSyncHooks: (SyncHost) -> SyncHooks = ::NoSyncHooks,
    deleteExtraLocalData: suspend () -> Unit = {}
) {
    val diagnostics = (context.applicationContext as MarkNotesApp).diagnosticBackend
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notesRoot = File(context.filesDir, "notes").apply { mkdirs() }
    val notes = LocalNoteStore(notesRoot)
    val imageImporter = ImageImporter(context.applicationContext, diagnostics)
    val settings = AppSettings(context, dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)

    /** The optional app lock (Settings, Privacy); locked on cold start when enabled. */
    val appLock = AppLock(
        enabled = { settings.appLockEnabled.value },
        timeoutMillis = { settings.lockTimeout.value.millis },
        clock = SystemClock::elapsedRealtime
    ).also { lock ->
        ContextCompat.registerReceiver(
            context.applicationContext,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) = lock.onScreenOff()
            },
            IntentFilter(Intent.ACTION_SCREEN_OFF),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
    }
    private val database = MarkNotesDatabase.create(context)
    private val credentials = CredentialStore(context, KeystoreCrypto(diagnostics = diagnostics))

    private val _localRevision = MutableStateFlow(0L)

    /** Bumped whenever the note tree changes (browser actions, sync, conflict resolution). */
    val localRevision: StateFlow<Long> = _localRevision

    fun notifyLocalChanged() {
        _localRevision.value = _localRevision.value + 1
    }

    /** Unsaved editor edits, written before every sync. */
    val pendingSaves = PendingSaves(diagnostics)

    /** The sync of this build, see [SyncHooks]; none in the libre app. */
    val syncHooks: SyncHooks = createSyncHooks(
        SyncHost(
            context = context.applicationContext,
            notes = notes,
            database = database,
            credentials = credentials,
            settings = settings,
            scope = appScope,
            diagnostics = diagnostics,
            pendingSaves = pendingSaves,
            onLocalChanged = ::notifyLocalChanged,
            deleteExtraLocalData = {
                deleteExtraLocalData()
                diagnostics.clear()
            }
        )
    )

    init {
        appScope.launch {
            combine(settings.syncOnReconnect, appLock.locked, ::Pair).collect { (enabled, locked) ->
                syncHooks.updateReconnectPolicy(enabled = enabled, unlocked = !locked)
            }
        }
    }
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MarkNotesApp).container
