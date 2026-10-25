package io.github.maxdac.marknotes.sync

import android.content.Context
import android.os.SystemClock
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticReporter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.ConflictMergeBack
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.DriveSyncTarget
import io.github.maxdac.marknotes.core.sync.FileBaseContentStore
import io.github.maxdac.marknotes.core.sync.FileConflictCopyStore
import io.github.maxdac.marknotes.core.sync.FileConflictStore
import io.github.maxdac.marknotes.core.sync.FileRenameStore
import io.github.maxdac.marknotes.core.sync.MultiDriveSync
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.core.sync.UserFacingError
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.DriveAccountDao
import io.github.maxdac.marknotes.data.DriveAccountEntity
import io.github.maxdac.marknotes.data.RoomSyncMetadataStore
import io.github.maxdac.marknotes.data.SyncRecordDao
import io.github.maxdac.marknotes.sync.MergeResult
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** A connected drive and the outcome of its last sync. */
data class DriveStatus(
    val account: DriveAccount,
    val lastSyncAt: Long,
    val message: String?,
    val unavailableReason: String?
)

/**
 * A folder deleted locally that [driveLabel] can't remove safely: it stays on the drive until the user removes it.
 * Shown once per deletion [generation], until acknowledged.
 */
data class FolderRemovalNotice(val driveId: String, val driveLabel: String, val path: String, val generation: Long)

data class SyncState(
    val drives: List<DriveStatus> = emptyList(),
    val running: Boolean = false,
    val message: String? = null,
    val conflicts: List<SyncConflict> = emptyList(),
    val folderNotices: List<FolderRemovalNotice> = emptyList()
) {
    val hasDrives: Boolean get() = drives.isNotEmpty()
}

/**
 * Keeps the local notes in sync with every connected drive. Sync is only triggered on app launch, on demand and,
 * with "Sync when reconnected" on, when the network comes back while the app is open ([reconnect]): no background
 * or periodic polling. Editors' pending saves are written first ([beforeSync]), and the
 * conflicts waiting for the merge-back UI are kept across restarts.
 */
