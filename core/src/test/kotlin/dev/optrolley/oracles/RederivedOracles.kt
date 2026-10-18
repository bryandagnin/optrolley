package dev.optrolley.oracles

import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.Entry
import dev.optrolley.core.Export
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Scope
import dev.optrolley.core.SectionKind
import dev.optrolley.core.SectionKind.ELSEWHERE
import dev.optrolley.core.SectionKind.LIST
import dev.optrolley.core.SectionKind.RUN
import dev.optrolley.core.SectionKind.UNPLACED
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import dev.optrolley.core.settle
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tier 3 group G: the oracles from the independent re-derivation. */
class RederivedOracles {

    private fun sorted(entries: List<Entry>) = entries.sortedBy { it.item.value }

    private fun tieish(rnd: Random): Long =
        if (rnd.nextBoolean()) rnd.nextLong(0, Tier3.TIE_WINDOW_MS) else rnd.nextLong(Tier3.TIE_WINDOW_MS, 60_000)

    /** The generated history with no trip in progress, a store selected and a few entries on the list. */
    private fun settled(c: Case, select: Boolean = true): Hist {
        val h = c.hist.fork()
        Gen(c.rnd, h).quiet()
        if (select && h.s.selected == null) h.select(h.s.stores.keys.random(c.rnd))
        POOL.shuffled(c.rnd).take(3).forEach { if (key(it) !in h.s.list) h.add(it) }
        return h
    }

    // O25 ---------------------------------------------------------------------------------------

    @Test
    fun `O25 unknown is not unavailable over random histories`() = property("O25", seed = 25) { c ->
        walk(c.hist.history) { st ->
            val store = st.s.selected ?: return@walk
            for (e in st.after.inSection(ELSEWHERE)) {
                assertTrue(
                    (store to e.item) in st.s.everTicked || st.s.standing(e.item, store).isNotEmpty(),
                    "${e.item} was never ticked at $store and has no standing not-here there, yet is in Elsewhere ${st.where()}",
                )
            }
        }
    }

    // O26 ---------------------------------------------------------------------------------------

    @Test
    fun `O26 section order over random histories`() = property("O26", seed = 26) { c ->
        val order = listOf(UNPLACED, RUN, ELSEWHERE)
        walk(c.hist.history) { st ->
            val kinds = st.after.sections.filter { it.entries.isNotEmpty() }.map { it.kind }
            val keys = st.after.keys()
            assertEquals(keys.size, keys.toSet().size, "an entry is in more than one place ${st.where()}")
            if (st.s.selected == null) {
                assertTrue(st.after.sections.size <= 1 && st.after.sections.all { it.kind == LIST }, "with no store selected there is one section ${st.where()}: $kinds")
                if (st.s.list.isNotEmpty()) assertEquals(listOf(LIST), kinds, "with no store selected there is one section ${st.where()}")
            } else {
                assertTrue(
                    kinds.all { it in order } && kinds == kinds.distinct() && kinds == kinds.sortedBy { order.indexOf(it) },
                    "sections out of order ${st.where()}: $kinds",
                )
            }
        }
    }

    // O27 ---------------------------------------------------------------------------------------

    @Test
    fun `O27 placed means Run over random histories`() = property("O27", seed = 27) { c ->
        walk(c.hist.history) { st ->
            val store = st.s.selected ?: return@walk
            for (e in st.after.flat()) {
                val section = st.after.sectionOf(e.item)
                if (section == ELSEWHERE) continue
                val expected = if (st.s.placed(e.item, store)) RUN else UNPLACED
                assertEquals(expected, section, "${e.item} at $store ${st.where()}")
            }
        }
    }

    @Test
    fun `O27 a trip with every tick tied places nothing`() {
        for (v in hardVariants) withCase("O27", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(StoreId("a"))
            h.tick(key("Milk")); h.tick(key("Bread"), gap = 4_999); h.tick(key("Eggs"), gap = 4_999)
            h.end()
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            assertEquals(listOf(UNPLACED), present(h.history).layout().map { it.first }, "one tie group teaches no position")
            h.tick(key("Milk")); h.tick(key("Bread"), gap = 5_000)
            h.end()
            h.add("Milk"); h.add("Bread")
            val p = present(h.history)
            assertEquals(RUN, p.sectionOf(key("Milk")))
            assertEquals(RUN, p.sectionOf(key("Bread")))
            assertEquals(UNPLACED, p.sectionOf(key("Eggs")))
        }
    }

