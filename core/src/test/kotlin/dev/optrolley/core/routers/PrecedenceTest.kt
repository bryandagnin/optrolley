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

class PrecedenceTest {
    private fun trip(vararg groups: List<String>) = TripOrder(groups.map { g -> g.map(::key).toSet() })

    /** A trip ticking [order] one at a time. */
    private fun walk(order: List<ItemKey>) = TripOrder(order.map { setOf(it) })

    private fun names(n: Int) = (0 until n).map { key("i%03d".format(it)) }

    private fun before(order: List<ItemKey>, a: ItemKey, b: ItemKey) = order.indexOf(a) < order.indexOf(b)

    @Test
    fun isRegisteredUnderItsId() {
        assertEquals(Precedence, Routers.all.byId("precedence"))
        assertEquals("Precedence", Precedence.label)
    }

    @Test
    fun returnsEveryItemAskedForExactlyOnce() {
        val trips = listOf(trip(listOf("a"), listOf("b"), listOf("c")), trip(listOf("c"), listOf("x", "a")))
        val items = keys("c", "a", "never", "b").toSet()
        val out = Precedence.order(trips, items)
        assertEquals(items.size, out.size)
        assertEquals(items, out.toSet())
        assertEquals(emptyList(), Precedence.order(trips, emptySet()))
        assertEquals(keys("z", "y"), Precedence.order(emptyList(), keys("z", "y").toSet()).reversed())
    }

    @Test
    fun orderHoldsThroughItemsNotAskedFor() {
        val trips = listOf(trip(listOf("c"), listOf("b")), trip(listOf("b"), listOf("a")))
        assertEquals(keys("c", "a"), Precedence.order(trips, keys("a", "c").toSet()))
    }

    // O13
    @Test
    fun oneTripIsReplayedExactly() {
        val random = Random(13)
        repeat(200) {
            val order = names(random.nextInt(2, 40)).shuffled(random)
            assertEquals(order, Precedence.order(listOf(walk(order)), order.shuffled(random).toSet()))
        }
    }

    // O14: trips drawn from a hidden total order, each a random sublist with random ties.
    @Test
    fun consistentHistoryIsHonoured() {
        val random = Random(14)
        repeat(150) {
            val hidden = names(random.nextInt(3, 30)).shuffled(random)
            val rank = hidden.withIndex().associate { (i, k) -> k to i }
            val trips = List(random.nextInt(1, 12)) {
                val sub = hidden.filter { random.nextDouble() < 0.6 }
                val groups = ArrayList<MutableSet<ItemKey>>()
                for (item in sub) {
                    if (groups.isEmpty() || random.nextDouble() < 0.7) groups += LinkedHashSet<ItemKey>()
                    groups.last() += item
                }
                TripOrder(groups)
            }
            // a must precede b in every agreeing total order iff a path a -> b exists in the trips' precedence.
            val succ = HashMap<ItemKey, MutableSet<ItemKey>>()
            for (t in trips) for (i in t.groups.indices) for (j in i + 1 until t.groups.size) {
                for (a in t.groups[i]) succ.getOrPut(a, ::HashSet).addAll(t.groups[j])
            }
            fun reach(a: ItemKey): Set<ItemKey> {
                val seen = HashSet<ItemKey>()
                val stack = ArrayDeque(succ[a].orEmpty())
                while (stack.isNotEmpty()) {
                    val x = stack.removeLast()
                    if (seen.add(x)) stack.addAll(succ[x].orEmpty())
                }
                return seen
            }
            val out = Precedence.order(trips, hidden.toSet())
            for (a in hidden) for (b in reach(a)) {
                assertTrue(before(out, a, b), "${a.value} before ${b.value} in $out")
                assertTrue(rank.getValue(a) < rank.getValue(b))
            }
        }
    }

    // O15
    @Test
    fun permutingWithinTieGroupsChangesNothing() {
        val random = Random(15)
        repeat(100) {
            val pool = names(25)
            val trips = List(random.nextInt(1, 15)) {
                val sub = pool.shuffled(random).take(random.nextInt(2, 25))
                TripOrder(sub.chunked(random.nextInt(1, 4)).map { it.toSet() })
            }
            val permuted = trips.map { t -> TripOrder(t.groups.map { g -> g.shuffled(random).toCollection(LinkedHashSet()) }) }
            val items = pool.toSet()
            assertEquals(Precedence.order(trips, items), Precedence.order(permuted, items.shuffled(random).toSet()))
        }
    }

    // O17: whatever the older history (up to 100 trips), K = 3 trips of a new order flip a pair.
    @Test
    fun aRearrangementIsAbsorbedAfterThreeTrips() {
        val random = Random(17)
        repeat(150) {
            val pool = names(random.nextInt(3, 25))
            val old = pool.shuffled(random)
            val new = pool.shuffled(random)
            val (a, b) = new.zipWithNext().firstOrNull { (x, y) -> before(old, y, x) } ?: return@repeat
            fun sub(order: List<ItemKey>, keep: Set<ItemKey>) =
                walk(order.filter { it in keep || random.nextDouble() < 0.5 })
            val older = List(random.nextInt(0, 101)) { sub(old, emptySet()) }
            val newer = List(3) { sub(new, setOf(a, b)) }
            val out = Precedence.order(older + newer, pool.toSet())
            assertTrue(before(out, a, b), "${a.value} before ${b.value} after ${older.size} older trips")
        }
    }

