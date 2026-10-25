package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.sync.LocalEntry
import io.github.maxdac.marknotes.sync.RemoteFolderDeletion
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** Deferred remote folder removal on drives that can't delete an empty folder atomically. */
class SyncEngineFolderRemovalTest {
    private lateinit var dir: File
    private lateinit var local: LocalNoteStore
    private lateinit var remote: FakeRemoteDriveStore
    private lateinit var metadata: InMemorySyncMetadataStore
    private lateinit var engine: SyncEngine

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("sync").toFile()
        local = LocalNoteStore(dir)
        remote = FakeRemoteDriveStore()
        metadata = InMemorySyncMetadataStore()
        engine = SyncEngine(local, remote, metadata) { 1000L }
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private suspend fun record(path: String): SyncRecord? = metadata.all().firstOrNull { it.path == path }

    private suspend fun syncClean(): SyncResult {
        val result = engine.sync()
        assertEquals(emptyList<String>(), result.errors)
        return result
    }

    private suspend fun assertStable() {
        remote.calls.clear()
        val result = syncClean()
        assertEquals(emptyList<String>(), remote.calls.filter { !it.startsWith("download:") })
        assertEquals(0, result.uploaded + result.downloaded + result.deletedLocal + result.deletedRemote)
        assertTrue(result.conflicts.isEmpty())
    }

    /** Syncs "folder" to a drive that can't remove folders, then deletes it locally and syncs again. */
    private suspend fun preserveFolder(vararg children: String): SyncResult {
        remote.remoteFolderDeletion = RemoteFolderDeletion.UNSUPPORTED
        local.createFolder("folder")
        children.forEach { local.createFolder(it) }
        syncClean()
        local.delete("folder")
        return syncClean()
    }

    private fun localEntries() = local.listAll().map { LocalEntry(it.path, it.isFolder, it.modifiedMillis, null) }

    @Test
    fun aFolderTheDriveCantRemoveIsPreservedWithOneDurableNotice() = runTest {
        val result = preserveFolder()

        assertEquals(0, result.deletedRemote + result.deletedLocal + result.uploaded + result.downloaded)
        assertTrue(remote.calls.none { it.startsWith("delete") })
        assertTrue(remote.isFolder("folder"))
        assertTrue(record("folder")!!.remoteFolderPreserved)
        val notice = metadata.folderNotices().single()
        assertEquals(FolderNotice("folder", remote.entry("folder")!!.id, notice.generation, false, true), notice)
        assertStable()
        // A restart keeps the same pending notice, and the folder stays deleted locally.
        engine = SyncEngine(local, remote, metadata) { 1000L }
        assertStable()
        assertFalse(local.exists("folder"))
        assertEquals(listOf(notice), metadata.folderNotices())
    }

    @Test
    fun anAcknowledgedNoticeIsNotIssuedAgain() = runTest {
        preserveFolder()
        val generation = metadata.folderNotices().single().generation

        metadata.acknowledgeFolderNotice(generation)
        syncClean()

        assertFalse(metadata.folderNotices().single().pending)
        assertTrue(record("folder")!!.remoteFolderPreserved)
    }

    @Test
    fun failingToSaveTheMarkerOrAcknowledgementKeepsThePreviousState() = runTest {
        metadata.failingWrites += "preserveFolder"
        remote.remoteFolderDeletion = RemoteFolderDeletion.UNSUPPORTED
        local.createFolder("folder")
        syncClean()
        local.delete("folder")

        val failed = engine.sync()

        assertTrue(failed.errors.single().contains("Simulated preserveFolder failure"))
        assertFalse(record("folder")!!.remoteFolderPreserved)
        assertTrue(metadata.folderNotices().isEmpty())
        metadata.failingWrites.clear()
        syncClean()
        val notice = metadata.folderNotices().single()
        metadata.failingWrites += "acknowledgeFolderNotice"
        assertTrue(runCatching { metadata.acknowledgeFolderNotice(notice.generation) }.isFailure)
        assertTrue(metadata.folderNotices().single().pending)
    }

    @Test
    fun aFolderRecreatedLocallyBeforeThePreservationIsAppliedIsLeftAlone() = runTest {
        remote.remoteFolderDeletion = RemoteFolderDeletion.UNSUPPORTED
        local.createFolder("folder")
        syncClean()
        local.delete("folder")
        remote.afterSnapshot = { local.createFolder("folder") }

        val result = syncClean()

        assertEquals(1, result.skipped)
        assertFalse(record("folder")!!.remoteFolderPreserved)
        assertTrue(metadata.folderNotices().isEmpty())
    }

