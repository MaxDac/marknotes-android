package io.github.maxdac.marknotes.core.notes

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AttachmentPreviewTest {
    @get:Rule
    val temporary = TemporaryFolder()

    private val hash = "a".repeat(64)

    @Test
    fun resolvesOnlyExistingCanonicalAttachmentsInEverySupportedFormat() {
        val root = temporary.newFolder()
        File(root, "attachments").mkdir()
        for (extension in listOf("png", "jpg", "gif", "webp")) {
            val path = "attachments/$hash.$extension"
            val image = File(root, path).apply { writeBytes(byteArrayOf(1)) }
            assertEquals(image.canonicalFile, Attachments.previewFile(root, path))
        }
        assertNull(Attachments.previewFile(root, "attachments/${"b".repeat(64)}.png"))
        File(root, "attachments/${"c".repeat(64)}.png").mkdir()
        assertNull(Attachments.previewFile(root, "attachments/${"c".repeat(64)}.png"))
    }

    @Test
    fun rejectsUrlsTraversalEncodedAliasesAndNoncanonicalNames() {
        val root = temporary.newFolder()
        val path = "attachments/$hash.png"
        File(root, "attachments").mkdir()
        File(root, path).writeBytes(byteArrayOf(1))
        val invalid = listOf(
            "file:///${File(root, path).path}",
            "content://images/$path",
            "http://example.com/$path",
            "https://example.com/$path",
            "//example.com/$path",
            "data:image/png;base64,AAAA",
            "marknotes-attachment:$path",
            "/$path",
            "./$path",
            "attachments/../$path",
            "attachments/../../outside.png",
            "attachments\\$hash.png",
            "attachments/%2e%2e/outside.png",
            "attachments/%61${hash.drop(1)}.png",
            "attachments%2f$hash.png",
            "attachments//$hash.png",
            "$path?query",
            "$path#fragment",
            "$path\n",
            " $path",
            "attachments/${hash.uppercase()}.png",
            "attachments/$hash.PNG",
            "attachments/$hash.svg",
            "attachments/short.png"
        )
        invalid.forEach { assertNull(it, Attachments.previewFile(root, it)) }
    }

    @Test
    fun rejectsAttachmentFolderSymlinksOutsideNotes() {
        val root = temporary.newFolder()
        val outside = temporary.newFolder()
        File(outside, "$hash.png").writeBytes(byteArrayOf(1))
        Files.createSymbolicLink(File(root, "attachments").toPath(), outside.toPath())
        assertNull(Attachments.previewFile(root, "attachments/$hash.png"))
    }

    @Test
    fun rejectsImageSymlinksOutsideAttachmentsIncludingSiblingPrefix() {
        val root = temporary.newFolder()
        val attachments = File(root, "attachments").apply { mkdir() }
        val sibling = File(root, "attachments-private").apply { mkdir() }
        val outside = File(sibling, "$hash.png").apply { writeBytes(byteArrayOf(1)) }
        Files.createSymbolicLink(File(attachments, "$hash.png").toPath(), outside.toPath())
        assertNull(Attachments.previewFile(root, "attachments/$hash.png"))
    }
}
