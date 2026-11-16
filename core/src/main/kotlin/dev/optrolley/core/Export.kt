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
 * The first build's export file (F9): the journal and nothing else, from
 * which that build derived everything. Still read, so that such a file can
 * be imported (O62); what is written now is [Backup].
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

/**
 * The export file (F9): the state, the record and the journal together, so
 * that importing it into an empty install gives back the same list and the
 * same presentation everywhere (O22), with nothing to re-derive.
 */
object Backup {
    const val VERSION = 2

    /** What an export file holds. A first-build file holds only [journal]; the rest is then to be derived from it. */
    class Contents(val stored: Stored?, val record: List<TripRecord>, val journal: List<Observation>)

    private val json = Json {
        classDiscriminator = "type"
        encodeDefaults = true
    }
    private val journalSerializer = ListSerializer(Observation.serializer())
    private val recordSerializer = ListSerializer(TripRecord.serializer())

    fun encode(stored: Stored, record: List<TripRecord>, journal: List<Observation>): String = json.encodeToString(
        JsonObject.serializer(),
        buildJsonObject {
            put("format", Export.FORMAT)
            put("version", VERSION)
            put("state", json.encodeToJsonElement(Stored.serializer(), stored))
            put("trips", json.encodeToJsonElement(recordSerializer, record))
            put("history", json.encodeToJsonElement(journalSerializer, journal))
        },
    )

    /** Reads a file of this version or the first build's. Throws [IllegalArgumentException] for anything else. */
    fun decode(text: String): Contents {
        val root = try {
            json.parseToJsonElement(text)
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Not JSON", e)
        }
        require(root is JsonObject) { "Not an export: expected a JSON object" }
        val version = root["version"]
        if (version is JsonPrimitive && !version.isString && version.intOrNull == Export.VERSION) {
            return Contents(null, emptyList(), Export.decode(text))
        }
        require(root.keys == setOf("format", "version", "state", "trips", "history")) { "Not an export: unexpected fields ${root.keys}" }
        val format = root.getValue("format")
        require(format is JsonPrimitive && format.isString && format.content == Export.FORMAT) { "Not an ${Export.FORMAT} file" }
        require(version is JsonPrimitive && !version.isString && version.intOrNull == VERSION) { "Unsupported version: $version" }
        return try {
            val stored = json.decodeFromJsonElement(Stored.serializer(), root.getValue("state"))
            val record = json.decodeFromJsonElement(recordSerializer, root.getValue("trips"))
            require(stored.trips == record.size) { "Malformed export: the state and the trips disagree" }
            Contents(stored, record, json.decodeFromJsonElement(journalSerializer, root.getValue("history")))
        } catch (e: SerializationException) {
            throw IllegalArgumentException("Malformed export", e)
        }
    }
}