    // O28 ---------------------------------------------------------------------------------------

    @Test
    fun `O28 a tick stays put over random histories`() = property("O28", seed = 28) { c ->
        walk(c.hist.history) { st ->
            val e = st.event
            if (e is Observation.Ticked || e is Observation.Unticked) {
                val item = if (e is Observation.Ticked) e.item else (e as Observation.Unticked).item
                val before = st.before!!
                assertEquals(before.layout(), st.after.layout(), "${st.where()} moved an entry or changed its section")
                for (entry in st.after.flat()) {
                    val was = before.entry(entry.item)
                    if (entry.item == item) {
                        assertEquals(was?.copy(ticked = e is Observation.Ticked), entry, "${st.where()} changed more than the ticked mark")
                    } else {
                        assertEquals(was, entry, "${st.where()} changed another entry")
                    }
                }
            }
            if (st.s.tripActive) {
                for (k in st.s.ticked) assertEquals(true, st.after.entry(k)?.ticked, "ticked $k is not shown as ticked ${st.where()}")
            }
        }
    }

    @Test
    fun `O28 a ticked entry stays in its section until the trip ends`() {
        for (v in hardVariants) withCase("O28", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            val olives = h.add("Olives"); val milk = h.add("Milk"); val tea = h.add("Tea")
            h.select(StoreId("a"))
            h.tick(milk); h.tick(tea)
            h.end()
            h.add("Milk"); h.add("Tea")
            h.notHere(olives)
            val before = present(h.history).layout()
            h.tick(olives)
            assertEquals(before, present(h.history).layout(), "ticking an entry in Elsewhere moved it")
            h.tick(tea)
            assertEquals(before, present(h.history).layout(), "ticking an entry in the Run moved it")
            assertTrue(present(h.history).entry(olives)!!.ticked)
        }
    }

    // O29 ---------------------------------------------------------------------------------------

    @Test
    fun `O29 learning waits for the next trip over random histories`() = property("O29", seed = 29) { c ->
        val rnd = c.rnd
        val h = settled(c)
        val list = h.s.list.toList()
        fun inTrip(): Hist {
            val f = h.fork()
            f.tick(list.random(rnd), gap = 30_000)
            repeat(rnd.nextInt(0, 2 * list.size)) {
                val unticked = f.s.list.filter { it !in f.s.ticked }
                if (f.s.ticked.isNotEmpty() && (unticked.isEmpty() || rnd.nextInt(4) == 0)) f.untick(f.s.ticked.random(rnd), tieish(rnd))
                else if (unticked.isNotEmpty()) f.tick(unticked.random(rnd), tieish(rnd))
            }
            return f
        }
        val a = inTrip()
        val b = inTrip()
        val t = maxOf(a.now, b.now)
        a.now = t; b.now = t
        val adds = POOL.filter { key(it) !in list }.shuffled(rnd).take(rnd.nextInt(0, 4))
        for (x in listOf(a, b)) adds.forEach { x.add(it, gap = 3_000) }
        assertEquals(present(a.history).shape(), present(b.history).shape(), "two histories differing only inside the trip in progress are presented differently")
    }

    // O30 ---------------------------------------------------------------------------------------

    @Test
    fun `O30 a mid-trip addition lands where it belongs over random histories`() = property("O30", seed = 30) { c ->
        val rnd = c.rnd
        val h = settled(c, select = false)
        val store = h.s.stores.keys.maxBy { st -> h.s.ended.count { it.store == st } + rnd.nextInt(3) }
        h.selectIfOther(store)
        val off = POOL.filter { key(it) !in h.s.list }
        if (off.isEmpty()) return@property
        val name = off.filter { h.s.placed(key(it), store) }.randomOrNull(rnd) ?: off.random(rnd)
        val x = key(name)
        val ticks = h.s.list.shuffled(rnd).take(rnd.nextInt(1, h.s.list.size + 1)).map { it to tieish(rnd) }

        val mid = h.fork()
        ticks.forEach { (k, gap) -> mid.tick(k, gap) }
        mid.add(name)
        val pre = h.fork()
        pre.add(name)
        ticks.forEach { (k, gap) -> pre.tick(k, gap) }

        val p = present(mid.history)
        val q = present(pre.history)
        assertEquals(q.sectionOf(x), p.sectionOf(x), "added mid-trip, $x is in a different section from where it would be")
        val pk = p.keys()
        val qk = q.keys()
        for (y in qk - x) {
            assertEquals(qk.indexOf(x) < qk.indexOf(y), pk.indexOf(x) < pk.indexOf(y), "added mid-trip, $x is on the other side of $y from where it would be: $pk vs $qk")
        }
    }

