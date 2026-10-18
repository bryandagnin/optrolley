package dev.optrolley.core.routers

import dev.optrolley.core.A
import dev.optrolley.core.Hist
import dev.optrolley.core.ItemKey
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

class PairwiseTest {
    private val names = (0 until 300).map { "item-%03d".format(it) }

    private fun trip(vararg groups: List<String>) = TripOrder(groups.map { g -> g.map(::key).toLinkedSet() })

    private fun List<ItemKey>.toLinkedSet(): Set<ItemKey> = LinkedHashSet(this)

    /** [items] walked in that order, cut into tie groups at random. */
    private fun walked(items: List<ItemKey>, random: Random, tieChance: Double): TripOrder {
        val groups = ArrayList<MutableList<ItemKey>>()
        for ((i, item) in items.withIndex()) {
            if (i == 0 || random.nextDouble() >= tieChance) groups += mutableListOf<ItemKey>()
            groups.last() += item
        }
        return TripOrder(groups.map { it.toLinkedSet() })
    }

    /** A random sublist of [order], in [order]'s order, of at least [min] items. */
    private fun sub(order: List<ItemKey>, random: Random, size: Int, must: Set<ItemKey> = emptySet()): List<ItemKey> {
        val chosen = (order.shuffled(random).take(size) + must).toSet()
        return order.filter { it in chosen }
    }

    private fun placed(trips: List<TripOrder>): Set<ItemKey> =
        trips.filter { it.groups.size > 1 }.flatMap { t -> t.groups.flatten() }.toSet()

    private fun assertPermutation(items: Set<ItemKey>, out: List<ItemKey>) {
        assertEquals(items.size, out.size, "returns each item once")
        assertEquals(items, out.toSet())
    }

    @Test
    fun `O13 one trip without ties is replayed exactly`() {
        val random = Random(13)
        repeat(300) {
            val size = random.nextInt(2, 41)
            val order = names.shuffled(random).take(size).map(::key)
            val trips = listOf(TripOrder(order.map { setOf(it) }))
            assertEquals(order, Pairwise.order(trips, order.toSet()))
            // Asking for some of them keeps their order.
            val some = order.filter { random.nextBoolean() }.toSet()
            assertEquals(order.filter { it in some }, Pairwise.order(trips, some))
        }
    }

    @Test
    fun `O14 a consistent history is honoured, transitively`() {
        val random = Random(14)
        repeat(1500) { run ->
            val n = if (run % 3 == 0) random.nextInt(2, 6) else random.nextInt(2, 40)
            val hidden = names.shuffled(random).take(n).map(::key)
            val tripCount = random.nextInt(1, 16)
            val trips = List(tripCount) {
                val size = if (random.nextInt(3) == 0) random.nextInt(2, 4) else random.nextInt(2, n + 1)
                walked(sub(hidden, random, size), random, tieChance = random.nextDouble(0.0, 0.5))
            }
            val rank = hidden.withIndex().associate { (i, k) -> k to i }
            // Pairs every agreeing total order fixes: the closure of observed pairs.
            val before = Array(n) { BooleanArray(n) }
            for (t in trips) for (i in t.groups.indices) for (j in i + 1 until t.groups.size) {
                for (a in t.groups[i]) for (b in t.groups[j]) before[rank.getValue(a)][rank.getValue(b)] = true
            }
            for (k in 0 until n) for (i in 0 until n) if (before[i][k]) for (j in 0 until n) if (before[k][j]) before[i][j] = true

            val asked = placed(trips).let { p -> if (random.nextBoolean()) p else p.filter { random.nextBoolean() }.toSet() }
            val out = Pairwise.order(trips, asked)
            assertPermutation(asked, out)
            for (i in out.indices) for (j in i + 1 until out.size) {
                val later = rank.getValue(out[i])
                val earlier = rank.getValue(out[j])
                assertTrue(!before[earlier][later], "run $run: ${out[j].value} must come before ${out[i].value}")
            }
        }
    }

    @Test
    fun `O15 the order within a tie group changes nothing`() {
        val random = Random(15)
        repeat(300) {
            val n = random.nextInt(3, 40)
            val pool = names.shuffled(random).take(n).map(::key)
            // Arbitrary, inconsistent histories: every trip in its own random order.
            val trips = List(random.nextInt(1, 25)) {
                walked(pool.shuffled(random).take(random.nextInt(2, n + 1)), random, tieChance = 0.4)
            }
            val permuted = trips.map { t -> TripOrder(t.groups.map { g -> g.shuffled(random).toLinkedSet() }) }
            val items = placed(trips)
            assertEquals(Pairwise.order(trips, items), Pairwise.order(permuted, items))
        }
    }

    @Test
    fun `O17 three trips of a new order flip a pair, whatever came before`() {
        val random = Random(17)
        val lengths = listOf(0, 1, 2, 3, 5, 10, 30, 100)
        repeat(400) { run ->
            val n = random.nextInt(2, 30)
            val old = names.shuffled(random).take(n).map(::key)
            val new = when (run % 4) {
                0 -> old.reversed() // indistinguishable from walking in by the other door
                1 -> old.toMutableList().also { val i = random.nextInt(n - 1); it[i] = old[i + 1]; it[i + 1] = old[i] }
                else -> old.shuffled(random)
            }
            val flipped = (0 until n).flatMap { i -> (i + 1 until n).map { j -> old[i] to old[j] } }
                .filter { (a, b) -> new.indexOf(b) < new.indexOf(a) }
            if (flipped.isEmpty()) return@repeat
            val (a, b) = flipped[random.nextInt(flipped.size)]
            val older = List(lengths[run % lengths.size]) {
                walked(sub(old, random, random.nextInt(2, n + 1)), random, tieChance = 0.3)
            }
            val newer = List(3) {
                val items = sub(new, random, random.nextInt(0, n + 1), must = setOf(a, b))
                // a and b in different groups: a tie would agree with either order.
                val t = walked(items, random, tieChance = 0.3)
                if (t.groups.any { a in it && b in it }) TripOrder(t.groups.flatMap { g -> g.map { setOf(it) } }) else t
            }
            val trips = older + newer
            val out = Pairwise.order(trips, placed(trips))
            assertTrue(out.indexOf(b) < out.indexOf(a), "run $run (${older.size} older trips): ${b.value} before ${a.value}")
        }
    }

