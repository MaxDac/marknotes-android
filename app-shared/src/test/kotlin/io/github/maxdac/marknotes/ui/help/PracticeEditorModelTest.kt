package io.github.maxdac.marknotes.ui.help

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeEditorModelTest {
    @Test
    fun resetDiscardsTextSelectionAndPreview() {
        val model = PracticeEditorModel("# Sample")
        model.value = TextFieldValue("private", TextRange(2, 4))
        model.preview = true
        model.reset()
        assertEquals(TextFieldValue("# Sample"), model.value)
        assertFalse(model.preview)
    }

    @Test
    fun newProcessModelCannotRestorePreviousPracticeText() {
        val old = PracticeEditorModel("# Sample")
        old.value = TextFieldValue("private")
        assertEquals("# Sample", PracticeEditorModel("# Sample").value.text)
    }

    @Test
    fun failedPersistenceKeepsTheSessionOpenAndDoesNotDiscardPractice() {
        val model = PracticeEditorModel("# Sample")
        model.value = TextFieldValue("private")
        model.preview = true
        assertFalse(model.finish { false })
        assertEquals("private", model.value.text)
        assertTrue(model.preview)
        assertTrue(model.finish { true })
        assertEquals("# Sample", model.value.text)
        assertFalse(model.preview)
    }
}
