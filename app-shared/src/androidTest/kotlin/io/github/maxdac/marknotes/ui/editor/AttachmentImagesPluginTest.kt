package io.github.maxdac.marknotes.ui.editor

import android.graphics.Bitmap
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.maxdac.marknotes.core.notes.Attachments
import io.noties.markwon.Markwon
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImagesPlugin
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentImagesPluginTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val executor = Executors.newSingleThreadExecutor()
    private val errors = CopyOnWriteArrayList<Throwable>()
    private lateinit var root: File
    private lateinit var path: String
    private lateinit var markwon: Markwon
    private lateinit var view: TextView
    private val drawables = mutableListOf<AsyncDrawable>()

    @Before
    fun setUp() {
        root = Files.createTempDirectory(context.cacheDir.toPath(), "preview-images").toFile()
        val bitmap = Bitmap.createBitmap(2, 3, Bitmap.Config.ARGB_8888)
        val bytes = java.io.ByteArrayOutputStream().use { output ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
            bitmap.recycle()
            output.toByteArray()
        }
        path = Attachments.pathFor(bytes, "png")
        File(root, Attachments.FOLDER).mkdir()
        File(root, path).writeBytes(bytes)
        instrumentation.runOnMainSync {
            view = TextView(context)
            markwon = Markwon.builder(context)
                .usePlugin(
                    ImagesPlugin.create { plugin ->
                        plugin.executorService(executor).errorHandler { _, error ->
                            errors.add(error)
                            null
                        }
                    }
                )
                .usePlugin(AttachmentImagesPlugin(root))
                .build()
        }
    }

    @After
    fun tearDown() {
        instrumentation.runOnMainSync { drawables.forEach { it.setCallback2(null) } }
        executor.shutdownNow()
        assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS))
        root.deleteRecursively()
    }

    private fun load(destination: String): AsyncDrawable {
        lateinit var drawable: AsyncDrawable
        instrumentation.runOnMainSync {
            markwon.setMarkdown(view, "![image]($destination)")
            val rendered = view.text as android.text.Spanned
            drawable = rendered.getSpans(0, rendered.length, AsyncDrawableSpan::class.java).single().drawable
            drawables.add(drawable)
            drawable.setCallback2(view)
        }
        executor.submit {}.get(10, TimeUnit.SECONDS)
        instrumentation.waitForIdleSync()
        return drawable
    }

    @Test
    fun canonicalAttachmentIsActuallyDecodedByThePreviewLoader() {
        val drawable = load(path)
        assertTrue(drawable.hasResult())
        assertEquals(2, drawable.result.intrinsicWidth)
        assertEquals(3, drawable.result.intrinsicHeight)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun previewNeverLoadsRawUrisTraversalOrEncodedAliases() {
        val hashName = File(path).name
        val invalid = listOf(
            File(root, path).toURI().toString(),
            "file:///sdcard/Download/a.png",
            "content://media/external/images/media/1",
            "http://127.0.0.1:1/image.png",
            "https://127.0.0.1:1/image.png",
            "//127.0.0.1:1/image.png",
            "data:image/png;base64,AAAA",
            "marknotes-attachment:$path",
            "attachments/../$path",
            "attachments/../../outside.png",
            "attachments/%2e%2e/outside.png",
            "attachments/%${hashName[0].code.toString(16)}${hashName.drop(1)}",
            "attachments%2f$hashName",
            "./$path",
            "$path?query",
            "$path#fragment",
            "attachments/short.png"
        )
        for (destination in invalid) {
            errors.clear()
            assertFalse(destination, load(destination).hasResult())
            assertEquals(destination, 1, errors.size)
            assertTrue(destination, errors.single() is IllegalArgumentException)
        }
    }

    @Test
    fun previewDoesNotFollowImageOrAttachmentFolderSymlinks() {
        val image = File(root, path)
        val outside = File(root, "outside.png")
        image.copyTo(outside)
        assertTrue(image.delete())
        Files.createSymbolicLink(image.toPath(), outside.toPath())
        assertFalse(load(path).hasResult())
        assertEquals(1, errors.size)

        assertTrue(image.delete())
        val folder = File(root, Attachments.FOLDER)
        assertTrue(folder.delete())
        val outsideFolder = File(root, "attachments-private").apply { mkdir() }
        outside.copyTo(File(outsideFolder, image.name))
        Files.createSymbolicLink(folder.toPath(), outsideFolder.toPath())
        errors.clear()
        assertFalse(load(path).hasResult())
        assertEquals(1, errors.size)
    }
}
