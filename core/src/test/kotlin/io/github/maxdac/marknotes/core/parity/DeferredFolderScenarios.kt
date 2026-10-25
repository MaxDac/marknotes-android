package io.github.maxdac.marknotes.core.parity

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.BaseContentStore
import io.github.maxdac.marknotes.core.sync.ConflictCopyStore
import io.github.maxdac.marknotes.core.sync.DriveSyncTarget
import io.github.maxdac.marknotes.core.sync.FakeRemoteDriveStore
import io.github.maxdac.marknotes.core.sync.FileBaseContentStore
import io.github.maxdac.marknotes.core.sync.FileConflictCopyStore
import io.github.maxdac.marknotes.core.sync.Hashing
import io.github.maxdac.marknotes.core.sync.InMemorySyncMetadataStore
import io.github.maxdac.marknotes.core.sync.MultiDriveSync
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import io.github.maxdac.marknotes.core.sync.SyncRecord
import io.github.maxdac.marknotes.sync.RemoteFolderDeletion
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `folder-delete-deferred` and `folder-delete-local-recreation-race` scenarios: deferred remote folder removal on
 * drives that declare it unsupported. Sessions go through [MultiDriveSync], as in the app, so the all-drive local
 * recreation preflight runs before any drive is planned. The metadata stores stand in for the app's Room database:
 * they survive a restart and share the device's notice generations.
 */
internal class DeferredFolderScenarios private constructor(private val root: File, driveCount: Int) {
    private val hooks = Hooks()
    private val drives = List(driveCount) { FakeRemoteDriveStore() }
    private val remotes = drives.map { HookedRemote(it, hooks) }
    private val generations = AtomicLong()
    private val stores = drives.map { InMemorySyncMetadataStore(generations) }
    private val metadata = stores.map { HookedMetadata(it, hooks) }
    private val local = LocalNoteStore(File(root, "notes"))
    private lateinit var bases: List<BaseContentStore>
    private lateinit var conflicts: List<ConflictCopyStore>
    private val drive get() = drives[0]

    init {
        restart()
    }

    private class Session(val errors: Int, val createdLocalFolders: Int)

    private fun restart() {
        bases = drives.indices.map { HookedBase(FileBaseContentStore(File(root, "state/$it/base")), hooks) }
        conflicts = drives.indices.map {
            HookedConflicts(FileConflictCopyStore(File(root, "state/$it/conflicts.json")), hooks)
        }
    }

    /** One sync of every drive. A drive whose sync stops (for example on a failed listing) counts as one error. */
    private suspend fun session(): Session {
        val targets = drives.indices.map {
            DriveSyncTarget("drive$it", remotes[it], metadata[it], bases[it], conflicts[it])
        }
        val outcomes = MultiDriveSync(local, "device", clock = { CLOCK }).sync(targets).outcomes
        return Session(
            outcomes.sumOf { it.result?.errors?.size ?: 1 },
            outcomes.sumOf { it.result?.createdLocalFolders ?: 0 }
        )
    }

    private suspend fun crashingSession() {
        val failure = runCatching { session() }.exceptionOrNull()
        check(failure is SimulatedCrash) { "The crash point wasn't reached: $failure" }
    }

    /** Throws [fault] the first time [op] runs: before it, or after it committed when [afterCommit]. */
    private fun once(op: String, afterCommit: Boolean = false, fault: () -> Throwable) {
        var fired = false
        val hook = { called: String, _: String ->
            if (!fired && called == op) {
                fired = true
                throw fault()
            }
        }
        if (afterCommit) hooks.after = hook else hooks.before = hook
    }

    private fun clearFaults() {
        hooks.before = { _, _ -> }
        hooks.after = { _, _ -> }
    }

    /** [path] was synced to every drive, then its top folder F was deleted locally. */
    private suspend fun deletedFolder(path: String) {
        local.write(path, "a\n")
        check(session().errors == 0) { "Setup sync failed" }
        local.delete("F")
        for (drive in drives) declareUnsupported(drive, true)
    }

    private fun declareUnsupported(drive: FakeRemoteDriveStore, unsupported: Boolean) {
        drive.supportsSafeFolderDeletion = !unsupported
        drive.remoteFolderDeletion =
            if (unsupported) RemoteFolderDeletion.UNSUPPORTED else RemoteFolderDeletion.ATOMIC_EMPTY_ONLY
    }

    private suspend fun record(path: String, store: Int = 0): SyncRecord? =
        stores[store].all().firstOrNull { it.path == path }

    private fun localText(path: String): String? = local.file(path).takeIf { it.isFile }?.readText()

