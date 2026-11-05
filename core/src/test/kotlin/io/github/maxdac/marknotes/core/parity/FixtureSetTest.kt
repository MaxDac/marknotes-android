package io.github.maxdac.marknotes.core.parity

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The `appliesTo` selection of [FixtureSet], on synthetic fixture files (no shared spec checkout needed). */
class FixtureSetTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun fixtures(): File = tmp.newFolder("fixtures").also { dir ->
        write(dir, "front-matter/split", null)
        write(dir, "issues/validate", """["sync", "desktop", "android-pro"]""")
        write(dir, "issues/pull", """["sync", "desktop", "android-pro"]""")
        write(dir, "everywhere/some", """["sync", "android", "android-pro"]""")
    }

    private fun write(dir: File, id: String, appliesTo: String?) {
        val file = File(dir, "$id.json").also { it.parentFile.mkdirs() }
        val applies = appliesTo?.let { """"appliesTo": $it,""" }.orEmpty()
        file.writeText(
            """{ $applies "cases": [ { "name": "c", "input": {"a": "b"}, "expected": {"x": 1} } ] }"""
        )
    }

    @Test
    fun libreSkipsIssuesFiles() {
        val set = FixtureSet(fixtures())
        assertEquals(listOf("everywhere/some", "front-matter/split"), set.ids())
        assertEquals(listOf("issues/pull", "issues/validate"), set.skippedIds())
        assertEquals(emptyList<FixtureCase>(), set.load("issues/pull"))
    }

    @Test
    fun proSelectsIssuesFiles() {
        val set = FixtureSet(fixtures(), SpecFixtures.PRO_KEY)
        assertEquals(
            listOf("everywhere/some", "front-matter/split", "issues/pull", "issues/validate"),
            set.ids()
        )
        assertEquals(emptyList<String>(), set.skippedIds())
        assertEquals(1, set.load("issues/pull").size)
    }

    @Test
    fun filesWithoutAppliesToApplyToEveryone() {
        val dir = fixtures()
        assertTrue(FixtureSet(dir).isApplicable("front-matter/split"))
        assertTrue(FixtureSet(dir, "web").isApplicable("front-matter/split"))
    }

    @Test
    fun proAlsoRunsFilesListingOnlyAndroid() {
        val dir = fixtures()
        write(dir, "libre/only", """["android"]""")
        assertTrue(FixtureSet(dir, SpecFixtures.PRO_KEY).isApplicable("libre/only"))
        assertTrue(FixtureSet(dir).isApplicable("libre/only"))
        assertEquals(false, FixtureSet(dir, "web").isApplicable("libre/only"))
    }

    @Test
    fun anUnmappedFileStillFails() {
        // The coverage rule of SpecCoverageTest: applicable ids must equal the mapped ones.
        val set = FixtureSet(fixtures())
        val mapped = listOf("front-matter/split")
        assertEquals(listOf("everywhere/some"), set.ids() - mapped.toSet())
    }
}