class SyncManager(
    private val context: Context,
    private val notes: LocalNoteStore,
    private val records: SyncRecordDao,
    private val accounts: DriveAccountDao,
    private val connectors: Map<DriveProvider, DriveConnector>,
    private val settings: AppSettings,
    private val scope: CoroutineScope,
    private val beforeSync: suspend () -> Unit = {},
    private val onLocalChanged: () -> Unit,
    /** Drops the editors' unsaved edits before the local data is deleted. */
    private val discardPendingSaves: suspend () -> Unit = {},
    /**
     * Deletes the data a build keeps outside the notes, the sync state and the connectors, see
     * [io.github.maxdac.marknotes.MarkNotesApp.deleteExtraLocalData].
     */
    private val deleteExtraLocalData: suspend () -> Unit = {},
    private val diagnostics: DiagnosticReporter = DiagnosticReporter.NONE
) {
    private val mutex = Mutex()
    private val multiSync = MultiDriveSync(notes, settings.deviceId).also { it.diagnostics = diagnostics }
    private val mergeBack = ConflictMergeBack(notes)
    private val migrated = CompletableDeferred<Unit>()
    private val conflictStore = FileConflictStore(File(context.filesDir, "sync-conflicts.json"))

    private val _state = MutableStateFlow(SyncState(message = settings.lastSyncSummary.ifBlank { null }))
    val state: StateFlow<SyncState> = _state

    /**
     * The optional "Sync when reconnected" scheduler. `MainActivity` feeds it the network and the foreground,
     * [AppContainer][io.github.maxdac.marknotes.AppContainer] the setting and the app lock.
     */
    internal val reconnect = ReconnectSync(scope, SystemClock::elapsedRealtime, ::syncOnReconnect)

    /** What the reconnect sync is doing, or why it stopped; shown in Settings. */
    val reconnectStatus: StateFlow<String?> get() = reconnect.status

    init {
        scope.launch {
            try {
                restoreConflicts()
                migrateLegacyAccounts()
            } finally {
                migrated.complete(Unit)
            }
            launch {
                state.map { it.conflicts }.distinctUntilChanged().drop(1).collect { conflicts ->
                    withContext(Dispatchers.IO) {
                        try {
                            conflictStore.save(conflicts)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            diagnostics.report(DiagnosticCode.SYNC_STATE, e)
                        }
                    }
                }
            }
            launch {
                combine(records.observePendingNotices(), accounts.observe()) { notices, entities ->
                    val labels = entities.filter { it.toAccount()?.provider in connectors }
                        .associate { it.id to it.label }
                    notices.mapNotNull { notice ->
                        val label = labels[notice.driveId] ?: return@mapNotNull null
                        FolderRemovalNotice(notice.driveId, label, notice.path, notice.generation)
                    }
                }.catch { e ->
                    if (e is Exception) diagnostics.report(DiagnosticCode.SYNC_STATE, e)
                }.collect { notices -> _state.update { it.copy(folderNotices = notices) } }
            }
            accounts.observe().collect { entities ->
                val drives = entities.mapNotNull { entity ->
                    // Accounts of a provider this build doesn't ship (e.g. a Pro database restored in libre).
                    val account = entity.toAccount()?.takeIf { it.provider in connectors } ?: return@mapNotNull null
                    DriveStatus(
                        account,
                        entity.lastSyncAt,
                        entity.lastMessage,
                        connector(account.provider).unavailableReason
                    )
                }
                _state.update { it.copy(drives = drives) }
            }
        }
    }

    fun connector(provider: DriveProvider): DriveConnector = connectors.getValue(provider)

    /** The providers this build can connect, in [DriveProvider] order. */
    val providers: List<DriveProvider> get() = DriveProvider.entries.filter { it in connectors }

    /** Called whenever the activity resumes, to complete browser-based sign-ins. */
    fun onActivityResumed() = connectors.values.forEach { it.onActivityResumed() }

    /** Saves a newly connected drive and syncs it. */
    fun addDrive(account: DriveAccount) {
        scope.launch {
            mutex.withLock { accounts.upsert(DriveAccountEntity.from(account)) }
            runSync()
        }
    }

    /** Forgets an account that signed in but was never saved as a drive (the user cancelled). */
    fun discardSignIn(signedIn: SignedInAccount) {
        scope.launch {
            mutex.withLock {
                if (accounts.get(signedIn.id) == null) signOut(signedIn.provider, signedIn.id)
            }
        }
    }

    /**
     * Disconnects a drive. Nothing is deleted from the drive. Local notes are kept unless [deleteLocalData] is set
     * and no other drive is connected: the local notes are shared by every drive, so they are deleted only with the
     * last one, after it is disconnected, and the deletion is never synced.
     */
    fun removeDrive(account: DriveAccount, deleteLocalData: Boolean = false) {
        scope.launch {
            migrated.await()
            var deleted = false
            mutex.withLock {
                forget(account)
                var message = "Disconnected ${account.label}"
                if (deleteLocalData && connectedDrives().isEmpty()) {
                    message = try {
                        deleteLocalDataLocked()
                        deleted = true
                        "Disconnected ${account.label} and deleted the local notes"
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        diagnostics.report(DiagnosticCode.LOCAL_DATA_DELETE, e)
                        "Disconnected ${account.label}, but could not delete the local notes: ${describe(e)}"
                    }
                }
                _state.update { s ->
                    s.copy(
                        conflicts = if (deleted) emptyList() else s.conflicts.filterNot { it.driveId == account.id },
                        message = message
                    )
                }
            }
            if (deleted) onLocalChanged()
        }
    }

    /**
     * Disconnects every drive, then deletes the notes, attachments and sync state stored on this device. Nothing is
     * deleted from the drives. Runs in the app scope, so leaving the screen doesn't stop it halfway. Throws when the
     * local data could not be deleted.
     */
    suspend fun deleteAllLocalData() = scope.async {
        migrated.await()
        try {
            mutex.withLock {
                // Every account, including those of providers this build doesn't ship.
                for (entity in accounts.all()) {
                    val account = entity.toAccount()
                    if (account != null && account.provider in connectors) {
                        forget(account)
                    } else {
                        records.clear(entity.id)
                        accounts.delete(entity.id)
                    }
                }
                deleteLocalDataLocked()
                _state.update { it.copy(conflicts = emptyList(), message = "Deleted the local data") }
            }
        } finally {
            onLocalChanged()
        }
    }.await()

    /** Signs out of [account] (best effort) and forgets it with its sync state. */
    private suspend fun forget(account: DriveAccount) {
        signOut(account.provider, account.id)
        records.clear(account.id)
        withContext(Dispatchers.IO) {
            baseStore(account.id).clear()
            pendingStore(account.id).clear()
            renameStore(account.id).clear()
        }
        accounts.delete(account.id)
    }

    private suspend fun connectedDrives() =
        accounts.all().mapNotNull { it.toAccount() }.filter { it.provider in connectors }

    /** Deletes the local data; called with the [mutex] held and once no drive is connected. */
    private suspend fun deleteLocalDataLocked() {
        discardPendingSaves()
        records.clearAll()
        withContext(Dispatchers.IO) {
            notes.deleteAll()
            File(context.filesDir, BASE_DIR).deleteRecursively()
            File(context.filesDir, PENDING_DIR).deleteRecursively()
            conflictStore.save(emptyList())
            // Import previews and camera captures; caches only hold copies, so all of it can go.
            context.cacheDir.listFiles()?.forEach { it.deleteRecursively() }
        }
        for (connector in connectors.values) connector.deleteLocalData()
        deleteExtraLocalData()
        settings.clearSyncState()
    }

    fun syncIfConnected() {
        scope.launch {
            migrated.await()
            if (accounts.all().isNotEmpty()) runSync()
        }
    }

    fun syncNow() {
        // Like on the web, a manual sync replaces a pending reconnect sync.
        reconnect.cancel()
        if (_state.value.running) return
        scope.launch { runSync() }
    }

    /** Three-way merge of a conflicted copy into its note, for the merge-back UI. */
    suspend fun mergePreview(conflict: SyncConflict): MergeResult =
        withContext(Dispatchers.IO) {
            try {
                mergeBack.merge(conflict)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.report(DiagnosticCode.SYNC, e)
                throw e
            }
        }

    /** Saves [text] as the note, deletes the conflicted copy, then syncs so every drive receives both changes. */
    fun mergeBack(conflict: SyncConflict, text: String) = settle(conflict, "merge") { mergeBack.apply(conflict, text) }

    /** Deletes the conflicted copy (locally, then on every drive at the next sync). */
    fun discardCopy(
        conflict: SyncConflict
    ) = settle(conflict, "discard the copy of") { mergeBack.discardCopy(conflict) }

    /** Keeps both files as they are and stops listing the conflict. */
    fun keepBoth(conflict: SyncConflict) {
        _state.update { s -> s.copy(conflicts = s.conflicts.filterNot { it.copyPath == conflict.copyPath }) }
    }

    /**
     * Saves that the user read [notice]; it is then not shown again for this deletion. A failed save keeps the
     * notice pending and says so.
     */
    fun acknowledgeFolderNotice(notice: FolderRemovalNotice) {
        scope.launch {
            try {
                RoomSyncMetadataStore(records, notice.driveId).acknowledgeFolderNotice(notice.generation)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.report(DiagnosticCode.SYNC_STATE, e)
                _state.update { it.copy(message = "Could not dismiss the notice for ${notice.path}: ${describe(e)}") }
            }
        }
    }

    private fun settle(conflict: SyncConflict, verb: String, action: () -> Unit) {
        scope.launch {
            val settled = mutex.withLock {
                try {
                    withContext(Dispatchers.IO) { action() }
                    keepBoth(conflict)
                    onLocalChanged()
                    true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.report(DiagnosticCode.SYNC, e)
                    _state.update { it.copy(message = "Could not $verb ${conflict.path}: ${describe(e)}") }
                    false
                }
            }
            if (settled) runSync()
        }
    }

    /** Conflicts from before a restart whose copy is still there. */
    private suspend fun restoreConflicts() {
        val saved = withContext(Dispatchers.IO) {
            try {
                conflictStore.load().filter { mergeBack.isPending(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                diagnostics.report(DiagnosticCode.SYNC_STATE, e)
                emptyList()
            }
        }
        if (saved.isNotEmpty()) {
            _state.update { s -> s.copy(conflicts = (saved + s.conflicts).distinctBy { it.copyPath }) }
        }
    }

    private suspend fun runSync() {
        migrated.await()
        syncLocked()
    }

    /** One automatic reconnect sync: the same sync as "Sync now", telling [reconnect] whether to retry. */
    internal suspend fun syncOnReconnect(): ReconnectResult {
        migrated.await()
        // The setting may have been turned off while the attempt waited.
        if (!settings.syncOnReconnect.value) return ReconnectResult.Stop
        return syncLocked()
    }

    private suspend fun syncLocked(): ReconnectResult = mutex.withLock {
        beforeSync()
        val drives = accounts.all().mapNotNull { it.toAccount() }.filter { it.provider in connectors }
        if (drives.isEmpty()) {
            _state.update { it.copy(message = "Add a drive in Settings to sync", conflicts = emptyList()) }
            return@withLock ReconnectResult.Stop
        }
        _state.update { it.copy(running = true, message = "Syncing…") }
        try {
            val messages = linkedMapOf<String, String>()
            val failures = mutableListOf<Exception>()
            val targets = drives.mapNotNull { account ->
                try {
                    target(account)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    diagnostics.report(DiagnosticCode.SYNC, e)
                    messages[account.id] = describe(e)
                    failures += e
                    null
                }
            }
            val result = withContext(Dispatchers.IO) { multiSync.sync(targets) }
            for (outcome in result.outcomes) {
                messages[outcome.driveId] = outcome.result
                    ?.let { (listOf(it.summary) + it.errors).joinToString("\n") }
                    ?: "Sync failed: ${outcome.error}"
            }
            val now = System.currentTimeMillis()
            for ((id, message) in messages) accounts.updateStatus(id, now, message)
            val summary = drives.joinToString("\n") {
                it.label + ": " + messages[it.id].orEmpty().lineSequence().first()
            }
            settings.lastSyncAt = now
            settings.lastSyncSummary = summary
            val pending = withContext(Dispatchers.IO) { _state.value.conflicts.filter { mergeBack.isPending(it) } }
            _state.update {
                it.copy(message = summary, conflicts = (pending + result.conflicts).distinctBy { c -> c.copyPath })
            }
            onLocalChanged()
            result.reconnectResult(failures)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.report(DiagnosticCode.SYNC, e)
            _state.update { it.copy(message = "Sync failed: ${describe(e)}") }
            e.reconnectResult()
        } finally {
            _state.update { it.copy(running = false) }
        }
    }

    private suspend fun target(account: DriveAccount): DriveSyncTarget {
        val connector = connector(account.provider)
        connector.unavailableReason?.let { error(it) }
        return DriveSyncTarget(
            driveId = account.id,
            remote = connector.store(account),
            metadata = RoomSyncMetadataStore(records, account.id),
            base = baseStore(account.id),
            pending = pendingStore(account.id),
            renames = renameStore(account.id)
        )
    }

    private fun baseStore(driveId: String) = FileBaseContentStore(File(context.filesDir, "$BASE_DIR/$driveId"))

    private fun pendingStore(driveId: String) =
        FileConflictCopyStore(File(context.filesDir, "$PENDING_DIR/$driveId.json"))

    private fun renameStore(driveId: String) =
        FileRenameStore(File(context.filesDir, "$PENDING_DIR/$driveId-renames.json"))

    private suspend fun signOut(provider: DriveProvider, accountId: String) {
        try {
            connector(provider).signOut(accountId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diagnostics.report(DiagnosticCode.SIGN_OUT, e)
            // Signing out is best effort: the local data is removed regardless.
        }
    }

    /** Turns setups that predate drive accounts (v1 synced a single Proton Drive folder) into drive accounts. */
    private suspend fun migrateLegacyAccounts() {
        val known = connectors.values.mapNotNull { it.legacyAccount(settings) }
            .filter { accounts.get(it.id) == null }
        for (account in known) {
            accounts.upsert(
                DriveAccountEntity.from(account, settings.lastSyncAt, settings.lastSyncSummary.ifBlank { null })
            )
        }
    }

    private fun describe(e: Exception): String = when (e) {
        is UserFacingError -> e.userMessage
        else -> e.message ?: e.javaClass.simpleName
    }

    private companion object {
        /** Base copies of the last synced version of each file, per drive, for three-way merges. */
        const val BASE_DIR = "sync-base"
        const val PENDING_DIR = "sync-pending"
    }
}
