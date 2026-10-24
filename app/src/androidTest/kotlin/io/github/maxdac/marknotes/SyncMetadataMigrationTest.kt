package io.github.maxdac.marknotes

import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.assertLocalMutationRaces
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.data.RoomSyncMetadataStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SyncMetadataMigrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeCaseRenamesAndNewCopiesNeverClobberConcurrentDestinationCreators() {
        val root = File(context.cacheDir, "rename-races-${UUID.randomUUID()}")
        try {
            assertLocalMutationRaces(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeFolderRenamePreservesExactSpellingChildIdentityAndEpochTimestamp() {
        val root = File(context.cacheDir, "rename-${UUID.randomUUID()}")
        try {
            val local = LocalNoteStore(root)
            local.writeNewBytes("Folder/Child.md", "same".toByteArray(), 0L)
            val identity = local.identity("Folder/Child.md")

            local.renameCase("Folder", "folder")

            assertEquals(listOf("folder"), root.list()!!.toList())
            assertEquals(listOf("Child.md"), File(root, "folder").list()!!.toList())
            assertEquals(identity, local.identity("folder/Child.md"))
            assertEquals(0L, local.modifiedMillis("folder/Child.md"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun versionTwoRecordsMigrateUnflaggedAndPersistNewDeletionFlags() = runBlocking<Unit> {
        val name = "sync-migration-${UUID.randomUUID()}.db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        try {
            SQLiteDatabase.openOrCreateDatabase(path, null).use { db ->
                db.execSQL(
                    "CREATE TABLE sync_records (driveId TEXT NOT NULL, path TEXT NOT NULL, " +
                        "isFolder INTEGER NOT NULL, remoteId TEXT NOT NULL, remoteVersion TEXT NOT NULL, " +
                        "localHash TEXT NOT NULL, syncedAt INTEGER NOT NULL, PRIMARY KEY(driveId, path))"
                )
                db.execSQL(
                    "CREATE TABLE drive_accounts (id TEXT NOT NULL PRIMARY KEY, provider TEXT NOT NULL, " +
                        "label TEXT NOT NULL, remoteFolder TEXT NOT NULL, createRootFolder INTEGER NOT NULL, " +
                        "rootFolderName TEXT NOT NULL, lastSyncAt INTEGER NOT NULL, lastMessage TEXT)"
                )
                db.execSQL("INSERT INTO sync_records VALUES ('a', 'Note.md', 0, 'remote', 'revision', 'hash', 1000)")
                db.execSQL("INSERT INTO sync_records VALUES ('b', 'Note.md', 0, 'other', 'old', 'hash', 1000)")
                db.version = 2
            }
            val db = open(name)
            try {
                val store = RoomSyncMetadataStore(db.syncRecords(), "a")
                val migrated = store.all().single()
                assertFalse(migrated.localDeleted)
                assertEquals("revision", migrated.remoteVersion)
                store.upsert(migrated.copy(localDeleted = true))
                store.rename("Note.md", "note.md")
                assertEquals("Note.md", RoomSyncMetadataStore(db.syncRecords(), "b").all().single().path)
            } finally {
                db.close()
            }
            val reopened = open(name)
            try {
                val restored = RoomSyncMetadataStore(reopened.syncRecords(), "a").all().single()
                assertTrue(restored.localDeleted)
                assertEquals("note.md", restored.path)
                assertEquals("hash", restored.localHash)
                assertEquals("revision", restored.remoteVersion)
            } finally {
                reopened.close()
            }
        } finally {
            context.deleteDatabase(name)
        }
    }

    private fun open(name: String) = Room.databaseBuilder(context, MarkNotesDatabase::class.java, name)
        .addMigrations(MarkNotesDatabase.MIGRATION_1_2, MarkNotesDatabase.MIGRATION_2_3)
        .build()
}
