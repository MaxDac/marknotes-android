package io.github.maxdac.marknotes.core.sync

import java.io.File
import java.io.IOException
import java.nio.file.Files

/**
 * Keeps, per drive, the content of every note as it was the last time it was in sync. It is the
 * common ancestor used for three-way merges.
 */
interface BaseContentStore {
    fun get(path: String): ByteArray?
    fun put(path: String, bytes: ByteArray)
    fun delete(path: String)
    fun rename(path: String, toPath: String)
    fun clear()
}

/** Stores base copies as files under [root], mirroring the note paths. */
class FileBaseContentStore(private val root: File) : BaseContentStore {
    private fun file(path: String) = File(root, path)

    override fun get(path: String): ByteArray? = file(path).takeIf { it.isFile }?.readBytes()

    override fun put(path: String, bytes: ByteArray) {
        val target = file(path)
        val parent = target.parentFile ?: throw IOException("Invalid path: $path")
        if (!parent.isDirectory && !parent.mkdirs()) throw IOException("Cannot create folder for $path")
        target.writeBytes(bytes)
    }

    override fun delete(path: String) {
        file(path).deleteRecursively()
    }

    override fun rename(path: String, toPath: String) {
        val source = file(path)
        if (!source.exists()) return
        val target = file(toPath)
        if (target.exists() && Files.isSameFile(source.toPath(), target.toPath())) {
            if (!source.renameTo(target)) throw IOException("Cannot rename base copy $path")
        } else {
            Files.move(source.toPath(), target.toPath())
        }
    }

    override fun clear() {
        root.deleteRecursively()
    }
}

class InMemoryBaseContentStore : BaseContentStore {
    private val items = HashMap<String, ByteArray>()

    override fun get(path: String): ByteArray? = synchronized(items) { items[path] }

    override fun put(path: String, bytes: ByteArray) {
        synchronized(items) { items[path] = bytes }
    }

    override fun delete(path: String) {
        synchronized(items) { items.keys.removeAll { it == path || it.startsWith("$path/") } }
    }

    override fun rename(path: String, toPath: String) {
        synchronized(items) {
            val moved = items.filterKeys { it == path || it.startsWith("$path/") }
            moved.forEach { (key, bytes) -> items[toPath + key.removePrefix(path)] = bytes }
            moved.keys.forEach(items::remove)
        }
    }

    override fun clear() {
        synchronized(items) { items.clear() }
    }
}
