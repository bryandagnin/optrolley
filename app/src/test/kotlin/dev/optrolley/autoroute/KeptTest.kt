package dev.optrolley.autoroute

import dev.optrolley.autoroute.ui.AppFixture
import dev.optrolley.core.Backup
import dev.optrolley.core.Barcode
import dev.optrolley.core.Book
import dev.optrolley.core.EndReason
import dev.optrolley.core.Export
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.TripRecord
import dev.optrolley.core.confidence
import dev.optrolley.core.present
import dev.optrolley.core.replay
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The three files (tier 4 §7): a first-build install carried over (O62), the
 * journal not needed afterwards (O59), and a write cut short made good (P2).
 * No Android here: they are plain files.
 */
class KeptTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val t0 = AppFixture.START + 100_000

    /** After the fixture: two trips at Tesco, a rename, a link, and a trip left in progress. */
    private val later: List<Observation> = buildList {
        var at = t0
        fun next() = at.also { at += 60_000 }
        add(Observation.Ticked(next(), AppFixture.MILK))
        add(Observation.Ticked(next(), AppFixture.BREAD))
        add(Observation.TripEnded(next(), EndReason.USER))
        add(Observation.Added(next(), "Milk"))
        add(Observation.Added(next(), "Jam"))
        add(Observation.CodeLinked(next(), Barcode("96385074"), "jam"))
        add(Observation.ItemRenamed(next(), ItemKey("jam"), "Plum jam"))
        add(Observation.Ticked(next(), ItemKey("jam")))
        add(Observation.Ticked(next(), AppFixture.MILK))
        add(Observation.TripEnded(next(), EndReason.USER))
        add(Observation.Added(next(), "Bread"))
        add(Observation.Added(next(), "plum jam"))
        add(Observation.Ticked(next(), AppFixture.BREAD))
    }

    private fun kept(dir: File = tmp.root) = Kept(HistoryStore(File(dir, "history.jsonl")))

    private fun assertShows(actions: List<Observation>, book: Book, how: String) {
        assertEquals(replay(actions), book.state, "the state $how")
        assertEquals(present(actions), book.present(), "the presentation $how")
        assertEquals(confidence(actions), book.confidence(), "the confidence $how")
        for (store in book.state.stores.keys) {
            val there = Observation.Selected(Long.MAX_VALUE / 2, store)
            val (elsewhere, _) = Book.of(actions + there)
            val moved = Book(book.stored(), record(book, actions)).also { it.apply(there) }
            assertEquals(elsewhere.present(), moved.present(), "the presentation at $store $how")
        }
    }

    private fun record(book: Book, actions: List<Observation>): List<TripRecord> = Book.of(actions).second.take(book.trips)

    private fun act(k: Kept, book: Book, actions: List<Observation>) {
        for (a in actions) k.keep(book, listOf(a), listOfNotNull(book.apply(a)))
    }

    @Test
    fun `a fresh install opens on nothing and writes nothing`() {
        val k = kept()
        assertEquals(0, k.open().actions)
        assertTrue(k.isEmpty())
        assertFalse(k.stateFile.exists())
    }

    @Test
    fun `O62 a first-build install is carried over with everything as it was`() {
        val first = AppFixture.history + later.filter { it !is Observation.ItemRenamed }
        kept().journal.append(first)

        val book = kept().open()
        assertShows(first, book, "after the update")
        assertEquals(first, kept().journal.load(), "the journal is left as it was")
        assertTrue(kept().stateFile.exists())
        assertEquals(Book.of(first).second, kept().readRecord())
        assertShows(first, kept().open(), "on the next start")
    }

    @Test
    fun `O62 a first-build export is imported with everything as it was`() {
        val first = AppFixture.history + later.filter { it !is Observation.ItemRenamed }
        val book = kept().import(Backup.decode(Export.encode(first)))
        assertShows(first, book, "after the import")
        assertShows(first, kept().open(), "on the next start")
    }

    @Test
    fun `O22 an export imported into an empty install gives everything back`() {
        val all = AppFixture.history + later
        val from = kept(tmp.newFolder("from"))
        val book = from.open()
        act(from, book, all)
        val file = Backup.encode(book.stored(), from.readRecord(), from.journal.load())

        val to = kept(tmp.newFolder("to"))
        assertShows(all, to.import(Backup.decode(file)), "after the import")
        assertShows(all, to.open(), "on the next start")
        assertEquals(all, to.journal.load())
    }

    @Test
    fun `O59 with the journal emptied everything is as before and goes on working`() {
        val all = AppFixture.history + later
        val cut = all.size - 4
        val k = kept()
        val book = k.open()
        act(k, book, all.subList(0, cut))
        k.emptyJournal(book)
        assertShows(all.subList(0, cut), book, "with the journal just emptied")

        val again = kept().open()
        assertShows(all.subList(0, cut), again, "on the next start with no journal")
        act(kept(), again, all.subList(cut, all.size))
        assertShows(all, kept().open(), "after more actions with the journal emptied")
        assertEquals(all.subList(cut, all.size), kept().journal.load(), "the journal holds only what came after")
    }

    @Test
    fun `P2 a write cut short at any point loses nothing shown as done and applies nothing twice`() {
        val all = AppFixture.history + later
        val k = kept()
        val book = k.open()
        val files = listOf(k.journal.file, k.recordFile, k.stateFile)
        fun snapshot() = files.map { if (it.exists()) it.readBytes() else null }

        for ((i, action) in all.withIndex()) {
            val before = snapshot()
            act(k, book, listOf(action))
            val after = snapshot()
            // Died after the journal was written, and after the record was, with the state still the old one.
            for (written in 1..2) {
                val dir = tmp.newFolder()
                files.forEachIndexed { f, file ->
                    (if (f < written) after[f] else before[f])?.let { File(dir, file.name).writeBytes(it) }
                }
                val recovered = kept(dir).open()
                assertShows(all.subList(0, i + 1), recovered, "after dying $written file(s) into action ${i + 1}")
                assertEquals(Book.of(all.subList(0, i + 1)).second, kept(dir).readRecord(), "the record after dying $written file(s) into action ${i + 1}")
                assertShows(all.subList(0, i + 1), kept(dir).open(), "on the start after that")
            }
        }
    }

    @Test
    fun `a state that cannot be read is rebuilt from the journal`() {
        val all = AppFixture.history + later
        val k = kept()
        act(k, k.open(), all)
        k.stateFile.writeText("{ not json")
        assertShows(all, kept().open(), "after the state was spoiled")
        assertEquals(Book.of(all).second, kept().readRecord())
    }
}
