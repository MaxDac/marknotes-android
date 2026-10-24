package io.github.maxdac.marknotes

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.diagnostics.DiagnosticCode
import io.github.maxdac.marknotes.ui.lock.LOCKED_TITLE
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DiagnosticsFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = context.appContainer
    private val diagnostics get() = container.diagnostics
    private lateinit var scenario: ActivityScenario<MainActivity>

    @Before
    fun setUp() {
        resetLock()
        runBlocking { diagnostics.clear() }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        resetLock()
        scenario.close()
        runBlocking { diagnostics.clear() }
    }

    private fun resetLock() = instrumentation.runOnMainSync {
        container.settings.setAppLockEnabled(false)
        container.appLock.onEnabledChanged()
    }

    private fun openDiagnostics() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Report a problem").performScrollTo()
    }

    @Test
    fun localReviewDisclosesTransmissionBeforeOpeningBrowserAndClearKeepsRecordingEnabled() {
        diagnostics.report(DiagnosticCode.NOTE_WRITE, IOException("password=private /Notes/private.md"))
        openDiagnostics()
        compose.onNodeWithText("Report a problem").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Review problem report")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Opening the draft sends this excerpt", substring = true).assertExists()
        compose.onNodeWithText("Open draft in browser").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Clear logs").performScrollTo().performClick()
        compose.onNodeWithText("Clear logs now").performClick()
        compose.waitUntil(10_000) {
            runBlocking { diagnostics.draft().body.contains("No events included.") }
        }
        diagnostics.report(DiagnosticCode.NOTE_READ, IOException("private"))
        assertTrue(runBlocking { diagnostics.draft().body.contains("NOTE_READ IO") })
    }

    @Test
    fun exportUsesOnlyNarrowReadGrantedPackageSpecificContentUriAndHasNoSecrets() = runBlocking {
        val failure = IOException("token=private /Notes/private.md")
        diagnostics.report(DiagnosticCode.NOTE_WRITE, failure)
        diagnostics.report(DiagnosticCode.NOTE_WRITE, failure)
        val share = diagnostics.shareIntent()
        assertEquals(Intent.ACTION_SEND, share.action)
        assertEquals("text/plain", share.type)
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, share.flags)
        @Suppress("DEPRECATION")
        val uri = share.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
        assertEquals("${context.packageName}.fileprovider", uri.authority)
        assertEquals("content", uri.scheme)
        assertEquals(uri, share.clipData!!.getItemAt(0).uri)
        val text = context.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
        assertFalse(text.contains("private"))
        assertFalse(text.contains("token"))
        assertEquals(1, text.lineSequence().count { it.contains("NOTE_WRITE") })
        val provider = context.packageManager.resolveContentProvider(uri.authority!!, PackageManager.GET_META_DATA)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val notes = File(context.filesDir, "notes")
        assertThrows(IllegalArgumentException::class.java) {
            FileProvider.getUriForFile(context, uri.authority!!, File(notes, "private.md"))
        }
        diagnostics.clear()
        assertFalse(File(context.cacheDir, "diagnostic-exports").listFiles().orEmpty().any())
    }

    @Test
    fun lockingDismissesReviewAndPreventsBrowserHandoff() {
        openDiagnostics()
        compose.onNodeWithText("Report a problem").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("Review problem report")).fetchSemanticsNodes().isNotEmpty()
        }
        instrumentation.runOnMainSync {
            container.settings.setAppLockEnabled(true)
            container.appLock.lock()
        }
        compose.onNodeWithText(LOCKED_TITLE).assertExists()
        compose.onNodeWithText("Open draft in browser").assertDoesNotExist()
    }

    @Test
    fun deletingAllLocalDataIncludesDiagnosticsAndSnapshots() = runBlocking {
        diagnostics.report(DiagnosticCode.NOTE_WRITE, IOException("private"))
        assertNotNull(diagnostics.shareIntent())
        container.syncManager.deleteAllLocalData()
        assertTrue(diagnostics.draft().body.contains("No events included."))
        assertFalse(File(context.cacheDir, "diagnostic-exports").listFiles().orEmpty().any())
    }
}
