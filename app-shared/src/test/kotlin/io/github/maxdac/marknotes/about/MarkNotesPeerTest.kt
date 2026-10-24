package io.github.maxdac.marknotes.about

import org.junit.Assert.assertEquals
import org.junit.Test

class MarkNotesPeerTest {
    @Test
    fun libreListsProDesktopAndWeb() {
        assertEquals(
            listOf(MarkNotesPeer.AndroidPro, MarkNotesPeer.Desktop, MarkNotesPeer.Web),
            MarkNotesPeer.others(MarkNotesEdition.Libre)
        )
    }

    @Test
    fun proListsLibreDesktopAndWeb() {
        assertEquals(
            listOf(MarkNotesPeer.AndroidLibre, MarkNotesPeer.Desktop, MarkNotesPeer.Web),
            MarkNotesPeer.others(MarkNotesEdition.Pro)
        )
    }

    @Test
    fun destinationsAndLabelsAreFixed() {
        assertEquals(
            mapOf(
                MarkNotesPeer.AndroidLibre to "https://f-droid.org/packages/io.github.maxdac.marknotes.libre/",
                MarkNotesPeer.AndroidPro to "https://github.com/MaxDac/MarkNotes/releases",
                MarkNotesPeer.Desktop to "https://github.com/MaxDac/marknotes-electron/releases",
                MarkNotesPeer.Web to "https://marknotes.fly.dev"
            ),
            MarkNotesPeer.entries.associateWith { it.url }
        )
        assertEquals(
            listOf(
                "MarkNotes for Android (F-Droid)",
                "MarkNotes Pro for Android (GitHub Releases)",
                "MarkNotes Desktop (GitHub Releases)",
                "MarkNotes Web"
            ),
            MarkNotesPeer.entries.map { it.label }
        )
    }
}
