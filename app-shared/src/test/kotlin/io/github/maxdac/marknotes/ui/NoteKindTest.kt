package io.github.maxdac.marknotes.ui

import io.github.maxdac.marknotes.core.notes.ChordSequences
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NoteKindTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val notes by lazy { LocalNoteStore(temp.root) }

    @Test
    fun markdownNotesOpenInTheMarkdownEditorWhateverTheSetting() {
        val path = notes.createNote("", "Plain")
        assertEquals(NoteKind.Markdown(), kindOf(notes, path, chordsEnabled = false))
        assertEquals(NoteKind.Markdown(), kindOf(notes, path, chordsEnabled = true))
    }

    @Test
    fun chordSequencesOpenInTheChordEditorOnlyWhenTheSettingIsOn() {
        val path = notes.createNote("", "Song", ChordSequences.TYPE)
        assertEquals(NoteKind.Chords, kindOf(notes, path, chordsEnabled = true))
        assertEquals(NoteKind.AskToEnableChords, kindOf(notes, path, chordsEnabled = false))
    }

    @Test
    fun unparsableChordSequencesAskFirstThenShowTheParseError() {
        File(temp.root, "Broken.md").writeText("---\ntype: chords\n---\n<<<<<<< ours\n## C (guitar)\n")
        assertEquals(NoteKind.AskToEnableChords, kindOf(notes, "Broken.md", chordsEnabled = false))
        val kind = kindOf(notes, "Broken.md", chordsEnabled = true)
        assertTrue(kind is NoteKind.Markdown && kind.notice?.contains("Line 1") == true)
    }

    @Test
    fun theSettingNeverChangesTheNote() {
        val path = notes.createNote("", "Song", ChordSequences.TYPE)
        val before = notes.read(path)
        kindOf(notes, path, chordsEnabled = false)
        kindOf(notes, path, chordsEnabled = true)
        assertEquals(before, notes.read(path))
    }
}
