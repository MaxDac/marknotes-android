package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.sync.LocalEntry
import io.github.maxdac.marknotes.sync.localDeletions

/** What we knew about a path the last time it was in sync on both sides. */
data class SyncRecord(
    val path: String,
    val isFolder: Boolean,
    val remoteId: String,
    val remoteVersion: String,
    val localHash: String,
    val syncedAt: Long,
    val localDeleted: Boolean = false
)

interface SyncMetadataStore {
    suspend fun all(): List<SyncRecord>
    suspend fun upsert(record: SyncRecord)
    suspend fun delete(path: String)
    suspend fun rename(path: String, toPath: String)
    suspend fun clear()
}

internal suspend fun rememberLocalDeletions(entries: List<LocalEntry>, metadata: SyncMetadataStore) {
    val records = metadata.all()
    val deleted = localDeletions(entries, records.map { it.toCore() }).toSet()
    for (record in records) {
        if (record.path in deleted && !record.localDeleted) metadata.upsert(record.copy(localDeleted = true))
    }
}

class InMemorySyncMetadataStore : SyncMetadataStore {
    private val records = linkedMapOf<String, SyncRecord>()

    override suspend fun all(): List<SyncRecord> = synchronized(records) { records.values.toList() }

    override suspend fun upsert(record: SyncRecord) {
        synchronized(records) { records[record.path] = record }
    }

    override suspend fun delete(path: String) {
        synchronized(records) { records.remove(path) }
    }

    override suspend fun rename(path: String, toPath: String) {
        synchronized(records) {
            val moved = records.filterKeys { it == path || it.startsWith("$path/") }
            moved.keys.forEach(records::remove)
            moved.forEach { (key, record) ->
                val newPath = toPath + key.removePrefix(path)
                records[newPath] = record.copy(path = newPath)
            }
        }
    }

    override suspend fun clear() {
        synchronized(records) { records.clear() }
    }
}