    @Test
    fun aNewChildOnTheDriveRestoresThePreservedFolder() = runTest {
        preserveFolder()
        remote.putFile("folder/new.md", "new")

        syncClean()

        assertEquals("new", local.read("folder/new.md"))
        assertFalse(record("folder")!!.remoteFolderPreserved)
        assertTrue(metadata.folderNotices().none { it.active })
    }

    @Test
    fun recreatingThePreservedFolderLocallyEndsItsLifecycle() = runTest {
        preserveFolder()
        val id = remote.entry("folder")!!.id
        val first = metadata.folderNotices().single().generation
        local.createFolder("folder")

        syncClean()

        assertEquals(SyncRecord("folder", true, id, "", "", 1000L), record("folder"))
        assertTrue(metadata.folderNotices().none { it.active })
        assertTrue(metadata.recreatedFolderIdentities().isEmpty())
        local.delete("folder")
        syncClean()
        val pending = metadata.folderNotices().single { it.pending }
        assertTrue(pending.generation > first)
    }

    @Test
    fun theDriveRemovingThePreservedFolderRetiresItsState() = runTest {
        preserveFolder()
        remote.remove("folder")

        syncClean()

        assertEquals(null, record("folder"))
        assertTrue(metadata.folderNotices().none { it.active })
    }

    @Test
    fun aRecreationWithTheSameDriveIdentityKeepsRemoteOnlyPreservedDescendants() = runTest {
        preserveFolder("folder/sub")
        assertEquals(2, metadata.folderNotices().count { it.pending })
        local.createFolder("folder")

        syncClean()

        assertFalse(local.exists("folder/sub"))
        assertFalse(record("folder")!!.remoteFolderPreserved)
        assertTrue(record("folder/sub")!!.remoteFolderPreserved)
        assertEquals(listOf("folder/sub"), metadata.folderNotices().filter { it.pending }.map { it.path })
    }

    @Test
    fun aRecreationRacingADriveReplacementRetiresTheOldDescendantHistory() = runTest {
        preserveFolder("folder/sub")
        local.createFolder("folder")
        // Recreation preflight runs (and keeps the old identity) before the drive folder is replaced.
        rememberLocalDeletions(localEntries(), metadata)
        val oldId = remote.entry("folder")!!.id
        assertEquals(mapOf("folder" to oldId), metadata.recreatedFolderIdentities())
        remote.remove("folder")
        remote.putFolder("folder/sub")

        syncClean()

        assertTrue(local.isFolder("folder/sub"))
        assertEquals(remote.entry("folder")!!.id, record("folder")!!.remoteId)
        assertEquals(remote.entry("folder/sub")!!.id, record("folder/sub")!!.remoteId)
        assertTrue(metadata.all().none { it.remoteFolderPreserved })
        assertTrue(metadata.folderNotices().none { it.active })
        assertTrue(metadata.recreatedFolderIdentities().isEmpty())
    }

    @Test
    fun aReplacedDriveFolderRetiresTheOldChildRecordsEvenWhenAChildDownloadFails() = runTest {
        local.write("folder/child.md", "same")
        syncClean()
        val oldChild = record("folder/child.md")!!
        remote.remove("folder")
        remote.putFile("folder/child.md", "replaced")
        remote.beforeDownload = { error("download failed") }

        engine.sync()

        assertEquals(remote.entry("folder")!!.id, record("folder")!!.remoteId)
        assertTrue(record("folder/child.md")?.remoteId != oldChild.remoteId)
        remote.beforeDownload = null
        local.delete("folder/child.md")
        engine.sync()
        assertTrue(remote.exists("folder/child.md"))
    }

    @Test
    fun aFailedDescendantRetirementKeepsThePreviousHistory() = runTest {
        preserveFolder("folder/sub")
        local.createFolder("folder")
        remote.afterSnapshot = { metadata.failingWrites += "retireFolderDescendants" }
        rememberLocalDeletions(localEntries(), metadata)
        remote.remove("folder")
        remote.putFolder("folder/sub")

        assertTrue(runCatching { engine.sync() }.isFailure)

        assertTrue(record("folder/sub")!!.remoteFolderPreserved)
        assertEquals(1, metadata.recreatedFolderIdentities().size)
        metadata.failingWrites.clear()
        syncClean()
        assertTrue(metadata.recreatedFolderIdentities().isEmpty())
        assertTrue(local.isFolder("folder/sub"))
    }
}
