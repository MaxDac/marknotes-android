package io.github.maxdac.marknotes.core.notes

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

private val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)

internal fun fileIdentity(file: File): String =
    Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()?.toString()
        ?: if (windows) WindowsFiles.identity(file) else throw IOException("Cannot verify file identity: $file")

/** Java's Windows moves can treat a case-only destination as a no-op; rename the opened identity instead. */
internal fun renameCaseFile(source: File, target: File) {
    val identity = fileIdentity(source)
    if (target.exists() && Files.isSameFile(source.toPath(), target.toPath())) {
        if (windows) {
            WindowsFiles.rename(source, target)
        } else if (!source.renameTo(target)) {
            throw IOException("Cannot rename $source to $target")
        }
    } else {
        Files.move(source.toPath(), target.toPath())
    }
    val names = target.parentFile.list().orEmpty().toList()
    check(names.contains(target.name)) { "Rename did not preserve exact case: $target; names=$names" }
    val movedIdentity = fileIdentity(target)
    check(movedIdentity == identity) {
        "Rename did not preserve identity: $target; identity=$identity -> $movedIdentity"
    }
}

/** Used only by JVM Windows hosts; Android uses its native Unix file key and no Windows API is loaded. */
private object WindowsFiles {
    init {
        check(Native.POINTER_SIZE == Long.SIZE_BYTES) { "Safe Windows case rename requires a 64-bit JVM" }
    }

    private val api by lazy { Native.load("kernel32", Kernel32::class.java) }

    fun identity(file: File): String = withHandle(file, 0) { handle ->
        Memory(FILE_INFORMATION_SIZE).use { info ->
            if (!api.GetFileInformationByHandle(handle, info)) failure("Read file identity", file)
            listOf(28L, 44L, 48L).joinToString(":") { info.getInt(it).toUInt().toString(16) }
        }
    }

    fun rename(source: File, target: File) {
        withHandle(source, DELETE_ACCESS) { handle ->
            val name = target.absolutePath.toByteArray(Charsets.UTF_16LE)
            // FILE_RENAME_INFO on Win64: flags at 0, root handle at 8, name byte length at 16, UTF-16 name at 20.
            // The Win32 DOS-path conversion also requires a terminator, outside FileNameLength.
            Memory(RENAME_HEADER_SIZE + name.size + Char.SIZE_BYTES).use { info ->
                info.clear()
                info.setInt(16L, name.size)
                info.write(RENAME_HEADER_SIZE, name, 0, name.size)
                if (!api.SetFileInformationByHandle(handle, FILE_RENAME_INFO, info, info.size().toInt())) {
                    failure("Case-only rename", source)
                }
            }
        }
    }

    private inline fun <T> withHandle(file: File, access: Int, block: (Pointer) -> T): T {
        val handle = api.CreateFileW(
            WString(file.absolutePath),
            access,
            SHARE_ALL,
            null,
            OPEN_EXISTING,
            DIRECTORY,
            null
        )
        if (Pointer.nativeValue(handle) == -1L) failure("Open file identity", file)
        return try {
            block(handle)
        } finally {
            if (!api.CloseHandle(handle)) failure("Close file identity", file)
        }
    }

    private fun failure(operation: String, file: File): Nothing =
        throw IOException("$operation failed for $file (Windows error ${Native.getLastError()})")

    @Suppress("FunctionNaming", "ktlint:standard:function-naming")
    private interface Kernel32 : StdCallLibrary {
        fun CreateFileW(
            name: WString,
            access: Int,
            share: Int,
            security: Pointer?,
            creation: Int,
            flags: Int,
            template: Pointer?
        ): Pointer

        fun GetFileInformationByHandle(handle: Pointer, info: Pointer): Boolean
        fun SetFileInformationByHandle(handle: Pointer, kind: Int, info: Pointer, size: Int): Boolean
        fun CloseHandle(handle: Pointer): Boolean
    }

    private const val FILE_INFORMATION_SIZE = 52L
    private const val RENAME_HEADER_SIZE = 20L
    private const val FILE_RENAME_INFO = 3
    private const val DELETE_ACCESS = 0x10000
    private const val SHARE_ALL = 7
    private const val OPEN_EXISTING = 3
    private const val DIRECTORY = 0x02000000
}
