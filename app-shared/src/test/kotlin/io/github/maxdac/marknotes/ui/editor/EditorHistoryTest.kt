package io.github.maxdac.marknotes.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorHistoryTest {
    private var now = 0L
    private val history = EditorHistory { now }

    /** Types [text] one character at a time at the end of [from], [stepMs] apart, recording each keystroke. */
    private fun type(from: EditorText, text: String, stepMs: Long = 100): EditorText = text.fold(from) { before, c ->
        now += stepMs
        val after = EditorText(before.text + c, before.text.length + 1)
        history.record(before, after, typing = true)
        after
    }

    @Test
    fun toolbarEditRestoresBothSelectionsAndRedoSurvivesCursorMoves() {
        val before = EditorText("word", 4, 0)
        val formatted = MarkdownEditing.toggleInline(before, "**")
        history.record(before, formatted)
        assertEquals(before, history.undo(formatted))
        assertTrue(history.canRedo)
        history.record(before, before.copy(selectionStart = 1, selectionEnd = 1))
        assertTrue(history.canRedo)
        assertEquals(formatted, history.redo(before))
        assertEquals(before, history.undo(formatted))
        history.record(before, EditorText("replacement", 11))
        assertFalse(history.canRedo)
        assertNull(history.redo(before))
    }

    @Test
    fun contiguousTypingIsOneStep() {
        val empty = EditorText("", 0)
        val typed = type(empty, "hello")
        assertEquals(empty, history.undo(typed))
        assertFalse(history.canUndo)
        assertEquals(typed, history.redo(empty))
    }

    @Test
    fun pauseCursorMoveOrToolbarEditStartsANewStep() {
        val hello = type(EditorText("", 0), "hello")
        now += EditorHistory.GROUP_DELAY_MS + 1
        val paused = type(hello, " world")
        assertEquals(hello, history.undo(paused))

        history.clear()
        val ab = type(EditorText("", 0), "ab")
        val moved = ab.copy(selectionStart = 0, selectionEnd = 0)
        now += 100
        val prefixed = EditorText("xab", 1)
        history.record(moved, prefixed, typing = true)
        assertEquals(moved, history.undo(prefixed))
        assertEquals(EditorText("", 0), history.undo(ab))

        history.clear()
        val word = type(EditorText("", 0), "word")
        val bold = MarkdownEditing.toggleInline(word, "**")
        history.record(word, bold)
        val more = type(bold, "x")
        assertEquals(bold, history.undo(more))
        assertEquals(word, history.undo(bold))
    }

    @Test
    fun lineBreaksStartANewStepAndListContinuationIsOne() {
        val original = type(EditorText("", 0), "- one")
        val entered = EditorText("- one\n", 6)
        val continued = MarkdownEditing.continueList(original, entered)!!
        now += 100
        history.record(original, continued, typing = true)
        val two = type(continued, "two")
        assertEquals(original, history.undo(two))
        assertEquals(EditorText("", 0), history.undo(original))
        assertEquals(original, history.redo(EditorText("", 0)))
        assertEquals(two, history.redo(original))
    }

    @Test
    fun typingAfterUndoDropsRedoAndStartsANewStep() {
        val first = type(EditorText("", 0), "abc")
        val undone = history.undo(first)!!
        val retyped = type(undone, "x")
        assertFalse(history.canRedo)
        assertEquals(undone, history.undo(retyped))
        assertFalse(history.canUndo)
    }

    @Test
    fun imageInsertionIsOneStep() {
        val body = EditorText("body", 4)
        val image = MarkdownEditing.insertBlock(body, "![image](attachments/hash.png)")
        history.record(body, image)
        assertEquals(body, history.undo(image))
        history.clear()
        assertFalse(history.canUndo)
        assertFalse(history.canRedo)
    }

    @Test
    fun historyIsBounded() {
        repeat(EditorHistory.MAX_STEPS + 20) { index ->
            history.record(EditorText("$index", 0), EditorText("${index + 1}", 0))
        }
        var value = EditorText("${EditorHistory.MAX_STEPS + 20}", 0)
        var count = 0
        while (history.canUndo) {
            value = history.undo(value)!!
            count++
        }
        assertEquals(EditorHistory.MAX_STEPS, count)
        assertEquals("20", value.text)
    }
}
