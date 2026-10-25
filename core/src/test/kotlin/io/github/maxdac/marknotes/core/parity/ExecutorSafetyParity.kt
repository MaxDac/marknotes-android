package io.github.maxdac.marknotes.core.parity

import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.BaseContentStore
import io.github.maxdac.marknotes.core.sync.ConflictCopyStore
import io.github.maxdac.marknotes.core.sync.FakeRemoteDriveStore
import io.github.maxdac.marknotes.core.sync.FileBaseContentStore
import io.github.maxdac.marknotes.core.sync.FileConflictCopyStore
import io.github.maxdac.marknotes.core.sync.InMemorySyncMetadataStore
import io.github.maxdac.marknotes.core.sync.PendingConflictCopy
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import io.github.maxdac.marknotes.core.sync.SyncEngine
import io.github.maxdac.marknotes.core.sync.SyncMetadataStore
import io.github.maxdac.marknotes.core.sync.SyncRecord
import io.github.maxdac.marknotes.core.sync.SyncResult
import io.github.maxdac.marknotes.sync.RemoteFolderDeletion
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Runs `sync/executor-safety` against the Android executor: the real [SyncEngine] with a local note tree on disk,
 * file-backed base copies and conflict journal, and an in-memory drive. Races are injected after the listing the
 * plan is built from; crashes abort the run at a durable boundary and a new engine restarts on the same stores.
 * Each case reports what the stores hold afterwards, keyed like the fixture's expected result.
 */
internal object ExecutorSafetyParity {
    fun run(case: FixtureCase): JsonElement {
        val scenario = case.string("scenario")
        val root = Files.createTempDirectory("marknotes-executor-safety").toFile()
        val observed = try {
            runBlocking {
                when (scenario) {
                    "folder-delete-deferred" -> DeferredFolderScenarios.run(root, case.string("event"))
                    "folder-delete-local-recreation-race" -> DeferredFolderScenarios.recreationRace(root)
                    else -> Scenarios(root).run(scenario, case.stringOrNull("crashAfter"))
                }
            }
        } finally {
            root.deleteRecursively()
        }
        val keys = case.expected.jsonObject.keys
        val missing = keys - observed.keys
        check(missing.isEmpty()) { "$case: the Android runner doesn't observe $missing" }
        return JsonObject(keys.associateWith { observed.getValue(it) })
    }
}

/** A simulated process death: an [Error], so the executor's per-action error handling can't absorb it. */
internal class SimulatedCrash(point: String) : Error("Simulated crash after $point")

/** Called around every durable or remote operation of the executor, with the operation and its path. */
internal class Hooks {
    var before: (op: String, path: String) -> Unit = { _, _ -> }
    var after: (op: String, path: String) -> Unit = { _, _ -> }

    /** Every operation started, in order. */
    val calls = mutableListOf<String>()

    inline fun <T> around(op: String, path: String, block: () -> T): T {
        calls += op
        before(op, path)
        return block().also { after(op, path) }
    }
}

@Suppress("TooManyFunctions")
internal class HookedRemote(private val drive: FakeRemoteDriveStore, private val hooks: Hooks) : RemoteDriveStore {
    /** Calls that would delete a folder without the atomic empty-only primitive. */
    var recursiveDeleteRequests = 0
        private set

    override val remoteFolderDeletion get() = drive.remoteFolderDeletion

    override suspend fun snapshot() = hooks.around("snapshot", "") { drive.snapshot() }

    override suspend fun download(entry: RemoteEntry) = hooks.around("download", entry.path) { drive.download(entry) }