    @Test
    fun `deterministic, and returns exactly the items asked for`() {
        val random = Random(19)
        repeat(100) {
            val pool = names.shuffled(random).take(30).map(::key)
            val trips = List(random.nextInt(0, 20)) { walked(pool.shuffled(random).take(random.nextInt(1, 30)), random, 0.3) }
            val items = (placed(trips) + pool.take(5) + keys("never-seen", "also-new")).toSet()
            val out = Pairwise.order(trips, items)
            assertPermutation(items, out)
            assertEquals(out, Pairwise.order(trips.toList(), LinkedHashSet(items.shuffled(random))))
        }
        assertEquals(emptyList(), Pairwise.order(listOf(trip(listOf("a"), listOf("b"))), emptySet()))
        assertEquals(keys("a", "b"), Pairwise.order(emptyList(), keys("b", "a").toSet()))
    }

    @Test
    fun `a disputed pair goes the way most recent evidence says`() {
        val ab = trip(listOf("a"), listOf("b"), listOf("c"))
        val ba = trip(listOf("b"), listOf("a"), listOf("c"))
        assertEquals(keys("b", "a", "c"), Pairwise.order(listOf(ab, ba), keys("a", "b", "c").toSet()))
        assertEquals(keys("a", "b", "c"), Pairwise.order(listOf(ba, ab), keys("a", "b", "c").toSet()))
        assertEquals(keys("a", "b", "c"), Pairwise.order(listOf(ba, ab, ab), keys("a", "b", "c").toSet()))
    }

    @Test
    fun `one walk from the other door does not turn the store round`() {
        val order = keys("a", "b", "c", "d", "e", "f")
        val forward = TripOrder(order.map { setOf(it) })
        val backward = TripOrder(order.reversed().map { setOf(it) })
        assertEquals(order, Pairwise.order(listOf(forward, forward, backward), order.toSet()))
        assertEquals(order, Pairwise.order(listOf(forward, forward, backward, forward, backward), order.toSet()))
    }

    @Test
    fun `fast enough to run on every presentation`() {
        val random = Random(50)
        val hidden = names.map(::key)
        val trips = List(100) { walked(sub(hidden, random, 30), random, 0.2) }
        val items = placed(trips)
        repeat(3) { Pairwise.order(trips, items) }
        val best = (1..5).minOf {
            val t0 = System.nanoTime()
            Pairwise.order(trips, items)
            (System.nanoTime() - t0) / 1_000_000.0
        }
        println("pairwise: 100 trips of 30, ${items.size} placed: best of 5 = $best ms")
        assertTrue(best < 50.0, "took $best ms")
    }

    // Through the presentation, with the history choosing this router.

    private fun chosen() = Hist().store(A).choose(Pairwise.id)

    @Test
    fun `present O13 one trip replayed`() {
        val h = chosen().trip(A, "milk", "bread", "eggs", "jam", "tea")
        h.add("tea", "eggs", "milk", "jam", "bread")
        assertEquals(keys("milk", "bread", "eggs", "jam", "tea"), present(h.list).section(SectionKind.RUN))
    }

    @Test
    fun `present O14 consistent trips combine`() {
        val h = chosen().trip(A, "milk", "eggs", "tea").trip(A, "bread", "eggs", "jam").trip(A, "milk", "bread")
        h.add("jam", "tea", "eggs", "bread", "milk")
        val run = present(h.list).section(SectionKind.RUN)
        for ((x, y) in listOf("milk" to "eggs", "eggs" to "tea", "bread" to "eggs", "eggs" to "jam", "milk" to "bread")) {
            assertTrue(run.indexOf(key(x)) < run.indexOf(key(y)), "$x before $y in $run")
        }
    }

    @Test
    fun `present O15 ticks inside a tie window teach nothing`() {
        fun history(tied: List<String>): Hist {
            val h = chosen().trip(A, "a", "b", "c", "d", "e")
            h.select(A).add("e", "d", "c", "b", "a")
            h.tick("e")
            tied.forEachIndexed { i, item -> h.tick(item, gap = if (i == 0) Hist.GAP else 1_000) }
            h.tick("a").end()
            return h.add("a", "b", "c", "d", "e")
        }
        assertEquals(present(history(listOf("d", "c", "b")).list), present(history(listOf("b", "d", "c")).list))
    }

    @Test
    fun `present O17 three trips flip a pair after a long history`() {
        val h = chosen()
        repeat(100) { h.trip(A, "a", "x", "b", "y") }
        repeat(3) { h.trip(A, "b", "y", "a") }
        h.add("y", "x", "b", "a")
        val run = present(h.list).section(SectionKind.RUN)
        assertTrue(run.indexOf(key("b")) < run.indexOf(key("a")), "$run")
    }
}
