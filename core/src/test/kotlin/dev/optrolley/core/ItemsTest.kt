package dev.optrolley.core

import dev.optrolley.core.Observation.Added
import dev.optrolley.core.Observation.CodeLinked
import dev.optrolley.core.Observation.CodeUnlinked
import dev.optrolley.core.Observation.Edited
import dev.optrolley.core.Observation.ItemRenamed
import dev.optrolley.core.Observation.NotHere
import dev.optrolley.core.Observation.Removed
import dev.optrolley.core.Observation.Selected
import dev.optrolley.core.Observation.StoreCreated
import dev.optrolley.core.Observation.Ticked
import dev.optrolley.core.Observation.TripEnded
import dev.optrolley.core.Observation.Unticked
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Items as things with a name (tier 4 §4.1), and the predicates P1, P4 and P5. */
class ItemsTest {
    private val shop = StoreId("shop")
    private fun key(name: String) = ItemKey.of(name)

    /** Minutes apart, so no two ticks tie. */
    private class Actions {
        val list = ArrayList<Observation>()
        private var at = 0L
        fun next(): Long {
            at += 60_000
            return at
        }

        operator fun plusAssign(make: (Long) -> Observation) {
            list += make(next())
        }
    }

    /** A trip at [shop] ticking [names] in order, each added first. */
    private fun Actions.walk(vararg names: String) {
        for (n in names) this += { Added(it, n) }
        for (n in names) {
            val item = replay(list).itemNamed(n)!!
            this += { Ticked(it, item) }
        }
        this += { TripEnded(it, EndReason.USER) }
    }

    private fun shopping() = Actions().apply {
        this += { StoreCreated(it, shop, "Shop") }
        this += { Selected(it, shop) }
    }

    private fun run(list: List<Observation>) =
        present(list).sections.firstOrNull { it.kind == SectionKind.RUN }?.entries.orEmpty().map { it.name }

    @Test
    fun `a renamed item keeps its id, its place and its codes`() {
        val code = Barcode("4006381333931")
        val a = shopping()
        a.walk("bread", "mlik", "eggs")
        a += { CodeLinked(it, code, "mlik") }
        a += { ItemRenamed(it, key("mlik"), "Milk") }
        for (n in listOf("eggs", "Milk", "bread")) a += { Added(it, n) }

        val state = replay(a.list)
        assertEquals("Milk", state.items[key("mlik")])
        assertEquals(key("mlik"), state.itemNamed("milk"))
        assertNull(state.itemNamed("mlik"))
        assertEquals("Milk", state.nameFor(code))
        assertEquals(listOf("bread", "Milk", "eggs"), run(a.list), "walked second as mlik, so second as Milk")
    }

    @Test
    fun `the old name typed again is a new item with nothing learned`() {
        val a = shopping()
        a.walk("bread", "milk", "eggs")
        a += { ItemRenamed(it, key("milk"), "Whole milk") }
        a += { Added(it, "milk") }
        a += { Added(it, "bread") }

        val state = replay(a.list)
        val fresh = state.itemNamed("milk")!!
        assertNotEquals(key("milk"), fresh)
        assertEquals("Whole milk", state.items[key("milk")])
        val sections = present(a.list).sections.associate { it.kind to it.entries.map { e -> e.item } }
        assertEquals(listOf(fresh), sections[SectionKind.UNPLACED])
        assertEquals(listOf(key("bread")), sections[SectionKind.RUN])
    }

    @Test
    fun `joined items share what was walked, said and linked of either`() {
        val (trim, lite) = Barcode("11111111") to Barcode("22222222")
        val other = StoreId("other")
        val a = shopping()
        a += { StoreCreated(it, other, "Other") }
        a.walk("bread", "trim milk", "eggs")
        a.walk("bread", "eggs", "lite milk")
        a.walk("bread", "eggs", "lite milk")
        a += { CodeLinked(it, trim, "trim milk") }
        a += { CodeLinked(it, lite, "lite milk") }
        a += { Added(it, "trim milk") }
        a += { NotHere(it, key("trim milk"), other, Scope.STORE) }
        a += { ItemRenamed(it, key("trim milk"), "Lite milk") }
        a += { Added(it, "eggs") }
        a += { Added(it, "bread") }

        val state = replay(a.list)
        assertNull(state.items[key("trim milk")])
        assertEquals("Lite milk", state.items[key("lite milk")])
        assertEquals(key("lite milk"), state.codes[trim])
        assertEquals(key("lite milk"), state.codes[lite])
        assertEquals("Lite milk", state.nameFor(trim))
        assertEquals(listOf(key("lite milk"), key("eggs"), key("bread")), state.entries.map { it.item })
        // Walked last twice and second once: the trips of both count for the one.
        assertEquals(listOf("bread", "eggs", "Lite milk"), run(a.list))
        assertEquals(3, state.trips.getValue(shop).count { t -> t.groups.any { key("lite milk") in it } })
        val there = present(a.list + Selected(a.next(), other)).sections.associate { it.kind to it.entries.map { e -> e.name } }
        assertEquals(listOf("Lite milk"), there[SectionKind.ELSEWHERE], "said not there of the one that was joined in")
    }

