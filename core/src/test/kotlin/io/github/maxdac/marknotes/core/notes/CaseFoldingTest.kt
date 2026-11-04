package io.github.maxdac.marknotes.core.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CaseFoldingTest {
    @Test
    fun simpleFoldingJoinsCharactersThatLowercasingKeepsApart() {
        // The shared sync library's vectors: long s, final and capital sigma.
        assertEquals(foldCase("ſ/Σ.md"), foldCase("S/ς.md"))
    }

    @Test
    fun dottedAndDotlessIAreNotFoldedToI() {
        assertNotEquals(foldCase("İ.md"), foldCase("i.md"))
        assertNotEquals(foldCase("ı.md"), foldCase("i.md"))
        assertNotEquals(foldCase("ı.md"), foldCase("I.md"))
    }

    @Test
    fun ordinaryCaseDifferencesFold() {
        assertEquals(foldCase("Folder/Note.md"), foldCase("folder/NOTE.MD"))
        assertEquals("ß", foldCase("ß"))
        assertEquals(foldCase("K"), foldCase("k"))
    }

    @Test
    fun foldingIsIdempotent() {
        val path = "Ünï/ſΣ/𐐀.md"
        assertEquals(foldCase(path), foldCase(foldCase(path)))
    }
}
