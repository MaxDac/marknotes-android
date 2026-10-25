package io.github.maxdac.marknotes.core.sync

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncMetadataStoreTest {
    private val store = InMemorySyncMetadataStore()

    private suspend fun preserved(path: String, id: String) {
        store.upsert(SyncRecord(path, true, id, "", "", 0L))
        store.preserveFolder(path, id)
    }

    @Test
    fun aCaseRenameMigratesMarkersNoticesAndEvidenceWithoutANewGeneration() = runTest {
        preserved("Parent/Old", "old")
        preserved("Parent/Gone", "gone")
        store.retireRecreatedFolders(listOf("Parent/Gone"))
        val notices = store.folderNotices()

        store.rename("Parent", "parent")

        assertTrue(store.all().single { it.path == "parent/Old" }.remoteFolderPreserved)
        assertEquals(
            notices.map { it.copy(path = it.path.replace("Parent", "parent")) },
            store.folderNotices()
        )
        assertEquals(mapOf("parent/Gone" to "gone"), store.recreatedFolderIdentities())
    }

    @Test
    fun preservingIsIdempotentAndBoundToTheFolderIdentity() = runTest {
        preserved("Old", "id")
        store.preserveFolder("Old", "id")

        assertEquals(1, store.folderNotices().size)
        assertTrue(runCatching { store.preserveFolder("Old", "other") }.isFailure)
        assertTrue(runCatching { store.acknowledgeFolderNotice(42) }.isFailure)
    }

    @Test
    fun recreationRemovesOnlyThePreservedFolderRecord() = runTest {
        preserved("Old", "id")
        store.upsert(SyncRecord("Old/kept.md", false, "child", "v", "h", 0L))

        store.retireRecreatedFolders(listOf("Old"))

        assertEquals(listOf("Old/kept.md"), store.all().map { it.path })
        assertFalse(store.folderNotices().single().active)
        assertEquals(mapOf("Old" to "id"), store.recreatedFolderIdentities())
    }

    @Test
    fun savingAReplacementFolderRetiresTheOldDescendantsAtomically() = runTest {
        store.upsert(SyncRecord("Old", true, "old", "", "", 0L))
        preserved("Old/sub", "sub")
        store.upsert(SyncRecord("Old/n.md", false, "n", "v", "h", 0L))
        store.upsert(SyncRecord("Older.md", false, "o", "v", "h", 0L))
        val before = store.all()
        store.failingWrites += "saveFolder"

        assertTrue(runCatching { store.saveFolder(SyncRecord("Old", true, "new", "", "", 1L)) }.isFailure)
        assertEquals(before, store.all())
        store.failingWrites.clear()
        store.saveFolder(SyncRecord("Old", true, "new", "", "", 1L))

        assertEquals(listOf("Old", "Older.md"), store.all().map { it.path }.sorted())
        assertFalse(store.folderNotices().single().active)
    }
}
