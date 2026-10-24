package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.NoteIds
import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.sync.Action
import io.github.maxdac.marknotes.sync.ExistingCopy
import io.github.maxdac.marknotes.sync.LocalEntry as CoreLocalEntry
import io.github.maxdac.marknotes.sync.ReconcileInput
import io.github.maxdac.marknotes.sync.ReconcileOutcome
import io.github.maxdac.marknotes.sync.RemoteEntry as CoreRemoteEntry
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.SyncInput
import io.github.maxdac.marknotes.sync.SyncRecord as CoreSyncRecord
import io.github.maxdac.marknotes.sync.buildManifest
import io.github.maxdac.marknotes.sync.isAttachmentPath
import io.github.maxdac.marknotes.sync.isSyncedEntry
import io.github.maxdac.marknotes.sync.plan
import io.github.maxdac.marknotes.sync.reconcile
import java.io.IOException
import kotlinx.coroutines.CancellationException

/**
 * A note edited on both sides whose edits could not be merged. The newest version ([winner]) was kept at [path]
 * and the other one was saved as a new note at [copyPath]; see [ConflictMergeBack] to merge it back.
 */
data class SyncConflict(
    val path: String,
    val copyPath: String,
    val winner: Side,
    /** Text of the note the last time both sides agreed, if known. */
    val baseText: String? = null,
    /** The drive the conflict was found on. */
    val driveId: String = ""
)

data class SyncResult(
    val uploaded: Int,
    val downloaded: Int,
    val deletedLocal: Int,
    val deletedRemote: Int,
    val conflicts: List<SyncConflict>,
    val errors: List<String>,
    val merged: Int = 0,
    /** Local folders created from the drive. */
    val createdLocalFolders: Int = 0,
    /** Steps skipped because the local file changed while the sync ran; the next sync picks them up. */
    val skipped: Int = 0,
    val renamedLocal: Int = 0
) {
    /**
     * Number of changes made to the local note tree (created folders and conflicted copies included), which other
     * drives need.
     */
    val localChanges: Int get() = downloaded + deletedLocal + merged + createdLocalFolders + conflicts.size +
        renamedLocal

    val summary: String
        get() = buildString {
            append("↑$uploaded ↓$downloaded")
            if (merged > 0) append(", merged $merged")
            if (deletedLocal + deletedRemote > 0) append(", deleted ${deletedLocal + deletedRemote}")
            if (conflicts.isNotEmpty()) append(", ${conflicts.size} conflicted copy(ies)")
            if (errors.isNotEmpty()) append(", ${errors.size} error(s)")
        }
}

/**
 * Two-way sync between the local note tree and one drive.
 *
 * The plan and the conflict handling come from the shared sync core
 * ([marknotes-sync](https://github.com/MaxDac/marknotes-sync)), which implements the marknotes-spec sync rules for
 * every MarkNotes app. This class only lists both sides, runs the planned actions against the stores and writes the
 * drive's `.marknotes/manifest.json` (see [SyncManifest]).
 *
 * The local tree can change while the plan runs (an editor autosave): before every local write or delete the file
 * is hashed again, and the step is skipped (left for the next sync) when it no longer matches what the plan saw.
 */