    /** Folder actions a sync performs: preservations, remote folder creations and removals, local folders made. */
    private suspend fun folderActions(): Pair<Int, Int> {
        val start = hooks.calls.size
        val recursive = remotes.sumOf { it.recursiveDeleteRequests }
        val result = session()
        val calls = hooks.calls.drop(start)
        val actions = calls.count { it in FOLDER_OPS } + result.createdLocalFolders +
            remotes.sumOf { it.recursiveDeleteRequests } - recursive
        return result.errors to actions
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    private suspend fun deferred(event: String): Map<String, JsonElement> {
        deletedFolder(if (event == "unchanged-subfolders") "F/Sub/a.md" else "F/a.md")
        val oldId = drive.entry("F")!!.id
        val baseline = record("F/a.md")
        when (event) {
            "before-preserve-save" -> once("metadata.preserveFolder") { SimulatedCrash("check preserve") }
            "after-preserve-save" -> once("metadata.preserveFolder", afterCommit = true) { SimulatedCrash("preserve") }
            "preserve-save-failure" -> once("metadata.preserveFolder") { IOException("Injected preserve failure") }
            "identity-replacement-restart" -> once("delete") { IOException("Injected drive delete failure") }
        }
        var errors = 0
        if (event == "before-preserve-save" || event == "after-preserve-save") {
            crashingSession()
        } else {
            errors += session().errors
        }
        clearFaults()
        restart()
        if (event in setOf("before-preserve-save", "after-preserve-save", "preserve-save-failure")) {
            errors += session().errors
        }
        var oldDescendantHistoryRetired: Boolean? = null
        val childBefore = drive.entry("F/a.md")
        val childBeforeText = drive.content("F/a.md")
        when (event) {
            "restart-acknowledged" -> {
                metadata[0].acknowledgeFolderNotice(stores[0].folderNotices().first().generation)
                restart()
            }

            "ack-save-failure" -> {
                once("metadata.acknowledgeFolderNotice") { IOException("Injected acknowledgement failure") }
                val acknowledged = runCatching {
                    metadata[0].acknowledgeFolderNotice(stores[0].folderNotices().first().generation)
                }
                check(acknowledged.isFailure) { "The acknowledgement failure wasn't injected" }
                errors++
                clearFaults()
                restart()
            }

            "new-child" -> drive.putFile("F/new.md", "new\n", 9)

            "changed-child" -> {
                val entry = drive.putFile("F/a.md", "base\n", 9)
                stores[0].upsert(
                    checkNotNull(baseline).copy(
                        remoteId = entry.id,
                        remoteVersion = entry.version,
                        localHash = Hashing.sha256Hex("base\n".toByteArray())
                    )
                )
                drive.putFile("F/a.md", "changed\n", 10)
            }

            "ignored-child" -> drive.putFile("F/keep.pdf", "ignored\n", 9)

            "new-empty-subfolder" -> drive.putFolder("F/New")

            "local-recreation" -> {
                local.createFolder("F")
                errors += session().errors
                local.delete("F")
            }

            "local-recreation-remote-missing", "local-recreation-preflight-restart" -> {
                // The user removes the empty folder on the drive, outside the unsupported adapter.
                check(drive.entry("F")?.isFolder == true && drive.snapshot().none { it.path.startsWith("F/") })
                drive.remove("F")
                local.createFolder("F")
                if (event == "local-recreation-preflight-restart") {
                    once("metadata.retireRecreatedFolders", afterCommit = true) { SimulatedCrash("preflight") }
                    crashingSession()
                    clearFaults()
                    restart()
                }
            }

            "remote-disappearance" -> drive.remove("F")

            "identity-replacement" -> {
                drive.remove("F")
                drive.putFolder("F")
                drive.putFile("F/a.md", "replacement\n", 10)
            }

            "identity-replacement-restart" -> {
                drive.replaceFolderIdentity("F")
                once("download") { IOException("Injected drive read failure") }
                errors += session().errors
                clearFaults()
                oldDescendantHistoryRetired = record("F/a.md") == null && bases[0].get("F/a.md") == null
                restart()
            }

            "multi-drive" -> {
                local.createFolder("F")
                val second = drives[1]
                check(second.entry("F/a.md") == null) { "The second drive still has the child" }
                second.remove("F")
                errors += session().errors
            }

            "supported" -> declareUnsupported(drive, false)

            "failed-listing" -> once("snapshot") { IOException("Injected listing failure") }
        }
        errors += session().errors
        clearFaults()
        var repeatActions = 0
        if (event in REPEAT_EVENTS) {
            val (repeatErrors, actions) = folderActions()
            errors += repeatErrors
            repeatActions = actions
        }
        return observe(errors, repeatActions, oldId, oldDescendantHistoryRetired, childBefore, childBeforeText)
    }

    @Suppress("LongParameterList")
    private suspend fun observe(
        errors: Int,
        repeatActions: Int,
        oldId: String,
        oldDescendantHistoryRetired: Boolean?,
        childBefore: RemoteEntry?,
        childBeforeText: String?
    ): Map<String, JsonElement> {
        val notices = stores.flatMap { it.folderNotices() }
        val folder = record("F")
        val remoteFolder = drive.entry("F")?.takeIf { it.isFolder }
        return mapOf(
            "errors" to JsonPrimitive(errors),
            "notices" to JsonPrimitive(notices.size),
            "pendingNotices" to JsonPrimitive(notices.count { it.pending }),
            "acknowledgedNotices" to JsonPrimitive(notices.count { it.acknowledged }),
            "activeNotices" to JsonPrimitive(notices.count { it.active }),
            "localFolderExists" to JsonPrimitive(local.file("F").isDirectory),
            "remoteFolderExists" to JsonPrimitive(remoteFolder != null),
            "folderRecordRetained" to JsonPrimitive(folder != null),
            "preserved" to JsonPrimitive(folder?.remoteFolderPreserved == true),
            "repeatActions" to JsonPrimitive(repeatActions),
            "recursiveDeleteRequests" to JsonPrimitive(remotes[0].recursiveDeleteRequests),
            "newChildDownloaded" to JsonPrimitive(localText("F/new.md") == "new\n"),
            "changedChildDownloaded" to JsonPrimitive(localText("F/a.md") == "changed\n"),
            "ignoredChildPreserved" to JsonPrimitive(drive.exists("F/keep.pdf")),
            "ignoredChildDownloaded" to JsonPrimitive(local.file("F/keep.pdf").exists()),
            "localSubfolderExists" to JsonPrimitive(local.file("F/New").isDirectory),
            "sameRemoteIdentity" to JsonPrimitive(remoteFolder?.id == oldId),
            "replacementChildDownloaded" to JsonPrimitive(localText("F/a.md") == "replacement\n"),
            "replacementIdentityRecorded" to JsonPrimitive(
                remoteFolder != null && folder?.remoteId == remoteFolder.id && remoteFolder.id != oldId
            ),
            "distinctNoticeDrives" to JsonPrimitive(stores.count { it.folderNotices().isNotEmpty() }),
            "bothRemoteFoldersExist" to JsonPrimitive(drives.all { it.isFolder("F") }),
            "preservedRecords" to JsonPrimitive(stores.sumOf { s -> s.all().count { it.remoteFolderPreserved } }),
            "oldDescendantHistoryRetired" to (oldDescendantHistoryRetired?.let(::JsonPrimitive) ?: JsonNull),
            "childDownloaded" to JsonPrimitive(childBeforeText != null && localText("F/a.md") == childBeforeText),
            "remoteChildPreserved" to JsonPrimitive(
                childBefore != null && drive.entry("F/a.md") == childBefore &&
                    drive.content("F/a.md") == childBeforeText
            )
        )
    }

    /** F is recreated locally after the plan was made, before its preservation runs: nothing may be preserved. */
    private suspend fun recreationRace(): Map<String, JsonElement> {
        deletedFolder("F/a.md")
        drive.afterSnapshot = { local.createFolder("F") }
        val errors = session().errors
        val folder = record("F")
        return mapOf(
            "errors" to JsonPrimitive(errors),
            "notices" to JsonPrimitive(stores[0].folderNotices().size),
            "localFolderExists" to JsonPrimitive(local.file("F").isDirectory),
            "remoteFolderExists" to JsonPrimitive(drive.isFolder("F")),
            "preserved" to JsonPrimitive(folder?.remoteFolderPreserved == true),
            "recursiveDeleteRequests" to JsonPrimitive(remotes[0].recursiveDeleteRequests)
        )
    }

    companion object {
        private const val CLOCK = 5_000L
        private val FOLDER_OPS = setOf("metadata.preserveFolder", "createFolder", "deleteFolderIfEmpty")

        /** Events followed by one more sync, which must not repeat any folder action. */
        private val REPEAT_EVENTS = setOf(
            "restart-pending",
            "restart-acknowledged",
            "unchanged-subfolders",
            "before-preserve-save",
            "after-preserve-save",
            "preserve-save-failure",
            "ack-save-failure"
        )

        suspend fun run(root: File, event: String) =
            DeferredFolderScenarios(root, if (event == "multi-drive") 2 else 1).deferred(event)

        suspend fun recreationRace(root: File) = DeferredFolderScenarios(root, 1).recreationRace()
    }
}
