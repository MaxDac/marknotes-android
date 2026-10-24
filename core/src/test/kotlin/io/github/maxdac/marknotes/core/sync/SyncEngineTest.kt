package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.NoteIds
import io.github.maxdac.marknotes.sync.ConflictChoice
import io.github.maxdac.marknotes.sync.Side
import io.github.maxdac.marknotes.sync.mergeText
import io.github.maxdac.marknotes.sync.parseManifest
import io.github.maxdac.marknotes.sync.readId
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private fun hex(c: Char) = c.toString().repeat(64)

private fun sha(text: String) = Hashing.sha256Hex(text.toByteArray())

/** Body of a conflicted copy, after checking that the copy got a valid note id of its own. */
internal fun copyBody(copy: String): String {
    val doc = FrontMatter.parse(copy)
    assertTrue("conflicted copy without a valid id: $copy", doc.id?.let(NoteIds::isValid) == true)
    return doc.body
}

class SyncEngineTest {
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

    @Test
    fun firstSyncUploadsLocalNotes() = runTest {
        local.write("root.md", "root")
        local.write("a/b/deep.md", "deep")
        local.createFolder("empty")

        val result = syncClean()

        assertEquals(2, result.uploaded)
        assertEquals("root", remote.content("root.md"))
        assertEquals("deep", remote.content("a/b/deep.md"))
        assertTrue(remote.isFolder("empty"))
        assertStable()
    }

    @Test
    fun firstSyncDownloadsRemoteNotes() = runTest {
        remote.putFile("x/note.md", "remote")
        remote.putFolder("y")

        val result = syncClean()

        assertEquals(1, result.downloaded)
        assertEquals("remote", local.read("x/note.md"))
        assertTrue(local.isFolder("y"))
        assertStable()
    }

    @Test
    fun ignoresNonMarkdownFiles() = runTest {
        local.write("image.png", "png")
        remote.putFile("doc.pdf", "pdf")
        local.write("attachments/readme.txt", "txt")
        remote.putFile("attachments/sub/x.png", "png")

        syncClean()

        assertFalse(remote.exists("image.png"))
        assertFalse(local.exists("doc.pdf"))
        assertFalse(remote.exists("attachments/readme.txt"))
        assertFalse(local.exists("attachments/sub/x.png"))
    }

    @Test
    fun attachmentsAreUploadedAndListedInTheManifest() = runTest {
        local.writeBytes("attachments/${hex('a')}.png", byteArrayOf(1, 2, 3))

        val result = syncClean()

        assertEquals(1, result.uploaded)
        assertTrue(remote.exists("attachments/${hex('a')}.png"))
        val manifest = parseManifest(remote.manifest!!.toString(Charsets.UTF_8))!!
        assertEquals(
            Hashing.sha256Hex(byteArrayOf(1, 2, 3)),
            manifest.files.getValue("attachments/${hex('a')}.png").sha256
        )
        assertStable()
    }

    @Test
    fun attachmentsAreDownloadedWithoutBaseCopies() = runTest {
        val base = InMemoryBaseContentStore()
        engine = SyncEngine(local, remote, metadata, base) { 1000L }
        remote.putFile("attachments/${sha("jpeg")}.jpg", "jpeg")

        val result = syncClean()

        assertEquals(1, result.downloaded)
        assertEquals("jpeg", local.read("attachments/${sha("jpeg")}.jpg"))
        assertEquals(null, base.get("attachments/${sha("jpeg")}.jpg"))
        assertStable()
    }

    @Test
    fun attachmentsOnBothSidesAreNeverConflicts() = runTest {
        local.write("attachments/${hex('c')}.png", "local bytes")
        remote.putFile("attachments/${hex('c')}.png", "remote bytes")

        val result = syncClean()

        assertTrue(result.conflicts.isEmpty())
        assertEquals(0, result.uploaded + result.downloaded)
        assertStable()
    }