    // O31 ---------------------------------------------------------------------------------------

    @Test
    fun `O31 undo and redo count once over random histories`() = property("O31", seed = 31) { c ->
        val rnd = c.rnd
        val base = settled(c)
        val list = base.s.list.toList()
        val finals = list.shuffled(rnd).take(rnd.nextInt(1, list.size + 1))
        val start = base.now + 60_000
        val used = HashSet<Long>()
        var t = start
        val times = finals.map { t += 1 + tieish(rnd); used += t; t }
        val endAt = t + 60_000
        fun freshTime(lo: Long, hi: Long): Long {
            while (true) {
                val x = rnd.nextLong(lo, hi)
                if (used.add(x)) return x
            }
        }
        val extra = mutableListOf<Observation>()
        fun cycles(item: ItemKey, until: Long) {
            val ts = List(2 * rnd.nextInt(1, 3)) { freshTime(start - 30_000, until) }.sorted()
            ts.chunked(2).forEach { (a, b) -> extra += Observation.Ticked(a, item); extra += Observation.Unticked(b, item) }
        }
        finals.forEachIndexed { i, k -> if (rnd.nextInt(3) == 0) cycles(k, times[i]) }
        (list - finals.toSet()).forEach { if (rnd.nextInt(3) == 0) cycles(it, endAt) }

        val clean = finals.indices.map { Observation.Ticked(times[it], finals[it]) }
        val a = base.fork().apply { clean.forEach { emit(it) }; emit(Observation.TripEnded(endAt, EndReason.USER)) }
        val b = base.fork().apply { (clean + extra).sortedBy { it.at }.forEach { emit(it) }; emit(Observation.TripEnded(endAt, EndReason.USER)) }
        assertEquals(present(a.history), present(b.history), "ticks undone and redone taught something")
        for (x in listOf(a, b)) POOL.forEach { if (key(it) !in x.s.list) x.add(it) }
        for (s in a.selections()) {
            assertEquals(present(a.at(s).history), present(b.at(s).history), "ticks undone and redone taught something, seen with $s selected")
        }
    }

    // O32 ---------------------------------------------------------------------------------------

    @Test
    fun `O32 no store, no learning over random histories`() = property("O32", seed = 32) { c ->
        val rnd = c.rnd
        val base = settled(c)
        base.selectIfOther(null)
        val list = base.s.list.toList()
        val a = base.fork()
        a.tick(list.random(rnd), gap = 30_000)
        repeat(rnd.nextInt(0, 2 * list.size)) {
            val unticked = a.s.list.filter { it !in a.s.ticked }
            if (a.s.ticked.isNotEmpty() && (unticked.isEmpty() || rnd.nextInt(5) == 0)) a.untick(a.s.ticked.random(rnd), tieish(rnd))
            else if (unticked.isNotEmpty()) a.tick(unticked.random(rnd), tieish(rnd))
        }
        val got = a.s.ticked.toList()
        val ending = rnd.nextInt(3)
        val back = base.s.stores.keys.random(rnd)
        when (ending) {
            0 -> a.end()
            1 -> a.select(back)
            else -> a.idle()
        }
        // The same entries leaving the list without being ticked.
        val b = base.fork()
        got.forEach { b.remove(it) }
        if (ending == 1) b.select(back)
        b.now = a.now
        for (x in listOf(a, b)) POOL.forEach { if (key(it) !in x.s.list) x.add(it) }
        assertEquals(a.s.list, b.s.list, "both lists must end up the same for the comparison to mean anything")
        for (s in a.selections()) {
            assertEquals(present(b.at(s).history), present(a.at(s).history), "ticks with no store selected changed the presentation with $s selected")
        }
    }

    // O33 ---------------------------------------------------------------------------------------

    @Test
    fun `O33 trip end over random histories`() = property("O33", seed = 33) { c ->
        walk(c.hist.history) { st ->
            if (st.wasTrip && !st.s.tripActive) {
                val before = st.before!!.flat()
                assertEquals(sorted(before.filter { !it.ticked }), sorted(st.after.flat()), "the trip ending ${st.where()} did not leave exactly the unticked entries, intact")
            }
        }
    }

