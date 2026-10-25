package io.github.maxdac.marknotes

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.ContextWrapper
import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.ui.chords.ADD_CHORD_FAB_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_TEXT_TAG
import io.github.maxdac.marknotes.ui.chords.ChordEditorScreen
import io.github.maxdac.marknotes.ui.editor.EDITOR_FIELD_TAG
import io.github.maxdac.marknotes.ui.editor.EditorScreen
import io.github.maxdac.marknotes.ui.importing.SAVE_TAG
import io.github.maxdac.marknotes.ui.importing.TITLE_TAG
import io.github.maxdac.marknotes.ui.sharing.ShareNoteButton
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NoteSharingFlowTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val container get() = context.appContainer
    private val notesDir get() = container.notesRoot
    private lateinit var scenario: ActivityScenario<MainActivity>
    private val body = "# Caf\u00e9\n\n**Bold** [Note](marknotes:abcd1234)\n![Photo](attachments/photo.png)\n"
    private val chords = "## C (guitar)\nfrets: x 3/1 2/1 0 1/1 0\n"

    @Before
    fun setUp() {
        instrumentation.runOnMainSync {
            container.settings.setGettingStartedConsumed(true)
            container.settings.setAppLockEnabled(false)
            container.appLock.onEnabledChanged()
            container.settings.setChordSequencesEnabled(false)
        }
        notesDir.deleteRecursively()
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        notesDir.deleteRecursively()
        container.settings.setChordSequencesEnabled(false)
    }

    private fun openNote(text: String = body, chordNote: Boolean = false) {
        scenario.close()
        notesDir.mkdirs()
        File(notesDir, "Song.md").writeText(
            "---\nid: abcd1234\n${if (chordNote) "type: chords\n" else ""}---\n$text"
        )
        container.settings.setChordSequencesEnabled(chordNote)
        scenario = ActivityScenario.launch(MainActivity::class.java)
        waitForText("Song")
        compose.onNodeWithText("Song").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasContentDescription("Share note"))
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
    }

    private fun waitForText(text: String) = compose.waitUntil(10_000) {
        compose.onAllNodes(hasText(text)).fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
    }

    private fun share(expected: String) {
        val launched = AtomicReference<Intent>()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CHOOSER) return null
                launched.set(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            compose.onNodeWithContentDescription("Share note").performClick()
            val chooser = launched.get()
            assertNotNull(chooser)
            assertEquals("Share note", chooser.getStringExtra(Intent.EXTRA_TITLE))
            @Suppress("DEPRECATION")
            val send = chooser.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
            assertEquals(Intent.ACTION_SEND, send.action)
            assertEquals("text/plain", send.type)
            assertEquals("Song", send.getStringExtra(Intent.EXTRA_SUBJECT))
            assertEquals(expected, send.getStringExtra(Intent.EXTRA_TEXT))
            assertFalse(send.hasExtra(Intent.EXTRA_STREAM))
            assertEquals(0, send.flags)
            assertEquals(null, send.clipData)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
    }

    @Test
    fun editorSharesCurrentMarkdownWithoutHiddenMetadata() {
        openNote()
        share(body)
        val edited = "$body\nLatest \uD83C\uDFB8 edit"
        compose.onNodeWithTag(EDITOR_FIELD_TAG).performTextReplacement(edited)
        share(edited)
    }

    @Test
    fun previewSharesTheSameSourceIncludingRecentEdits() {
        openNote()
        val edited = "$body\nPreview edit"
        compose.onNodeWithTag(EDITOR_FIELD_TAG).performTextReplacement(edited)
        compose.onNodeWithContentDescription("Preview").performClick()
        share(edited)
    }

    @Test
    fun chordListAndTextModeShareCurrentBodyButNotBuilderDrafts() {
        openNote(chords, chordNote = true)
        share(chords)
        compose.onNodeWithContentDescription("View as text").performClick()
        val edited = "$chords\n"
        compose.onNodeWithTag(CHORD_TEXT_TAG).performTextReplacement(edited)
        share(edited)
        compose.onNodeWithContentDescription("View as chords").performClick()
        compose.onNodeWithTag(ADD_CHORD_FAB_TAG).performClick()
        compose.onNodeWithContentDescription("Share note").assertDoesNotExist()
    }

    @Test
    fun markdownReadFailureDoesNotOfferSharingEvenAfterDismissingError() {
        scenario.onActivity { activity ->
            activity.setContent { EditorScreen("../invalid.md", onBack = {}) }
        }
        waitForText("Error")
        compose.onNodeWithContentDescription("Share note").assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("Share note").assertDoesNotExist()
    }

    @Test
    fun chordReadFailureDoesNotOfferSharingEvenAfterDismissingError() {
        scenario.onActivity { activity ->
            activity.setContent { ChordEditorScreen("../invalid.md", onBack = {}) }
        }
        waitForText("Error")
        compose.onNodeWithContentDescription("Share note").assertDoesNotExist()
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithContentDescription("Share note").assertDoesNotExist()
    }

    private fun assertShareFailure(failure: RuntimeException, expected: String) {
        val message = AtomicReference<String>()
        scenario.onActivity { activity ->
            val failingContext = object : ContextWrapper(activity) {
                override fun startActivity(intent: Intent) {
                    throw failure
                }
            }
            activity.setContent {
                CompositionLocalProvider(LocalContext provides failingContext) {
                    ShareNoteButton("Song", body, message::set)
                }
            }
        }
        compose.onNodeWithContentDescription("Share note").performClick()
        assertEquals(expected, message.get())
    }

    @Test
    fun unavailableShareSheetShowsAnActionableError() = assertShareFailure(
        ActivityNotFoundException(),
        "No app is available to share this note. Install an app that accepts text and try again."
    )

    @Test
    fun blockedSharingShowsAnError() = assertShareFailure(
        SecurityException(),
        "Sharing is not allowed on this device."
    )

    private fun importInto(folder: String, existing: Boolean = false) {
        scenario.close()
        val destination = File(notesDir, folder)
        destination.mkdirs()
        if (existing) File(destination, "Imported.md").writeText("Existing note\n")
        val intent = Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_SEND
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, "Imported body")
        }
        scenario = ActivityScenario.launch(intent)
        waitForText("Import note")
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag(SAVE_TAG) and isEnabled())
                .fetchSemanticsNodes(atLeastOneRootRequired = false).isNotEmpty()
        }
        compose.onNodeWithTag(TITLE_TAG).performTextReplacement("Imported")
        compose.onNodeWithContentDescription("Choose folder").performClick()
        val choices = compose.onAllNodes(hasText(folder.ifEmpty { "MarkNotes" }))
        choices[choices.fetchSemanticsNodes().lastIndex].performClick()
        compose.onNodeWithTag(SAVE_TAG).performClick()
        val saved = File(destination, if (existing) "Imported 2.md" else "Imported.md")
        compose.waitUntil(10_000) { saved.exists() && saved.readText().contains("Imported body") }
        assertEquals("Imported body\n", FrontMatter.parse(saved.readText()).body)
        assertTrue(FrontMatter.parse(saved.readText()).id != null)
        if (existing) assertEquals("Existing note\n", File(destination, "Imported.md").readText())
    }

    @Test
    fun importCanSaveAtTheRoot() = importInto("")

    @Test
    fun importCanSaveInAnExistingNestedFolder() = importInto("Work/Meetings")

    @Test
    fun importDoesNotOverwriteAnExistingNoteInTheChosenFolder() = importInto("Work/Meetings", existing = true)
}
