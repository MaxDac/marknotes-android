package io.github.maxdac.marknotes

import android.content.ContextWrapper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.sync.DriveAccount
import io.github.maxdac.marknotes.core.sync.DriveProvider
import io.github.maxdac.marknotes.core.sync.FileConflictStore
import io.github.maxdac.marknotes.core.sync.RemoteDriveStore
import io.github.maxdac.marknotes.core.sync.RemoteUnavailableException
import io.github.maxdac.marknotes.core.sync.SyncConflict
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.data.DriveAccountEntity
import io.github.maxdac.marknotes.data.MarkNotesDatabase
import io.github.maxdac.marknotes.data.SyncRecordEntity
import io.github.maxdac.marknotes.sync.DriveConnector
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.SignInScreen
import io.github.maxdac.marknotes.sync.SignedInAccount
import io.github.maxdac.marknotes.sync.SyncManager
import io.github.maxdac.marknotes.ui.MarkNotesTheme
import io.github.maxdac.marknotes.ui.settings.SettingsScreen
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ReconnectDriveFlowTest {
    @get:Rule val compose = createEmptyComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val root = File(context.filesDir, "reconnect-test-${UUID.randomUUID()}")
    private val account = DriveAccount("original", DriveProvider.DROPBOX, "Original", "chosen", true, "My notes")
    private val entity = DriveAccountEntity.from(account, 123L, "Sign in again")
    private val record = SyncRecordEntity(account.id, "a.md", false, "remote-id", "rev", "hash", 123L)
    private val conflict = SyncConflict("a.md", "a (conflicted copy).md", Side.LOCAL, "base", account.id)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connector = FakeConnector()
    private lateinit var database: MarkNotesDatabase
    private lateinit var manager: SyncManager
    private lateinit var scenario: ActivityScenario<MainActivity>
    private lateinit var activity: MainActivity
    private lateinit var stateFiles: Map<File, String>

    @Before
    fun setUp(): Unit = runBlocking {
        context.appContainer.settings.setGettingStartedConsumed(true)
        val isolatedContext = object : ContextWrapper(context) {
            override fun getFilesDir(): File = root.apply { mkdirs() }
            override fun getCacheDir(): File = File(root, "cache").apply { mkdirs() }
        }
        database = Room.inMemoryDatabaseBuilder(context, MarkNotesDatabase::class.java).build()
        database.driveAccounts().upsert(entity)
        database.syncRecords().upsert(record)
        val notes = LocalNoteStore(File(root, "notes"))
        notes.write("a.md", "local changes")
        notes.write(conflict.copyPath, "conflicting changes")
        val conflictFile = File(root, "sync-conflicts.json")
        FileConflictStore(conflictFile).save(listOf(conflict))
        stateFiles = mapOf(
            File(root, "sync-base/${account.id}/a.md") to "base",
            File(root, "sync-pending/${account.id}.json") to "pending conflict identity",
            File(root, "sync-pending/${account.id}-renames.json") to "pending rename identity",
            conflictFile to conflictFile.readText()
        )
        stateFiles.forEach { (file, text) ->
            file.parentFile!!.mkdirs()
            file.writeText(text)
        }
        manager = SyncManager(
            isolatedContext,
            notes,
            database.syncRecords(),
            database.driveAccounts(),
            mapOf(DriveProvider.DROPBOX to connector),
            AppSettings(context, dynamicColorSupported = false),
            scope,
            onLocalChanged = {}
        )
        waitUntil { manager.state.value.drives.size == 1 }
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario.onActivity { activity = it }
    }

    @After
    fun tearDown() {
        scenario.close()
        scope.cancel()
        database.close()
        root.deleteRecursively()
    }

    @Test
    fun reconnectRetainsAccountOptionsRecordsBaseAndJournals() = runBlocking {
        assertTrue(withContext(Dispatchers.Main) { manager.reconnectDrive(activity, account) })
        assertRetained()
        assertEquals(listOf(account), connector.reconnected)
        assertEquals("Reconnected Original", manager.state.value.message)
        assertFalse(manager.state.value.running)
    }

    @Test
    fun cancellationAndMismatchRetainAllStateAndNeverSignOut() = runBlocking {
        connector.result = false
        assertFalse(withContext(Dispatchers.Main) { manager.reconnectDrive(activity, account) })
        assertRetained()
        connector.failure = IllegalStateException("Wrong account")
        assertThrows(IllegalStateException::class.java) {
            runBlocking { withContext(Dispatchers.Main) { manager.reconnectDrive(activity, account) } }
        }
        assertRetained()
        assertTrue(manager.state.value.message.orEmpty().contains("Wrong account"))
        assertFalse(manager.state.value.running)
    }

    @Test
    fun reconnectWaitsForSyncBeforeReplacingCredentialsAndInvalidatingClients() = runBlocking {
        connector.storeGate = CompletableDeferred()
        manager.syncNow()
        waitUntil { connector.storeActive }
        val reconnect = async(Dispatchers.Main) { manager.reconnectDrive(activity, account) }
        delay(100)
        assertTrue(connector.reconnected.isEmpty())
        connector.storeGate!!.complete(Unit)
        assertTrue(reconnect.await())
        assertFalse(connector.storeActive)
        assertEquals(listOf(account), connector.reconnected)
        assertEquals(record, database.syncRecords().all(account.id).single())
    }

    @Test
    fun settingsInAppSignInContinuesReconnectNotAddDriveAndBackIsNondestructive() = runBlocking {
        connector.inApp = true
        showSettings()
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        compose.onNodeWithText("Cancel authentication").performClick()
        assertRetained()
        assertTrue(connector.reconnected.isEmpty())
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        // The reconnect screen's BackHandler must be composed before the back press, or the activity finishes.
        compose.onNodeWithText("Cancel authentication").assertExists()
        scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        compose.onNodeWithText("Cancel authentication").assertExists()
        scenario.recreate()
        scenario.onActivity { activity = it }
        showSettings()
        compose.onNodeWithText("Complete authentication").performClick()
        compose.waitUntil { manager.state.value.message == "Reconnected Original" }
        compose.onNodeWithText("Add drive").assertExists()
        assertRetained()
        assertEquals(listOf(account), connector.reconnected)
        assertTrue(connector.cancelled >= 3)
    }

    @Test
    fun unsupportedProviderHidesReconnect() {
        connector.supportsReconnect = false
        showSettings()
        compose.onNodeWithText("Disconnect").performScrollTo().assertExists()
        compose.onNodeWithText("Reconnect").assertDoesNotExist()
        assertTrue(connector.reconnected.isEmpty())
    }

    @Test
    fun unsupportedConnectorContractFailsRatherThanPretendingToReconnect() = runBlocking {
        connector.supportsReconnect = false
        val failure = assertThrows(UnsupportedOperationException::class.java) {
            runBlocking { withContext(Dispatchers.Main) { manager.reconnectDrive(activity, account) } }
        }
        assertTrue(failure.message.orEmpty().contains("not supported"))
        assertRetained()
    }

    @Test
    fun failedInAppContinuationShowsErrorAndKeepsTheOriginalDrive() = runBlocking {
        connector.inApp = true
        connector.failure = IllegalStateException("Wrong account")
        showSettings()
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        compose.onNodeWithText("Complete authentication").performClick()
        compose.waitUntil {
            !manager.state.value.running &&
                manager.state.value.message.orEmpty().contains("Wrong account")
        }
        compose.onNodeWithText("Reconnect").performScrollTo().assertExists()
        assertRetained()
        assertTrue(connector.reconnected.isEmpty())
    }

    @Test
    fun inAppScreenCanRefuseUnknownLegacyIdentityWithoutCrashingOrStartingReconnect() = runBlocking {
        connector.screenFailure = IllegalStateException("Unknown legacy account")
        showSettings()
        compose.onNodeWithText("Reconnect").performScrollTo().performClick()
        compose.onNodeWithText("Reconnect failed: Unknown legacy account").performScrollTo().assertExists()
        assertRetained()
        assertTrue(connector.reconnected.isEmpty())
        assertEquals(1, connector.cancelled)
    }

    private fun showSettings() {
        scenario.onActivity {
            it.setContent {
                MarkNotesTheme {
                    SettingsScreen(onAddDrive = {}, onOpenConflicts = {}, onBack = {}, syncManager = manager)
                }
            }
        }
    }

    private suspend fun assertRetained() {
        assertEquals(listOf(entity), database.driveAccounts().all())
        assertEquals(listOf(record), database.syncRecords().all(account.id))
        assertEquals(listOf(conflict), manager.state.value.conflicts)
        stateFiles.forEach { (file, content) -> assertEquals(content, file.readText()) }
        assertEquals("local changes", File(root, "notes/a.md").readText())
        assertTrue(connector.signedOut.isEmpty())
        assertEquals(0, connector.signIns)
    }

    private suspend fun waitUntil(condition: () -> Boolean) = withTimeout(10_000L) {
        while (!condition()) delay(20)
    }

    private class FakeConnector : DriveConnector {
        override val provider = DriveProvider.DROPBOX
        override val unavailableReason: String? = null
        override var supportsReconnect = true
        val reconnected = mutableListOf<DriveAccount>()
        val signedOut = mutableListOf<String>()
        var result = true
        var failure: Exception? = null
        var screenFailure: Exception? = null
        var signIns = 0
        var cancelled = 0
        var inApp = false
        var storeGate: CompletableDeferred<Unit>? = null

        @Volatile var storeActive = false

        override fun reconnectScreen(account: DriveAccount): SignInScreen? {
            screenFailure?.let { throw it }
            return if (inApp) {
                { done, back ->
                    Column {
                        Button(onClick = done) { Text("Complete authentication") }
                        Button(onClick = back) { Text("Cancel authentication") }
                    }
                }
            } else {
                null
            }
        }

        override fun cancelReconnect(account: DriveAccount) {
            cancelled++
        }

        override suspend fun reconnect(activity: ComponentActivity, account: DriveAccount): Boolean {
            if (!supportsReconnect) return super.reconnect(activity, account)
            check(!storeActive)
            failure?.let { throw it }
            reconnected += account
            return result
        }

        override suspend fun signIn(activity: ComponentActivity): SignedInAccount? {
            signIns++
            error("Reconnect must not add an account")
        }

        override suspend fun folderBrowser(accountId: String) = null

        override suspend fun store(account: DriveAccount): RemoteDriveStore {
            storeActive = true
            try {
                storeGate?.await()
            } finally {
                storeActive = false
            }
            throw RemoteUnavailableException("offline")
        }

        override suspend fun signOut(accountId: String) {
            signedOut += accountId
        }
    }
}