    @Test
    fun `O33 trip end by finishing or selecting another store or none`() {
        for (v in hardVariants) for (way in 0..2) withCase("O33", v, way, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a")); h.store(StoreId("b"))
            val milk = h.add("Milk")
            val eggs = h.add("Eggs", "12", "free range")
            val bread = h.add("Bread", "1", "sliced")
            h.select(StoreId("a"))
            h.tick(milk); h.tick(bread); h.untick(bread)
            val before = present(h.history).flat()
            when (way) {
                0 -> h.end()
                1 -> h.select(StoreId("b"))
                else -> h.select(null)
            }
            val after = present(h.history).flat()
            assertEquals(sorted(before.filter { it.item != milk }), sorted(after), "way $way")
            assertEquals("12" to "free range", after.first { it.item == eggs }.let { it.quantity to it.note })
        }
    }

    @Test
    fun `O33 trip end when the idle timeout passes`() {
        for (v in hardVariants) withCase("O33", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            val milk = h.add("Milk"); val eggs = h.add("Eggs", "6", null)
            h.select(StoreId("a"))
            h.tick(milk)
            assertEquals(h.history, settle(h.history, h.now + Tier3.IDLE_TIMEOUT_MS - 1), "ended before the idle timeout")
            val ended = settle(h.history, h.now + Tier3.IDLE_TIMEOUT_MS + 1)
            assertTrue(ended.drop(h.size).any { it is Observation.TripEnded }, "the idle end is not recorded")
            assertEquals(listOf(eggs), present(ended).keys(), "the idle end did not take the ticked entry off the list")

            // Idleness counts from the trip's last observation, not its first tick.
            val g = Hist(v.prefix())
            g.store(StoreId("a")); g.add("Milk"); g.select(StoreId("a"))
            g.tick(milk)
            g.add("Tea", gap = Tier3.IDLE_TIMEOUT_MS - 60_000)
            assertEquals(g.history, settle(g.history, g.now + 60 * 60_000), "ended within the timeout of the trip's last observation")
            assertTrue(settle(g.history, g.now + Tier3.IDLE_TIMEOUT_MS + 1).drop(g.size).any { it is Observation.TripEnded })
        }
    }

    @Test
    fun `O33 a session with no store selected ends the same way`() {
        for (v in hardVariants) for (way in 0..2) withCase("O33", v, way, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            val milk = h.add("Milk"); val eggs = h.add("Eggs", null, "brown")
            h.tick(milk)
            when (way) {
                0 -> h.end()
                1 -> h.select(StoreId("a"))
                else -> h.idle()
            }
            val after = present(h.history).flat()
            assertEquals(listOf(eggs), after.map { it.item }, "way $way")
            assertEquals("brown", after.single().note)
        }
    }

    // O34 ---------------------------------------------------------------------------------------

    @Test
    fun `O34 chain scope over random histories`() = property("O34", seed = 34) { c ->
        val rnd = c.rnd
        val h = c.hist.fork()
        if (h.s.stores.values.none { it != null }) {
            h.chain(ChainId("cx"))
            h.store(StoreId("x1"), chain = ChainId("cx")); h.store(StoreId("x2"), chain = ChainId("cx"))
        }
        val store = h.s.stores.filterValues { it != null }.keys.random(rnd)
        val chain = h.s.chainOf(store)
        h.selectIfOther(store)
        if (rnd.nextBoolean()) h.s.list.filter { it !in h.s.ticked }.randomOrNull(rnd)?.let { h.tick(it) }
        val item = h.add("Only in this test")
        h.notHere(item, Scope.CHAIN)
        assertEquals(ELSEWHERE, present(h.history).sectionOf(item), "said of the chain at $store, not in Elsewhere there at once")
        for (s in h.s.stores.keys) {
            val inElsewhere = present(h.at(s).history).sectionOf(item) == ELSEWHERE
            assertEquals(h.s.chainOf(s) == chain, inElsewhere, "said of chain $chain at $store; seen at $s (chain ${h.s.chainOf(s)})")
        }
    }

