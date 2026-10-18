package dev.optrolley.core

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * The export file (F9) is the history and nothing else: the list, items and
 * stores are all derived from it, so it is enough to rebuild them (O22).
 */
object Export {
    const val FORMAT = "optrolley-history"
    const val VERSION = 1

    // Defaults are written out so the file reads the same to anything but this decoder.
    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }
    private val historySerializer = ListSerializer(Observation.serializer())

    fun encode(history: List<Observation>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", FORMAT)
            put("version", VERSION)
            put("history", json.encodeToJsonElement(historySerializer, history))
        },
    )

    /** Throws [IllegalArgumentException] for anything that is not a valid export. */
    fun decode(text: String): List<Observation> {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not JSON", e)
        }
        require(root is JsonObject) { "Not an export: expected a JSON object" }
        require(root.keys == setOf("format", "version", "history")) { "Not an export: unexpected fields ${root.keys}" }
        val format = root.getValue("format")
        require(format is JsonPrimitive && format.isString && format.content == FORMAT) { "Not an $FORMAT file" }
        val version = root.getValue("version")
        require(version is JsonPrimitive && !version.isString && version.intOrNull == VERSION) {
            "Unsupported version: $version"
        }
        val history: JsonElement = root.getValue("history")
        return try {
            json.decodeFromJsonElement(historySerializer, history)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Malformed history", e)
        }
    }
}