    @Test
    fun aRearrangementOverridesOlderSettledPairsThatContradictIt() {
        // b < x < a for 100 trips, then a < b three times with x absent: x must give way.
        val old = List(100) { trip(listOf("b"), listOf("x"), listOf("a")) }
        val new = List(3) { trip(listOf("a"), listOf("b")) }
        val out = Precedence.order(old + new, keys("a", "b", "x").toSet())
        assertTrue(before(out, key("a"), key("b")), "$out")
    }

    // H6: a walk from the other door is read as its mirror, not as a rearrangement.
    @Test
    fun aReversedWalkIsReadAsItsMirror() {
        val order = names(10)
        val trips = List(5) { walk(order) } + walk(order.reversed())
        assertEquals(order, Precedence.order(trips, order.toSet()))
        val partial = walk(order.filterIndexed { i, _ -> i % 2 == 0 }.reversed())
        assertEquals(order, Precedence.order(trips + partial, order.toSet()))
    }

    @Test
    fun theOrientationShownIsTheOneMostTripsWereWalkedIn() {
        val order = names(10)
        val trips = listOf(walk(order.reversed()), walk(order), walk(order.drop(2)), walk(order.take(7)))
        assertEquals(order, Precedence.order(trips, order.toSet()))
    }

    // O17 where the new order is the old one reversed, so each new trip also looks like a mirrored walk.
    @Test
    fun aReversalSeenThreeTimesIsAbsorbed() {
        val random = Random(18)
        repeat(100) {
            val old = names(random.nextInt(2, 20)).shuffled(random)
            val (b, a) = old.shuffled(random).take(2).let { (x, y) -> if (before(old, x, y)) x to y else y to x }
            fun sub(order: List<ItemKey>) = walk(order.filter { it == a || it == b || random.nextDouble() < 0.5 })
            val older = List(random.nextInt(0, 101)) { sub(old) }
            val out = Precedence.order(older + List(3) { sub(old.reversed()) }, old.toSet())
            assertTrue(before(out, a, b), "${a.value} before ${b.value} after ${older.size} older trips")
        }
    }

    @Test
    fun isDeterministic() {
        val random = Random(19)
        val pool = names(40)
        val trips = List(30) { TripOrder(pool.shuffled(random).take(20).chunked(2).map { it.toSet() }) }
        val first = Precedence.order(trips, pool.toSet())
        repeat(5) { assertEquals(first, Precedence.order(trips.toList(), pool.shuffled(random).toCollection(HashSet()))) }
    }

    @Test
    fun isFastEnoughForEveryPresentation() {
        val random = Random(20)
        val pool = names(300)
        val trips = List(100) { walk(pool.shuffled(random).take(30)) }
        repeat(20) { Precedence.order(trips, pool.toSet()) } // warm-up
        val runs = 50
        val start = System.nanoTime()
        repeat(runs) { Precedence.order(trips, pool.toSet()) }
        val ms = (System.nanoTime() - start) / 1e6 / runs
        println("precedence: $ms ms per order of 300 items over 100 trips")
        assertTrue(ms < 50, "$ms ms")
    }

    // Through the presentation, with the history choosing this router.
    @Test
    fun presentsThroughTheChosenRouter() {
        val h = Hist().choose(Precedence.id).store(A)
        h.trip(A, "c", "a", "b", "d")
        h.add("d", "b", "new", "a", "c")
        h.select(A)
        assertEquals(keys("c", "a", "b", "d"), present(h.list).section(SectionKind.RUN), "O13")
        assertEquals(keys("new"), present(h.list).section(SectionKind.UNPLACED))
    }

    @Test
    fun presentAbsorbsARearrangementAfterThreeTrips() {
        val h = Hist().choose(Precedence.id).store(A)
        repeat(20) { h.trip(A, "b", "x", "a") }
        repeat(2) { h.trip(A, "a", "b") }
        h.add("x", "a", "b").select(A)
        assertEquals(keys("b", "x", "a"), present(h.list).section(SectionKind.RUN), "two trips are not yet enough")
        h.end().trip(A, "a", "b")
        h.add("x", "a", "b").select(A)
        val run = present(h.list).section(SectionKind.RUN)
        assertTrue(before(run, key("a"), key("b")), "$run")
        assertEquals(present(h.list), present(h.list.toList()), "O19")
    }

    @Test
    fun presentIgnoresPermutationsWithinTies() {
        fun history(firstTie: List<String>): List<Observation> {
            val h = Hist().choose(Precedence.id).store(A).select(A).add("a", "b", "c", "d")
            h.tick("a")
            firstTie.forEach { h.tick(it, 1_000) }
            h.tick("d").end()
            return h.add("d", "c", "b", "a").select(A).list
        }
        assertEquals(present(history(listOf("b", "c"))), present(history(listOf("c", "b"))))
    }
}