    @Test
    fun `O34 chain scope reaches every store of the chain and no other`() {
        for (v in hardVariants) withCase("O34", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val c = ChainId("c"); val d = ChainId("d")
            h.chain(c); h.chain(d)
            val inChain = listOf("a1", "a2", "a3").map(::StoreId)
            inChain.forEach { h.store(it, chain = c) }
            h.store(StoreId("d1"), chain = d); h.store(StoreId("x"))
            val olives = h.add("Olives"); h.add("Milk")
            h.select(StoreId("a2"))
            h.notHere(olives, Scope.CHAIN)
            fun elsewhereAt(s: StoreId) = present(h.at(s).history).sectionOf(olives) == ELSEWHERE
            for (s in h.s.stores.keys) assertEquals(s in inChain, elsewhereAt(s), "at $s")
            // A purchase is a statement about its own store only.
            h.select(StoreId("a1"))
            h.tick(olives)
            h.end()
            h.add("Olives")
            assertEquals(false, elsewhereAt(StoreId("a1")), "bought at a1")
            assertEquals(true, elsewhereAt(StoreId("a2")), "still said of the chain, at a2")
            assertEquals(true, elsewhereAt(StoreId("a3")), "still said of the chain, at a3")
            assertEquals(false, elsewhereAt(StoreId("d1")))
            assertEquals(false, elsewhereAt(StoreId("x")))
        }
    }

    // O35 ---------------------------------------------------------------------------------------

    @Test
    fun `O35 not-here is reversible over random histories`() = property("O35", seed = 35) { c ->
        val rnd = c.rnd
        val f = c.hist.fork()
        val store = f.s.selected ?: f.s.stores.keys.random(rnd).also { f.select(it) }
        var candidates = f.s.list.filter { it !in f.s.ticked && !f.s.saidNotHere(it, store) }
        if (candidates.isEmpty()) candidates = listOf(f.add("Only in this test"))
        val item = candidates.random(rnd)
        val name = f.s.names.getValue(item)
        val scope = if (f.s.chainOf(store) != null && rnd.nextBoolean()) Scope.CHAIN else Scope.STORE
        val said = f.size
        f.notHere(item, scope, store)
        val gen = Gen(rnd, f)
        gen.protected += item
        gen.run(rnd.nextInt(0, 40))
        if (item !in f.s.list) f.add(name)
        f.selectIfOther(store)
        val undone = f.size
        f.undoNotHere(item, scope, store)
        gen.protected.clear()
        gen.run(rnd.nextInt(0, 30))

        val withIt = Hist(f.history)
        val without = Hist(f.history.filterIndexed { i, _ -> i != said && i != undone })
        without.now = withIt.now
        assertEquals(present(withIt.history), present(without.history), "undoing not-here did not give the presentation as if never said")
        for (s in withIt.selections()) {
            assertEquals(present(withIt.at(s).history), present(without.at(s).history), "undoing not-here did not give the presentation as if never said, with $s selected")
        }
        val kept = Export.decode(Export.encode(withIt.history))
        assertTrue(kept[said] is Observation.NotHere && kept[undone] is Observation.NotHereUndone, "the history did not keep both the not-here and its undoing")
    }

    @Test
    fun `O35 undoing a mid-trip not-here puts the entry back`() {
        for (v in hardVariants) withCase("O35", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(StoreId("a"))
            listOf("Milk", "Bread", "Eggs").forEach { h.tick(key(it)) }
            h.end()
            listOf("Eggs", "Tea", "Milk", "Bread").forEach { h.add(it) }
            h.tick(key("Milk"))
            val before = present(h.history)
            h.notHere(key("Eggs"))
            assertEquals(ELSEWHERE, present(h.history).sectionOf(key("Eggs")), "said mid-trip, it moves at once")
            h.undoNotHere(key("Eggs"))
            assertEquals(before, present(h.history), "undone, it is as if never said")
        }
    }

    // O36 ---------------------------------------------------------------------------------------

    @Test
    fun `O36 items carry over over random histories`() = property("O36", seed = 36) { c ->
        val rnd = c.rnd
        val h = settled(c)
        val stores = h.s.stores.keys
        val item = h.s.list.filter { k -> stores.any { h.s.placed(k, it) } }.randomOrNull(rnd) ?: h.s.list.random(rnd)
        val name = h.s.names.getValue(item)
        val before = h.selections().associateWith { present(h.at(it).history) }
        val again = h.fork()
        again.remove(item)
        again.add(spelling(rnd, name))
        for ((s, p) in before) {
            val q = present(again.at(s).history)
            assertEquals(p.sectionOf(item), q.sectionOf(item), "removed and added again, $item changed section with $s selected")
            if (p.sectionOf(item) == RUN) {
                val runP = p.inSection(RUN).map { it.item }
                val runQ = q.inSection(RUN).map { it.item }
                for (y in runP - item) {
                    assertEquals(runP.indexOf(item) < runP.indexOf(y), runQ.indexOf(item) < runQ.indexOf(y), "removed and added again, $item moved across $y at $s")
                }
            }
        }
    }

