package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.FrontMatter
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ConflictCopyRecoveryTest {
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("copy-recovery").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun everyCrashWindowReusesTheDurableCopyIdAndLosingEditTime() = runTest {
        for (phase in listOf("intent", "copy", "winner", "winner-record", "copy-upload", "copy-record")) {
            val root = File(dir, phase)
            val local = LocalNoteStore(File(root, "notes"))
            val remote = FakeRemoteDriveStore()
            val metadata = InMemorySyncMetadataStore()
            val journal = File(root, "pending.json")
            val durable = FileConflictCopyStore(journal)
            local.write("n.md", "local")
            local.file("n.md").setLastModified(2000L)
            remote.putFile("n.md", "remote", 1000L)
            var crashed = false
            fun crash(at: String) {
                if (phase == at && !crashed) {
                    crashed = true
                    throw SimulatedCrash()
                }
            }
            val pending = object : ConflictCopyStore by durable {
                override fun put(pending: PendingConflictCopy) {
                    durable.put(pending)
                    crash("intent")
                }
            }
            val drive = object : RemoteDriveStore by remote {
                override suspend fun snapshot(): List<RemoteEntry> {
                    if (local.listAll().any { it.path.contains("(conflicted copy)") }) crash("copy")
                    return remote.snapshot()
                }

                override suspend fun uploadRevision(
                    entry: RemoteEntry,
                    bytes: ByteArray,
                    modifiedMillis: Long
                ): RemoteEntry = remote.uploadRevision(entry, bytes, modifiedMillis).also { crash("winner") }

                override suspend fun uploadNew(path: String, bytes: ByteArray, modifiedMillis: Long): RemoteEntry =
                    remote.uploadNew(path, bytes, modifiedMillis).also { crash("copy-upload") }
            }
            val records = object : SyncMetadataStore by metadata {
                override suspend fun upsert(record: SyncRecord) {
                    metadata.upsert(record)
                    crash(if (record.path == "n.md") "winner-record" else "copy-record")
                }
            }
            try {
                SyncEngine(local, drive, records, pending = pending).sync()
                throw AssertionError("Crash point was not exercised: $phase")
            } catch (e: SimulatedCrash) {
                assertTrue(crashed)
            }
            val saved = FileConflictCopyStore(journal).all().single()

            val result = SyncEngine(local, remote, metadata, pending = FileConflictCopyStore(journal)).sync()

            assertEquals("$phase errors", emptyList<String>(), result.errors)
            val copies = local.listAll().filter { it.path.contains("(conflicted copy)") }
            assertEquals("$phase copies", 1, copies.size)
            val path = copies.single().path
            assertEquals(saved.intent.copyPath, path)
            assertEquals(saved.intent.newId, FrontMatter.parse(local.read(path)).id)
            assertEquals("remote", copyBody(local.read(path)))
            assertEquals(1000L, local.modifiedMillis(path))
            assertEquals(1000L, remote.entry(path)!!.modifiedMillis)
            assertEquals(local.read(path), remote.content(path))
            assertEquals("local", local.read("n.md"))
            assertEquals("local", remote.content("n.md"))
            assertTrue(FileConflictCopyStore(journal).all().isEmpty())
        }
    }

    @Test
    fun remoteWinnerRecoveryDoesNotLoseTheLocalLosingVersion() = runTest {
        val local = LocalNoteStore(File(dir, "notes"))
        val remote = FakeRemoteDriveStore()
        val metadata = InMemorySyncMetadataStore()
        val journal = FileConflictCopyStore(File(dir, "pending.json"))
        local.write("n.md", "local")
        local.file("n.md").setLastModified(1000L)
        remote.putFile("n.md", "remote", 2000L)
        val records = object : SyncMetadataStore by metadata {
            override suspend fun upsert(record: SyncRecord) {
                throw SimulatedCrash()
            }
        }
        try {
            SyncEngine(local, remote, records, pending = journal).sync()
            throw AssertionError("Crash point was not exercised")
        } catch (e: SimulatedCrash) {
            assertEquals("remote", local.read("n.md"))
        }
        val saved = journal.all().single()

        val result = SyncEngine(local, remote, metadata, pending = journal).sync()

        assertEquals(emptyList<String>(), result.errors)
        assertEquals("local", copyBody(local.read(saved.intent.copyPath)))
        assertEquals(1000L, local.modifiedMillis(saved.intent.copyPath))
        assertEquals(saved.intent.newId, FrontMatter.parse(local.read(saved.intent.copyPath)).id)
        assertEquals("remote", remote.content("n.md"))
        assertTrue(journal.all().isEmpty())
    }

    @Test
    fun editedCopyIsPreservedAndNeverAdoptedByRetry() = runTest {
        val local = LocalNoteStore(File(dir, "notes"))
        val remote = FakeRemoteDriveStore()
        val metadata = InMemorySyncMetadataStore()
        val journal = FileConflictCopyStore(File(dir, "pending.json"))
        local.write("n.md", "local")
        local.file("n.md").setLastModified(2000L)
        remote.putFile("n.md", "remote", 1000L)
        remote.failingRevisions += "n.md"
        assertFalse(SyncEngine(local, remote, metadata, pending = journal).sync().errors.isEmpty())
        val saved = journal.all().single()
        local.write(saved.intent.copyPath, "user edited the copy")
        remote.failingRevisions.clear()

        val result = SyncEngine(local, remote, metadata, pending = journal).sync()

        assertEquals(emptyList<String>(), result.errors)
        assertEquals("user edited the copy", local.read(saved.intent.copyPath))
        val newCopy = result.conflicts.single().copyPath
        assertTrue(newCopy != saved.intent.copyPath)
        assertEquals("remote", copyBody(local.read(newCopy)))
        assertTrue(FrontMatter.parse(local.read(newCopy)).id != saved.intent.newId)
        assertTrue(journal.all().isEmpty())
    }

    @Test
    fun malformedJournalFailsExplicitlyInsteadOfStartingANewConflict() {
        val file = File(dir, "pending.json")
        file.writeText("broken")
        assertThrows(IllegalArgumentException::class.java) { FileConflictCopyStore(file).all() }
        assertEquals("broken", file.readText())
    }

    private class SimulatedCrash : Error()
}
