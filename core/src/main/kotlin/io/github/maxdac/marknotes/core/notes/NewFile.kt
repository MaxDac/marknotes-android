package io.github.maxdac.marknotes.core.notes

import java.io.File
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.FileTime

internal enum class NewFileStage { BYTES_WRITTEN, MTIME_SET, FLUSHED, PUBLISHED }

/** Only complete, timestamped, flushed bytes may become visible at the create-only destination. */
internal fun writeNewFile(
    target: File,
    bytes: ByteArray,
    modifiedMillis: Long,
    checkpoint: (NewFileStage) -> Unit = {}
) {
    val temp = File.createTempFile(".marknotes-new-", ".tmp", target.parentFile)
    try {
        FileChannel.open(temp.toPath(), StandardOpenOption.WRITE).use {
            val buffer = ByteBuffer.wrap(bytes)
            while (buffer.hasRemaining()) it.write(buffer)
            checkpoint(NewFileStage.BYTES_WRITTEN)
            Files.setLastModifiedTime(temp.toPath(), FileTime.fromMillis(modifiedMillis))
            checkpoint(NewFileStage.MTIME_SET)
            it.force(true)
        }
        checkpoint(NewFileStage.FLUSHED)
        moveFileNoReplace(temp, target)
        checkpoint(NewFileStage.PUBLISHED)
    } finally {
        Files.deleteIfExists(temp.toPath())
    }
}