    @Test
    fun `O36 a matching name carries the item's placement`() {
        for (v in hardVariants) withCase("O36", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(StoreId("a"))
            listOf("Bread", "Milk", "Eggs").forEach { h.tick(key(it)) }
            h.end()
            listOf("eggs ", "  MILK", "bread").forEach { h.add(it) }
            val placed = present(h.history).layout()
            assertEquals(listOf(RUN), placed.map { it.first }, "re-added under other spellings, they are still placed")
            h.remove(key("Milk"))
            h.add("milk")
            assertEquals(placed, present(h.history).layout(), "removed and added again")
        }
    }

    // O37 ---------------------------------------------------------------------------------------

    @Test
    fun `O37 details move nothing over random histories`() = property("O37", seed = 37) { c ->
        val h = c.hist.history
        val edits = mutableListOf<Int>()
        walk(h) { st ->
            val e = st.event
            if (e is Observation.Edited && e.name == st.wasNames[e.item]) {
                assertEquals(st.before!!.layout(), st.after.layout(), "editing details ${st.where()} moved something")
                edits += st.k
            }
        }
        for (k in edits.shuffled(c.rnd).take(3)) {
            val without = Hist(h.subList(0, k - 1))
            val with = Hist(h.subList(0, k))
            without.now = with.now
            for (s in with.selections()) {
                assertEquals(present(without.at(s).history).layout(), present(with.at(s).history).layout(), "editing details at #${k - 1} moved something with $s selected")
            }
        }
    }

    // O38 ---------------------------------------------------------------------------------------

    @Test
    fun `O38 renaming moves nothing over random histories`() = property("O38", seed = 38) { c ->
        walk(c.hist.history) { st ->
            val e = st.event
            if (e is Observation.StoreRenamed || e is Observation.ChainRenamed) {
                assertEquals(st.before, st.after, "${st.where()} changed the presentation")
            }
        }
        val rnd = c.rnd
        val h = c.hist.fork()
        val target = h.s.stores.keys.random(rnd)
        val oldName = h.s.storeNames.getValue(target)
        val renamed = h.fork()
        renamed.emit(Observation.StoreRenamed(renamed.next(1_000), target, "$oldName (old)"))
        h.s.chainOf(target)?.let { renamed.emit(Observation.ChainRenamed(renamed.next(1_000), it, "Renamed chain")) }
        h.now = renamed.now
        for (s in h.selections()) {
            assertEquals(present(h.at(s).history), present(renamed.at(s).history), "renaming $target changed the presentation with $s selected")
        }
        renamed.store(StoreId("new"), oldName)
        renamed.select(StoreId("new"))
        val p = present(renamed.history)
        assertEquals(renamed.s.list, p.keys(), "a new store under the old name is not in paper order")
        assertTrue(p.layout().all { it.first == UNPLACED }, "a new store under the old name has learned something: ${p.layout()}")
    }

    @Test
    fun `O38 renaming a store keeps its route, and its old name starts cold`() {
        for (v in hardVariants) withCase("O38", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.chain(ChainId("c"), "Chain")
            h.store(StoreId("a"), "Corner", ChainId("c"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(StoreId("a"))
            listOf("Eggs", "Bread", "Milk").forEach { h.tick(key(it)) }
            h.end()
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            val before = present(h.history)
            h.emit(Observation.StoreRenamed(h.next(1_000), StoreId("a"), "Old corner"))
            h.emit(Observation.ChainRenamed(h.next(1_000), ChainId("c"), "Renamed"))
            assertEquals(before, present(h.history), "renaming moved something")
            h.store(StoreId("b"), "Corner")
            h.select(StoreId("b"))
            assertEquals(listOf(UNPLACED to listOf("Milk", "Bread", "Eggs").map(::key)), present(h.history).layout(), "a new store under the old name starts cold")
        }
    }
}
