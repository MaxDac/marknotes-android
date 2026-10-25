package io.github.maxdac.marknotes.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.FolderNotice
import io.github.maxdac.marknotes.core.sync.SyncMetadataStore
import io.github.maxdac.marknotes.core.sync.SyncRecord
import java.io.IOException
import kotlinx.coroutines.flow.Flow

/** Sync state of one path on one drive. */
@Entity(tableName = "sync_records", primaryKeys = ["driveId", "path"])
data class SyncRecordEntity(
    val driveId: String,
    val path: String,
    val isFolder: Boolean,
    val remoteId: String,
    val remoteVersion: String,
    val localHash: String,
    val syncedAt: Long,
    @ColumnInfo(defaultValue = "0") val localDeleted: Boolean = false,
    @ColumnInfo(defaultValue = "0") val remoteFolderPreserved: Boolean = false
)

/**
 * A manual-removal notice for a folder deleted locally that a drive can't remove (see
 * [io.github.maxdac.marknotes.core.sync.FolderNotice]). The auto-increment [generation] is never reused.
 */
@Entity(tableName = "folder_notices")
data class FolderNoticeEntity(
    @PrimaryKey(autoGenerate = true) val generation: Long = 0,
    val driveId: String,
    val path: String,
    val remoteId: String,
    val acknowledged: Boolean,
    val active: Boolean
) {
    fun toNotice() = FolderNotice(path, remoteId, generation, acknowledged, active)
}

/** Remote identity of a preserved folder whose record was retired because the folder was recreated locally. */
@Entity(tableName = "recreated_folder_identities", primaryKeys = ["driveId", "path"])
data class RecreatedFolderIdentityEntity(val driveId: String, val path: String, val remoteId: String)

@Entity(tableName = "drive_accounts")
data class DriveAccountEntity(
    @PrimaryKey val id: String,
    val provider: String,
    val label: String,
    val remoteFolder: String,
    val createRootFolder: Boolean,
    val rootFolderName: String,
    val lastSyncAt: Long,
    val lastMessage: String?
) {
    fun toAccount(): DriveAccount? {
        val provider = DriveProvider.entries.firstOrNull { it.name == provider } ?: return null
        return DriveAccount(id, provider, label, remoteFolder, createRootFolder, rootFolderName)
    }

    companion object {
        fun from(account: DriveAccount, lastSyncAt: Long = 0L, lastMessage: String? = null) = DriveAccountEntity(
            account.id,
            account.provider.name,
            account.label,
            account.remoteFolder,
            account.createRootFolder,
            account.rootFolderName,
            lastSyncAt,
            lastMessage
        )
    }
}

@Dao
@Suppress("TooManyFunctions")
interface SyncRecordDao {
    @Query("SELECT * FROM sync_records WHERE driveId = :driveId")
    suspend fun all(driveId: String): List<SyncRecordEntity>

    @Query("SELECT * FROM sync_records WHERE driveId = :driveId AND path = :path")
    suspend fun get(driveId: String, path: String): SyncRecordEntity?

    @Upsert
    suspend fun upsert(entity: SyncRecordEntity)

    @Query("DELETE FROM sync_records WHERE driveId = :driveId AND path = :path")
    suspend fun delete(driveId: String, path: String)

    @Query("SELECT * FROM folder_notices WHERE driveId = :driveId ORDER BY generation")
    suspend fun notices(driveId: String): List<FolderNoticeEntity>

    /** Notices of every drive waiting for the user's acknowledgement. */
    @Query("SELECT * FROM folder_notices WHERE active = 1 AND acknowledged = 0 ORDER BY generation")
    fun observePendingNotices(): Flow<List<FolderNoticeEntity>>

    @Insert
    suspend fun insertNotice(notice: FolderNoticeEntity): Long

    @Query("UPDATE folder_notices SET path = :path WHERE generation = :generation")
    suspend fun moveNotice(generation: Long, path: String)

    @Query("UPDATE folder_notices SET active = 0 WHERE driveId = :driveId AND path = :path AND active = 1")
    suspend fun retireNotice(driveId: String, path: String)

    @Query("UPDATE folder_notices SET active = 0 WHERE generation = :generation")
    suspend fun retireNotice(generation: Long)

    /** Returns the number of notices acknowledged: 0 when [generation] isn't a notice of [driveId]. */
    @Query("UPDATE folder_notices SET acknowledged = 1 WHERE driveId = :driveId AND generation = :generation")
    suspend fun acknowledgeNotice(driveId: String, generation: Long): Int

    @Query("SELECT * FROM recreated_folder_identities WHERE driveId = :driveId")
    suspend fun recreatedIdentities(driveId: String): List<RecreatedFolderIdentityEntity>

    @Upsert
    suspend fun upsertIdentity(entity: RecreatedFolderIdentityEntity)

