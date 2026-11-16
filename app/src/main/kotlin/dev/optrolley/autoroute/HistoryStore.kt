package dev.optrolley.autoroute

import dev.optrolley.core.Observation
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile

/**
 * The journal (D19): one [Observation] per line of JSON, appended as each
 * action is made. Every write is synced to disk before it returns, so an
 * action the UI shows as done survives the app being killed straight after.
 * The app does not read it to run, only to make good a write cut short
 * ([Kept]), to carry a first-build install over, and to export.
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
    fun load(): List<Observation> = loadFrom(0)

    /**
     * Reads what was appended after the first [offset] bytes: the actions a
     * state that reflects that much of the file has not seen. Nothing, and
     * nothing is read, if the file is no longer than that.
     */
    fun loadFrom(offset: Long): List<Observation> {
        if (length() <= offset) return emptyList()
        val bytes = RandomAccessFile(file, "r").use { f ->
            f.seek(offset)
            ByteArray((f.length() - offset).toInt()).also(f::readFully)
        }
        val end = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (end < bytes.size) {
            RandomAccessFile(file, "rw").use { it.setLength(offset + end) }
        }
        return String(bytes, 0, end, Charsets.UTF_8)
            .lineSequence()
            .filter { it.isNotEmpty() }
            .mapIndexed { i, line ->
                try {
                    json.decodeFromString(Observation.serializer(), line)
                } catch (e: IllegalArgumentException) {
                    throw CorruptHistoryException("Line ${i + 1} of ${file.name}${if (offset > 0) " after byte $offset" else ""} is not an observation", e)
                }
            }
            .toList()
    }

    fun isEmpty(): Boolean = length() == 0L

    fun length(): Long = if (file.exists()) file.length() else 0L

    /** Forgets every action journalled so far (D19). */
    fun clear() {
        if (file.exists()) RandomAccessFile(file, "rw").use { it.setLength(0) }
    }

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

class CorruptHistoryException(message: String, cause: Throwable?) : IllegalStateException(message, cause)
