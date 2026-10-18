package dev.optrolley.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ReplayTest {
    private fun items(h: Hist) = replay(h.list).entries.map { it.item }

    @Test
    fun emptyHistory() {
        val s = replay(emptyList())
        assertEquals(emptyList(), s.entries)
        assertNull(s.selected)
        assertNull(s.trip)
        assertEquals(emptyMap(), s.trips)
        assertEquals(Routers.all.defaultId, s.router)
    }

    @Test
    fun addKeepsOrderAndMatchesNames() {
        val h = Hist().add("Milk", "bread", "  MILK ").addWith("eggs", "6", "free range")
        val s = replay(h.list)
        assertEquals(keys("milk", "bread", "eggs"), s.entries.map { it.item })
        assertEquals("Milk", s.entries[0].name)
        assertEquals("MILK", s.known[key("milk")], "known keeps the latest spelling")
        assertEquals(Entry(key("eggs"), "eggs", "6", "free range", false), s.entries[2])
    }

    @Test
    fun editChangesDetailsInPlace() {
        val h = Hist().add("milk", "bread").edit("milk", "Milk", "2", "semi")
        val s = replay(h.list)
        assertEquals(Entry(key("milk"), "Milk", "2", "semi", false), s.entries[0])
        assertEquals(keys("milk", "bread"), s.entries.map { it.item })
    }

    @Test
    fun editRelinksAndKeepsPlace() {
        val h = Hist().add("milk", "bred", "eggs").edit("bred", "Bread")
        val s = replay(h.list)
        assertEquals(keys("milk", "bread", "eggs"), s.entries.map { it.item })
        assertEquals("Bread", s.known[key("bread")])
        assertEquals("bred", s.known[key("bred")], "names once typed stay known")
    }

    @Test
    fun editOntoAnItemAlreadyListedMergesAtTheEarlierPlace() {
        val h = Hist().add("bread", "milk", "milc").edit("milc", "milk", "2", null)
        val s = replay(h.list)
        assertEquals(keys("bread", "milk"), s.entries.map { it.item })
        assertEquals("2", s.entries[1].quantity)
        val back = Hist().add("milc", "bread", "milk").edit("milk", "milc")
        assertEquals(keys("milc", "bread"), items(back))
    }

    @Test
    fun mergedEntryStaysTickedOnlyIfBothWere() {
        val one = Hist().select(A).add("milk", "milc").tick("milk").edit("milc", "milk").end()
        assertEquals(keys("milk"), items(one), "one of the two was not yet got")
        val both = Hist().select(A).add("milk", "milc").ticks("milk", "milc").edit("milc", "milk").end()
        assertEquals(emptyList(), items(both))
    }

    @Test
    fun editOfAnItemNotOnTheListDoesNothing() {
        val h = Hist().add("milk").edit("bread", "eggs")
        assertEquals(keys("milk"), items(h))
    }

    @Test
    fun removeAndAddAgainGoesToTheEnd() {
        val h = Hist().add("milk", "bread").remove("milk").add("milk")
        assertEquals(keys("bread", "milk"), items(h))
    }

    @Test
    fun tickAndUntickMarkTheEntry() {
        val h = Hist().add("milk", "bread").tick("milk")
        assertEquals(listOf(true, false), replay(h.list).entries.map { it.ticked })
        h.untick("milk")
        assertEquals(listOf(false, false), replay(h.list).entries.map { it.ticked })
    }

    @Test
    fun firstTickStartsATripAtTheSelectedStore() {
        val h = Hist().store(A).select(A).add("milk")
        assertNull(replay(h.list).trip)
        h.tick("milk")
        val trip = assertNotNull(replay(h.list).trip)
        assertEquals(A, trip.store)
        assertEquals(h.t, trip.startedAt)
        h.add("bread")
        assertEquals(h.t, replay(h.list).trip!!.lastAt)
    }

    @Test
    fun tickWithNoStoreStartsAStorelessTrip() {
        val h = Hist().add("milk").tick("milk")
        assertNull(assertNotNull(replay(h.list).trip).store)
    }

    @Test
    fun tickOfAnItemNotOnTheListIsIgnored() {
        val h = Hist().select(A).add("milk").tick("bread")
        assertNull(replay(h.list).trip)
    }

    @Test
    fun tripEndRemovesTickedAndLearns() {
        val h = Hist().select(A).addWith("milk", "2", "x").add("bread", "eggs").ticks("bread", "eggs").end()
        val s = replay(h.list)
        assertNull(s.trip)
        assertEquals(listOf(Entry(key("milk"), "milk", "2", "x", false)), s.entries)
        assertEquals(listOf(TripOrder(listOf(setOf(key("bread")), setOf(key("eggs"))))), s.trips[A])
    }

    @Test
    fun selectingAnotherStoreOrNoneEndsTheTrip() {
        val other = Hist().select(A).add("milk").tick("milk").select(B)
        assertEquals(1, replay(other.list).trips[A]?.size)
        assertEquals(B, replay(other.list).selected)
        assertEquals(emptyList(), items(other))
        val none = Hist().select(A).add("milk").tick("milk").select(null)
        assertEquals(1, replay(none.list).trips[A]?.size)
        assertNull(replay(none.list).trip)
    }

    @Test
    fun selectingTheSameStoreChangesNothing() {
        val h = Hist().select(A).add("milk", "bread").tick("milk").select(A)
        val s = replay(h.list)
        assertNotNull(s.trip)
        assertEquals(keys("milk", "bread"), s.entries.map { it.item })
        val none = Hist().add("milk").tick("milk").select(null)
        assertNotNull(replay(none.list).trip)
    }

    @Test
    fun storelessTripEndsAndLearnsNothing() {
        val h = Hist().add("milk", "bread").ticks("milk", "bread").select(A)
        val s = replay(h.list)
        assertEquals(emptyList(), s.entries)
        assertEquals(emptyMap(), s.trips)
    }

    @Test
    fun endWithNothingTickedRecordsNoTrip() {
        val h = Hist().select(A).add("milk").tick("milk").untick("milk").end()
        val s = replay(h.list)
        assertNull(s.trip)
        assertEquals(null, s.trips[A])
        assertEquals(keys("milk"), s.entries.map { it.item })
    }

    @Test
    fun tripEndWithNoTripDoesNothing() {
        val h = Hist().select(A).add("milk").end()
        assertEquals(keys("milk"), items(h))
        assertEquals(emptyMap(), replay(h.list).trips)
    }

    @Test
    fun tieWindowChainsAtTheBoundary() {
        val h = Hist().select(A).add("a", "b", "c", "d", "e")
            .tick("a").tick("b", 4_999).tick("c", 4_999).tick("d", 5_000).tick("e", 10_000).end()
        val groups = replay(h.list).trips.getValue(A).single().groups
        assertEquals(listOf(setOf(key("a"), key("b"), key("c")), setOf(key("d")), setOf(key("e"))), groups)
    }

    @Test
    fun undoneAndRedoneCountsOnceAtTheFinalTick() {
        val h = Hist().select(A).add("a", "b", "c").tick("a").tick("b").untick("a").tick("c").tick("a").end()
        val groups = replay(h.list).trips.getValue(A).single().groups
        assertEquals(listOf(setOf(key("b")), setOf(key("c")), setOf(key("a"))), groups)
    }

    @Test
    fun tickUndoneTeachesNothingAndStays() {
        val h = Hist().select(A).add("a", "b", "c").tick("a").tick("b").untick("b").tick("c").end()
        val s = replay(h.list)
        assertEquals(listOf(setOf(key("a")), setOf(key("c"))), s.trips.getValue(A).single().groups)
        assertEquals(keys("b"), s.entries.map { it.item })
    }

    @Test
    fun removedMidTripIsNotLearned() {
        val h = Hist().select(A).add("a", "b").tick("a").tick("b").remove("a").end()
        assertEquals(listOf(setOf(key("b"))), replay(h.list).trips.getValue(A).single().groups)
    }

    @Test
    fun relinkedMidTripIsLearnedUnderTheNewName() {
        val h = Hist().select(A).add("a", "bred").tick("a").tick("bred").edit("bred", "bread").end()
        assertEquals(listOf(setOf(key("a")), setOf(key("bread"))), replay(h.list).trips.getValue(A).single().groups)
    }

    @Test
    fun storesAndChains() {
        val h = Hist().chain(CHAIN).store(A, CHAIN).store(B)
        h.list += Observation.StoreRenamed(h.t + 1, A, "Big A")
        h.list += Observation.ChainRenamed(h.t + 2, CHAIN, "Big chain")
        h.setChain(B, CHAIN).setChain(A, null)
        val s = replay(h.list)
        assertEquals(Store(A, "Big A", null), s.stores[A])
        assertEquals(Store(B, "b", CHAIN), s.stores[B])
        assertEquals(Chain(CHAIN, "Big chain"), s.chains[CHAIN])
    }

    @Test
    fun routerChoiceWaitsForTheNextTrip() {
        val h = Hist().select(A).add("a", "b").choose("x").tick("a")
        assertEquals(Routers.all.defaultId, replay(h.list).router, "unknown ids fall back to the default")
        h.choose("one")
        assertEquals("x", Fold(h.list).routerChoice, "the choice in force when the trip began")
        h.end()
        assertEquals("one", Fold(h.list).routerChoice)
    }

    @Test
    fun settleEndsAnIdleTripAtTheMomentItWentIdle() {
        val h = Hist().select(A).add("milk").tick("milk")
        val last = h.t
        assertSame(h.list, settle(h.list, last + Rules.IDLE_TIMEOUT_MS - 1))
        val settled = settle(h.list, last + Rules.IDLE_TIMEOUT_MS + 12_345)
        assertEquals(h.list.size + 1, settled.size)
        assertEquals(Observation.TripEnded(last + Rules.IDLE_TIMEOUT_MS, EndReason.IDLE), settled.last())
        assertEquals(h.list, settled.dropLast(1), "the record is only appended to")
        assertNull(replay(settled).trip)
        assertSame(settled, settle(settled, last + 10 * Rules.IDLE_TIMEOUT_MS))
    }

    @Test
    fun settleCountsFromTheTripsLastObservationOfAnyKind() {
        val h = Hist().select(A).add("milk").tick("milk").add("bread")
        val last = h.t
        assertEquals(1, settle(h.list, last + Rules.IDLE_TIMEOUT_MS).size - h.list.size)
        assertSame(h.list, settle(h.list, last + Rules.IDLE_TIMEOUT_MS - 1))
    }

    @Test
    fun settleWithNoTripDoesNothing() {
        val h = Hist().select(A).add("milk")
        assertSame(h.list, settle(h.list, h.t + 100 * Rules.IDLE_TIMEOUT_MS))
    }

    @Test
    fun replayIsFastEnough() {
        val h = Hist().store(A).store(B)
        val rnd = kotlin.random.Random(7)
        while (h.list.size < 10_000) {
            h.select(if (rnd.nextBoolean()) A else B)
            val names = (0 until 30).map { "item${rnd.nextInt(300)}" }.distinct()
            h.add(*names.toTypedArray())
            names.shuffled(rnd).take(20).forEach { h.tick(it, rnd.nextLong(1_000, 20_000)) }
            h.end()
        }
        repeat(5) { replay(h.list) }
        val start = System.nanoTime()
        val s = replay(h.list)
        val ms = (System.nanoTime() - start) / 1_000_000
        assertTrue(s.trips.isNotEmpty())
        assertTrue(ms < 100, "replay of ${h.list.size} observations took $ms ms")
    }
}

/** A router that orders by name, to tell routers apart. */
class Named(override val id: String) : Router {
    override val label = id
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>) = items.sortedBy { it.value }
}
