package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.sync.LocalEntry
import io.github.maxdac.marknotes.sync.localDeletions
import io.github.maxdac.marknotes.sync.recreatedFolders
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/** What we knew about a path the last time it was in sync on both sides. */
data class SyncRecord(
    val path: String,
    val isFolder: Boolean,
    val remoteId: String,
    val remoteVersion: String,
    val localHash: String,
    val syncedAt: Long,
    val localDeleted: Boolean = false,
    /**
     * The folder was deleted locally but the drive can't safely remove an empty folder, so it was left there and
     * the user was asked to remove it (see [FolderNotice]). Bound to [remoteId].
     */
    val remoteFolderPreserved: Boolean = false
)

/**
 * The one-time notice asking the user to remove a locally deleted folder from a drive that can't remove it safely.
 * [generation] is unique on the device and identifies one deletion lifecycle; [active] turns false when that
 * lifecycle ends (the folder is recreated, synced again, disappears from the drive or is replaced).
 */
data class FolderNotice(
    val path: String,
    val remoteId: String,
    val generation: Long,
    val acknowledged: Boolean,
    val active: Boolean
) {
    /** Shown to the user until acknowledged. */
    val pending: Boolean get() = active && !acknowledged
}

/**
 * Sync history of one drive. Every method is atomic: implementations backed by a database run each one in a single
 * transaction, so a failure leaves the previous state.
 */
interface SyncMetadataStore {
    suspend fun all(): List<SyncRecord>
    suspend fun upsert(record: SyncRecord)
    suspend fun delete(path: String)

    /** Moves the records, folder notices and recreation evidence at and below [path] to [toPath]. */
    suspend fun rename(path: String, toPath: String)

    /** Deletes every record, folder notice and piece of recreation evidence of the drive. */
    suspend fun clear()

    /** Folder notices of the drive, including acknowledged and retired ones. */
    suspend fun folderNotices(): List<FolderNotice>

    /** Remote folder identities whose preserved folder record was retired by a local recreation, by path. */
    suspend fun recreatedFolderIdentities(): Map<String, String>

    /**
     * Marks the folder record at [path] preserved and issues a new pending [FolderNotice] with a new generation.
     * Does nothing when the record is already preserved. Fails when there is no folder record with [remoteId].
     */
    suspend fun preserveFolder(path: String, remoteId: String)

    /**
     * Local recreation preflight: removes the (preserved) folder records at [paths], retires their active notices
     * and keeps their remote identity as evidence, leaving descendant records alone.
     */
    suspend fun retireRecreatedFolders(paths: List<String>)

    /**
     * Saves a synced folder record (never preserved). When it replaces a folder record with another remote identity,
     * the old descendant records and their notices are retired with it. Also retires the path's active notice and
     * its recreation evidence.
     */
    suspend fun saveFolder(record: SyncRecord)

    /** Retires the records and active notices below [path] (not [path] itself): its remote identity was replaced. */
    suspend fun retireFolderDescendants(path: String)

    /** Deletes the records at [paths] and retires their active notices and recreation evidence. */
    suspend fun forget(paths: List<String>)

    /** Durably acknowledges the notice [generation] of this drive; fails when there is none. */
    suspend fun acknowledgeFolderNotice(generation: Long)
}

/**
 * Before planning any drive: flags the records of paths deleted locally and ends the deferred removal of the
 * preserved folders that exist locally again (see [SyncMetadataStore.retireRecreatedFolders]).
 */
internal suspend fun rememberLocalDeletions(entries: List<LocalEntry>, metadata: SyncMetadataStore) {
    val records = metadata.all()
    val cores = records.map { it.toCore() }
    val deleted = localDeletions(entries, cores).toSet()
    for (record in records) {
        if (record.path in deleted && !record.localDeleted) metadata.upsert(record.copy(localDeleted = true))
    }
    val recreated = recreatedFolders(entries, cores)
    if (recreated.isNotEmpty()) metadata.retireRecreatedFolders(recreated)
}

/**
 * In-memory [SyncMetadataStore]. Stores of different drives on one device share [generations], so notice
 * generations are unique on the device.
 */