    @Test
    fun attachmentDeletionsPropagate() = runTest {
        local.write("attachments/${hex('d')}.png", "d")
        remote.putFile("attachments/${sha("e")}.gif", "e")
        syncClean()

        local.delete("attachments/${hex('d')}.png")
        remote.remove("attachments/${sha("e")}.gif")
        val result = syncClean()

        assertEquals(1, result.deletedRemote)
        assertEquals(1, result.deletedLocal)
        assertFalse(remote.exists("attachments/${hex('d')}.png"))
        assertFalse(local.exists("attachments/${sha("e")}.gif"))
        assertStable()
    }

    @Test
    fun identicalContentOnBothSidesIsNotAConflict() = runTest {
        local.write("n.md", "same")
        remote.putFile("n.md", "same")

        val result = syncClean()

        assertTrue(result.conflicts.isEmpty())
        assertStable()
    }

    @Test
    fun differentContentWithoutHistoryKeepsTheNewestAndSavesACopy() = runTest {
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")

        val result = syncClean()

        val conflict = result.conflicts.single()
        assertEquals("n (conflicted copy).md", conflict.copyPath)
        assertEquals(Side.LOCAL, conflict.winner)
        assertEquals(null, conflict.baseText)
        assertEquals("local", local.read("n.md"))
        assertEquals("local", remote.content("n.md"))
        assertEquals("remote", copyBody(local.read(conflict.copyPath)))
        assertEquals(local.read(conflict.copyPath), remote.content(conflict.copyPath))
        assertStable()
    }

