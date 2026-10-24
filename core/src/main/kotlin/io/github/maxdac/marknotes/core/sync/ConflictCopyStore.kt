package io.github.maxdac.marknotes.core.sync

import io.github.maxdac.marknotes.sync.ConflictCopyIntent
import io.github.maxdac.marknotes.sync.Side
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** Write-ahead recovery state, separate from the merge-back UI's completed conflicts. */
data class PendingConflictCopy(
    val intent: ConflictCopyIntent,
    val copyText: String,
    val localText: String,
    val remoteText: String,
    val remoteEntry: RemoteEntry,
    val baseText: String?,
    val winner: Side,
    val winnerRecorded: Boolean = false
)

interface ConflictCopyStore {
    fun all(): List<PendingConflictCopy>
    fun put(pending: PendingConflictCopy)
    fun delete(path: String)
    fun clear()
}

class InMemoryConflictCopyStore : ConflictCopyStore {
    private val items = linkedMapOf<String, PendingConflictCopy>()

    override fun all(): List<PendingConflictCopy> = items.values.toList()

    override fun put(pending: PendingConflictCopy) {
        items[pending.intent.path] = pending
    }

    override fun delete(path: String) {
        items.remove(path)
    }

    override fun clear() = items.clear()
}

/** Per-drive journal. Corrupt or unreadable recovery state fails sync instead of silently duplicating a copy. */
class FileConflictCopyStore(private val file: File) : ConflictCopyStore {
    override fun all(): List<PendingConflictCopy> {
        if (!file.exists()) return emptyList()
        return Json.parseToJsonElement(file.readText()).jsonArray.map { value ->
            val o = value.jsonObject
            val entry = o.getValue("remoteEntry").jsonObject
            PendingConflictCopy(
                intent = conflictCopyIntentFromJson(o.getValue("intent").jsonObject),
                copyText = o.string("copyText"),
                localText = o.string("localText"),
                remoteText = o.string("remoteText"),
                remoteEntry = RemoteEntry(
                    entry.string("path"),
                    entry.string("id"),
                    entry.getValue("isFolder").jsonPrimitive.boolean,
                    entry.string("version"),
                    entry.number("modifiedMillis")
                ),
                baseText = o.getValue("baseText").jsonPrimitive.contentOrNull,
                winner = Side.valueOf(o.string("winner")),
                winnerRecorded = o.getValue("winnerRecorded").jsonPrimitive.boolean
            )
        }
    }

    override fun put(pending: PendingConflictCopy) =
        save(all().filterNot { it.intent.path == pending.intent.path } + pending)

    override fun delete(path: String) = save(all().filterNot { it.intent.path == path })

    override fun clear() {
        Files.deleteIfExists(file.toPath())
    }

    private fun save(items: List<PendingConflictCopy>) {
        val json = JsonArray(
            items.map { pending ->
                buildJsonObject {
                    put("intent", pending.intent.toJson())
                    put("copyText", pending.copyText)
                    put("localText", pending.localText)
                    put("remoteText", pending.remoteText)
                    put("baseText", pending.baseText)
                    put("winner", pending.winner.name)
                    put("winnerRecorded", pending.winnerRecorded)
                    put(
                        "remoteEntry",
                        buildJsonObject {
                            put("path", pending.remoteEntry.path)
                            put("id", pending.remoteEntry.id)
                            put("isFolder", pending.remoteEntry.isFolder)
                            put("version", pending.remoteEntry.version)
                            put("modifiedMillis", pending.remoteEntry.modifiedMillis)
                        }
                    )
                }
            }
        )
        writeRecoveryJson(file, json)
    }
}

internal fun writeRecoveryJson(file: File, json: JsonElement) {
    Files.createDirectories(file.parentFile.toPath())
    val temp = File(file.parentFile, "${file.name}.tmp")
    FileOutputStream(temp).use {
        it.write(json.toString().toByteArray(Charsets.UTF_8))
        it.fd.sync()
    }
    Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}

internal fun ConflictCopyIntent.toJson(): JsonObject = buildJsonObject {
    put("path", path)
    put("localHash", localHash)
    put("remoteHash", remoteHash)
    put("remoteId", remoteId)
    put("remoteVersion", remoteVersion)
    put("localModifiedMillis", localModifiedMillis)
    put("remoteModifiedMillis", remoteModifiedMillis)
    put("baseHash", baseHash?.let(::JsonPrimitive) ?: JsonNull)
    put("newId", newId)
    put("copyPath", copyPath)
    put("copyModifiedMillis", copyModifiedMillis)
}

internal fun conflictCopyIntentFromJson(o: JsonObject) = ConflictCopyIntent(
    path = o.string("path"),
    localHash = o.string("localHash"),
    remoteHash = o.string("remoteHash"),
    remoteId = o.string("remoteId"),
    remoteVersion = o.string("remoteVersion"),
    localModifiedMillis = o.number("localModifiedMillis"),
    remoteModifiedMillis = o.number("remoteModifiedMillis"),
    baseHash = o.getValue("baseHash").jsonPrimitive.contentOrNull,
    newId = o.string("newId"),
    copyPath = o.string("copyPath"),
    copyModifiedMillis = o.number("copyModifiedMillis")
)

private fun JsonObject.string(key: String) = getValue(key).jsonPrimitive.content

private fun JsonObject.number(key: String) = getValue(key).jsonPrimitive.long
