package dev.optrolley.autoroute

import dev.optrolley.core.Observation
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The record (T5): one [Observation] per line of JSON, only ever appended
 * (O20). Every write is synced to disk before it returns, so an action the UI
 * shows as done survives the app being killed straight after.
 *
 * Not thread-safe; the caller serialises access.
 */
class HistoryStore(
    val file: File,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    /**
     * Reads the whole history. A final line without its newline is a write
     * that was cut off before it returned, so it was never reported done: it
     * is dropped from the file rather than replayed half-written.
     */
    fun load(): List<Observation> {
        if (!file.exists()) return emptyList()
        val bytes = file.readBytes()
        val end = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (end < bytes.size) {
            RandomAccessFile(file, "rw").use { it.setLength(end.toLong()) }
        }
        return String(bytes, 0, end, Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotEmpty() }
            .mapIndexed { i, line ->
                try {
                    json.decodeFromString(Observation.serializer(), line)
                } catch (e: IllegalArgumentException) {
                    throw CorruptHistoryException("Line ${i + 1} of ${file.name} is not an observation", e)
                }
            }
            .toList()
    }

    fun isEmpty(): Boolean = !file.exists() || file.length() == 0L

    fun append(observations: List<Observation>) {
        if (observations.isEmpty()) return
        file.parentFile?.mkdirs()
        FileOutputStream(file, true).use { out ->
            out.write(encode(observations))
            out.flush()
            sync(out)
        }
    }

    /**
     * Replaces an empty history with [observations], all or nothing: written
     * beside the record and renamed over it, so a crash leaves either nothing
     * or everything.
     */
    fun importAll(observations: List<Observation>) {
        if (!isEmpty()) throw HistoryNotEmptyException()
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".import")
        FileOutputStream(temp, false).use { out ->
            out.write(encode(observations))
            out.flush()
            sync(out)
        }
        if (!temp.renameTo(file)) {
            temp.delete()
            throw java.io.IOException("Could not move the imported history into place")
        }
    }

    private fun encode(observations: List<Observation>): ByteArray =
        buildString {
            for (o in observations) {
                append(json.encodeToString(Observation.serializer(), o))
                append('\n')
            }
        }.toByteArray(Charsets.UTF_8)

    companion object {
        /** The same encoding core's export uses for each observation. */
        val json = Json {
            classDiscriminator = "type"
            encodeDefaults = true
        }
    }
}

class HistoryNotEmptyException : IllegalStateException("Import only works into an empty history")

class CorruptHistoryException(message: String, cause: Throwable) : IllegalStateException(message, cause)
