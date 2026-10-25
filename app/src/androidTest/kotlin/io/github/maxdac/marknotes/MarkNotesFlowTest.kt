package io.github.maxdac.marknotes

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputContentInfo
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.AnnotatedString
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.ui.browser.EMPTY_NEW_CHORDS_TAG
import io.github.maxdac.marknotes.ui.browser.EMPTY_NEW_NOTE_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_CHORDS_MENU_ITEM_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_FAB_TAG
import io.github.maxdac.marknotes.ui.browser.NEW_NOTE_MENU_ITEM_TAG
import io.github.maxdac.marknotes.ui.chords.ADD_CHORD_FAB_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_LIST_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_NAME_FIELD_TAG
import io.github.maxdac.marknotes.ui.chords.CHORD_TEXT_TAG
import io.github.maxdac.marknotes.ui.chords.ENABLE_CHORDS_DIALOG_TAG
import io.github.maxdac.marknotes.ui.chords.FRETBOARD_TAG
import io.github.maxdac.marknotes.ui.editor.EDITOR_FIELD_TAG
import io.github.maxdac.marknotes.ui.editor.EDITOR_NOTICE_TAG
import io.github.maxdac.marknotes.ui.editor.PREVIEW_TAG
import io.github.maxdac.marknotes.ui.settings.CHORD_SEQUENCES_SWITCH_TAG
import io.github.maxdac.marknotes.ui.settings.DELETE_ALL_LOCAL_DATA_TEXT
import io.github.maxdac.marknotes.ui.settings.NO_DRIVES_TEXT
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** End-to-end UI tests that drive [MainActivity] against the real app-private note store. */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class MarkNotesFlowTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private lateinit var scenario: ActivityScenario<MainActivity>
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val notesDir: File
        get() = File(context.filesDir, "notes")
    private val settings get() = context.appContainer.settings

    @Before
    fun setUp() {
        settings.setGettingStartedConsumed(true)
        notesDir.deleteRecursively()
        settings.setChordSequencesEnabled(false)
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun tearDown() {
        scenario.close()
        notesDir.deleteRecursively()
        settings.setChordSequencesEnabled(false)
    }

    /** Turns the Chord sequences setting on, as the Settings switch does. */
    private fun enableChordSequences() {
        settings.setChordSequencesEnabled(true)
        compose.waitForIdle()
    }

    /** Relaunches the app with the notes folder holding [files] (path to content). */
    private fun relaunchWith(vararg files: Pair<String, String>) {
        scenario.close()
        notesDir.mkdirs()
        files.forEach { (path, content) -> File(notesDir, path).writeText(content) }
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    /** The note text without its (hidden) front-matter. */
    private fun bodyOf(note: File) = FrontMatter.parse(note.readText()).body

    /** Opens the floating "New" menu (Chord sequences setting on) and picks the item with [itemTag]. */
    private fun newFromFab(itemTag: String) {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(itemTag), timeoutMillis = 5_000)
        compose.onNodeWithTag(itemTag).performClick()
    }

    /** Taps the floating "New" button, which asks for a note name right away while chord sequences are off. */
    private fun newNoteFromFab() {
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
    }

    private val chordSong = "---\ntype: chords\n---\n## C (guitar)\nfrets: x 3/1 2/1 0 1/1 0\n"

    @Test
    fun emptyLibraryShowsPlaceholder() {
        compose.onNodeWithText("No notes here yet").assertExists()
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).assertExists()
        compose.onNodeWithTag(EMPTY_NEW_NOTE_TAG).assertExists()
        compose.onNodeWithTag(EMPTY_NEW_CHORDS_TAG).assertDoesNotExist()
    }

    @Test
    fun chordSequenceCreationIsOffByDefault() {
        assertFalse(settings.chordSequencesEnabled.value)
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_CHORDS_MENU_ITEM_TAG).assertDoesNotExist()
        compose.onNodeWithText("New chord sequence").assertDoesNotExist()
    }

    @Test
    fun settingsSwitchOffersChordSequenceCreation() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithTag(CHORD_SEQUENCES_SWITCH_TAG).performScrollTo().assertIsOff().performClick()
        compose.onNodeWithTag(CHORD_SEQUENCES_SWITCH_TAG).assertIsOn()
        assertTrue(settings.chordSequencesEnabled.value)
        compose.onNodeWithContentDescription("Back").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(EMPTY_NEW_CHORDS_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(NEW_CHORDS_MENU_ITEM_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_NOTE_MENU_ITEM_TAG).assertExists()
    }

    @Test
    fun openingChordSequenceWhileOffAndDecliningStaysInTheList() {
        relaunchWith("Song.md" to chordSong)
        compose.waitUntilExactlyOneExists(hasText("Song"), timeoutMillis = 5_000)
        compose.onNodeWithText("Song").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(ENABLE_CHORDS_DIALOG_TAG), timeoutMillis = 5_000)
        compose.onNodeWithText("No").performClick()

        compose.waitUntilDoesNotExist(hasTestTag(ENABLE_CHORDS_DIALOG_TAG), timeoutMillis = 5_000)
        compose.onNodeWithText("Song").assertExists()
        compose.onNodeWithTag(CHORD_LIST_TAG).assertDoesNotExist()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertDoesNotExist()
        assertFalse(settings.chordSequencesEnabled.value)
        assertEquals(chordSong, File(notesDir, "Song.md").readText())
    }

    @Test
    fun openingChordSequenceWhileOffAndAcceptingEnablesTheSetting() {
        relaunchWith("Song.md" to chordSong)
        compose.waitUntilExactlyOneExists(hasText("Song"), timeoutMillis = 5_000)
        compose.onNodeWithText("Song").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(ENABLE_CHORDS_DIALOG_TAG), timeoutMillis = 5_000)
        compose.onNodeWithText("Yes").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(CHORD_LIST_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("1 C: x 3/1 2/1 0 1/1 0").assertExists()
        assertTrue(settings.chordSequencesEnabled.value)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(NEW_NOTE_FAB_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(NEW_CHORDS_MENU_ITEM_TAG), timeoutMillis = 5_000)
    }

    @Test
    fun fabMenuOffersNoteAndChordSequence() {
        enableChordSequences()
        compose.onNodeWithTag(NEW_NOTE_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(NEW_NOTE_MENU_ITEM_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(NEW_CHORDS_MENU_ITEM_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
        compose.onNode(hasSetTextAction()).performTextInput("Tune")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(ADD_CHORD_FAB_TAG), timeoutMillis = 5_000)
    }

    @Test
    fun emptyFolderButtonsCreateNoteAndChordSequence() {
        enableChordSequences()
        compose.waitUntilExactlyOneExists(hasTestTag(EMPTY_NEW_CHORDS_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(EMPTY_NEW_CHORDS_TAG).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Riffs")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(ADD_CHORD_FAB_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Riffs"))
        assertTrue(ChordSequences.isChordSequence(File(notesDir, "Riffs.md").readText()))

        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Empty")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasText("Empty"))
        compose.onNodeWithText("Empty").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EMPTY_NEW_NOTE_TAG))
        compose.onNodeWithTag(EMPTY_NEW_NOTE_TAG).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Plain")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG), timeoutMillis = 5_000)
        assertTrue(File(notesDir, "Empty/Plain.md").isFile)
    }

    @Test
    fun createFolderAndNoteInsideIt() {
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Work")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntilExactlyOneExists(hasText("Work"))
        compose.onNodeWithText("Work").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes here yet"))
        compose.onNodeWithContentDescription("Back").assertExists()

        newNoteFromFab()
        compose.onNode(hasSetTextAction()).performTextInput("Meeting")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG))

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Meeting"))
        assertTrue(File(notesDir, "Work").isDirectory)
        assertTrue(File(notesDir, "Work/Meeting.md").isFile)
    }

    @Test
    fun editNoteTogglePreviewAndAutosave() {
        newNoteFromFab()
        compose.onNode(hasSetTextAction()).performTextInput("Shopping")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG))
        compose.onNodeWithTag(EDITOR_FIELD_TAG).performTextInput("# Groceries\n\n- milk")

        compose.onNodeWithContentDescription("Preview").performClick()
        compose.onNodeWithTag(PREVIEW_TAG).assertExists()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertDoesNotExist()

        compose.onNodeWithContentDescription("Edit").performClick()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Shopping"))
        val note = File(notesDir, "Shopping.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note).isNotEmpty() }
        assertNotNull(FrontMatter.parse(note.readText()).id)
        assertEquals("# Groceries\n\n- milk", bodyOf(note))
    }

    @Test
    fun enterContinuesChecklistAndToolbarFormats() {
        newNoteFromFab()
        compose.waitUntilExactlyOneExists(hasSetTextAction(), timeoutMillis = 5_000)
        compose.onNode(hasSetTextAction()).performTextInput("Todo")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG), timeoutMillis = 5_000)

        val editor = compose.onNodeWithTag(EDITOR_FIELD_TAG)
        compose.onNodeWithContentDescription("Checkbox").performClick()
        editor.performTextInput("milk")
        editor.performTextInput("\n")
        editor.performTextInput("eggs")
        editor.performTextInput("\n")
        editor.performTextInput("\n")
        compose.onNodeWithContentDescription("Bold").performClick()
        editor.performTextInput("done")

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Todo"))
        val note = File(notesDir, "Todo.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note).isNotEmpty() }
        assertEquals("- [ ] milk\n- [ ] eggs\n**done**", bodyOf(note))
    }

    /** A small PNG shared through the app's FileProvider, like an image another app copies or a keyboard sends. */
    private fun sharedPng(name: String): Uri {
        val file = File(context.cacheDir, "camera/$name").apply { parentFile?.mkdirs() }
        val bitmap = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /** Creates the note [name] and focuses its editor. */
    private fun openNewNote(name: String) {
        newNoteFromFab()
        compose.onNode(hasSetTextAction()).performTextInput(name)
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(EDITOR_FIELD_TAG).performClick()
        compose.waitForIdle()
    }

    /** The autosaved body of the note [name], once it holds an attachment image. */
    private fun savedImageBody(name: String): String {
        val note = File(notesDir, "$name.md")
        compose.waitUntil(timeoutMillis = 10_000) { note.isFile && "](attachments/" in bodyOf(note) }
        return bodyOf(note)
    }

    private val attachmentImage = Regex("""!\[(.*)]\(attachments/([0-9a-f]{64})\.png\)""")

    @Test
    fun pastedImageIsStoredAsAttachment() {
        val uri = sharedPng("pasted.png")
        openNewNote("Pasted")
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newUri(context.contentResolver, "image", uri))
        }
        // Android only lets the focused window read the clipboard.
        compose.waitUntil(timeoutMillis = 10_000) {
            var focused = false
            scenario.onActivity { focused = it.hasWindowFocus() }
            focused
        }

        compose.onNodeWithTag(EDITOR_FIELD_TAG).performKeyInput {
            keyDown(Key.CtrlLeft)
            pressKey(Key.V)
            keyUp(Key.CtrlLeft)
        }

        val match = attachmentImage.find(savedImageBody("Pasted"))
        assertNotNull(match)
        assertEquals("pasted", match!!.groupValues[1])
        assertTrue(File(notesDir, "attachments/${match.groupValues[2]}.png").isFile)
    }

    @Test
    fun keyboardInsertedImageIsStoredAsAttachment() {
        val uri = sharedPng("sticker.png")
        openNewNote("Sticker")

        var accepted = false
        var mimeTypes: List<String> = emptyList()
        scenario.onActivity { activity ->
            val editor = EditorInfo()
            val connection = requireNotNull(activity.window.decorView.findFocus()?.onCreateInputConnection(editor))
            mimeTypes = editor.contentMimeTypes?.toList().orEmpty()
            val content = InputContentInfo(uri, ClipDescription("sticker", arrayOf("image/png")))
            accepted = connection.commitContent(content, 0, null)
        }

        assertTrue("image/png" in mimeTypes)
        assertTrue(accepted)
        val match = attachmentImage.find(savedImageBody("Sticker"))
        assertNotNull(match)
        assertEquals("sticker", match!!.groupValues[1])
    }

    @Test
    fun undoAndRedoTypingAndToolbarEdits() {
        newNoteFromFab()
        compose.onNode(hasSetTextAction()).performTextInput("Draft")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_FIELD_TAG), timeoutMillis = 5_000)

        val editor = compose.onNodeWithTag(EDITOR_FIELD_TAG)
        val undo = compose.onNodeWithContentDescription("Undo")
        val redo = compose.onNodeWithContentDescription("Redo")
        undo.assertIsNotEnabled()
        redo.assertIsNotEnabled()

        editor.performTextInput("word")
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(0, 4, false) }
        compose.onNodeWithContentDescription("Bold").performClick()
        editor.assertTextEquals("**word**")

        undo.performClick()
        editor.assertTextEquals("word")
        redo.assertIsEnabled()
        undo.performClick()
        editor.assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        undo.assertIsNotEnabled()
        redo.performClick()
        editor.assertTextEquals("word")

        // Hardware keyboard shortcuts drive the same history.
        editor.performClick()
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Y) } }
        editor.assertTextEquals("**word**")
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        editor.assertTextEquals("word")
        editor.performKeyInput { withKeyDown(Key.CtrlLeft) { withKeyDown(Key.ShiftLeft) { pressKey(Key.Z) } } }
        editor.assertTextEquals("**word**")

        // A new edit after an undo drops the redo branch.
        undo.performClick()
        editor.performSemanticsAction(SemanticsActions.SetSelection) { it(4, 4, false) }
        editor.performTextInput("s")
        redo.assertIsNotEnabled()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Draft"))
        val note = File(notesDir, "Draft.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note) == "words" }
    }

    @Test
    fun repeatedBackFromSubfolderNeverLeavesBlankScreen() {
        compose.onNodeWithContentDescription("New folder").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Inbox")
        compose.onNodeWithText("OK").performClick()
        compose.waitUntilExactlyOneExists(hasText("Inbox"))
        compose.onNodeWithText("Inbox").performClick()
        compose.waitUntilExactlyOneExists(hasText("No notes here yet"))

        val back = compose.onNodeWithContentDescription("Back")
        back.performClick()
        runCatching { back.performClick() }

        compose.waitUntilExactlyOneExists(hasText("Inbox"))
        compose.onNodeWithContentDescription("Settings").assertExists()
    }

    @Test
    fun createChordSequenceAddChordAndReopen() {
        enableChordSequences()
        newFromFab(NEW_CHORDS_MENU_ITEM_TAG)
        compose.onNode(hasSetTextAction()).performTextInput("Song")
        compose.onNodeWithText("OK").performClick()

        compose.waitUntilExactlyOneExists(hasTestTag(ADD_CHORD_FAB_TAG), timeoutMillis = 5_000)
        compose.onNodeWithTag(ADD_CHORD_FAB_TAG).performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(FRETBOARD_TAG), timeoutMillis = 5_000)
        // Semantic clicks don't depend on where the nested scroll containers leave each cell on small screens.
        listOf("A2 fret 3", "D3 fret 2", "Open G3", "B3 fret 1", "Open E4").forEach { cell ->
            compose.onNodeWithContentDescription(cell).performSemanticsAction(SemanticsActions.OnClick)
            compose.waitForIdle()
        }
        compose.waitUntilExactlyOneExists(hasText("Frets: x 3/1 2/1 0 1/1 0", substring = true), timeoutMillis = 5_000)
        compose.onNodeWithTag(CHORD_NAME_FIELD_TAG).performScrollTo().performTextInput("C")
        compose.onNodeWithText("Add").performSemanticsAction(SemanticsActions.OnClick)
        compose.waitUntilExactlyOneExists(hasTestTag(CHORD_LIST_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("1 C: x 3/1 2/1 0 1/1 0").assertExists()

        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntilExactlyOneExists(hasText("Song"))
        val note = File(notesDir, "Song.md")
        compose.waitUntil(timeoutMillis = 5_000) { note.isFile && bodyOf(note).isNotEmpty() }
        assertTrue(ChordSequences.isChordSequence(note.readText()))
        assertEquals("## C (guitar)\nfrets: x 3/1 2/1 0 1/1 0\n", bodyOf(note))

        compose.onNodeWithText("Song").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(CHORD_LIST_TAG), timeoutMillis = 5_000)
        compose.onNodeWithContentDescription("View as text").performClick()
        compose.onNodeWithTag(CHORD_TEXT_TAG).assertExists()
    }

    @Test
    fun unparsableChordFileOpensInMarkdownEditor() {
        settings.setChordSequencesEnabled(true)
        relaunchWith("Broken.md" to "---\ntype: chords\n---\n<<<<<<< ours\n## C (guitar)\n")

        compose.waitUntilExactlyOneExists(hasText("Broken"), timeoutMillis = 5_000)
        compose.onNodeWithText("Broken").performClick()
        compose.waitUntilExactlyOneExists(hasTestTag(EDITOR_NOTICE_TAG), timeoutMillis = 5_000)
        compose.onNodeWithText("Line 1", substring = true).assertExists()
        compose.onNodeWithTag(EDITOR_FIELD_TAG).assertExists()
    }

    @Test
    fun settingsAndAddDriveShowProviders() {
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(NO_DRIVES_TEXT).assertExists()
        compose.onNodeWithText("System").assertExists()
        compose.onNodeWithText("Dark").assertExists()

        compose.onNodeWithText("Add drive").performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Dropbox"), timeoutMillis = 5_000)
        compose.onNodeWithText("Dropbox").assertExists()
        compose.onNodeWithText("Proton Drive").assertDoesNotExist()
        compose.onNodeWithText("OneDrive").assertDoesNotExist()
    }

    @Test
    fun deleteAllLocalDataEmptiesTheNotesFolder() {
        scenario.close()
        File(notesDir, "Folder").mkdirs()
        File(notesDir, "Folder/Note.md").writeText("secret")
        File(notesDir, "attachments").mkdirs()
        File(notesDir, "attachments/a.png").writeBytes(byteArrayOf(1))
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntilAtLeastOneExists(hasText("Folder"), timeoutMillis = 5_000)

        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText(DELETE_ALL_LOCAL_DATA_TEXT).performScrollTo().performClick()
        compose.waitUntilExactlyOneExists(hasText("Delete all local data?"), timeoutMillis = 5_000)
        compose.onNode(hasText("Delete") and hasClickAction()).performClick()

        // Either the wipe finishes or the screen reports why it failed; dump the screen if neither happens.
        val failed = hasText("Could not delete the local data", substring = true)
        val settled = runCatching {
            compose.waitUntil(timeoutMillis = 15_000) {
                notesDir.list().isNullOrEmpty() || compose.onAllNodes(failed).fetchSemanticsNodes().isNotEmpty()
            }
        }
        val screen = { compose.onRoot(useUnmergedTree = true).printToString() }
        assertTrue("Local data not deleted: ${notesDir.list()?.toList()}\n${screen()}", settled.isSuccess)
        assertTrue("Deletion failed:\n${screen()}", compose.onAllNodes(failed).fetchSemanticsNodes().isEmpty())
        assertEquals(emptyList<String>(), notesDir.list()!!.toList())

        val browserShown = runCatching {
            compose.waitUntilExactlyOneExists(hasText("No notes here yet"), timeoutMillis = 10_000)
        }
        assertTrue("Empty note list not shown:\n${screen()}", browserShown.isSuccess)
    }
}
