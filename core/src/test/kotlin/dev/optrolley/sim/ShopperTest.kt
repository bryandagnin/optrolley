package dev.optrolley.sim

import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Rules
import dev.optrolley.core.StoreId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class ShopperTest {
    private val cases = ReferenceSuite.cases
    private fun scenario(name: String) = cases.filter { it.scenario.name == name }
    private val spec = ReferenceSuite.shops[1]

    @Test
    fun `every history in the suite is well formed`() {
        for (case in cases) wellFormed(case.run)
    }

    @Test
    fun `the walk visits zones in order, forwards or backwards`() {
        for (case in cases) for (trip in case.run.trips) {
            val zones = trip.walk.map { trip.layout.zoneOf(it.item) }
            val ordered = if (trip.reversed) zones.zipWithNext().all { (a, b) -> a >= b } else zones.zipWithNext().all { (a, b) -> a <= b }
            assertTrue(ordered, "${case.scenario.name}/${case.seed} trip ${trip.number}: $zones")
            // Moving between zones always takes longer than the tie window.
            trip.walk.zipWithNext().filter { (a, b) -> trip.layout.zoneOf(a.item) != trip.layout.zoneOf(b.item) }
                .forEach { (a, b) -> assertTrue(b.at - a.at >= Rules.TIE_WINDOW_MS) }
        }
    }

    @Test
    fun `within a zone, picks are seconds apart`() {
        for (case in scenario("clean")) for (trip in case.run.trips) {
            trip.walk.zipWithNext().filter { (a, b) -> trip.layout.zoneOf(a.item) == trip.layout.zoneOf(b.item) }
                .forEach { (a, b) -> assertTrue(b.at - a.at in 2_000L..12_000L) }
        }
    }

    @Test
    fun `lists are realistic in size and composition`() {
        for (case in cases) for (trip in case.run.trips) {
            val stocked = trip.listed.count { it in trip.layout }
            assertTrue(stocked in Habits.LIST_SIZE, "list of $stocked")
            assertEquals(trip.walk.size, stocked)
        }
        // Bigger shops, bigger lists; and most of a list was bought before.
        val sizes = ReferenceSuite.shops.map { s -> scenario("clean").filter { it.run.shop.spec == s }.flatMap { it.run.trips }.map { it.walk.size }.average() }
        assertEquals(sizes.sorted(), sizes)
        val repeat = scenario("clean").flatMap { c -> c.run.trips.drop(4).map { t -> t to c } }.map { (t, c) ->
            val before = c.run.trips.take(t.number - 1).flatMap { it.walk.map(Pick::item) }.toSet()
            t.walk.count { it.item in before }.toDouble() / t.walk.size
        }.average()
        assertTrue(repeat in 0.6..0.95, "repeat share $repeat")
    }

    @Test
    fun `with no noise, ticks follow picks`() {
        for (case in scenario("clean")) for (trip in case.run.trips) {
            assertEquals(trip.walk.map { it.item }, trip.ticks.map { it.item })
            trip.walk.zip(trip.ticks).forEach { (p, t) -> assertTrue(t.at - p.at in 300L..2_500L) }
            assertTrue(trip.batches.isEmpty() && trip.late.isEmpty() && !trip.reversed && trip.absent.isEmpty())
        }
    }

    @Test
    fun `tick noise corrupts the evidence, not the walk`() {
        val clean = scenario("clean")
        for (name in listOf("batched", "late", "absent")) {
            scenario(name).zip(clean).forEach { (noisy, base) ->
                assertEquals(base.seed, noisy.seed)
                noisy.run.trips.zip(base.run.trips).forEach { (a, b) ->
                    assertEquals(relative(b.walk), relative(a.walk), name)
                    assertEquals(b.layout, a.layout, name)
                    assertEquals(b.listed, a.listed.filter { it in a.layout }, name)
                }
            }
        }
        scenario("rearranged").zip(clean).forEach { (noisy, base) ->
            noisy.run.trips.zip(base.run.trips).take(ReferenceSuite.REARRANGE_AT - 1).forEach { (a, b) -> assertEquals(b.walk, a.walk) }
        }
    }

    /** A walk with its clock started at zero: trips start at different times once noise shifts the clock. */
    private fun relative(walk: List<Pick>) = walk.map { Pick(it.item, it.at - walk.first().at) }

    @Test
    fun `batched ticks land inside one tie group`() {
        var batched = 0
        var picks = 0
        var spanning = 0
        for (case in scenario("batched")) for (trip in case.run.trips) {
            picks += trip.walk.size
            val groups = Measure.tieGroups(trip.ticks)
            for (batch in trip.batches) {
                batched += batch.size
                assertTrue(batch.size in 1..5)
                assertTrue(groups.any { it.containsAll(batch) }, "batch $batch split across $groups")
                val at = trip.walk.filter { it.item in batch }.map { it.at }
                if (at.max() - at.min() >= Rules.TIE_WINDOW_MS) spanning++
            }
        }
        val share = batched.toDouble() / picks
        assertTrue(share in 0.15..0.35, "batched share $share")
        assertTrue(spanning > 0, "no batch hides an order the walk had")
    }

    @Test
    fun `late ticks come after later picks`() {
        var late = 0
        var picks = 0
        for (case in scenario("late")) for (trip in case.run.trips) {
            picks += trip.walk.size
            late += trip.late.size
            val walked = trip.walk.map { it.item }
            val ticked = trip.ticks.map { it.item }
            for (item in trip.late) {
                val p = walked.indexOf(item)
                if (p == walked.lastIndex) continue
                val at = trip.ticks.first { it.item == item }.at
                assertTrue(at > trip.walk[p + 1].at, "late $item ticked before the next pick")
            }
            val onTime = walked.filter { it !in trip.late }
            assertEquals(onTime, ticked.filter { it !in trip.late })
        }
        val share = late.toDouble() / picks
        assertTrue(share in 0.06..0.14, "late share $share")
    }

    @Test
    fun `some trips walk the shop backwards`() {
        val trips = scenario("reversed").flatMap { it.run.trips }
        val share = trips.count { it.reversed }.toDouble() / trips.size
        assertTrue(share in 0.15..0.35, "reversed share $share")
        val all = Shopper.simulate(spec, Noise(reverse = 1.0), 3, 5)
        val none = Shopper.simulate(spec, Noise(), 3, 5)
        all.trips.zip(none.trips).forEach { (r, f) ->
            assertTrue(r.reversed && !f.reversed)
            assertEquals(f.walk.map { f.layout.zoneOf(it.item) }.reversed(), r.walk.map { r.layout.zoneOf(it.item) })
            assertEquals(f.listed, r.listed)
        }
    }

    @Test
    fun `the rearrangement takes effect at its trip`() {
        for (case in scenario("rearranged") + scenario("everything")) {
            val (before, after) = case.run.trips.partition { it.number < ReferenceSuite.REARRANGE_AT }
            assertTrue(before.all { it.layout == case.run.shop.layout })
            val moved = after.first().layout
            assertNotEquals(case.run.shop.layout, moved)
            assertTrue(after.all { it.layout == moved })
            assertEquals(case.run.shop.layout.items, moved.items)
            assertEquals(ReferenceSuite.REARRANGE_AT, after.first().number)
        }
    }

    @Test
    fun `absent items are listed, never ticked, sometimes said not here`() {
        var listed = 0
        var notHere = 0
        var removed = 0
        var carried = 0
        for (case in scenario("absent")) {
            val wanted = case.run.shop.unstocked.take(case.run.noise.absent).toSet()
            for (trip in case.run.trips) {
                assertEquals(trip.listed.filter { it in wanted }, trip.absent)
                assertTrue(trip.ticks.none { it.item in wanted })
                listed += trip.absent.size
            }
            case.run.trips.zipWithNext().forEach { (a, b) -> carried += a.absent.count { it in b.listed } }
            notHere += case.run.history.count { it is Observation.NotHere && it.item in wanted }
            removed += case.run.history.count { it is Observation.Removed && it.item in wanted }
        }
        assertTrue(listed > 0 && notHere > 0 && removed > 0 && carried > 0, "$listed $notHere $removed $carried")
    }

    @Test
    fun `the same seed gives the same history, a different one does not`() {
        val noise = ReferenceSuite.scenarios.last().noise
        val a = Shopper.simulate(spec, noise, 42, ReferenceSuite.TRIPS)
        val b = Shopper.simulate(spec, noise, 42, ReferenceSuite.TRIPS)
        val c = Shopper.simulate(spec, noise, 43, ReferenceSuite.TRIPS)
        assertEquals(a.history, b.history)
        assertEquals(a.trips, b.trips)
        assertNotEquals(a.history, c.history)
        assertEquals(Measure.baselines("x", a, 42), Measure.baselines("x", b, 42))
    }

    /** The history is one a real user could have produced, and matches the trips recorded beside it. */
    private fun wellFormed(run: SimRun) {
        val h = run.history
        h.zipWithNext().forEach { (a, b) -> assertTrue(b.at >= a.at, "time runs backwards at $b") }
        assertIs<Observation.StoreCreated>(h.first())
        val onList = LinkedHashSet<ItemKey>()
        val ticks = mutableListOf<Pick>()
        var selected: StoreId? = null
        var trip = 0
        for ((i, o) in h.withIndex()) {
            when (o) {
                is Observation.StoreCreated -> assertEquals(0, i)
                is Observation.Added -> {
                    val item = ItemKey.of(o.name)
                    assertTrue(item !in onList && ticks.isEmpty())
                    onList += item
                }
                is Observation.Selected -> {
                    assertEquals(run.shop.store, o.store)
                    assertTrue(ticks.isEmpty())
                    selected = o.store
                    assertEquals(i + 1, run.trips[trip].presentAt)
                    assertEquals(run.trips[trip].listed, onList.toList())
                }
                is Observation.Ticked -> {
                    assertEquals(run.shop.store, selected)
                    assertTrue(o.item in onList && ticks.none { it.item == o.item })
                    assertTrue(o.item in run.trips[trip].layout)
                    ticks += Pick(o.item, o.at)
                }
                is Observation.NotHere -> {
                    assertEquals(run.shop.store, o.store)
                    assertTrue(o.item in onList && o.item in run.shop.unstocked)
                }
                is Observation.TripEnded -> {
                    val t = run.trips[trip]
                    assertEquals(t.ticks, ticks)
                    assertEquals(t.walk.map { it.item }.toSet(), ticks.map { it.item }.toSet())
                    assertTrue(ticks.zipWithNext().all { (a, b) -> b.at - a.at < Rules.IDLE_TIMEOUT_MS })
                    onList -= ticks.map { it.item }.toSet()
                    ticks.clear()
                    trip++
                }
                is Observation.Removed -> {
                    assertTrue(o.item in onList && ticks.isEmpty())
                    onList -= o.item
                }
                else -> fail("unexpected $o")
            }
        }
        assertTrue(ticks.isEmpty())
        assertEquals(run.trips.size, trip)
    }
}