    override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long) =
        hooks.around("uploadNew", path) { drive.uploadNew(path, bytes, modifiedMillis) }

    override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long) =
        hooks.around("uploadRevision", entry.path) { drive.uploadRevision(entry, bytes, modifiedMillis) }

    override suspend fun createFolder(path: String) = hooks.around("createFolder", path) { drive.createFolder(path) }

    override suspend fun delete(entry: RemoteEntry) = hooks.around("delete", entry.path) {
        if (entry.isFolder || drive.isFolder(entry.path)) recursiveDeleteRequests++
        drive.delete(entry)
    }

    override suspend fun deleteFolderIfEmpty(entry: RemoteEntry) =
        hooks.around("deleteFolderIfEmpty", entry.path) { drive.deleteFolderIfEmpty(entry) }

    override suspend fun rename(entry: RemoteEntry, toPath: String) =
        hooks.around("rename", entry.path) { drive.rename(entry, toPath) }

    override suspend fun readManifest() = hooks.around("readManifest", "") { drive.readManifest() }

    override suspend fun writeManifest(bytes: ByteArray) = hooks.around("writeManifest", "") {
        drive.writeManifest(bytes)
    }
}

@Suppress("TooManyFunctions")
internal class HookedMetadata(private val store: SyncMetadataStore, private val hooks: Hooks) : SyncMetadataStore {
    override suspend fun all() = hooks.around("metadata.all", "") { store.all() }

    override suspend fun upsert(record: SyncRecord) = hooks.around("metadata.upsert", record.path) {
        store.upsert(record)
    }

    override suspend fun delete(path: String) = hooks.around("metadata.delete", path) { store.delete(path) }

    override suspend fun rename(path: String, toPath: String) =
        hooks.around("metadata.rename", path) { store.rename(path, toPath) }

    override suspend fun clear() = hooks.around("metadata.clear", "") { store.clear() }

    override suspend fun folderNotices() = hooks.around("metadata.folderNotices", "") { store.folderNotices() }

    override suspend fun recreatedFolderIdentities() =
        hooks.around("metadata.recreatedFolderIdentities", "") { store.recreatedFolderIdentities() }

    override suspend fun preserveFolder(path: String, remoteId: String) =
        hooks.around("metadata.preserveFolder", path) { store.preserveFolder(path, remoteId) }

    override suspend fun retireRecreatedFolders(paths: List<String>) =
        hooks.around("metadata.retireRecreatedFolders", "") { store.retireRecreatedFolders(paths) }

    override suspend fun saveFolder(record: SyncRecord) =
        hooks.around("metadata.saveFolder", record.path) { store.saveFolder(record) }

    override suspend fun retireFolderDescendants(path: String) =
        hooks.around("metadata.retireFolderDescendants", path) { store.retireFolderDescendants(path) }

    override suspend fun forget(paths: List<String>) = hooks.around("metadata.forget", "") { store.forget(paths) }

    override suspend fun acknowledgeFolderNotice(generation: Long) =
        hooks.around("metadata.acknowledgeFolderNotice", "") { store.acknowledgeFolderNotice(generation) }
}

internal class HookedBase(private val store: BaseContentStore, private val hooks: Hooks) : BaseContentStore {
    override fun get(path: String) = hooks.around("base.get", path) { store.get(path) }

    override fun put(path: String, bytes: ByteArray) = hooks.around("base.put", path) { store.put(path, bytes) }

    override fun delete(path: String) = hooks.around("base.delete", path) { store.delete(path) }

    override fun rename(path: String, toPath: String) =
        hooks.around("base.rename", path) { store.rename(path, toPath) }

    override fun clear() = hooks.around("base.clear", "") { store.clear() }
}

internal class HookedConflicts(private val store: ConflictCopyStore, private val hooks: Hooks) : ConflictCopyStore {
    override fun all() = hooks.around("conflicts.all", "") { store.all() }

    override fun put(pending: PendingConflictCopy) =
        hooks.around("conflicts.put", pending.intent.path) { store.put(pending) }

    override fun delete(path: String) = hooks.around("conflicts.delete", path) { store.delete(path) }

    override fun clear() = hooks.around("conflicts.clear", "") { store.clear() }
}

/** One device and one drive. [restart] drops the engine and reopens the durable stores from disk. */
private class Scenarios(private val root: File) {
    private val hooks = Hooks()
    private val drive = FakeRemoteDriveStore()
    private val remote = HookedRemote(drive, hooks)
    private val local = LocalNoteStore(File(root, "notes"))