    @Query("DELETE FROM recreated_folder_identities WHERE driveId = :driveId AND path = :path")
    suspend fun deleteIdentity(driveId: String, path: String)

    @Transaction
    suspend fun rename(driveId: String, path: String, toPath: String) {
        fun inside(p: String) = p == path || p.startsWith("$path/")
        fun moved(p: String) = toPath + p.removePrefix(path)
        val moved = all(driveId).filter { inside(it.path) }
        moved.forEach { delete(driveId, it.path) }
        moved.forEach { upsert(it.copy(path = moved(it.path))) }
        notices(driveId).filter { inside(it.path) }.forEach { moveNotice(it.generation, moved(it.path)) }
        val identities = recreatedIdentities(driveId).filter { inside(it.path) }
        identities.forEach { deleteIdentity(driveId, it.path) }
        identities.forEach { upsertIdentity(it.copy(path = moved(it.path))) }
    }

    @Transaction
    suspend fun preserveFolder(driveId: String, path: String, remoteId: String) {
        val record = get(driveId, path)?.takeIf { it.isFolder && it.remoteId == remoteId }
            ?: throw IOException("folder record identity changed")
        if (record.remoteFolderPreserved) return
        upsert(record.copy(remoteFolderPreserved = true))
        insertNotice(
            FolderNoticeEntity(driveId = driveId, path = path, remoteId = remoteId, acknowledged = false, active = true)
        )
    }

    @Transaction
    suspend fun retireRecreatedFolders(driveId: String, paths: List<String>) {
        for (path in paths) {
            val record = get(driveId, path) ?: continue
            delete(driveId, path)
            retireNotice(driveId, path)
            upsertIdentity(RecreatedFolderIdentityEntity(driveId, path, record.remoteId))
        }
    }

    @Transaction
    suspend fun saveFolder(entity: SyncRecordEntity) {
        val old = get(entity.driveId, entity.path)
        if (old != null && old.isFolder && old.remoteId != entity.remoteId) {
            retireFolderDescendants(entity.driveId, entity.path)
        }
        deleteIdentity(entity.driveId, entity.path)
        retireNotice(entity.driveId, entity.path)
        upsert(entity)
    }

    @Transaction
    suspend fun retireFolderDescendants(driveId: String, path: String) {
        all(driveId).filter { it.path.startsWith("$path/") }.forEach { delete(driveId, it.path) }
        notices(driveId).filter { it.active && it.path.startsWith("$path/") }.forEach { retireNotice(it.generation) }
    }

    @Transaction
    suspend fun forget(driveId: String, paths: List<String>) {
        for (path in paths) {
            delete(driveId, path)
            retireNotice(driveId, path)
            deleteIdentity(driveId, path)
        }
    }

    @Query("DELETE FROM sync_records WHERE driveId = :driveId")
    suspend fun clearRecords(driveId: String)

    @Query("DELETE FROM folder_notices WHERE driveId = :driveId")
    suspend fun clearNotices(driveId: String)

    @Query("DELETE FROM recreated_folder_identities WHERE driveId = :driveId")
    suspend fun clearIdentities(driveId: String)

    /** Forgets a drive's sync history, notices included; other drives keep theirs. */
    @Transaction
    suspend fun clear(driveId: String) {
        clearRecords(driveId)
        clearNotices(driveId)
        clearIdentities(driveId)
    }

    @Query("DELETE FROM sync_records")
    suspend fun clearAllRecords()

    @Query("DELETE FROM folder_notices")
    suspend fun clearAllNotices()

    @Query("DELETE FROM recreated_folder_identities")
    suspend fun clearAllIdentities()

    @Transaction
    suspend fun clearAll() {
        clearAllRecords()
        clearAllNotices()
        clearAllIdentities()
    }
}

@Dao
interface DriveAccountDao {
    @Query("SELECT * FROM drive_accounts ORDER BY rowid")
    fun observe(): Flow<List<DriveAccountEntity>>

    @Query("SELECT * FROM drive_accounts ORDER BY rowid")
    suspend fun all(): List<DriveAccountEntity>

    @Query("SELECT * FROM drive_accounts WHERE id = :id")
    suspend fun get(id: String): DriveAccountEntity?

    @Upsert
    suspend fun upsert(entity: DriveAccountEntity)

    @Query("UPDATE drive_accounts SET lastSyncAt = :at, lastMessage = :message WHERE id = :id")
    suspend fun updateStatus(id: String, at: Long, message: String?)

    @Query("DELETE FROM drive_accounts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM drive_accounts")
    suspend fun deleteAll()
}

