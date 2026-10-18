package dev.optrolley.oracles

import dev.optrolley.core.ChainId
import dev.optrolley.core.Export
import dev.optrolley.core.Observation
import dev.optrolley.core.Routers
import dev.optrolley.core.Scope
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import dev.optrolley.core.replay
import dev.optrolley.core.settle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tier 3 group E: the record (T5). */
class RecordOracles {

    @Test
    fun `O20 append-only - any prefix replays and presents, and nothing is rewritten`() = property("O20", seed = 20) { c ->
        val h = c.hist.history
        for (k in 0..h.size) {
            val prefix = ArrayList(h.subList(0, k))
            val snapshot = prefix.toList()
            replay(prefix)
            present(prefix)
            assertEquals(snapshot, prefix, "replaying or presenting prefix $k altered the history it was given")
        }
    }

    @Test
    fun `O20 append-only - settling only appends`() = property("O20", seed = 120) { c ->
        val h = c.hist.history
        val last = h.last().at
        for (now in listOf(last, last + 60_000, last + Tier3.IDLE_TIMEOUT_MS - 1, last + Tier3.IDLE_TIMEOUT_MS + 1, last + 10 * Gen.DAY)) {
            val settled = settle(h, now)
            assertTrue(settled.size >= h.size, "settling at $now removed observations")
            assertEquals(h, settled.subList(0, h.size), "settling at $now altered recorded observations")
        }
    }

    @Test
    fun `O20 append-only - appending never changes the decoded earlier part of an export`() = property("O20", seed = 220) { c ->
        val h = c.hist.history
        val whole = Export.decode(Export.encode(h))
        for (k in (0..h.size).shuffled(c.rnd).take(8)) {
            val part = Export.decode(Export.encode(h.subList(0, k).toList()))
            assertEquals(part, whole.subList(0, k), "the export of the first $k observations is not the start of the whole export")
        }
        c.gen.run(10)
        val longer = Export.decode(Export.encode(c.hist.history))
        assertEquals(whole, longer.subList(0, whole.size), "appending changed the decoded earlier part of the export")
    }

    @Test
    fun `O21 rebuildable over random histories`() = property("O21", seed = 21) { c ->
        val h = c.hist
        val originals = h.selections().associateWith { present(h.at(it).history) }
        val replayed = replay(h.history)
        // Other work first, so nothing derived from this history survives to be reused.
        val other = Hist(start = T0 + 1_000_000)
        Gen(Random(c.seed + 1), other).setup().run()
        present(other.history)
        replay(other.history)
        val rebuilt = Hist(h.history.map { it.copied() })
        assertEquals(replayed, replay(rebuilt.history), "rebuilding from the history gave a different state")
        for ((store, p) in originals) {
            assertEquals(p, present(rebuilt.at(store).history), "rebuilding from the history gave a different presentation with $store selected")
        }
    }

    private fun assertRoundTrip(h: Hist) {
        val imported = Hist(Export.decode(Export.encode(h.history)))
        assertEquals(replay(h.history).entries, replay(imported.history).entries, "the imported list differs")
        assertEquals(present(h.history), present(imported.history), "the imported presentation differs")
        for (store in h.selections()) {
            assertEquals(present(h.at(store).history), present(imported.at(store).history), "the imported presentation differs with $store selected")
        }
    }

    @Test
    fun `O22 round trip over random histories`() = property("O22", seed = 22) { c -> assertRoundTrip(c.hist) }

    @Test
    fun `O22 round trip with awkward text`() {
        for (v in hardVariants) withCase("O22", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val chain = ChainId("ch \"1\"")
            h.chain(chain, "Tesco, \"Extra\"\n")
            h.store(StoreId("s/1"), "Corner shop — \\ ünïcødé 🛒", chain)
            h.store(StoreId("s 2"), "")
            val a = h.add("  Crème \"fraîche\"\t", "½ kg", "line one\nline two")
            val b = h.add("{\"json\": true}", "", "")
            val c = h.add("日本茶", null, "🙂")
            h.select(StoreId("s/1"))
            h.notHere(c, Scope.CHAIN)
            h.tick(b); h.tick(a, gap = 2_000)
            h.end()
            h.add("crème \"FRAÎCHE\"")
            h.select(StoreId("s 2"))
            h.emit(Observation.RouterChosen(h.next(1_000), Routers.all.list.last().id))
            assertRoundTrip(h)
        }
    }
}