class InMemorySyncMetadataStore(private val generations: AtomicLong = AtomicLong()) : SyncMetadataStore {
    private val records = linkedMapOf<String, SyncRecord>()
    private val notices = mutableListOf<FolderNotice>()
    private val identities = linkedMapOf<String, String>()

    /** Names of the methods whose writes fail, to test that callers keep the previous state. */
    val failingWrites = mutableSetOf<String>()

    private inline fun <T> write(name: String, block: () -> T): T = synchronized(records) {
        if (name in failingWrites) throw IOException("Simulated $name failure")
        val saved = Triple(LinkedHashMap(records), notices.toList(), LinkedHashMap(identities))
        try {
            block()
        } catch (e: Exception) {
            records.clear()
            records.putAll(saved.first)
            notices.clear()
            notices.addAll(saved.second)
            identities.clear()
            identities.putAll(saved.third)
            throw e
        }
    }

    override suspend fun all(): List<SyncRecord> = synchronized(records) { records.values.toList() }

    override suspend fun upsert(record: SyncRecord) = write("upsert") { records[record.path] = record }

    override suspend fun delete(path: String) {
        write("delete") { records.remove(path) }
    }

    override suspend fun rename(path: String, toPath: String) = write("rename") {
        fun moved(key: String) = toPath + key.removePrefix(path)
        fun inside(key: String) = key == path || NotePaths.isAncestor(path, key)
        val movedRecords = records.filterKeys(::inside)
        movedRecords.keys.forEach(records::remove)
        movedRecords.forEach { (key, record) -> records[moved(key)] = record.copy(path = moved(key)) }
        notices.replaceAll { if (inside(it.path)) it.copy(path = moved(it.path)) else it }
        val movedIdentities = identities.filterKeys(::inside)
        movedIdentities.keys.forEach(identities::remove)
        movedIdentities.forEach { (key, id) -> identities[moved(key)] = id }
    }

    override suspend fun clear() = write("clear") {
        records.clear()
        notices.clear()
        identities.clear()
    }

    override suspend fun folderNotices(): List<FolderNotice> = synchronized(records) { notices.toList() }

    override suspend fun recreatedFolderIdentities(): Map<String, String> =
        synchronized(records) { LinkedHashMap(identities) }

    override suspend fun preserveFolder(path: String, remoteId: String) = write("preserveFolder") {
        val record = records[path]?.takeIf { it.isFolder && it.remoteId == remoteId }
            ?: throw IOException("folder record identity changed")
        if (!record.remoteFolderPreserved) {
            records[path] = record.copy(remoteFolderPreserved = true)
            notices += FolderNotice(path, remoteId, generations.incrementAndGet(), acknowledged = false, active = true)
        }
    }

    override suspend fun retireRecreatedFolders(paths: List<String>) = write("retireRecreatedFolders") {
        for (path in paths) {
            val record = records.remove(path) ?: continue
            retireNotice(path)
            identities[path] = record.remoteId
        }
    }

    override suspend fun saveFolder(record: SyncRecord) = write("saveFolder") {
        val old = records[record.path]
        if (old != null && old.isFolder && old.remoteId != record.remoteId) retireDescendants(record.path)
        identities.remove(record.path)
        retireNotice(record.path)
        records[record.path] = record.copy(isFolder = true, localDeleted = false, remoteFolderPreserved = false)
    }

    override suspend fun retireFolderDescendants(path: String) =
        write("retireFolderDescendants") { retireDescendants(path) }

    override suspend fun forget(paths: List<String>) = write("forget") {
        for (path in paths) {
            records.remove(path)
            retireNotice(path)
            identities.remove(path)
        }
    }

    override suspend fun acknowledgeFolderNotice(generation: Long) = write("acknowledgeFolderNotice") {
        val index = notices.indexOfFirst { it.generation == generation }
        if (index < 0) throw IOException("folder notice not found")
        notices[index] = notices[index].copy(acknowledged = true)
    }

    private fun retireNotice(path: String) {
        notices.replaceAll { if (it.path == path && it.active) it.copy(active = false) else it }
    }

    private fun retireDescendants(path: String) {
        records.keys.removeAll { NotePaths.isAncestor(path, it) }
        notices.replaceAll { if (NotePaths.isAncestor(path, it.path) && it.active) it.copy(active = false) else it }
    }
}
