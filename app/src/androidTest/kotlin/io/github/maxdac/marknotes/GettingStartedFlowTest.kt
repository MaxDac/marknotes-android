package io.github.maxdac.marknotes

import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.data.AppSettings
import io.github.maxdac.marknotes.shared.R
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG
import io.github.maxdac.marknotes.ui.help.DEMO_EDITOR_TAG
import io.github.maxdac.marknotes.ui.help.GETTING_STARTED_SKIP_TAG
import io.github.maxdac.marknotes.ui.help.GETTING_STARTED_TAG
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GettingStartedFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = context.appContainer
    private val settings get() = container.settings
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val notes get() = container.notesRoot

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            settings.setAppLockEnabled(false)
            container.appLock.onEnabledChanged()
            settings.setGettingStartedConsumed(false)
        }
        notes.deleteRecursively()
        notes.mkdirs()
    }

    @After
    fun tearDown() {
        if (::scenario.isInitialized) scenario.close()
        instrumentation.runOnMainSync {
            settings.setAppLockEnabled(false)
            container.appLock.onEnabledChanged()
            settings.setGettingStartedConsumed(true)
        }
        notes.deleteRecursively()
    }

    @Test
    fun skipPersistsAcrossRecreationSyncCleanupAndHelpReplay() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForGuide()
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).assertDoesNotExist()
        compose.onNodeWithTag(GETTING_STARTED_SKIP_TAG).performClick()
        assertTrue(settings.gettingStartedConsumed.value)
        settings.clearSyncState()
        assertTrue(AppSettings(context, dynamicColorSupported = false).gettingStartedConsumed.value)
        scenario.recreate()
        compose.onNodeWithTag(GETTING_STARTED_TAG).assertDoesNotExist()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Help").performScrollTo().performClick()
        compose.onNodeWithText("Getting started").performClick()
        waitForGuide()
        assertTrue(settings.gettingStartedConsumed.value)
        compose.onNodeWithContentDescription("Close guide").performClick()
        compose.onNodeWithText("Markdown guide").performClick()
        compose.onNodeWithText("Text, headings and links").performScrollTo().assertExists()
    }

    @Test
    fun existingWorkspaceDemoNeverEntersNoteSaveOrAttachmentPipelines() {
        File(notes, "legacy.md").writeText("A legacy note without an ID")
        File(notes, "attachments").mkdir()
        File(notes, "attachments/keep.png").writeBytes(byteArrayOf(1, 2, 3))
        val before = snapshot()
        val revision = container.localRevision.value
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForGuide()
        next()
        next()
        compose.onNodeWithTag(DEMO_EDITOR_TAG).performTextReplacement("private practice")
        scenario.recreate()
        compose.onNodeWithTag(DEMO_EDITOR_TAG).assertTextContains("private practice")
        compose.onNodeWithText("Preview practice text").performScrollTo().performClick()
        compose.onNodeWithTag(GETTING_STARTED_SKIP_TAG).performClick()
        runBlocking {
            delay(1_000)
            container.pendingSaves.flushAll()
        }
        assertEquals(before, snapshot())
        assertEquals(revision, container.localRevision.value)
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Help").performScrollTo().performClick()
        compose.onNodeWithText("Getting started").performClick()
        waitForGuide()
        next()
        next()
        compose.onNodeWithTag(
            DEMO_EDITOR_TAG
        ).assertTextContains(context.getString(R.string.guide_demo_source, context.getString(R.string.app_name)))
        assertEquals(before, snapshot())
    }

    @Test
    fun pauseAndLockDeferWithoutConsumingOrLosingTheCurrentStep() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForGuide()
        next()
        next()
        compose.onNodeWithTag(DEMO_EDITOR_TAG).performTextReplacement("clear this on lock")
        scenario.moveToState(Lifecycle.State.CREATED)
        assertFalse(settings.gettingStartedConsumed.value)
        scenario.moveToState(Lifecycle.State.RESUMED)
        waitForGuide()
        compose.onNodeWithTag(DEMO_EDITOR_TAG).assertTextContains("clear this on lock")
        instrumentation.runOnMainSync {
            settings.setAppLockEnabled(true)
            container.appLock.lock()
        }
        compose.onNodeWithTag(GETTING_STARTED_TAG).assertDoesNotExist()
        assertFalse(settings.gettingStartedConsumed.value)
        instrumentation.runOnMainSync {
            settings.setAppLockEnabled(false)
            container.appLock.onEnabledChanged()
        }
        waitForGuide()
        compose.onNodeWithTag(
            DEMO_EDITOR_TAG
        ).assertTextContains(context.getString(R.string.guide_demo_source, context.getString(R.string.app_name)))
        Espresso.pressBack()
        assertTrue(settings.gettingStartedConsumed.value)
    }

    @Test
    fun incomingShareHasPriorityAndDoesNotConsumeAutomaticGuide() {
        val share = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Shared content")
        }
        scenario = ActivityScenario.launch(share)
        compose.onNodeWithText("Import note").assertExists()
        compose.onNodeWithTag(GETTING_STARTED_TAG).assertDoesNotExist()
        assertFalse(settings.gettingStartedConsumed.value)
        scenario.recreate()
        compose.onNodeWithText("Import note").assertExists()
        assertFalse(settings.gettingStartedConsumed.value)
        scenario.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForGuide()
    }

    @Test
    fun finishConsumesTheAutomaticGuide() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForGuide()
        repeat(4) { next() }
        compose.onNodeWithText("Finish").performScrollTo().performClick()
        assertTrue(settings.gettingStartedConsumed.value)
        scenario.recreate()
        compose.onNodeWithTag(GETTING_STARTED_TAG).assertDoesNotExist()
    }

    @Test
    fun largeTextAndLandscapeKeepDismissalAndPracticeReachable() {
        val originalScale = context.resources.configuration.fontScale
        try {
            shell("settings put system font_scale 2.0")
            scenario = ActivityScenario.launch(MainActivity::class.java)
            scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
            waitForGuide()
            compose.onNodeWithTag(GETTING_STARTED_SKIP_TAG).assertIsDisplayed()
            next()
            next()
            compose.onNodeWithTag(DEMO_EDITOR_TAG).performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag(GETTING_STARTED_SKIP_TAG).performClick()
            assertTrue(settings.gettingStartedConsumed.value)
        } finally {
            shell("settings put system font_scale $originalScale")
            if (::scenario.isInitialized) {
                scenario.onActivity { it.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
            }
        }
    }

    private fun next() = compose.onNodeWithText("Next").performScrollTo().performClick()

    private fun waitForGuide() = compose.waitUntil(10_000) {
        compose.onAllNodesWithTag(GETTING_STARTED_SKIP_TAG).fetchSemanticsNodes().size == 1
    }

    private fun snapshot() = notes.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(notes).path to it.readBytes().toList() }

    private fun shell(command: String) {
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command))
            .use { it.readBytes() }
    }
}
