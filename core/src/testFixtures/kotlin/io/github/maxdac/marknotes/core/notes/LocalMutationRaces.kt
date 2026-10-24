package io.github.maxdac.marknotes.core.notes

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** Real concurrent destination creators, shared by host and on-device filesystem tests. */
fun assertLocalMutationRaces(root: File) {
    val pool = Executors.newFixedThreadPool(2)
    try {
        for (kind in listOf("file-rename", "folder-rename", "new-copy")) {
            repeat(200) { iteration ->
                val directory = File(root, "$kind-$iteration")
                val local = LocalNoteStore(directory)
                val source = if (kind == "folder-rename") "Folder" else "Note.md"
                val target = if (kind == "folder-rename") "folder" else "note.md"
                if (kind == "folder-rename") {
                    local.createFolderNew(source)
                } else if (kind == "file-rename") {
                    local.writeNewBytes(source, "original".toByteArray(), 0L)
                }
                val sourceIdentity = if (kind == "new-copy") null else local.identity(source)
                val barrier = CyclicBarrier(2)
                fun race(operation: () -> Unit) = pool.submit<Boolean> {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        operation()
                        true
                    } catch (_: IOException) {
                        false
                    }
                }
                val mutation = race {
                    if (kind == "new-copy") {
                        local.writeNewBytes(target, "original".toByteArray(), 0L)
                    } else {
                        local.renameCase(source, target)
                    }
                }
                val creator = race {
                    if (kind == "folder-rename") {
                        Files.createDirectory(File(directory, target).toPath())
                    } else {
                        Files.write(
                            File(directory, target).toPath(),
                            "concurrent".toByteArray(),
                            StandardOpenOption.CREATE_NEW,
                            StandardOpenOption.WRITE
                        )
                    }
                }
                val mutated = mutation.get(15, TimeUnit.SECONDS)
                val created = creator.get(15, TimeUnit.SECONDS)
                assertTrue("$kind/$iteration must have exactly one winner", mutated xor created)
                assertWinner(kind, local, sourceIdentity, mutated)
            }
        }
    } finally {
        pool.shutdownNow()
        assertTrue("Race workers did not stop", pool.awaitTermination(15, TimeUnit.SECONDS))
    }
}

private fun assertWinner(kind: String, local: LocalNoteStore, identity: String?, mutated: Boolean) {
    if (kind == "folder-rename") {
        assertEquals(identity, local.identity(if (mutated) "folder" else "Folder"))
    } else {
        assertEquals(if (mutated) "original" else "concurrent", local.read("note.md"))
        if (kind == "file-rename") {
            assertEquals(identity, local.identity(if (mutated) "note.md" else "Note.md"))
            if (!mutated) assertEquals("original", local.read("Note.md"))
        }
    }
}
