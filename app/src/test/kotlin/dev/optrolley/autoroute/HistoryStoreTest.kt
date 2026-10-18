package dev.optrolley.autoroute

import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Scope
import dev.optrolley.core.StoreId
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The record on disk: append-only, synced, and imported only into nothing (T5, O20). */
class HistoryStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val store = StoreId("s1")
    private val chain = ChainId("c1")
    private val milk = ItemKey("milk")

    /** One of every kind, with nulls and non-ASCII, so the encoding is exercised whole. */
    private val everyKind: List<Observation> = listOf(
        Observation.ChainCreated(1, chain, "Tesco"),
        Observation.StoreCreated(2, store, "Tesco Extra", chain),
        Observation.StoreCreated(3, StoreId("s2"), "Corner shop"),
        Observation.StoreRenamed(4, store, "Tesco Extra — Ponsonby"),
        Observation.StoreChainSet(5, StoreId("s2"), null),
        Observation.ChainRenamed(6, chain, "Tesco Group"),
        Observation.Selected(7, store),
        Observation.Added(8, "Milk", "2 L", "trim \"lite\""),
        Observation.Added(9, "Crème fraîche"),
        Observation.Edited(10, milk, "Milk", null, "blue top"),
        Observation.Ticked(11, milk),
        Observation.Unticked(12, milk),
        Observation.NotHere(13, ItemKey("crème fraîche"), store, Scope.CHAIN),
        Observation.NotHereUndone(14, ItemKey("crème fraîche"), store, Scope.CHAIN),
        Observation.Removed(15, milk),
        Observation.TripEnded(16, EndReason.IDLE),
        Observation.Selected(17, null),
        Observation.RouterChosen(18, "first-seen"),
    )

    private fun file() = File(tmp.root, "history.jsonl")

    @Test
    fun `a missing file is an empty history`() {
        val s = HistoryStore(file())
        assertTrue(s.isEmpty())
        assertEquals(emptyList(), s.load())
    }

    @Test
    fun `what is appended is what a fresh load reads back`() {
        HistoryStore(file()).append(everyKind.take(5))
        HistoryStore(file()).append(everyKind.drop(5))
        assertEquals(everyKind, HistoryStore(file()).load())
    }

    @Test
    fun `appending never touches the lines already written`() {
        val s = HistoryStore(file())
        s.append(everyKind.take(3))
        val before = file().readBytes()
        s.append(everyKind.drop(3).take(1))
        s.append(everyKind.drop(4))
        val after = file().readBytes()
        assertTrue(after.size > before.size)
        assertContentEquals(before, after.copyOf(before.size))
    }

    @Test
    fun `one observation per line, tagged by type`() {
        HistoryStore(file()).append(everyKind)
        val lines = file().readText().split('\n')
        assertEquals("", lines.last(), "the file ends with a newline")
        assertEquals(everyKind.size, lines.size - 1)
        assertTrue(lines[0].startsWith("{\"type\":\"chain-created\""), lines[0])
        assertTrue(lines[7].startsWith("{\"type\":\"added\""), lines[7])
    }

    @Test
    fun `an append is synced with its bytes already written, before it returns`() {
        val synced = mutableListOf<Long>()
        val s = HistoryStore(file()) { out ->
            out.flush()
            synced += out.channel.size()
            out.fd.sync()
        }
        s.append(everyKind.take(2))
        assertEquals(listOf(file().length()), synced)
        s.append(everyKind.drop(2))
        assertEquals(2, synced.size)
        assertEquals(file().length(), synced.last())
    }

    @Test
    fun `appending nothing writes nothing`() {
        var syncs = 0
        val s = HistoryStore(file()) { syncs++ }
        s.append(emptyList())
        assertFalse(file().exists())
        assertEquals(0, syncs)
    }

    @Test
    fun `a write cut off mid-line is dropped, and the next append is whole`() {
        val s = HistoryStore(file())
        s.append(everyKind.take(2))
        file().appendText("{\"type\":\"added\",\"at\":99,\"na")
        assertEquals(everyKind.take(2), HistoryStore(file()).load())
        s.append(everyKind.drop(2).take(1))
        assertEquals(everyKind.take(3), HistoryStore(file()).load())
    }

    @Test
    fun `a corrupt line is reported, not skipped`() {
        val s = HistoryStore(file())
        s.append(everyKind.take(1))
        file().appendText("not json\n")
        s.append(everyKind.drop(1).take(1))
        val e = assertFailsWith<CorruptHistoryException> { HistoryStore(file()).load() }
        assertTrue(e.message!!.contains("Line 2"), e.message)
    }

    @Test
    fun `import writes the whole history into an empty store`() {
        val s = HistoryStore(file())
        s.importAll(everyKind)
        assertEquals(everyKind, HistoryStore(file()).load())
        assertEquals(listOf("history.jsonl"), tmp.root.list()!!.toList(), "no temporary file is left behind")
    }

    @Test
    fun `import into an existing empty file works`() {
        file().createNewFile()
        HistoryStore(file()).importAll(everyKind)
        assertEquals(everyKind, HistoryStore(file()).load())
    }

    @Test
    fun `import refuses a store that already has a history, and leaves it as it was`() {
        val s = HistoryStore(file())
        s.append(everyKind.take(1))
        val before = file().readBytes()
        assertFailsWith<HistoryNotEmptyException> { s.importAll(everyKind) }
        assertContentEquals(before, file().readBytes())
        assertEquals(listOf("history.jsonl"), tmp.root.list()!!.toList())
    }

    @Test
    fun `appending after an import continues the same record`() {
        val s = HistoryStore(file())
        s.importAll(everyKind.take(4))
        s.append(everyKind.drop(4))
        assertEquals(everyKind, HistoryStore(file()).load())
    }
}
