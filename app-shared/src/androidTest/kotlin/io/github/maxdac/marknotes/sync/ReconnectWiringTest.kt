package io.github.maxdac.marknotes.sync

import android.content.ContextWrapper
import android.net.ConnectivityManager
import androidx.activity.ComponentActivity
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import io.github.maxdac.marknotes.core.sync.RemoteFolderBrowser
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.DriveAccountEntity
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import java.io.File
import java.net.SocketTimeoutException
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** "Sync when reconnected" wired to a real [SyncManager], settings and connectivity service. */
@RunWith(AndroidJUnit4::class)
class ReconnectWiringTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private val id = "reconnect-${UUID.randomUUID()}"
    private val root = File(base.filesDir, id)
    private val context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root.apply { mkdirs() }
        override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = base.getSharedPreferences("$id-$name", mode)
    }
    private lateinit var database: MarkNotesDatabase
    private lateinit var scope: CoroutineScope
    private lateinit var settings: AppSettings
    private lateinit var notes: LocalNoteStore
    private val account = DriveAccount("test", DriveProvider.DROPBOX, "Test", "", true)
    private var failure: Exception? = null
    private var snapshots = 0
    private var uploads = 0
    private val remote = object : RemoteDriveStore {
        override suspend fun snapshot(): List<RemoteEntry> {
            snapshots++
            failure?.let { throw it }
            return emptyList()
        }

        override suspend fun download(entry: RemoteEntry): ByteArray = error("No remote files")

        override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry {
            uploads++
            return RemoteEntry(path, path, false, "1", modifiedMillis)
        }

        override suspend fun uploadRevision(entry: RemoteEntry, bytes: ByteArray, modifiedMillis: Long): RemoteEntry =
            error("No remote revisions")

        override suspend fun createFolder(path: String): RemoteEntry = error("No folders")

        override suspend fun delete(entry: RemoteEntry): Unit = error("No remote deletions")
    }
    private val connector = object : DriveConnector {
        override val provider = DriveProvider.DROPBOX
        override val unavailableReason: String? = null
        override suspend fun signIn(activity: ComponentActivity): SignedInAccount? = null
        override suspend fun folderBrowser(accountId: String): RemoteFolderBrowser? = null
        override suspend fun store(account: DriveAccount): RemoteDriveStore = remote
        override suspend fun signOut(accountId: String) = Unit
    }

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(base, MarkNotesDatabase::class.java).build()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        settings = AppSettings(context, false)
        notes = LocalNoteStore(File(root, "notes"))
        database.driveAccounts().upsert(DriveAccountEntity.from(account))
    }

    @After
    fun tearDown() {
        scope.cancel()
        database.close()
        root.deleteRecursively()
        base.deleteSharedPreferences("$id-settings")
    }

    private fun manager(beforeSync: suspend () -> Unit = {}) = SyncManager(
        context,
        notes,
        database.syncRecords(),
        database.driveAccounts(),
        mapOf(DriveProvider.DROPBOX to connector),
        settings,
        scope,
        beforeSync,
        {}
    )

    @Test
    fun preferenceDefaultsOffAndSurvivesClearingTheSyncState() {
        assertFalse(settings.syncOnReconnect.value)
        settings.setSyncOnReconnect(true)
        assertTrue(AppSettings(context, false).syncOnReconnect.value)
        settings.clearSyncState()
        assertTrue(AppSettings(context, false).syncOnReconnect.value)
        settings.setSyncOnReconnect(false)
        assertFalse(AppSettings(context, false).syncOnReconnect.value)
    }

    @Test
    fun connectivityCallbackRegistersAndUnregistersWithTheNormalPermission() = runBlocking {
        withTimeout(5_000) {
            // first() cancels the collection, which unregisters the real callback.
            requireNotNull(base.getSystemService(ConnectivityManager::class.java)).connections().first()
        }
        Unit
    }

    @Test
    fun doesNothingWhileTheSettingIsOff() = runBlocking {
        assertEquals(ReconnectResult.Stop, manager().syncOnReconnect())
        assertEquals(0, snapshots)
    }

    @Test
    fun savesTheEditorsFirstLikeSyncNow() = runBlocking {
        settings.setSyncOnReconnect(true)
        val saves = PendingSaves()
        saves.register { notes.write("edited.md", "pending editor text") }
        val manager = manager { saves.flushAll() }
        withTimeout(15_000) {
            assertEquals(ReconnectResult.Complete, manager.syncOnReconnect())
        }
        assertEquals(1, snapshots)
        assertEquals(1, uploads)
        assertEquals("pending editor text", notes.read("edited.md"))
        assertFalse(manager.state.value.running)
    }

    @Test
    fun retriesTransportFailuresButStopsForSignInAndNoDrives() = runBlocking {
        settings.setSyncOnReconnect(true)
        val manager = manager()
        failure = SocketTimeoutException("offline")
        assertEquals(ReconnectResult.Retry(), manager.syncOnReconnect())
        failure = RemoteUnavailableException("Sign in again")
        assertEquals(ReconnectResult.Stop, manager.syncOnReconnect())
        database.driveAccounts().delete(account.id)
        assertEquals(ReconnectResult.Stop, manager.syncOnReconnect())
        assertFalse(manager.state.value.running)
    }

    @Test
    fun syncNowReplacesAPendingReconnectSync() {
        settings.setSyncOnReconnect(true)
        val manager = manager()
        manager.reconnect.update(enabled = true, online = true, foreground = true, unlocked = true)
        manager.reconnect.update(online = false)
        manager.reconnect.update(online = true)
        assertEquals(ReconnectSync.PENDING_TEXT, manager.reconnectStatus.value)
        manager.syncNow()
        assertNull(manager.reconnectStatus.value)
    }
}
