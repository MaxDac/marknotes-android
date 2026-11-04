package io.github.maxdac.marknotes.sync

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.runtime.Composable
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import io.github.maxdac.marknotes.auth.CredentialStore
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * A folder deleted locally that [driveLabel] can't remove safely: it stays on the drive until the user removes it.
 * Shown once per deletion [generation], until acknowledged.
 */
data class FolderRemovalNotice(val driveId: String, val driveLabel: String, val path: String, val generation: Long)

/** What the screens show of the sync of the app that has one; see [SyncHooks.state]. */
data class SyncUiState(
    val running: Boolean = false,
    val hasDrives: Boolean = false,
    val conflictCount: Int = 0,
    val folderNotices: List<FolderRemovalNotice> = emptyList()
)

/** What an app that syncs needs from the app container to build its [SyncHooks]. */
@Suppress("LongParameterList")
class SyncHost(
    val context: Context,
    val notes: LocalNoteStore,
    val database: MarkNotesDatabase,
    val credentials: CredentialStore,
    val settings: AppSettings,
    val scope: CoroutineScope,
    val diagnostics: DiagnosticReporter,
    /** Unsaved editor edits: write them with [PendingSaves.flushAll] before every sync. */
    val pendingSaves: PendingSaves,
    /** Call whenever sync (or a conflict resolution) changed the note tree. */
    val onLocalChanged: () -> Unit,
    /**
     * Deletes the data the build keeps on the device besides the notes and the sync state, then the diagnostics;
     * see [io.github.maxdac.marknotes.MarkNotesApp.deleteExtraLocalData].
     */
    val deleteExtraLocalData: suspend () -> Unit
)

/**
 * The sync of an app, as far as the screens of this repository know it. The libre app has none ([NoSyncHooks]:
 * notes stay on the device and no sync UI is shown); an app that syncs returns its own implementation from
 * [io.github.maxdac.marknotes.MarkNotesApp.createSyncHooks]. The screens only use this interface, so nothing here
 * depends on a sync library or a cloud provider.
 */
@Suppress("TooManyFunctions")
interface SyncHooks {
    /** Whether the app offers any cloud provider; the sync UI (drives, "Sync now") is shown only then. */
    val available: Boolean

    /** What the browser and Settings show: progress, conflicts and the folder-removal notices. */
    val state: StateFlow<SyncUiState>

    /** Names of the providers that can be connected, comma separated, for the getting-started guide ("" for none). */
    val providerNames: String

    /** Texts shown under About, for example a provider's terms. */
    val aboutNotices: List<String>

    /** Syncs once at launch if a drive is connected. */
    fun syncIfConnected()

    /** A manual sync, the "Sync now" button of the browser. */
    fun syncNow()

    /** Saves that the user read [notice]; it is then not shown again for this deletion. */
    fun acknowledgeFolderNotice(notice: FolderRemovalNotice)

    /** Called whenever the main activity resumes, which is when browser-based sign-ins return. */
    fun onActivityResumed()

    /** Feeds the network state to "Sync when reconnected" for as long as [activity] exists. */
    fun watchNetwork(activity: ComponentActivity)

    /** Whether the app is in the foreground; reconnect syncs only run there. */
    fun onForeground(foreground: Boolean)

    /** The main activity is being destroyed for good; a reconnect seen before must not add a second sync. */
    fun onActivityFinishing()

    /** The "Sync when reconnected" setting and whether the app is unlocked. */
    fun updateReconnectPolicy(enabled: Boolean, unlocked: Boolean)

    /**
     * Disconnects every drive, then deletes the notes, attachments and sync state stored on this device. Nothing is
     * deleted from the drives. Throws when the local data could not be deleted.
     */
    suspend fun deleteAllLocalData()

    /** Adds the destinations of the sync screens (routes "add_drive", "conflicts" and the sign-in screens). */
    fun NavGraphBuilder.syncDestinations(navController: NavController) {}

    /**
     * Wraps the whole Settings page: shows a full-page screen of its own instead of [content] while one is needed
     * (a drive's sign-in), otherwise calls [content] with whether sync is busy, which disables the other
     * destructive actions.
     */
    @Composable
    fun SettingsPage(content: @Composable (busy: Boolean) -> Unit) = content(false)

    /** The drives block of Settings; inside the page's column. Shows nothing when [available] is false. */
    @Composable
    fun ColumnScope.DrivesSection(
        busy: Boolean,
        onAddDrive: () -> Unit,
        onOpenConflicts: () -> Unit,
        onLocalDataDeleted: () -> Unit
    ) {
    }
}

/**
 * The libre app's sync: none. Only deleting all local data does something, because the notes, attachments, caches
 * and the sync state of earlier versions are still stored on the device.
 */
class NoSyncHooks(private val host: SyncHost) : SyncHooks {
    override val available: Boolean get() = false
    override val state: StateFlow<SyncUiState> = MutableStateFlow(SyncUiState())
    override val providerNames: String get() = ""
    override val aboutNotices: List<String> get() = emptyList()

    override fun syncIfConnected() = Unit

    override fun syncNow() = Unit

    override fun acknowledgeFolderNotice(notice: FolderRemovalNotice) = Unit

    override fun onActivityResumed() = Unit

    override fun watchNetwork(activity: ComponentActivity) = Unit

    override fun onForeground(foreground: Boolean) = Unit

    override fun onActivityFinishing() = Unit

    override fun updateReconnectPolicy(enabled: Boolean, unlocked: Boolean) = Unit

    // In the app scope, so leaving the screen doesn't stop it halfway.
    override suspend fun deleteAllLocalData() = host.scope.async {
        try {
            host.pendingSaves.discardAll()
            host.database.syncRecords().clearAll()
            host.database.driveAccounts().deleteAll()
            withContext(Dispatchers.IO) {
                host.notes.deleteAll()
                for (directory in LEFTOVER_SYNC_DIRECTORIES) File(host.context.filesDir, directory).deleteRecursively()
                File(host.context.filesDir, LEFTOVER_CONFLICTS_FILE).delete()
                // Import previews and camera captures; caches only hold copies, so all of it can go.
                host.context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
            }
            host.deleteExtraLocalData()
            host.settings.clearSyncState()
        } finally {
            host.onLocalChanged()
        }
    }.await()

    private companion object {
        /** Base copies and recovery journals which the sync of earlier versions kept in the files directory. */
        val LEFTOVER_SYNC_DIRECTORIES = listOf("sync-base", "sync-pending")
        const val LEFTOVER_CONFLICTS_FILE = "sync-conflicts.json"
    }
}
