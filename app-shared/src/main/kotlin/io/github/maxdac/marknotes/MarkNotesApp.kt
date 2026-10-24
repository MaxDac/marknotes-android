package io.github.maxdac.marknotes

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.SystemClock
import androidx.core.content.ContextCompat
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.security.AppLock
import io.github.maxdac.marknotes.sync.DriveConnector
import io.github.maxdac.marknotes.sync.DropboxConnector
import io.github.maxdac.marknotes.sync.PendingSaves
import io.github.maxdac.marknotes.sync.SyncManager
import io.github.maxdac.marknotes.ui.editor.ImageImporter
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The Application of both builds: the libre app (Dropbox only) and MarkNotes Pro, which adds its providers
 * through [extraConnectors].
 */
abstract class MarkNotesApp : Application() {
    lateinit var container: AppContainer
        private set

    /** The Dropbox app key, also used as the `db-<key>` redirect scheme in the manifest. */
    protected abstract val dropboxAppKey: String

    /** Connectors for the providers this build adds to Dropbox. */
    protected open fun extraConnectors(context: Context, credentials: CredentialStore): List<DriveConnector> =
        emptyList()

    /**
     * Deletes the data this build keeps on the device besides the notes, the sync state and what its connectors
     * clear in [DriveConnector.deleteLocalData], when the user deletes all local data. Drives are already
     * disconnected; nothing may be deleted from them.
     */
    protected open suspend fun deleteExtraLocalData(context: Context) {}

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this, dropboxAppKey, ::extraConnectors) { deleteExtraLocalData(this) }
    }
}

/** Manual dependency container shared by the whole app. */
class AppContainer(
    context: Context,
    dropboxAppKey: String,
    extraConnectors: (Context, CredentialStore) -> List<DriveConnector> = { _, _ -> emptyList() },
    deleteExtraLocalData: suspend () -> Unit = {}
) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val notesRoot = File(context.filesDir, "notes").apply { mkdirs() }
    val notes = LocalNoteStore(notesRoot)
    val imageImporter = ImageImporter(context.applicationContext)
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
    private val credentials = CredentialStore(context)

    /** Every provider this build can connect. */
    val connectors: Map<DriveProvider, DriveConnector> =
        (
            listOf(
                DropboxConnector(credentials, dropboxAppKey)
            ) + extraConnectors(context.applicationContext, credentials)
            )
            .associateBy { it.provider }

    private val _localRevision = MutableStateFlow(0L)

    /** Bumped whenever the note tree changes (browser actions, sync, conflict resolution). */
    val localRevision: StateFlow<Long> = _localRevision

    fun notifyLocalChanged() {
        _localRevision.value = _localRevision.value + 1
    }

    /** Unsaved editor edits, written before every sync. */
    val pendingSaves = PendingSaves()

    val syncManager = SyncManager(
        context = context.applicationContext,
        notes = notes,
        records = database.syncRecords(),
        accounts = database.driveAccounts(),
        connectors = connectors,
        settings = settings,
        scope = appScope,
        beforeSync = pendingSaves::flushAll,
        onLocalChanged = ::notifyLocalChanged,
        discardPendingSaves = pendingSaves::discardAll,
        deleteExtraLocalData = deleteExtraLocalData
    )
}

val Context.appContainer: AppContainer
    get() = (applicationContext as MarkNotesApp).container
