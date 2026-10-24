package io.github.maxdac.marknotes.sync

import com.dropbox.core.DbxRequestConfig
import com.dropbox.core.http.HttpRequestor
import com.dropbox.core.v2.DbxClientV2
import com.dropbox.core.v2.files.CreateFolderErrorException
import io.github.maxdac.marknotes.core.sync.RemoteEntry
import java.io.ByteArrayOutputStream
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DropboxDriveStoreTest {
    @Test
    fun newUploadsUseStrictAddWithoutAutorename() = runBlocking {
        val http = Requests(
            200,
            """{".tag":"file","name":"n.md","path_lower":"/notes/n.md","path_display":"/notes/n.md",
                "id":"id:file","client_modified":"2020-01-01T00:00:00Z","server_modified":"2020-01-01T00:00:00Z",
                "rev":"123456789","size":4}"""
        )

        store(http).uploadNew("n.md", "note".toByteArray(), 0L)

        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().endsWith("/files/upload"))
        val args = http.headers.getValue("Dropbox-API-Arg")
        assertTrue(args.contains("\"strict_conflict\":true"))
        assertTrue(args.contains("\"autorename\":false"))
        assertTrue(args.contains("\"mode\":\"add\""))
        assertTrue(args.contains("\"client_modified\":\"1970-01-01T00:00:00Z\""))
    }

    @Test
    fun plannedFolderConflictFailsWithoutLookingUpOrAdoptingTheExistingIdentity() {
        val http = Requests(
            409,
            """{"error_summary":"path/conflict/folder/","error":{".tag":"path",
                "path":{".tag":"conflict","conflict":{".tag":"folder"}}}}"""
        )

        assertThrows(CreateFolderErrorException::class.java) {
            runBlocking { store(http).createFolder("folder") }
        }

        assertEquals(1, http.urls.size)
        assertTrue(http.urls.single().endsWith("/files/create_folder_v2"))
    }

    @Test
    fun unsupportedSafeFolderDeletionMakesNoRecursiveRequest() {
        val http = Requests(500, "{}")
        val folder = RemoteEntry("folder", "id:folder", true, "", 0L)

        val failure = assertThrows(IOException::class.java) {
            runBlocking { store(http).deleteFolderIfEmpty(folder) }
        }
        assertTrue(failure.message.orEmpty().contains("Safe empty-folder deletion is not supported"))
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { store(http).delete(folder) }
        }
        assertTrue(http.urls.isEmpty())
    }

    @Test
    fun unsupportedRemoteCaseRenameMakesNoMoveOrTemporaryRenameRequest() {
        val http = Requests(500, "{}")
        val entry = RemoteEntry("Note.md", "id:file", false, "revision", 1000L)

        val failure = assertThrows(IOException::class.java) {
            runBlocking { store(http).rename(entry, "note.md") }
        }

        assertTrue(failure.message.orEmpty().contains("Safe case-only rename is not supported"))
        assertTrue(http.urls.isEmpty())
    }

    private fun store(http: Requests): DropboxDriveStore {
        val config = DbxRequestConfig.newBuilder("marknotes-tests").withHttpRequestor(http).build()
        return DropboxDriveStore(DbxClientV2(config, "test-token"), "notes")
    }

    private class Requests(private val status: Int, private val response: String) : HttpRequestor() {
        val urls = mutableListOf<String>()
        val headers = linkedMapOf<String, String>()

        override fun doGet(url: String, headers: Iterable<Header>): Response = error("Unexpected GET: $url")

        override fun startPut(url: String, headers: Iterable<Header>): Uploader = error("Unexpected PUT: $url")

        override fun startPost(url: String, headers: Iterable<Header>): Uploader {
            urls += url
            headers.forEach { this.headers[it.key] = it.value }
            return object : Uploader() {
                private val bytes = ByteArrayOutputStream()

                override fun getBody() = bytes

                override fun close() = Unit

                override fun abort() = Unit

                override fun finish() = Response(
                    status,
                    response.byteInputStream(),
                    mapOf("Content-Type" to listOf("application/json"), "X-Dropbox-Request-Id" to listOf("test"))
                )
            }
        }
    }
}