    /** Stands in for the app's Room database, which survives a restart. */
    private val metadata = HookedMetadata(InMemorySyncMetadataStore(), hooks)
    private lateinit var base: BaseContentStore
    private lateinit var conflicts: ConflictCopyStore

    init {
        restart()
    }

    private fun restart() {
        base = HookedBase(FileBaseContentStore(File(root, "state/base")), hooks)
        conflicts = HookedConflicts(FileConflictCopyStore(File(root, "state/conflicts.json")), hooks)
    }

    private suspend fun sync(): SyncResult = SyncEngine(
        local,
        remote,
        metadata,
        base,
        driveId = "drive",
        deviceId = "device",
        pending = conflicts,
        clock = { CLOCK }
    ).sync()

    private suspend fun syncCleanly() {
        val result = sync()
        check(result.errors.isEmpty()) { "Setup sync failed: ${result.errors}" }
    }

    /** Runs [block] once, right after the next remote listing (the one the plan is built from). */
    private fun afterListing(block: () -> Unit) {
        drive.afterSnapshot = block
    }

    private suspend fun recorded(path: String) = metadata.all().any { it.path == path }

    suspend fun run(scenario: String, crashAfter: String?): Map<String, JsonElement> = when (scenario) {
        "folder-delete-concurrent-upload",
        "folder-delete-unsupported",
        "folder-delete-default-unsupported",
        "folder-delete-runtime-failure",
        "folder-delete-child-revision-race",
        "folder-delete-unsupported-child-revision-race" -> folderDelete(scenario)

        "new-file-create-race" -> newFileCreateRace()

        "new-folder-create-race" -> newFolderCreateRace()

        "conflict-copy-restart" -> conflictCopyRestart(requireNotNull(crashAfter) { "crashAfter is required" })

        else -> error("Unknown executor-safety scenario: $scenario")
    }

    /** F/a.md is synced, then local F is deleted and the next sync plans the remote deletions. */
    private suspend fun folderDelete(scenario: String): Map<String, JsonElement> {
        local.write("F/a.md", "a\n")
        syncCleanly()
        local.delete("F")
        if ("unsupported" in scenario) {
            // Without the atomic primitive the inherited deleteFolderIfEmpty throws. The default scenario keeps
            // the default ATOMIC_EMPTY_ONLY declaration, so only the others declare their removal unsupported.
            drive.supportsSafeFolderDeletion = false
            if (scenario != "folder-delete-default-unsupported") {
                drive.remoteFolderDeletion = RemoteFolderDeletion.UNSUPPORTED
            }
        }
        when (scenario) {
            "folder-delete-concurrent-upload" -> afterListing { drive.putFile("F/new.md", "unseen\n", 9) }

            "folder-delete-runtime-failure" -> hooks.before = { op, _ ->
                if (op == "deleteFolderIfEmpty") throw IOException("Injected folder removal failure")
            }

            "folder-delete-child-revision-race",
            "folder-delete-unsupported-child-revision-race" -> afterListing {
                drive.putFile("F/a.md", "changed\n", 10)
            }
        }
        val result = sync()
        return mapOf(
            "errors" to JsonPrimitive(result.errors.size),
            "folderRecordRetained" to JsonPrimitive(recorded("F")),
            "unseenPreserved" to JsonPrimitive(drive.content("F/new.md") == "unseen\n"),
            "changedChildPreserved" to JsonPrimitive(drive.content("F/a.md") == "changed\n"),
            "childRecordRetained" to JsonPrimitive(recorded("F/a.md")),
            "recursiveDeleteRequests" to JsonPrimitive(remote.recursiveDeleteRequests),
            "notices" to JsonPrimitive(metadata.folderNotices().size)
        )
    }

