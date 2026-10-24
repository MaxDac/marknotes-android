package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.core.notes.NotePaths
import io.github.maxdac.marknotes.sync.foldCase
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

data class PendingRename(
    val path: String,
    val toPath: String,
    val local: Boolean,
    val remote: RemoteEntry,
    val localIdentity: String?
) {
    init {
        require(path.isNotEmpty() && NotePaths.normalize(path) == path) { "Invalid rename source" }
        require(toPath.isNotEmpty() && NotePaths.normalize(toPath) == toPath) { "Invalid rename destination" }
        require(path != toPath && foldCase(path) == foldCase(toPath)) { "Not a case-only rename" }
        require(remote.path == path || remote.path == toPath) { "Remote rename identity has an unrelated path" }
        require(remote.id.isNotBlank() && (!local || !localIdentity.isNullOrBlank())) { "Missing rename identity" }
    }
}

interface RenameStore {
    fun all(): List<PendingRename>
    fun put(rename: PendingRename)
    fun delete(path: String)
    fun clear()
}

class InMemoryRenameStore : RenameStore {
    private val items = linkedMapOf<String, PendingRename>()

    override fun all(): List<PendingRename> = items.values.toList()

    override fun put(rename: PendingRename) {
        items[rename.path] = rename
    }

    override fun delete(path: String) {
        items.remove(path)
    }

    override fun clear() = items.clear()
}

/** Bridges the filesystem/provider rename and the per-drive history transaction across process death. */
class FileRenameStore(private val file: File) : RenameStore {
    override fun all(): List<PendingRename> {
        if (!file.exists()) return emptyList()
        return Json.parseToJsonElement(file.readText()).jsonArray.map {
            val o = it.jsonObject
            val remote = o.getValue("remote").jsonObject
            PendingRename(
                path = o.getValue("path").jsonPrimitive.content,
                toPath = o.getValue("toPath").jsonPrimitive.content,
                local = o.getValue("local").jsonPrimitive.boolean,
                remote = RemoteEntry(
                    remote.getValue("path").jsonPrimitive.content,
                    remote.getValue("id").jsonPrimitive.content,
                    remote.getValue("isFolder").jsonPrimitive.boolean,
                    remote.getValue("version").jsonPrimitive.content,
                    remote.getValue("modifiedMillis").jsonPrimitive.long
                ),
                localIdentity = o.getValue("localIdentity").jsonPrimitive.contentOrNull
            )
        }
    }

    override fun put(rename: PendingRename) = save(all().filterNot { it.path == rename.path } + rename)

    override fun delete(path: String) = save(all().filterNot { it.path == path })

    override fun clear() {
        Files.deleteIfExists(file.toPath())
    }

    private fun save(renames: List<PendingRename>) {
        writeRecoveryJson(
            file,
            JsonArray(
                renames.map { rename ->
                    buildJsonObject {
                        put("path", rename.path)
                        put("toPath", rename.toPath)
                        put("local", rename.local)
                        put("localIdentity", rename.localIdentity)
                        put(
                            "remote",
                            buildJsonObject {
                                put("path", rename.remote.path)
                                put("id", rename.remote.id)
                                put("isFolder", rename.remote.isFolder)
                                put("version", rename.remote.version)
                                put("modifiedMillis", rename.remote.modifiedMillis)
                            }
                        )
                    }
                }
            )
        )
    }
}
