package dev.optrolley.autoroute

import dev.optrolley.core.Backup
import dev.optrolley.core.Book
import dev.optrolley.core.Observation
import dev.optrolley.core.Stored
import dev.optrolley.core.TripRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile

/**
 * What the app keeps between runs (tier 4 §7), in three files beside each
 * other: the [journal] of every action, the record of ended trips, and the
 * state. Each action is written to them in that order, each synced before
 * the next, so the only thing a crash can leave is actions in the journal
 * that the state has not caught up with; [open] makes that good.
 *
 * Not thread-safe; the caller serialises access.
 */
class Kept(
    val journal: HistoryStore,
    private val sync: (FileOutputStream) -> Unit = { it.fd.sync() },
) {
    private val dir: File = journal.file.absoluteFile.parentFile!!
    val stateFile = File(dir, STATE_FILE)
    val recordFile = File(dir, RECORD_FILE)

    /** The state, with how much of the journal file it reflects: more than that is actions not yet applied. */
    @Serializable
    private class StateFile(val journalBytes: Long, val state: Stored)

    /**
     * Takes up what was kept. On a fresh install that is nothing. On an
     * install the first build left, which has a journal and no state, the
     * journal is applied once from the start and the state and record
     * written (O62). After that the journal is read only for what a crash
     * left unapplied, and not at all if there is nothing.
     */
    fun open(): Book {
        val kept = readState()
        if (kept == null) {
            val (book, record) = Book.of(journal.load())
            if (book.actions > 0) {
                write(recordFile, encode(record), append = false)
                writeState(book)
            }
            return book
        }
        val onRecord = readRecord()
        if (onRecord.size < kept.state.trips) throw CorruptHistoryException("${recordFile.name} holds fewer trips than ${stateFile.name} knows of", null)
        val book = Book(kept.state, onRecord.take(kept.state.trips))
        val unapplied = journal.loadFrom(kept.journalBytes)
        if (unapplied.isNotEmpty() || onRecord.size > kept.state.trips) {
            // A trip already on record from before the crash is not appended again.
            val fresh = unapplied.mapNotNull(book::apply).drop(onRecord.size - kept.state.trips)
            write(recordFile, encode(fresh), append = true)
            writeState(book)
        }
        return book
    }

    /** Keeps [actions], just applied to [book], and the [trips] they ended. */
    fun keep(book: Book, actions: List<Observation>, trips: List<TripRecord>) {
        if (actions.isEmpty()) return
        journal.append(actions)
        write(recordFile, encode(trips), append = true)
        writeState(book)
    }

    /** The record as kept, oldest trip first. */
    fun readRecord(): List<TripRecord> {
        if (!recordFile.exists()) return emptyList()
        val bytes = recordFile.readBytes()
        // A last line without its newline was cut off before it was reported written.
        val end = bytes.lastIndexOf('\n'.code.toByte()) + 1
        if (end < bytes.size) RandomAccessFile(recordFile, "rw").use { it.setLength(end.toLong()) }
        return String(bytes, 0, end, Charsets.UTF_8).lineSequence().filter { it.isNotEmpty() }.mapIndexed { i, line ->
            try {
                json.decodeFromString(TripRecord.serializer(), line)
            } catch (e: IllegalArgumentException) {
                throw CorruptHistoryException("Line ${i + 1} of ${recordFile.name} is not a trip", e)
            }
        }.toList()
    }

    fun isEmpty(): Boolean = journal.isEmpty() && !stateFile.exists()

    /** Fills an empty install from an export (F9, O22): all three files, the state last. */
    fun import(contents: Backup.Contents): Book {
        if (!isEmpty()) throw HistoryNotEmptyException()
        journal.importAll(contents.journal)
        val stored = contents.stored ?: return open()
        val book = Book(stored, contents.record)
        write(recordFile, encode(contents.record), append = false)
        writeState(book)
        return book
    }

    /**
     * Empties the journal (D19). Nothing shown depends on it (O59); only
     * what an export says of past actions is lost.
     */
    fun emptyJournal(book: Book) {
        journal.clear()
        writeState(book)
    }

    private fun readState(): StateFile? {
        if (!stateFile.exists()) return null
        return try {
            json.decodeFromString(StateFile.serializer(), stateFile.readText(Charsets.UTF_8))
        } catch (e: SerializationException) {
            // The state is rebuilt from the journal if it cannot be read; with no journal there is nothing to rebuild it from.
            if (journal.isEmpty()) throw CorruptHistoryException("${stateFile.name} cannot be read", e)
            null
        } catch (e: IllegalArgumentException) {
            if (journal.isEmpty()) throw CorruptHistoryException("${stateFile.name} cannot be read", e)
            null
        }
    }

    /** Written beside itself and renamed into place, so it is never seen half-written. */
    private fun writeState(book: Book) {
        val text = json.encodeToString(StateFile.serializer(), StateFile(journal.length(), book.stored()))
        val temp = File(dir, "$STATE_FILE.new")
        write(temp, text.toByteArray(Charsets.UTF_8), append = false)
        if (!temp.renameTo(stateFile)) {
            temp.delete()
            throw IOException("Could not move the state into place")
        }
    }

    private fun write(file: File, bytes: ByteArray, append: Boolean) {
        if (append && bytes.isEmpty()) return
        dir.mkdirs()
        FileOutputStream(file, append).use { out ->
            out.write(bytes)
            out.flush()
            sync(out)
        }
    }

    private fun encode(trips: List<TripRecord>): ByteArray = buildString {
        for (t in trips) {
            append(json.encodeToString(TripRecord.serializer(), t))
            append('\n')
        }
    }.toByteArray(Charsets.UTF_8)

    companion object {
        const val STATE_FILE = "state.json"
        const val RECORD_FILE = "trips.jsonl"
        private val json = Json { encodeDefaults = true }
    }
}