    private suspend fun newFileCreateRace(): Map<String, JsonElement> {
        local.write("p.md", "local\n")
        afterListing { drive.putFile("p.md", "existing\n", 8) }
        val result = sync()
        return mapOf(
            "errors" to JsonPrimitive(result.errors.size),
            "existingTextPreserved" to JsonPrimitive(drive.content("p.md") == "existing\n"),
            "creationRecorded" to JsonPrimitive(recorded("p.md"))
        )
    }

    private suspend fun newFolderCreateRace(): Map<String, JsonElement> {
        local.createFolder("F")
        var raced: String? = null
        afterListing {
            drive.putFolder("F")
            raced = drive.entry("F")?.id
        }
        val result = sync()
        return mapOf(
            "errors" to JsonPrimitive(result.errors.size),
            "existingIdentityPreserved" to JsonPrimitive(raced != null && drive.entry("F")?.id == raced),
            "creationRecorded" to JsonPrimitive(recorded("F"))
        )
    }

    /**
     * p.md is edited on both sides after a sync; the newer drive edit wins and the local edit (time 1000) is saved
     * as a conflicted copy. The run crashes right after [crashAfter], then a restarted engine completes it.
     */
    @Suppress("CyclomaticComplexMethod")
    private suspend fun conflictCopyRestart(crashAfter: String): Map<String, JsonElement> {
        local.write("p.md", "base\n")
        syncCleanly()
        local.write("p.md", LOSING)
        check(local.file("p.md").setLastModified(LOSING_MILLIS))
        drive.putFile("p.md", WINNING, LOSING_MILLIS * 2)

        var crashed = false
        fun crash(): Nothing {
            crashed = true
            throw SimulatedCrash(crashAfter)
        }
        val copyWritten = { local.listAll().any { isCopy(it.path) } }
        val winnerWritten = { local.file("p.md").readText() == WINNING }
        hooks.before = { _, _ ->
            // A local write isn't a hooked operation: crash before whatever durable step comes next.
            if (!crashed && crashAfter == "copy" && copyWritten()) crash()
            if (!crashed && crashAfter == "winner" && winnerWritten()) crash()
        }
        hooks.after = { op, path ->
            val point = when {
                op == "conflicts.put" -> "intent"
                op == "metadata.upsert" && path == "p.md" -> "winner-record"
                op == "uploadNew" && isCopy(path) -> "copy-upload"
                op == "metadata.upsert" && isCopy(path) -> "copy-record"
                else -> null
            }
            if (!crashed && point == crashAfter) crash()
        }
        val crashRun = runCatching { sync() }
        check(crashed && crashRun.exceptionOrNull() is SimulatedCrash) {
            "Crash point not reached: $crashAfter (${crashRun.exceptionOrNull() ?: crashRun.getOrNull()})"
        }
        hooks.before = { _, _ -> }
        hooks.after = { _, _ -> }

        restart()
        val errors = sync().errors.size + sync().errors.size
        val copies = (local.listAll().map { it.path } + drive.snapshot().map { it.path }).filter(::isCopy).distinct()
        val copy = copies.firstOrNull()?.takeIf { local.file(it).isFile }
        val copyText = copy?.let(local::read)
        val copyMillis = copy?.let(local::modifiedMillis)
        val preserved = copy != null && FrontMatter.parse(copyText!!).body == LOSING &&
            drive.content(copy) == copyText && drive.entry(copy)?.modifiedMillis == copyMillis
        return mapOf(
            "errors" to JsonPrimitive(errors),
            "copyCount" to JsonPrimitive(copies.size),
            "copyModifiedMillis" to (copyMillis?.let(::JsonPrimitive) ?: JsonNull),
            "losingTextPreserved" to JsonPrimitive(preserved),
            "intentCleared" to JsonPrimitive(conflicts.all().isEmpty())
        )
    }

    private fun isCopy(path: String) = "(conflicted copy)" in path

    private companion object {
        const val CLOCK = 5_000L
        const val LOSING = "losing\n"
        const val WINNING = "winning\n"
        const val LOSING_MILLIS = 1_000L
    }
}
