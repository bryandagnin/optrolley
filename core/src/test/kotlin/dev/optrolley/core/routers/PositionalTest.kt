package dev.optrolley.core.routers

import dev.optrolley.core.A
import dev.optrolley.core.Hist
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Routers
import dev.optrolley.core.SectionKind
import dev.optrolley.core.TripOrder
import dev.optrolley.core.key
import dev.optrolley.core.keys
import dev.optrolley.core.present
import dev.optrolley.core.section
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PositionalTest {
    private fun names(n: Int) = (0 until n).map { key("i%03d".format(it)) }

    private fun walk(order: List<ItemKey>) = TripOrder(order.map { setOf(it) })

    private fun before(order: List<ItemKey>, a: ItemKey, b: ItemKey) = order.indexOf(a) < order.indexOf(b)

    /** [order] cut into tie groups at random, each group a set in a random iteration order. */
    private fun grouped(order: List<ItemKey>, rnd: Random, tieChance: Double = 0.3): TripOrder {
        val groups = ArrayList<MutableList<ItemKey>>()
        for (item in order) {
            if (groups.isEmpty() || rnd.nextDouble() >= tieChance) groups += mutableListOf<ItemKey>()
            groups.last() += item
        }
        return TripOrder(groups.map { LinkedHashSet(it.shuffled(rnd)) })
    }

    /** A random subset of [order], at least two items, kept in that order. */
    private fun subset(order: List<ItemKey>, rnd: Random): List<ItemKey> {
        while (true) {
            val s = order.filter { rnd.nextDouble() < 0.6 }
            if (s.size >= 2) return s
        }
    }

    /** Pairs every total order agreeing with [trips] puts first-before-second. */
    private fun forced(trips: List<TripOrder>): Set<Pair<ItemKey, ItemKey>> {
        val succ = HashMap<ItemKey, MutableSet<ItemKey>>()
        for (t in trips) for (i in 1 until t.groups.size) for (a in t.groups[i - 1]) for (b in t.groups[i]) {
            succ.getOrPut(a, ::HashSet) += b
        }
        val out = HashSet<Pair<ItemKey, ItemKey>>()
        for (a in succ.keys) {
            val stack = ArrayDeque(succ.getValue(a))
            val seen = HashSet<ItemKey>()
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                if (seen.add(x)) {
                    out += a to x
                    succ[x]?.let { stack.addAll(it) }
                }
            }
        }
        return out
    }

    private fun placed(trips: List<TripOrder>) = trips.filter { it.groups.size > 1 }.flatMap { t -> t.groups.flatten() }.toSet()

    @Test
    fun isRegisteredUnderItsId() {
        assertEquals(Positional, Routers.all.byId("positional"))
        assertEquals("Positional", Positional.label)
    }

    @Test
    fun returnsEveryItemAskedForExactlyOnce() {
        val trips = listOf(walk(keys("a", "b", "c")), TripOrder(listOf(keys("c").toSet(), keys("x", "a").toSet())))
        val items = keys("c", "a", "never", "b").toSet()
        val out = Positional.order(trips, items)
        assertEquals(items.size, out.size)
        assertEquals(items, out.toSet())
        assertEquals(emptyList(), Positional.order(trips, emptySet()))
        assertEquals(keys("z", "y").sortedBy { it.value }, Positional.order(emptyList(), keys("z", "y").toSet()))
    }

    @Test
    fun o13OneTripIsReplayedExactly() {
        val rnd = Random(13)
        repeat(200) {
            val order = names(30).shuffled(rnd).take(rnd.nextInt(2, 31))
            assertEquals(order, Positional.order(listOf(walk(order)), order.shuffled(rnd).toSet()))
        }
    }

    @Test
    fun o13ThroughThePresentation() {
        val h = Hist().choose(Positional.id).store(A).trip(A, "milk", "eggs", "bread", "tea")
        h.add("tea", "bread", "milk", "eggs").select(A)
        assertEquals(keys("milk", "eggs", "bread", "tea"), present(h.list).section(SectionKind.RUN))
    }

    @Test
    fun o14ConsistentHistoryIsHonoured() {
        val rnd = Random(14)
        repeat(1_000) {
            // Short lists included: that is where a mean of positions alone goes wrong.
            val hidden = names(rnd.nextInt(2, 13)).shuffled(rnd)
            val trips = List(rnd.nextInt(1, 9)) { grouped(subset(hidden, rnd), rnd) }
            val items = hidden.toSet()
            val out = Positional.order(trips, items)
            for ((a, b) in forced(trips)) assertTrue(before(out, a, b), "$a before $b in $out from $trips")
        }
    }

    @Test
    fun o14OnTheSmallestCase() {
        // Means alone put b (1 and 0: 1/2) before a (3/4), against the trip that saw them both.
        val trips = listOf(walk(keys("w", "x", "y", "a", "b")), walk(keys("b", "c")))
        assertEquals(keys("w", "x", "y", "a", "b", "c"), Positional.order(trips, keys("a", "b", "c", "w", "x", "y").toSet()))
    }

    @Test
    fun o14ThroughThePresentation() {
        val rnd = Random(1414)
        repeat(100) {
            val hidden = (0 until rnd.nextInt(2, 9)).map { "item$it" }.shuffled(rnd)
            val h = Hist().choose(Positional.id).store(A)
            val trips = ArrayList<List<String>>()
            repeat(rnd.nextInt(1, 6)) {
                val s = hidden.filter { rnd.nextDouble() < 0.7 }.takeIf { it.size >= 2 } ?: hidden
                trips += s
                h.trip(A, *s.toTypedArray())
            }
            h.add(*hidden.toTypedArray()).select(A)
            val shown = present(h.list).section(SectionKind.RUN)
            val forced = forced(trips.map { t -> walk(t.map(::key)) })
            for ((a, b) in forced) assertTrue(before(shown, a, b), "$a before $b in $shown")
        }
    }

    @Test
    fun o15PermutingWithinTiesChangesNothing() {
        val rnd = Random(15)
        repeat(300) {
            val pool = names(20)
            val trips = List(rnd.nextInt(1, 12)) {
                val order = pool.shuffled(rnd).take(rnd.nextInt(2, 20))
                grouped(order, rnd, tieChance = 0.4)
            }
            val items = placed(trips) + key("unseen")
            val permuted = trips.map { t -> TripOrder(t.groups.map { g -> LinkedHashSet(g.shuffled(rnd)) }) }
            assertEquals(Positional.order(trips, items), Positional.order(permuted, items))
        }
    }

    @Test
    fun o15ThroughThePresentation() {
        fun history(tied: List<String>): List<Observation> {
            val h = Hist().choose(Positional.id).store(A).select(A).add("a", "b", "c", "d", "e")
            h.tick("a")
            tied.forEachIndexed { i, n -> h.tick(n, if (i == 0) Hist.GAP else 1_000) }
            h.tick("e").end()
            return h.add("e", "d", "c", "b", "a").select(A).list
        }
        val shown = present(history(listOf("b", "c", "d")))
        for (order in listOf(listOf("c", "d", "b"), listOf("d", "b", "c"), listOf("b", "d", "c"))) {
            assertEquals(shown, present(history(order)))
        }
    }

    @Test
    fun o17ThreeTripsInANewOrderWinEveryPairTheyHold() {
        val rnd = Random(17)
        for (old in listOf(0, 1, 2, 5, 20, 60, 100)) repeat(40) {
            val pool = names(rnd.nextInt(3, 31))
            val was = pool.shuffled(rnd)
            val now = pool.shuffled(rnd)
            val trips = List(old) { grouped(subset(was, rnd), rnd) } + List(3) { walk(subset(now, rnd)) }
            val out = Positional.order(trips, placed(trips))
            val recent = trips.takeLast(3).map { t -> t.groups.flatten() }
            val common = recent[0].filter { i -> recent.all { i in it } }
            for (a in common) for (b in common) {
                if (now.indexOf(a) < now.indexOf(b)) assertTrue(before(out, a, b), "old=$old: $a before $b")
            }
        }
    }

    @Test
    fun o17ThroughThePresentation() {
        val h = Hist().choose(Positional.id).store(A)
        repeat(100) { h.trip(A, "a", "b", "c", "d") }
        repeat(3) { h.trip(A, "d", "b", "a") }
        h.add("a", "b", "c", "d").select(A)
        val shown = present(h.list).section(SectionKind.RUN)
        assertTrue(before(shown, key("d"), key("b")) && before(shown, key("b"), key("a")), "$shown")
    }

    @Test
    fun aReversedWalkTeachesTheSameOrder() {
        val order = names(12)
        val trips = listOf(walk(order), walk(order.drop(2)), walk(order.reversed()), walk(order.filterIndexed { i, _ -> i % 3 != 0 }))
        assertEquals(order, Positional.order(trips, order.toSet()))
    }

    @Test
    fun aRearrangedShopIsLearnedFromTheTripsSince() {
        // Two trips are short of O17's three, so this is the mean restarting.
        val was = names(12)
        val now = was.subList(0, 3) + was.subList(6, 12) + was.subList(3, 6)
        val trips = List(15) { walk(was) } + List(2) { walk(now) }
        assertEquals(now, Positional.order(trips, was.toSet()))
    }

    @Test
    fun deterministic() {
        val rnd = Random(19)
        repeat(100) {
            val pool = names(25)
            val trips = List(rnd.nextInt(0, 30)) { grouped(pool.shuffled(rnd).take(rnd.nextInt(1, 25)), rnd) }
            val items = placed(trips) + pool.take(3)
            val first = Positional.order(trips, items)
            assertEquals(first, Positional.order(trips.toList(), LinkedHashSet(items.shuffled(rnd))))
            assertEquals(first, Positional.order(trips, HashSet(items)))
        }
    }

    @Test
    fun fastEnoughForEveryPresentation() {
        val rnd = Random(5)
        val pool = names(300)
        val trips = List(100) { grouped(pool.shuffled(rnd).take(30), rnd, tieChance = 0.1) }
        val items = placed(trips)
        repeat(20) { Positional.order(trips, items) }
        val runs = 50
        val t0 = System.nanoTime()
        repeat(runs) { Positional.order(trips, items) }
        val ms = (System.nanoTime() - t0) / 1e6 / runs
        println("positional: ${"%.2f".format(ms)} ms per order, 100 trips of 30 over ${items.size} items")
        assertTrue(ms < 50, "$ms ms")
    }
}
