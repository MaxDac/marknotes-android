package io.github.maxdac.marknotes

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.LocalNoteStore
import io.github.maxdac.marknotes.core.notes.assertLocalMutationRaces
import java.io.File
import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/** Case-only renames and new-copy creation on the device's real filesystem. */
@RunWith(AndroidJUnit4::class)
class LocalRenameOnDeviceTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun nativeCaseRenamesAndNewCopiesNeverClobberConcurrentDestinationCreators() {
        val root = File(context.cacheDir, "rename-races-${UUID.randomUUID()}")
        try {
            assertLocalMutationRaces(root)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun nativeFolderRenamePreservesExactSpellingChildIdentityAndEpochTimestamp() {
        val root = File(context.cacheDir, "rename-${UUID.randomUUID()}")
        try {
            val local = LocalNoteStore(root)
            local.writeNewBytes("Folder/Child.md", "same".toByteArray(), 0L)
            val identity = local.identity("Folder/Child.md")

            local.renameCase("Folder", "folder")

            assertEquals(listOf("folder"), root.list()!!.toList())
            assertEquals(listOf("Child.md"), File(root, "folder").list()!!.toList())
            assertEquals(identity, local.identity("folder/Child.md"))
            assertEquals(0L, local.modifiedMillis("folder/Child.md"))
        } finally {
            root.deleteRecursively()
        }
    }
}