@Database(
    entities = [
        SyncRecordEntity::class,
        DriveAccountEntity::class,
        FolderNoticeEntity::class,
        RecreatedFolderIdentityEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class MarkNotesDatabase : RoomDatabase() {
    abstract fun syncRecords(): SyncRecordDao

    abstract fun driveAccounts(): DriveAccountDao

    companion object {
        /** Id of the drive account created for the v1 single Proton Drive setup. */
        const val LEGACY_PROTON_ID = "proton"

        /** v1 had a single Proton Drive: its records become the records of the "proton" drive. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `drive_accounts` (`id` TEXT NOT NULL, `provider` TEXT NOT NULL, " +
                        "`label` TEXT NOT NULL, `remoteFolder` TEXT NOT NULL, `createRootFolder` INTEGER NOT NULL, " +
                        "`rootFolderName` TEXT NOT NULL, `lastSyncAt` INTEGER NOT NULL, `lastMessage` TEXT, " +
                        "PRIMARY KEY(`id`))"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `sync_records_new` (`driveId` TEXT NOT NULL, `path` TEXT NOT NULL, " +
                        "`isFolder` INTEGER NOT NULL, `remoteId` TEXT NOT NULL, `remoteVersion` TEXT NOT NULL, " +
                        "`localHash` TEXT NOT NULL, `syncedAt` INTEGER NOT NULL, PRIMARY KEY(`driveId`, `path`))"
                )
                db.execSQL(
                    "INSERT INTO `sync_records_new` (`driveId`, `path`, `isFolder`, `remoteId`, `remoteVersion`, " +
                        "`localHash`, `syncedAt`) SELECT '$LEGACY_PROTON_ID', `path`, `isFolder`, `remoteId`, " +
                        "`remoteVersion`, `localHash`, `syncedAt` FROM `sync_records`"
                )
                db.execSQL("DROP TABLE `sync_records`")
                db.execSQL("ALTER TABLE `sync_records_new` RENAME TO `sync_records`")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `sync_records` ADD COLUMN `localDeleted` INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Deferred removal of folders a drive can't delete; existing records have no deferral. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "ALTER TABLE `sync_records` ADD COLUMN `remoteFolderPreserved` INTEGER NOT NULL DEFAULT 0"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `folder_notices` (`generation` INTEGER PRIMARY KEY AUTOINCREMENT " +
                        "NOT NULL, `driveId` TEXT NOT NULL, `path` TEXT NOT NULL, `remoteId` TEXT NOT NULL, " +
                        "`acknowledged` INTEGER NOT NULL, `active` INTEGER NOT NULL)"
                )
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `recreated_folder_identities` (`driveId` TEXT NOT NULL, " +
                        "`path` TEXT NOT NULL, `remoteId` TEXT NOT NULL, PRIMARY KEY(`driveId`, `path`))"
                )
            }
        }

        fun create(context: Context): MarkNotesDatabase =
            Room.databaseBuilder(context, MarkNotesDatabase::class.java, "marknotes.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                .build()
    }
}

/** Sync metadata of a single drive. */
@Suppress("TooManyFunctions")
class RoomSyncMetadataStore(private val dao: SyncRecordDao, private val driveId: String) : SyncMetadataStore {
    override suspend fun all(): List<SyncRecord> = dao.all(driveId).map {
        SyncRecord(
            it.path,
            it.isFolder,
            it.remoteId,
            it.remoteVersion,
            it.localHash,
            it.syncedAt,
            it.localDeleted,
            it.remoteFolderPreserved
        )
    }

    override suspend fun upsert(record: SyncRecord) = dao.upsert(entity(record))

    override suspend fun delete(path: String) = dao.delete(driveId, path)

    override suspend fun rename(path: String, toPath: String) = dao.rename(driveId, path, toPath)

    override suspend fun clear() = dao.clear(driveId)

    override suspend fun folderNotices(): List<FolderNotice> = dao.notices(driveId).map { it.toNotice() }

    override suspend fun recreatedFolderIdentities(): Map<String, String> =
        dao.recreatedIdentities(driveId).associate { it.path to it.remoteId }

    override suspend fun preserveFolder(path: String, remoteId: String) = dao.preserveFolder(driveId, path, remoteId)

    override suspend fun retireRecreatedFolders(paths: List<String>) = dao.retireRecreatedFolders(driveId, paths)

    override suspend fun saveFolder(record: SyncRecord) =
        dao.saveFolder(entity(record.copy(isFolder = true, localDeleted = false, remoteFolderPreserved = false)))

    override suspend fun retireFolderDescendants(path: String) = dao.retireFolderDescendants(driveId, path)

    override suspend fun forget(paths: List<String>) = dao.forget(driveId, paths)

    override suspend fun acknowledgeFolderNotice(generation: Long) {
        if (dao.acknowledgeNotice(driveId, generation) == 0) throw IOException("folder notice not found")
    }

    private fun entity(record: SyncRecord) = SyncRecordEntity(
        driveId,
        record.path,
        record.isFolder,
        record.remoteId,
        record.remoteVersion,
        record.localHash,
        record.syncedAt,
        record.localDeleted,
        record.remoteFolderPreserved
    )
}
