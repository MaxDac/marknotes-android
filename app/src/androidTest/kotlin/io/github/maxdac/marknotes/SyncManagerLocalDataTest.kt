package io.github.maxdac.marknotes

import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.Attachments
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.DriveAccountEntity
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.data.SyncRecordEntity
import io.github.maxdac.marknotes.sync.DriveConnector
import io.github.maxdac.marknotes.sync.PendingSaves
import io.github.maxdac.marknotes.sync.SignedInAccount
import io.github.maxdac.marknotes.sync.SyncManager
import java.io.File
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Deleting the local data: never while a drive is connected, never on a drive, and every drive is disconnected. */
@RunWith(AndroidJUnit4::class)
class SyncManagerLocalDataTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var database: MarkNotesDatabase
    private lateinit var notes: LocalNoteStore
    private lateinit var scope: CoroutineScope
    private lateinit var connector: FakeConnector
    private lateinit var pendingSaves: PendingSaves
    private lateinit var manager: SyncManager
    private var extraDeleted = 0

    private val first = DriveAccount("first", DriveProvider.DROPBOX, "First", "", true)
    private val second = DriveAccount("second", DriveProvider.DROPBOX, "Second", "", true)

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(context, MarkNotesDatabase::class.java).build()
        notes = LocalNoteStore(File(context.filesDir, "test-notes-${UUID.randomUUID()}"))
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        connector = FakeConnector()
        pendingSaves = PendingSaves()
        listOf(first, second).forEach { database.driveAccounts().upsert(DriveAccountEntity.from(it)) }
        database.syncRecords().upsert(SyncRecordEntity(first.id, "a.md", false, "r", "v", "h", 1))
        database.syncRecords().upsert(SyncRecordEntity(second.id, "a.md", false, "r", "v", "h", 1))
        notes.write("a.md", "note")
        notes.write("folder/b.md", "note")
        Attachments.save(notes, byteArrayOf(1, 2, 3), "png")
        baseDir(first.id).apply { mkdirs() }.resolve("a.md").writeText("base")
        baseDir(second.id).apply { mkdirs() }.resolve("a.md").writeText("base")
        val settings = AppSettings(context, dynamicColorSupported = false)
        settings.lastSyncSummary = "First: ok"
        manager = SyncManager(
            context = context,
            notes = notes,
            records = database.syncRecords(),
            accounts = database.driveAccounts(),
            connectors = mapOf(DriveProvider.DROPBOX to connector),
            settings = settings,
            scope = scope,
            onLocalChanged = {},
            discardPendingSaves = pendingSaves::discardAll,
            deleteExtraLocalData = { extraDeleted++ }
        )
        waitUntil { manager.state.value.drives.size == 2 }
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        notes.root.deleteRecursively()
        File(context.filesDir, "sync-base").deleteRecursively()
    }

    private fun baseDir(driveId: String) = File(context.filesDir, "sync-base/$driveId")

    private suspend fun waitUntil(condition: suspend () -> Boolean) = withTimeout(TIMEOUT_MS) {
        while (!condition()) delay(POLL_MS)
    }

    private fun assertNotesKept() {
        assertEquals("note", notes.read("a.md"))
        assertEquals("note", notes.read("folder/b.md"))
    }

    @Test
    fun keepsLocalNotesWhileAnotherDriveIsConnected() = runBlocking {
        manager.removeDrive(first, deleteLocalData = true)
        waitUntil { manager.state.value.drives.map { it.account.id } == listOf(second.id) }
        waitUntil { manager.state.value.message == "Disconnected First" }

        assertNotesKept()
        assertEquals(listOf(first.id), connector.signedOut)
        assertEquals(1, database.syncRecords().all(second.id).size)
        assertTrue(baseDir(second.id).resolve("a.md").isFile)
        assertFalse(baseDir(first.id).exists() && baseDir(first.id).list()!!.isNotEmpty())
        assertEquals(0, connector.localDataDeleted)
        assertEquals(0, extraDeleted)
    }

    @Test
    fun keepsLocalNotesWhenDisconnectingWithoutDeleting() = runBlocking {
        manager.removeDrive(first)
        waitUntil { manager.state.value.drives.size == 1 }
        manager.removeDrive(second)
        waitUntil { manager.state.value.drives.isEmpty() }
        waitUntil { manager.state.value.message == "Disconnected Second" }
        assertNotesKept()
    }

    @Test
    fun deletesLocalNotesWithTheLastDrive() = runBlocking {
        manager.removeDrive(first)
        waitUntil { manager.state.value.drives.size == 1 }
        manager.removeDrive(second, deleteLocalData = true)
        waitUntil { manager.state.value.message == "Disconnected Second and deleted the local notes" }

        assertTrue(notes.root.isDirectory)
        assertEquals(emptyList<String>(), notes.root.list()!!.toList())
        assertEquals(emptyList<DriveAccountEntity>(), database.driveAccounts().all())
        assertEquals(emptyList<SyncRecordEntity>(), database.syncRecords().all(second.id))
        assertFalse(File(context.filesDir, "sync-base").exists())
        assertEquals(1, connector.localDataDeleted)
        assertEquals(1, extraDeleted)
        assertEquals(listOf(first.id, second.id), connector.signedOut)
        // Nothing reached a drive: the fake connector never created a store.
        assertEquals(0, connector.storesCreated)
    }

    @Test
    fun deleteAllLocalDataDisconnectsEveryDriveFirst() = runBlocking {
        val generation = pendingSaves.generation
        manager.deleteAllLocalData()

        assertEquals(listOf(first.id, second.id), connector.signedOut.sorted())
        assertEquals(emptyList<DriveAccountEntity>(), database.driveAccounts().all())
        assertEquals(emptyList<SyncRecordEntity>(), database.syncRecords().all(first.id))
        assertEquals(emptyList<SyncRecordEntity>(), database.syncRecords().all(second.id))
        assertEquals(emptyList<String>(), notes.root.list()!!.toList())
        assertFalse(File(context.filesDir, "sync-base").exists())
        assertFalse(pendingSaves.current(generation))
        assertEquals(1, connector.localDataDeleted)
        assertEquals(1, extraDeleted)
        assertEquals(0, connector.storesCreated)
        assertEquals("Deleted the local data", manager.state.value.message)
        waitUntil { manager.state.value.drives.isEmpty() }
    }

    private class FakeConnector : DriveConnector {
        val signedOut = CopyOnWriteArrayList<String>()

        @Volatile var localDataDeleted = 0

        @Volatile var storesCreated = 0

        override val provider = DriveProvider.DROPBOX
        override val unavailableReason: String? = null

        override suspend fun signIn(activity: ComponentActivity): SignedInAccount? = null

        override suspend fun folderBrowser(accountId: String) = null

        override suspend fun store(account: DriveAccount): RemoteDriveStore {
            storesCreated++
            throw RemoteUnavailableException("offline")
        }

        override suspend fun signOut(accountId: String) {
            signedOut += accountId
        }

        override suspend fun deleteLocalData() {
            localDataDeleted++
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
        const val POLL_MS = 20L
    }
}