@Suppress("LongParameterList")
class SyncEngine(
    private val local: LocalNoteStore,
    private val remote: RemoteDriveStore,
    private val metadata: SyncMetadataStore,
    private val base: BaseContentStore = InMemoryBaseContentStore(),
    private val driveId: String = "",
    private val deviceId: String = "",
    private val pending: ConflictCopyStore = InMemoryConflictCopyStore(),
    private val renames: RenameStore = InMemoryRenameStore(),
    private val clock: () -> Long = System::currentTimeMillis
) {

    suspend fun sync(): SyncResult = Run().execute()

    suspend fun recoverRenames(): Int = RenameRecovery(local, remote, metadata, base, renames).recover()

    @Suppress("TooManyFunctions")
    private inner class Run {
        private var uploaded = 0
        private var downloaded = 0
        private var deletedLocal = 0
        private var deletedRemote = 0
        private var merged = 0
        private var createdLocalFolders = 0
        private var skipped = 0
        private var renamedLocal = 0
        private val conflicts = mutableListOf<SyncConflict>()
        private val errors = mutableListOf<String>()
        private val modified = HashMap<String, Long>()
        private val knownPaths = HashSet<String>()
        private val blockedPaths = HashSet<String>()
        private var renamed = false

        /** What the plan saw for each synced local path: the file's hash or [FOLDER_STATE]. */
        private val planned = HashMap<String, String>()

        suspend fun execute(): SyncResult {
            renamedLocal += recoverRenames()
            for (copy in pending.all()) {
                guarded(copy.intent.path) { completeConflictCopy(copy) }
                if (pending.all().any { it.intent.path == copy.intent.path }) {
                    blockedPaths += copy.intent.path
                    blockedPaths += copy.intent.copyPath
                }
            }
            val manifest = readManifest()
            do {
                renamed = false
                applyPlan(manifest)
            } while (renamed)
            guarded(SyncManifest.PATH) {
                val records = metadata.all().map { it.toCore() }
                buildManifest(records, deviceId, clock(), manifest)?.let {
                    remote.writeManifest(it.toByteArray(Charsets.UTF_8))
                }
            }
            return SyncResult(
                uploaded,
                downloaded,
                deletedLocal,
                deletedRemote,
                conflicts.toList(),
                errors.toList(),
                merged,
                createdLocalFolders,
                skipped,
                renamedLocal
            )
        }

        private suspend fun applyPlan(manifest: String?) {
            planned.clear()
            modified.clear()
            val localEntries = local.listAll().map {
                modified[it.path] = it.modifiedMillis
                val hash = if (it.isFolder || !isSyncedEntry(it.path, false)) {
                    null
                } else {
                    Hashing.sha256Hex(local.readBytes(it.path))
                }
                planned[it.path] = hash ?: FOLDER_STATE
                CoreLocalEntry(it.path, it.isFolder, it.modifiedMillis, hash)
            }
            val remoteEntries = remote.snapshot()
            knownPaths += localEntries.map { it.path } + remoteEntries.map { it.path }
            rememberLocalDeletions(localEntries, metadata)
            val input = SyncInput(
                local = localEntries,
                remote = remoteEntries.map { it.toCore() },
                records = metadata.all().map { it.toCore() },
                manifest = manifest
            )
            for (action in plan(input).actions) {
                if (blockedPaths.none { it == action.path || NotePaths.isAncestor(it, action.path) }) {
                    guarded(action.path) { apply(action) }
                }
                if (renamed) break
            }
        }

        private suspend fun readManifest(): String? = try {
            remote.readManifest()?.toString(Charsets.UTF_8)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The manifest is an optimisation: sync works without it.
            null
        }

        @Suppress("CyclomaticComplexMethod")
        private suspend fun apply(action: Action) {
            when (action) {
                is Action.Error -> errors += "${action.path}: ${action.message}"

                is Action.RecordFolder -> recordFolder(action.path, action.remoteId)

                is Action.CreateRemoteFolder -> recordFolder(action.path, remote.createFolder(action.path).id)

                is Action.CreateLocalFolder -> {
                    local.createFolderNew(action.path)
                    recordFolder(action.path, action.remoteId)
                    createdLocalFolders++
                }

                is Action.Forget -> forget(action.path)

                is Action.UploadNew -> {
                    val modifiedMillis = local.modifiedMillis(action.path)
                    val bytes = local.readBytes(action.path)
                    recordFile(action.path, remote.uploadNew(action.path, bytes, modifiedMillis), bytes)
                    uploaded++
                }

                is Action.UploadRevision -> {
                    val modifiedMillis = local.modifiedMillis(action.path)
                    val bytes = local.readBytes(action.path)
                    recordFile(
                        action.path,
                        remote.uploadRevision(action.remote.toApp(), bytes, modifiedMillis),
                        bytes
                    )
                    uploaded++
                }

                is Action.Download -> download(action.path, action.remote.toApp())

                is Action.Record -> {
                    upsertFile(action.path, action.remote.toApp(), action.sha256)
                    if (action.updateBase) base.put(action.path, local.readBytes(action.path))
                }

                is Action.DeleteLocal -> {
                    if (!unchanged(action.path)) return
                    local.delete(action.path)
                    forget(action.path)
                    deletedLocal++
                }

                is Action.DeleteRemote -> {
                    remote.delete(action.remote.toApp())
                    forget(action.path)
                    deletedRemote++
                }

                is Action.DeleteLocalFolder -> {
                    if (!treeUnchanged(action.path)) return
                    local.deleteEmptyFolder(action.path)
                    forgetTree(action.path, action.forget)
                    deletedLocal++
                }

                is Action.DeleteRemoteFolder -> {
                    remote.deleteFolderIfEmpty(action.remote.toApp())
                    forgetTree(action.path, action.forget)
                    deletedRemote++
                }

                is Action.Reconcile -> reconcileNote(action.path, action.remote, action.hasRecord)

                is Action.RenameLocal -> renameLocal(action)

                is Action.RenameRemote -> renameRemote(action)
            }
        }

        private suspend fun renameLocal(action: Action.RenameLocal) {
            if (!unchanged(action.path, action.sha256 ?: FOLDER_STATE)) return
            if (action.isFolder && !treeUnchanged(action.path)) return
            renames.put(
                PendingRename(action.path, action.toPath, true, action.remote.toApp(), local.identity(action.path))
            )
            renamedLocal += recoverRenames()
            renamed = true
        }

        private suspend fun renameRemote(action: Action.RenameRemote) {
            if (!unchanged(action.toPath, action.sha256 ?: FOLDER_STATE)) return
            if (action.isFolder && !treeUnchanged(action.toPath)) return
            renames.put(PendingRename(action.path, action.toPath, false, action.remote.toApp(), null))
            recoverRenames()
            renamed = true
        }

        private suspend fun download(path: String, entry: RemoteEntry) {
            val bytes = remote.download(entry)
            if (isAttachmentPath(path) && !Attachments.matchesName(path, bytes)) {
                throw IOException("downloaded bytes don't match the attachment's name")
            }
            if (!unchanged(path)) return
            if (planned[path] == null) {
                local.writeNewBytes(path, bytes, entry.modifiedMillis)
            } else {
                local.writeBytes(path, bytes, entry.modifiedMillis)
            }
            recordFile(path, entry, bytes)
            downloaded++
        }

        /** True when [path] is as the plan saw it; otherwise counts the step as skipped. */
        private fun unchanged(path: String, expected: String? = planned[path]): Boolean =
            (localState(path) == expected).also { if (!it) skipped++ }

        /** True when no synced entry below [folder] was added or changed since planning. */
        private fun treeUnchanged(folder: String): Boolean {
            val same = local.listAll().all {
                !NotePaths.isAncestor(folder, it.path) || !isSyncedEntry(it.path, it.isFolder) ||
                    localState(it.path) == planned[it.path]
            }
            if (!same) skipped++
            return same
        }

        private fun localState(path: String): String? {
            val file = local.file(path)
            return when {
                file.isFile -> Hashing.sha256Hex(local.readBytes(path))
                file.isDirectory -> FOLDER_STATE
                else -> null
            }
        }

        /** The note changed on both sides: keep it, merge it, or keep the newest and save the other as a copy. */
        private suspend fun reconcileNote(path: String, remoteEntry: CoreRemoteEntry, hasRecord: Boolean) {
            val entry = remoteEntry.toApp()
            val localBytes = local.readBytes(path)
            val localHash = Hashing.sha256Hex(localBytes)
            if (localHash != planned[path]) {
                skipped++
                return
            }
            val remoteBytes = remote.download(entry)
            val baseText = if (hasRecord) base.get(path)?.toString(Charsets.UTF_8) else null
            val outcome = reconcile(
                ReconcileInput(
                    path = path,
                    local = localBytes.toString(Charsets.UTF_8),
                    localModifiedMillis = modified[path] ?: 0L,
                    remote = remoteBytes.toString(Charsets.UTF_8),
                    remoteEntry = remoteEntry,
                    base = baseText,
                    existingPaths = knownPaths.toList(),
                    newId = NoteIds.generate(),
                    conflictCopyIntent = null,
                    existingCopy = null
                )
            )
            when (outcome) {
                is ReconcileOutcome.Same -> recordFile(path, entry, localBytes)

                is ReconcileOutcome.Merged -> {
                    if (!unchanged(path, localHash)) return
                    val bytes = outcome.text.toByteArray(Charsets.UTF_8)
                    local.writeBytes(path, bytes)
                    recordFile(path, remote.uploadRevision(entry, bytes, local.modifiedMillis(path)), bytes)
                    merged++
                }

                is ReconcileOutcome.NewestWins -> newestWins(path, entry, outcome, localBytes, remoteBytes, baseText)
            }
        }

        /** Journal the exact copy before creating it or changing the winner, so every crash window is replayable. */
        @Suppress("LongParameterList")
        private suspend fun newestWins(
            path: String,
            entry: RemoteEntry,
            outcome: ReconcileOutcome.NewestWins,
            localBytes: ByteArray,
            remoteBytes: ByteArray,
            baseText: String?
        ) {
            val localHash = Hashing.sha256Hex(localBytes)
            if (!unchanged(path, localHash)) return
            val copy = PendingConflictCopy(
                outcome.conflictCopyIntent,
                outcome.copyText,
                localBytes.toString(Charsets.UTF_8),
                remoteBytes.toString(Charsets.UTF_8),
                entry,
                baseText,
                outcome.winner
            )
            pending.put(copy)
            completeConflictCopy(copy)
        }

        private suspend fun refreshedCopy(copy: PendingConflictCopy): PendingConflictCopy {
            val intent = copy.intent
            val entries = remote.snapshot()
            val remoteCopy = entries.firstOrNull { it.path == intent.copyPath }
            val verifiedRemote = remoteCopy?.let { matchesCopy(it, copy) } ?: true
            val existing = if (local.file(intent.copyPath).isFile && verifiedRemote) {
                ExistingCopy(intent.copyPath, local.read(intent.copyPath), local.modifiedMillis(intent.copyPath))
            } else {
                null
            }
            val outcome = reconcile(
                ReconcileInput(
                    path = intent.path,
                    local = copy.localText,
                    localModifiedMillis = intent.localModifiedMillis,
                    remote = copy.remoteText,
                    remoteEntry = copy.remoteEntry.toCore(),
                    base = copy.baseText,
                    existingPaths = (local.listAll().map { it.path } + entries.map { it.path }).distinct(),
                    newId = NoteIds.generate(),
                    conflictCopyIntent = intent,
                    existingCopy = existing
                )
            )
            check(outcome is ReconcileOutcome.NewestWins) { "Pending conflict no longer describes a conflict" }
            return copy.copy(intent = outcome.conflictCopyIntent, copyText = outcome.copyText).also {
                pending.put(it)
            }
        }

        private suspend fun matchesCopy(entry: RemoteEntry, copy: PendingConflictCopy): Boolean =
            !entry.isFolder && entry.modifiedMillis == copy.intent.copyModifiedMillis &&
                remote.download(entry).contentEquals(copy.copyText.toByteArray(Charsets.UTF_8))

        private suspend fun completeConflictCopy(saved: PendingConflictCopy) {
            var copy = refreshedCopy(saved)
            val intent = copy.intent
            val copyBytes = copy.copyText.toByteArray(Charsets.UTF_8)
            if (!local.exists(intent.copyPath)) {
                local.writeNewBytes(intent.copyPath, copyBytes, intent.copyModifiedMillis)
            }
            check(
                local.readBytes(intent.copyPath).contentEquals(copyBytes) &&
                    local.modifiedMillis(intent.copyPath) == intent.copyModifiedMillis
            ) {
                "Conflicted copy changed before it could be saved: ${intent.copyPath}"
            }
            knownPaths += intent.copyPath
            conflicts += SyncConflict(intent.path, intent.copyPath, copy.winner, copy.baseText, driveId)
            if (!copy.winnerRecorded) {
                completeWinner(copy)
                copy = copy.copy(winnerRecorded = true)
                pending.put(copy)
            }
            val existing = remote.snapshot().firstOrNull { it.path == intent.copyPath }
            val uploadedCopy = if (existing == null) {
                check(local.readBytes(intent.copyPath).contentEquals(copyBytes)) {
                    "Conflicted copy changed during sync: ${intent.copyPath}"
                }
                remote.uploadNew(intent.copyPath, copyBytes, intent.copyModifiedMillis).also { uploaded++ }
            } else {
                check(matchesCopy(existing, copy)) { "Conflicted copy destination changed: ${intent.copyPath}" }
                existing
            }
            recordFile(intent.copyPath, uploadedCopy, copyBytes)
            pending.delete(intent.path)
        }

        private suspend fun completeWinner(copy: PendingConflictCopy) {
            val intent = copy.intent
            val current = remote.snapshot().firstOrNull { it.path == intent.path && it.id == intent.remoteId }
            val localHash = localState(intent.path)
            val remoteUnchanged = current?.version == intent.remoteVersion
            if (copy.winner == Side.LOCAL) {
                val bytes = copy.localText.toByteArray(Charsets.UTF_8)
                if (localHash == intent.localHash && remoteUnchanged) {
                    recordFile(
                        intent.path,
                        remote.uploadRevision(copy.remoteEntry, bytes, intent.localModifiedMillis),
                        bytes
                    )
                    uploaded++
                } else if (localHash == intent.localHash && current != null &&
                    Hashing.sha256Hex(remote.download(current)) == intent.localHash
                ) {
                    recordFile(intent.path, current, bytes)
                }
            } else if (remoteUnchanged && (localHash == intent.localHash || localHash == intent.remoteHash)) {
                val bytes = copy.remoteText.toByteArray(Charsets.UTF_8)
                if (localHash == intent.localHash) {
                    local.writeBytes(intent.path, bytes, intent.remoteModifiedMillis)
                    downloaded++
                }
                recordFile(intent.path, copy.remoteEntry, bytes)
            }
        }

        private inline fun guarded(path: String, block: () -> Unit) {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                errors += "$path: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        /** Records a synced file and, for notes, keeps [bytes] as the base copy for later merges. */
        private suspend fun recordFile(path: String, entry: RemoteEntry, bytes: ByteArray) {
            upsertFile(path, entry, Hashing.sha256Hex(bytes))
            if (!isAttachmentPath(path)) base.put(path, bytes)
        }

        private suspend fun upsertFile(path: String, entry: RemoteEntry, hash: String) {
            metadata.upsert(SyncRecord(path, false, entry.id, entry.version, hash, clock()))
        }

        private suspend fun recordFolder(path: String, remoteId: String) {
            metadata.upsert(SyncRecord(path, true, remoteId, "", "", clock()))
        }

        private suspend fun forget(path: String) {
            metadata.delete(path)
            base.delete(path)
        }

        private suspend fun forgetTree(folder: String, paths: List<String>) {
            paths.forEach { metadata.delete(it) }
            base.delete(folder)
        }
    }
}

/** [SyncEngine]'s marker for a local folder in its planned-state map (never a valid hash). */
private const val FOLDER_STATE = "/"

private val Action.path: String
    get() = when (this) {
        is Action.Error -> path
        is Action.RecordFolder -> path
        is Action.CreateRemoteFolder -> path
        is Action.CreateLocalFolder -> path
        is Action.Forget -> path
        is Action.UploadNew -> path
        is Action.UploadRevision -> path
        is Action.Download -> path
        is Action.Record -> path
        is Action.DeleteLocal -> path
        is Action.DeleteRemote -> path
        is Action.DeleteLocalFolder -> path
        is Action.DeleteRemoteFolder -> path
        is Action.Reconcile -> path
        is Action.RenameLocal -> path
        is Action.RenameRemote -> path
    }

private fun RemoteEntry.toCore() = CoreRemoteEntry(path, id, isFolder, version, modifiedMillis)

private fun CoreRemoteEntry.toApp() = RemoteEntry(path, id, isFolder, version, modifiedMillis)

internal fun SyncRecord.toCore() = CoreSyncRecord(
    path,
    isFolder,
    remoteId,
    remoteVersion,
    localHash,
    syncedAt,
    localDeleted
)
