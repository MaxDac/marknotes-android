package io.github.maxdac.marknotes.core.diagnostics

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicLong

/**
 * Owns both private events and share snapshots under one lock and budget. Each event is a tiny independent
 * record, so a crash never needs to rewrite history. The extra event-count cap bounds directory work.
 */
class DiagnosticStore(
    private val directory: File,
    private val exports: File,
    private val clock: () -> Long = System::currentTimeMillis
) {
    private val generation = AtomicLong()
    private var sequence = 0L

    fun generation(): Long = generation.get()

    @Synchronized
    fun append(event: DiagnosticEvent, expectedGeneration: Long = generation.get()) {
        if (expectedGeneration != generation.get()) return
        prune()
        val now = clock()
        if (event.timestamp < now - MAX_AGE_MILLIS || event.timestamp > now) return
        val bytes = (event.line() + "\n").toByteArray(Charsets.US_ASCII)
        require(bytes.size <= MAX_EVENT_BYTES)
        ensureDirectory(directory)
        makeRoom(bytes.size.toLong())
        var file: File
        do {
            file = File(directory, "$now-${sequence++}.event")
        } while (!file.createNewFile())
        FileOutputStream(file).use { stream ->
            stream.write(bytes)
            stream.fd.sync()
        }
        if (!file.setLastModified(event.timestamp)) throw IOException("Diagnostic timestamp could not be set")
    }

    @Synchronized
    fun events(): List<DiagnosticEvent> {
        prune()
        return eventFiles().map { readEvent(it) }.sortedBy { it.timestamp }
    }

    @Synchronized
    fun export(): File {
        val events = events()
        val text = DiagnosticReport.localText(events)
        val bytes = text.toByteArray(Charsets.US_ASCII)
        ensureDirectory(exports)
        // One outstanding snapshot keeps the total small; the UI discloses that clearing invalidates exports.
        exportFiles().forEach(::delete)
        makeRoom(bytes.size.toLong())
        val now = clock()
        val file = File(exports, "diagnostics-$now-${sequence++}.txt")
        FileOutputStream(file).use { it.write(bytes) }
        val oldest = events.firstOrNull()?.timestamp ?: now
        if (!file.setLastModified(oldest)) throw IOException("Diagnostic timestamp could not be set")
        return file
    }

    @Synchronized
    fun clear() {
        generation.incrementAndGet()
        (eventFiles() + exportFiles()).forEach(::delete)
    }

    @Synchronized
    fun prune() {
        val now = clock()
        (eventFiles() + exportFiles()).forEach { file ->
            if (file.lastModified() < now - MAX_AGE_MILLIS || file.lastModified() > now) delete(file)
        }
        val files = eventFiles().sortedBy { it.lastModified() }
        files.take((files.size - MAX_EVENTS).coerceAtLeast(0)).forEach(::delete)
        makeRoom(0)
    }

    private fun readEvent(file: File): DiagnosticEvent {
        if (file.length() > MAX_EVENT_BYTES) throw IOException("Invalid diagnostic record")
        return try {
            DiagnosticEvent.parse(file.readText(Charsets.US_ASCII).trimEnd('\n'))
        } catch (e: IllegalArgumentException) {
            throw IOException("Invalid diagnostic record", e)
        }
    }

    private fun makeRoom(bytes: Long) {
        require(bytes <= MAX_BYTES)
        val files = (eventFiles() + exportFiles()).sortedBy { it.lastModified() }
        var size = files.sumOf { it.length() }
        for (file in files) {
            if (size + bytes <= MAX_BYTES) break
            size -= file.length()
            delete(file)
        }
    }

    private fun eventFiles() = files(directory)
    private fun exportFiles() = files(exports)

    private fun files(root: File): List<File> {
        if (!root.exists()) return emptyList()
        return root.listFiles()?.toList() ?: throw IOException("Diagnostics could not be listed")
    }

    private fun ensureDirectory(root: File) {
        if (!root.isDirectory && !root.mkdirs()) throw IOException("Diagnostics directory is unavailable")
    }

    private fun delete(file: File) {
        if (!file.delete()) throw IOException("Diagnostics could not be deleted")
    }

    companion object {
        const val MAX_BYTES = 5_242_880L
        const val MAX_AGE_MILLIS = 7L * 24 * 60 * 60 * 1_000
        private const val MAX_EVENT_BYTES = 128L
        private const val MAX_EVENTS = 2_048
    }
}