    @Test
    fun `two entries joined stay ticked only if both were`() {
        fun joined(vararg ticked: String): State {
            val a = shopping()
            a += { Added(it, "bread") }
            a += { Added(it, "milc") }
            a += { Added(it, "milk") }
            for (n in ticked) a += { Ticked(it, key(n)) }
            a += { ItemRenamed(it, key("milk"), "milc") }
            return replay(a.list)
        }
        assertEquals(listOf(Entry(key("bread"), "bread", null, null, false), Entry(key("milc"), "milc", null, null, false)), joined("milk").entries)
        assertTrue(joined("milk", "milc").entries.single { it.item == key("milc") }.ticked)
    }

    @Test
    fun `a trip that ticked both of two items later joined counts the later tick`() {
        val a = shopping()
        a.walk("skim milk", "bread", "eggs", "trim milk")
        a += { ItemRenamed(it, key("skim milk"), "trim milk") }
        assertEquals(TripOrder(listOf(setOf(key("bread")), setOf(key("eggs")), setOf(key("trim milk")))), replay(a.list).trips.getValue(shop).single())
    }

    @Test
    fun `an unlinked code is as one never linked`() {
        val (one, two) = Barcode("11111111") to Barcode("22222222")
        val a = Actions()
        a += { CodeLinked(it, one, "sugar") }
        a += { CodeLinked(it, two, "sugar") }
        a += { CodeUnlinked(it, one) }
        val state = replay(a.list)
        assertNull(state.nameFor(one))
        assertEquals("sugar", state.nameFor(two))
        assertEquals(mapOf(two to key("sugar")), state.codes)
        assertEquals("sugar", state.items[key("sugar")], "the item it meant is as it was")
    }

    @Test
    fun `an edit to a new name still moves the entry to that name's item, as the first build did`() {
        val a = shopping()
        a.walk("bread", "milk", "eggs")
        a += { Added(it, "bred") }
        a += { Edited(it, key("bred"), "Bread", null, null) }
        val state = replay(a.list)
        assertEquals(listOf(key("bread")), state.entries.map { it.item })
        assertEquals("bred", state.items[key("bred")], "the item it was for is left as it was")
    }

    private val pool = listOf("milk", "bread", "eggs", "tea", "jam", "Whole Milk", "rye", "oats")
    private val codes = List(4) { Barcode("0000000$it") }

    private fun randomActions(rnd: Random, n: Int): List<Observation> {
        val a = shopping()
        repeat(n) {
            val state = replay(a.list)
            val item = state.items.keys.randomOrNull(rnd)
            when (rnd.nextInt(12)) {
                0, 1, 2 -> a += { Added(it, pool.random(rnd)) }
                3, 4 -> item?.let { i -> a += { Ticked(it, i) } }
                5 -> item?.let { i -> a += { Unticked(it, i) } }
                6 -> item?.let { i -> a += { Removed(it, i) } }
                7 -> a += { TripEnded(it, EndReason.USER) }
                8 -> a += { CodeLinked(it, codes.random(rnd), pool.random(rnd)) }
                9 -> a += { CodeUnlinked(it, codes.random(rnd)) }
                10 -> item?.let { i -> a += { ItemRenamed(it, i, pool.random(rnd) + if (rnd.nextBoolean()) "" else " x") } }
                else -> item?.let { i -> a += { NotHere(it, i, shop, Scope.STORE) } }
            }
        }
        return a.list
    }

    @Test
    fun `P1 one action at a time is the fold of them all`() {
        repeat(40) { seed ->
            val actions = randomActions(Random(seed), 60)
            val fold = Fold()
            for ((i, action) in actions.withIndex()) {
                fold.apply(action)
                if (i % 7 == 0 || i == actions.lastIndex) {
                    assertEquals(replay(actions.subList(0, i + 1)), fold.state(), "seed $seed after ${i + 1} actions")
                    assertEquals(present(actions.subList(0, i + 1)), present(fold), "seed $seed after ${i + 1} actions")
                }
            }
            assertEquals(actions.size, fold.count)
        }
    }

    @Test
    fun `P4 and P5 nothing names a joined item, and no item's id changes`() {
        repeat(40) { seed ->
            val actions = randomActions(Random(1_000 + seed), 80)
            val fold = Fold()
            var before = fold.state()
            for (action in actions) {
                fold.apply(action)
                val state = fold.state()
                val live = state.items.keys
                assertTrue(state.entries.all { it.item in live }, "seed $seed: an entry names no item after $action")
                assertTrue(state.codes.values.all { it in live }, "seed $seed: a code names no item after $action")
                assertTrue(state.known.keys.all { it in live }, "seed $seed: a known name is of no item after $action")
                assertTrue(state.trips.values.flatten().all { t -> t.groups.all { g -> g.all { it in live } } }, "seed $seed: a trip names no item after $action")
                assertEquals(state.items.size, state.items.values.map { ItemKey.of(it) }.toSet().size, "seed $seed: two items share a name after $action")
                // An item goes only by being joined into another; none comes to stand for a different thing.
                if (action !is ItemRenamed) assertTrue(live.containsAll(before.items.keys), "seed $seed: an item vanished at $action")
                before = state
            }
        }
    }
}
