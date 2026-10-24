package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RenameRecoveryTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("rename-recovery").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun renameRecoveryFinishesHistoryMigrationBeforePlanningOrDeletionFlags() = runTest {
        for (mode in listOf("local", "remote", "history")) {
            for (phase in listOf("base", "records", "after-records")) {
                val root = File(dir, "$mode-$phase")
                val local = LocalNoteStore(File(root, "notes"))
                val remote = FakeRemoteDriveStore()
                val records = InMemorySyncMetadataStore()
                val base = FileBaseContentStore(File(root, "base"))
                val journal = File(root, "renames.json")
                local.write("Note.md", "same")
                local.file("Note.md").setLastModified(1000L)
                assertTrue(SyncEngine(local, remote, records, base).sync().errors.isEmpty())
                if (mode != "remote") {
                    remote.rename(remote.entry("Note.md")!!, "note.md")
                }
                if (mode != "local") {
                    local.renameCase("Note.md", "note.md")
                }
                val failingBase = object : BaseContentStore by base {
                    override fun rename(path: String, toPath: String) {
                        if (phase == "base") throw SimulatedCrash()
                        base.rename(path, toPath)
                    }
                }
                val failingRecords = object : SyncMetadataStore by records {
                    override suspend fun rename(path: String, toPath: String) {
                        if (phase == "records") throw SimulatedCrash()
                        records.rename(path, toPath)
                        throw SimulatedCrash()
                    }
                }
                try {
                    val result = SyncEngine(
                        local,
                        remote,
                        failingRecords,
                        failingBase,
                        renames = FileRenameStore(journal)
                    ).sync()
                    throw AssertionError(
                        "Rename crash was not exercised: $mode/$phase; $result"
                    )
                } catch (e: SimulatedCrash) {
                    assertEquals(listOf("note.md"), local.listAll().map { it.path })
                    assertEquals(listOf("note.md"), remote.snapshot().map { it.path })
                    assertEquals(1000L, local.modifiedMillis("note.md"))
                }

                val result = SyncEngine(
                    local,
                    remote,
                    records,
                    FileBaseContentStore(File(root, "base")),
                    renames = FileRenameStore(journal)
                ).sync()

                assertEquals(emptyList<String>(), result.errors)
                assertEquals(listOf("note.md"), records.all().map { it.path })
                assertFalse(records.all().single().localDeleted)
                assertEquals("same", base.get("note.md")!!.toString(Charsets.UTF_8))
                assertEquals(listOf("note.md"), File(root, "base").list()!!.toList())
                assertTrue(FileRenameStore(journal).all().isEmpty())
            }
        }
    }

    @Test
    fun unsupportedRemoteFolderRenameDoesNotBlockTheNextPassAfterUserReverts() = runTest {
        val local = LocalNoteStore(File(dir, "notes"))
        val remote = FakeRemoteDriveStore()
        val metadata = InMemorySyncMetadataStore()
        val base = InMemoryBaseContentStore()
        val journal = FileRenameStore(File(dir, "renames.json"))
        local.write("Folder/Child.md", "same")
        assertTrue(SyncEngine(local, remote, metadata, base).sync().errors.isEmpty())
        val original = remote.entry("Folder")!!
        val originalRecords = metadata.all()
        val unsupported = object : RemoteDriveStore by remote {
            override suspend fun rename(entry: RemoteEntry, toPath: String): RemoteEntry =
                throw UnsupportedRemoteOperationException("Direct case rename is unsupported")
        }
        local.renameCase("Folder", "folder")
        local.write("unrelated.md", "first")

        val failedRename = SyncEngine(local, unsupported, metadata, base, renames = journal).sync()

        assertFalse(failedRename.errors.isEmpty())
        assertTrue(journal.all().isEmpty())
        assertEquals(original, remote.entry("Folder"))
        assertEquals(
            originalRecords,
            metadata.all().filter { it.path != "unrelated.md" }.map { it.copy(localDeleted = false) }
        )
        assertEquals("first", remote.content("unrelated.md"))

        local.renameCase("folder", "Folder")
        local.write("unrelated.md", "second")
        val next = SyncEngine(local, unsupported, metadata, base, renames = journal).sync()

        assertEquals(emptyList<String>(), next.errors)
        assertTrue(journal.all().isEmpty())
        assertEquals(original, remote.entry("Folder"))
        assertEquals("second", remote.content("unrelated.md"))
    }

    @Test
    fun recoveredFolderRenameKeepsChildSpellingAndModificationTime() = runTest {
        val local = LocalNoteStore(File(dir, "notes"))
        val remote = FakeRemoteDriveStore()
        val metadata = InMemorySyncMetadataStore()
        val base = InMemoryBaseContentStore()
        val journal = FileRenameStore(File(dir, "renames.json"))
        local.write("Folder/Child.md", "same")
        local.file("Folder/Child.md").setLastModified(1000L)
        SyncEngine(local, remote, metadata, base).sync()
        val entry = remote.entry("Folder")!!
        remote.rename(entry, "folder")
        journal.put(PendingRename("Folder", "folder", true, remote.entry("folder")!!, local.identity("Folder")))
        local.renameCase("Folder", "folder")

        val result = SyncEngine(local, remote, metadata, base, renames = journal).sync()

        assertEquals(emptyList<String>(), result.errors)
        assertEquals(listOf("folder", "folder/Child.md"), local.listAll().map { it.path })
        assertEquals(1000L, local.modifiedMillis("folder/Child.md"))
        assertEquals(setOf("folder", "folder/Child.md"), metadata.all().map { it.path }.toSet())
        assertTrue(journal.all().isEmpty())
    }

    private class SimulatedCrash : Error()
}