    @Test
    fun localEditUploadsRevision() = runTest {
        local.write("n.md", "v1")
        syncClean()
        local.write("n.md", "v2")
        remote.calls.clear()

        val result = syncClean()

        assertEquals(1, result.uploaded)
        assertEquals(listOf("uploadRevision:n.md"), remote.calls)
        assertEquals("v2", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun remoteEditDownloads() = runTest {
        local.write("n.md", "v1")
        syncClean()
        remote.putFile("n.md", "v2")

        val result = syncClean()

        assertEquals(1, result.downloaded)
        assertEquals("v2", local.read("n.md"))
        assertStable()
    }

    @Test
    fun overlappingEditsKeepTheNewerLocalVersion() = runTest {
        local.write("dir/n.md", "base")
        syncClean()
        local.write("dir/n.md", "local")
        remote.putFile("dir/n.md", "remote", modifiedMillis = 1L)

        val result = syncClean()

        val conflict = result.conflicts.single()
        assertEquals(Side.LOCAL, conflict.winner)
        assertEquals("base", conflict.baseText)
        assertEquals("local", local.read("dir/n.md"))
        assertEquals("local", remote.content("dir/n.md"))
        assertEquals("remote", copyBody(local.read("dir/n (conflicted copy).md")))
        assertEquals(local.read("dir/n (conflicted copy).md"), remote.content("dir/n (conflicted copy).md"))
        assertTrue(result.localChanges > 0)
        assertStable()
    }

    @Test
    fun overlappingEditsKeepTheNewerRemoteVersionAndTiesGoToTheDrive() = runTest {
        local.write("n.md", "base")
        syncClean()
        local.write("n.md", "local")
        local.file("n.md").setLastModified(5_000L)
        remote.putFile("n.md", "remote", modifiedMillis = 5_000L)

        val conflict = syncClean().conflicts.single()

        assertEquals(Side.REMOTE, conflict.winner)
        assertEquals("remote", local.read("n.md"))
        assertEquals("remote", remote.content("n.md"))
        assertEquals("local", copyBody(local.read(conflict.copyPath)))
        assertEquals(local.read(conflict.copyPath), remote.content(conflict.copyPath))
        assertStable()
    }

    @Test
    fun conflictedCopyGetsANewIdAndAFreeName() = runTest {
        local.write("n.md", "---\nid: aaaaaaaa\n---\nbase\n")
        syncClean()
        local.write("n (conflicted copy).md", "an older copy")
        syncClean()
        local.write("n.md", "---\nid: aaaaaaaa\n---\nlocal\n")
        remote.putFile("n.md", "---\nid: aaaaaaaa\n---\nremote\n")

        val conflict = syncClean().conflicts.single()

        assertEquals("n (conflicted copy) 2.md", conflict.copyPath)
        assertEquals("---\nid: aaaaaaaa\n---\nlocal\n", local.read("n.md"))
        val copy = local.read(conflict.copyPath)
        val id = readId(copy)!!
        assertTrue(NoteIds.isValid(id))
        assertNotEquals("aaaaaaaa", id)
        assertEquals("---\nid: $id\n---\nremote\n", copy)
        assertStable()
    }

    @Test
    fun conflictedCopyCanBeMergedBack() = runTest {
        local.write("n.md", "---\nid: aaaaaaaa\n---\na\nb\n")
        syncClean()
        local.write("n.md", "---\nid: aaaaaaaa\n---\na\nlocal\n")
        remote.putFile("n.md", "---\nid: aaaaaaaa\n---\na\nremote\n", modifiedMillis = 1L)
        val conflict = syncClean().conflicts.single()
        val mergeBack = ConflictMergeBack(local)
        assertTrue(mergeBack.isPending(conflict))

        val merge = mergeBack.merge(conflict)
        assertEquals(1, merge.conflicts)
        mergeBack.apply(conflict, mergeText(merge.chunks, mapOf(0 to ConflictChoice.BOTH)))

        assertFalse(mergeBack.isPending(conflict))
        val result = syncClean()
        assertEquals(1, result.uploaded)
        assertEquals(1, result.deletedRemote)
        assertEquals("---\nid: aaaaaaaa\n---\na\nlocal\nremote\n", remote.content("n.md"))
        assertFalse(remote.exists(conflict.copyPath))
        assertStable()
    }

    @Test
    fun discardingAConflictedCopyDeletesItEverywhere() = runTest {
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")
        val conflict = syncClean().conflicts.single()

        ConflictMergeBack(local).discardCopy(conflict)
        syncClean()

        assertFalse(local.exists(conflict.copyPath))
        assertFalse(remote.exists(conflict.copyPath))
        assertEquals("local", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun localDeletionTrashesRemote() = runTest {
        local.write("n.md", "x")
        syncClean()
        local.delete("n.md")

        val result = syncClean()

        assertEquals(1, result.deletedRemote)
        assertFalse(remote.exists("n.md"))
        assertStable()
    }

    @Test
    fun remoteDeletionDeletesLocal() = runTest {
        local.write("n.md", "x")
        syncClean()
        remote.remove("n.md")

        val result = syncClean()

        assertEquals(1, result.deletedLocal)
        assertFalse(local.exists("n.md"))
        assertStable()
    }

    @Test
    fun localDeletionLosesAgainstRemoteEdit() = runTest {
        local.write("n.md", "v1")
        syncClean()
        local.delete("n.md")
        remote.putFile("n.md", "v2")

        syncClean()

        assertEquals("v2", local.read("n.md"))
        assertStable()
    }

    @Test
    fun remoteDeletionLosesAgainstLocalEdit() = runTest {
        local.write("n.md", "v1")
        syncClean()
        remote.remove("n.md")
        local.write("n.md", "v2")

        syncClean()

        assertEquals("v2", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun localFolderDeletionRemovesKnownChildrenBeforeEmptyFolders() = runTest {
        local.write("a/x.md", "x")
        local.write("a/b/y.md", "y")
        syncClean()
        local.delete("a")
        remote.calls.clear()

        syncClean()

        assertEquals(
            listOf("delete:a/b/y.md", "delete:a/x.md", "deleteFolderIfEmpty:a/b", "deleteFolderIfEmpty:a"),
            remote.calls
        )
        assertFalse(remote.exists("a"))
        assertTrue(metadata.all().isEmpty())
        assertStable()
    }

    @Test
    fun remoteFolderDeletionDeletesLocalFolder() = runTest {
        local.write("a/x.md", "x")
        local.write("a/b/y.md", "y")
        syncClean()
        remote.remove("a")

        syncClean()

        assertFalse(local.exists("a"))
        assertTrue(metadata.all().isEmpty())
        assertStable()
    }

    @Test
    fun remoteFolderDeletionKeepsLocallyEditedNotes() = runTest {
        local.write("a/x.md", "x")
        local.write("a/y.md", "y")
        syncClean()
        remote.remove("a")
        local.write("a/x.md", "edited")

        syncClean()

        assertTrue(remote.isFolder("a"))
        assertEquals("edited", remote.content("a/x.md"))
        assertFalse(local.exists("a/y.md"))
        assertStable()
    }

    @Test
    fun aFailedStepIsReportedAndTheSyncGoesOn() = runTest {
        local.write("a.md", "a")
        local.write("b.md", "b")
        remote.failingUploads += "a.md"

        val result = engine.sync()

        assertEquals(listOf("a.md: upload refused"), result.errors)
        assertEquals("b", remote.content("b.md"))
        remote.failingUploads.clear()
        syncClean()
        assertEquals("a", remote.content("a.md"))
    }

    @Test
    fun nonOverlappingEditsOnBothSidesAreMergedAutomatically() = runTest {
        local.write("n.md", "a\nb\nc\n")
        syncClean()
        local.write("n.md", "A\nb\nc\n")
        remote.putFile("n.md", "a\nb\nC\n")

        val result = syncClean()

        assertEquals(1, result.merged)
        assertTrue(result.conflicts.isEmpty())
        assertEquals("A\nb\nC\n", local.read("n.md"))
        assertEquals("A\nb\nC\n", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun manifestDescribesSyncedNotesAndIsOnlyRewrittenOnChange() = runTest {
        local.write("a/n.md", "x")
        syncClean()

        val manifest = parseManifest(remote.manifest!!.toString(Charsets.UTF_8))!!
        assertEquals(setOf("a/n.md"), manifest.files.keys)
        assertEquals(Hashing.sha256Hex("x".toByteArray()), manifest.files.getValue("a/n.md").sha256)
        assertEquals(1, remote.manifestWrites)

        syncClean()
        assertEquals(1, remote.manifestWrites)

        local.write("a/n.md", "y")
        syncClean()
        assertEquals(2, remote.manifestWrites)
    }

    @Test
    fun manifestAvoidsDownloadingNotesThatAreAlreadyIdentical() = runTest {
        local.write("n.md", "same")
        syncClean()
        // A second device with the same note connects the same drive.
        val otherDir = Files.createTempDirectory("other").toFile()
        try {
            val other = LocalNoteStore(otherDir)
            other.write("n.md", "same")
            remote.calls.clear()

            val result = SyncEngine(other, remote, InMemorySyncMetadataStore()) { 2000L }.sync()

            assertTrue(result.conflicts.isEmpty())
            assertEquals(emptyList<String>(), remote.calls)
        } finally {
            otherDir.deleteRecursively()
        }
    }

    @Test
    fun aDownloadRacingAnEditorSaveIsSkipped() = runTest {
        local.write("n.md", "v1")
        syncClean()
        remote.putFile("n.md", "v2")
        remote.beforeDownload = { local.write("n.md", "typed") }

        val result = syncClean()

        assertEquals(1, result.skipped)
        assertEquals(0, result.downloaded)
        assertEquals("typed", local.read("n.md"))
        assertEquals(Hashing.sha256Hex("v1".toByteArray()), record("n.md")!!.localHash)
    }

    @Test
    fun aRemoteDeletionRacingAnEditorSaveKeepsTheNote() = runTest {
        local.write("n.md", "v1")
        syncClean()
        remote.remove("n.md")
        remote.afterSnapshot = { local.write("n.md", "typed") }

        val result = syncClean()

        assertEquals(1, result.skipped)
        assertEquals(0, result.deletedLocal)
        assertEquals("typed", local.read("n.md"))
        syncClean()
        assertEquals("typed", remote.content("n.md"))
        assertStable()
    }

    @Test
    fun aRemoteFolderDeletionKeepsANoteSavedDuringTheSync() = runTest {
        local.write("a/x.md", "x")
        syncClean()
        remote.remove("a")
        remote.afterSnapshot = { local.write("a/new.md", "new") }

        val result = syncClean()

        assertEquals(1, result.skipped)
        assertEquals("new", local.read("a/new.md"))
        syncClean()
        assertEquals("new", remote.content("a/new.md"))
    }

    @Test
    fun aRemoteDeleteFailsWhenTheFileChangedOnTheDriveSinceTheSnapshot() = runTest {
        local.write("n.md", "v1")
        syncClean()
        local.delete("n.md")
        remote.afterSnapshot = { remote.putFile("n.md", "other device") }

        val result = engine.sync()

        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().startsWith("n.md: "))
        assertEquals("other device", remote.content("n.md"))
        assertTrue(record("n.md") != null)
        syncClean()
        assertEquals("other device", local.read("n.md"))
        assertStable()
    }

    @Test
    fun anAttachmentWhoseBytesDoNotMatchItsNameIsRejected() = runTest {
        val path = "attachments/${Hashing.sha256Hex("good".toByteArray())}.png"
        remote.putFile(path, "good")
        remote.corruptDownloads[path] = "bad".toByteArray()

        val result = engine.sync()

        assertEquals(1, result.errors.size)
        assertTrue(result.errors.single().startsWith("$path: "))
        assertFalse(local.exists(path))
        assertEquals(null, record(path))
        remote.corruptDownloads.clear()
        syncClean()
        assertEquals("good", local.read(path))
    }

    @Test
    fun creatingALocalFolderIsALocalChange() = runTest {
        remote.putFolder("empty")

        val result = syncClean()

        assertEquals(1, result.createdLocalFolders)
        assertEquals(1, result.localChanges)
        assertTrue(local.isFolder("empty"))
    }

    @Test
    fun anUploadRacingAnotherDevicesCreateNeverOverwritesIt() = runTest {
        local.write("new.md", "mine")
        remote.afterSnapshot = { remote.putFile("new.md", "theirs") }

        val result = engine.sync()

        assertEquals(1, result.errors.size)
        assertEquals("theirs", remote.content("new.md"))
        assertEquals("mine", local.read("new.md"))
        assertEquals(null, record("new.md"))
    }

    @Test
    fun plannedFolderCreationNeverAdoptsARacingRemoteFolder() = runTest {
        local.createFolder("folder")
        remote.afterSnapshot = { remote.putFolder("folder") }

        val result = engine.sync()

        assertEquals(1, result.errors.size)
        assertTrue(remote.isFolder("folder"))
        assertEquals(null, record("folder"))
        syncClean()
        assertTrue(record("folder") != null)
    }

    @Test
    fun plannedFolderCreationNeverAdoptsARacingLocalFolder() = runTest {
        remote.putFolder("folder")
        remote.afterSnapshot = { local.createFolder("folder") }

        val result = engine.sync()

        assertEquals(1, result.errors.size)
        assertEquals(0, result.createdLocalFolders)
        assertTrue(local.isFolder("folder"))
        assertEquals(null, record("folder"))
    }

    @Test
    fun failedConditionalChildDeletionKeepsTheChangedChildAndBothRecords() = runTest {
        local.write("folder/known.md", "before")
        syncClean()
        local.delete("folder")
        remote.afterSnapshot = { remote.putFile("folder/known.md", "concurrent edit") }

        val result = engine.sync()

        assertEquals(2, result.errors.size)
        assertEquals(0, result.deletedRemote)
        assertEquals("concurrent edit", remote.content("folder/known.md"))
        assertTrue(remote.isFolder("folder"))
        assertTrue(record("folder/known.md") != null)
        assertTrue(record("folder") != null)
    }

    @Test
    fun remoteFolderDeletionNeverDeletesUnseenChildren() = runTest {
        local.createFolder("folder")
        syncClean()
        local.delete("folder")
        remote.afterSnapshot = { remote.putFile("folder/new.md", "unseen") }

        val result = engine.sync()

        assertEquals(1, result.errors.size)
        assertEquals(0, result.deletedRemote)
        assertEquals("unseen", remote.content("folder/new.md"))
        assertTrue(record("folder") != null)
    }

    @Test
    fun unsupportedSafeFolderDeletionReportsAnErrorAndRetainsState() = runTest {
        local.createFolder("folder")
        syncClean()
        local.delete("folder")
        remote.supportsSafeFolderDeletion = false

        val result = engine.sync()

        assertTrue(result.errors.single().contains("Safe empty-folder deletion is not supported"))
        assertEquals(0, result.deletedRemote)
        assertTrue(remote.isFolder("folder"))
        assertTrue(record("folder") != null)
    }

    @Test
    fun localFolderDeletionKeepsHiddenAndUnsupportedChildrenAndState() = runTest {
        for (child in listOf(".hidden", "unsupported.txt")) {
            local.createFolder("folder")
            syncClean()
            remote.remove("folder")
            val file = File(dir, "folder/$child")
            file.writeText("unseen")

            val result = engine.sync()

            if (child.startsWith(".")) assertEquals(1, result.errors.size) else assertTrue(result.errors.isEmpty())
            assertEquals(0, result.deletedLocal)
            assertEquals("unseen", file.readText())
            assertTrue(record("folder") != null)
            local.delete("folder")
            metadata.clear()
        }
    }

    @Test
    fun theConflictedCopyIsSavedBeforeTheNoteIsOverwritten() = runTest {
        local.write("n.md", "local")
        remote.putFile("n.md", "remote")
        remote.failingRevisions += "n.md"

        val result = engine.sync()

        assertEquals(listOf("n.md: upload refused"), result.errors)
        val conflict = result.conflicts.single()
        assertEquals("remote", copyBody(local.read(conflict.copyPath)))
        assertEquals("local", local.read("n.md"))
        assertEquals("remote", remote.content("n.md"))
        assertFalse(remote.exists(conflict.copyPath))
    }

    @Test
    fun uploadsCarryTheLocalEditTimeAndDownloadsKeepTheDriveOne() = runTest {
        local.write("up.md", "up")
        local.file("up.md").setLastModified(123_000L)
        remote.putFile("down.md", "down", modifiedMillis = 456_000L)

        syncClean()

        assertEquals(123_000L, remote.entry("up.md")!!.modifiedMillis)
        assertEquals(456_000L, local.modifiedMillis("down.md"))
        assertStable()
    }

    @Test
    fun remoteCaseRenameMigratesLocalPathsAndOnlyThisDrivesState() = runTest {
        val base = InMemoryBaseContentStore()
        engine = SyncEngine(local, remote, metadata, base) { 1000L }
        local.write("Note.md", "same")
        syncClean()
        val old = record("Note.md")!!
        remote.rename(remote.entry("Note.md")!!, "note.md")

        val result = syncClean()

        assertEquals(listOf("note.md"), local.listAll().map { it.path })
        assertEquals(old.localHash, record("note.md")!!.localHash)
        assertEquals(null, record("Note.md"))
        assertEquals("same", base.get("note.md")!!.toString(Charsets.UTF_8))
        assertEquals(null, base.get("Note.md"))
        assertEquals(1, result.renamedLocal)
        assertStable()
    }

    @Test
    fun localCaseRenameMovesRemoteIdentityWithoutChangingBytesOrTime() = runTest {
        local.write("Note.md", "same")
        local.file("Note.md").setLastModified(123_000L)
        syncClean()
        val old = remote.entry("Note.md")!!
        local.renameCase("Note.md", "note.md")

        syncClean()

        assertEquals(
            "calls=${remote.calls}; records=${metadata.all()}; local=${local.listAll()}",
            old.copy(path = "note.md"),
            remote.entry("note.md")
        )
        assertEquals("same", remote.content("note.md"))
        assertEquals(null, record("Note.md"))
        assertStable()
    }

    @Test
    fun remoteFolderCaseRenameMigratesDescendantRecordsAndBaseCopies() = runTest {
        val base = InMemoryBaseContentStore()
        engine = SyncEngine(local, remote, metadata, base) { 1000L }
        local.write("Folder/child.md", "same")
        syncClean()
        remote.rename(remote.entry("Folder")!!, "folder")

        syncClean()

        assertEquals(listOf("folder", "folder/child.md"), local.listAll().map { it.path })
        assertEquals(setOf("folder", "folder/child.md"), metadata.all().map { it.path }.toSet())
        assertEquals("same", base.get("folder/child.md")!!.toString(Charsets.UTF_8))
        assertEquals(null, base.get("Folder/child.md"))
        assertStable()
    }

    @Test
    fun alreadyAgreedFileSpellingMigratesOldHistoryBeforeDownloadingTheChangedRemoteRevision() = runTest {
        val base = InMemoryBaseContentStore()
        engine = SyncEngine(local, remote, metadata, base) { 1000L }
        local.write("Note.md", "old")
        syncClean()
        remote.rename(remote.entry("Note.md")!!, "note.md")
        local.renameCase("Note.md", "note.md")
        val changed = remote.putFile("note.md", "new remote", 2000L)
        remote.calls.clear()

        syncClean()

        assertEquals(listOf("note.md"), local.listAll().map { it.path })
        assertEquals("new remote", local.read("note.md"))
        assertEquals(changed.version, record("note.md")!!.remoteVersion)
        assertEquals("new remote", base.get("note.md")!!.toString(Charsets.UTF_8))
        assertFalse(remote.calls.any { it.startsWith("rename:") || it.startsWith("upload") })
        assertStable()
    }

    @Test
    fun alreadyRenamedFolderMigratesOnlyHistoryWithoutMovingOrOverwritingTheDesiredTree() = runTest {
        val base = InMemoryBaseContentStore()
        engine = SyncEngine(local, remote, metadata, base) { 1000L }
        local.write("Folder/Child.md", "same")
        syncClean()
        val previous = metadata.all().associateBy { it.path }
        remote.rename(remote.entry("Folder")!!, "folder")
        local.renameCase("Folder", "folder")
        val identity = local.identity("folder/Child.md")
        remote.calls.clear()

        syncClean()

        assertEquals(listOf("folder", "folder/Child.md"), local.listAll().map { it.path })
        assertEquals(identity, local.identity("folder/Child.md"))
        assertEquals(previous.getValue("Folder/Child.md").localHash, record("folder/Child.md")!!.localHash)
        assertEquals(previous.getValue("Folder/Child.md").remoteVersion, record("folder/Child.md")!!.remoteVersion)
        assertEquals("same", base.get("folder/Child.md")!!.toString(Charsets.UTF_8))
        assertFalse(remote.calls.any { it.startsWith("rename:") })
        assertEquals(null, record("Folder/Child.md"))
        assertStable()
    }

    @Test
    fun editedStaleCaseRenameReportsAnErrorWithoutMovingEitherVersion() = runTest {
        local.write("Note.md", "same")
        syncClean()
        remote.rename(remote.entry("Note.md")!!, "note.md")
        local.write("Note.md", "edited")

        val result = engine.sync()

        assertTrue(result.errors.isNotEmpty())
        assertEquals(listOf("Note.md"), local.listAll().map { it.path })
        assertEquals("edited", local.read("Note.md"))
        assertEquals("same", remote.content("note.md"))
        assertTrue(record("Note.md") != null)
    }
}
