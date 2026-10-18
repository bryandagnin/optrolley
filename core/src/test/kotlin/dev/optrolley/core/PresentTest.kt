package dev.optrolley.core

import dev.optrolley.core.SectionKind.ELSEWHERE
import dev.optrolley.core.SectionKind.LIST
import dev.optrolley.core.SectionKind.RUN
import dev.optrolley.core.SectionKind.UNPLACED
import kotlin.test.Test
import kotlin.test.assertEquals

class PresentTest {
    /** A router that returns whatever it is told to, to test the repair. */
    private class Fixed(override val id: String, val out: (Set<ItemKey>) -> List<ItemKey>) : Router {
        override val label = id
        override fun order(trips: List<TripOrder>, items: Set<ItemKey>) = out(items)
    }

    @Test
    fun noStoreIsThePlainList() {
        val p = present(Hist().add("c", "a", "b").list)
        assertEquals(null, p.store)
        assertEquals(listOf(LIST), p.kinds())
        assertEquals(keys("c", "a", "b"), p.section(LIST))
        assertEquals(listOf(Section(LIST, emptyList())), present(emptyList()).sections, "always one section")
    }

    @Test
    fun noStoreIgnoresWhatWasLearned() {
        val h = Hist().store(A).trip(A, "b", "a").select(null).add("a", "b")
        assertEquals(keys("a", "b"), present(h.list).section(LIST))
    }

    @Test
    fun coldStoreHasEverythingUnplaced() {
        val p = present(Hist().store(A).select(A).add("c", "a", "b").list)
        assertEquals(A, p.store)
        assertEquals(listOf(UNPLACED), p.kinds())
        assertEquals(keys("c", "a", "b"), p.section(UNPLACED))
    }

    @Test
    fun oneTripTeachesItsOrder() {
        val h = Hist().store(A).trip(A, "c", "a", "b").add("d", "b", "a", "c")
        val p = present(h.list)
        assertEquals(listOf(UNPLACED, RUN), p.kinds())
        assertEquals(keys("d"), p.section(UNPLACED))
        assertEquals(keys("c", "a", "b"), p.section(RUN))
    }

    @Test
    fun aTripOfOneTieGroupPlacesNothing() {
        val h = Hist().select(A).add("a", "b").tick("a").tick("b", 1_000).end().add("b", "a")
        assertEquals(keys("b", "a"), present(h.list).section(UNPLACED))
    }

    @Test
    fun anItemTiedWithSomeButApartFromAnotherIsPlaced() {
        val h = Hist().select(A).add("a", "b", "c").tick("a").tick("b", 1_000).tick("c").end().add("c", "b", "a")
        assertEquals(keys("c", "b", "a").toSet(), present(h.list).section(RUN).toSet())
        assertEquals(emptyList(), present(h.list).section(UNPLACED))
    }

    @Test
    fun placementIsNeverLost() {
        val h = Hist().trip(A, "a", "b").select(A).add("a").tick("a").end().add("a")
        assertEquals(keys("a"), present(h.list).section(RUN))
    }

    @Test
    fun storesLearnIndependently() {
        val h = Hist().trip(A, "a", "b").select(B).add("b", "a")
        assertEquals(keys("b", "a"), present(h.list).section(UNPLACED))
    }

    @Test
    fun theOrderHoldsStillDuringATrip() {
        val h = Hist().trip(A, "a", "b", "c", "d").select(A).add("d", "c", "b", "a")
        val before = present(h.list).all()
        h.tick("c").tick("a").untick("c").tick("d", 1_000)
        assertEquals(before, present(h.list).all())
        assertEquals(keys("a", "b", "c", "d"), before)
    }

    @Test
    fun anEntryAddedMidTripLandsWhereItBelongs() {
        val h = Hist().trip(A, "a", "b", "c", "d").select(A).add("a", "d").tick("a").add("c", "x", "b")
        val p = present(h.list)
        assertEquals(keys("x"), p.section(UNPLACED))
        assertEquals(keys("a", "b", "c", "d"), p.section(RUN))
    }

    @Test
    fun notHereMovesTheEntryAtOnceAndUndoMovesItBack() {
        val h = Hist().trip(A, "a", "b").select(A).add("a", "b", "c").tick("a")
        h.notHere("b", A)
        val p = present(h.list)
        assertEquals(keys("a"), p.section(RUN))
        assertEquals(keys("b"), p.section(ELSEWHERE))
        assertEquals(1, p.elsewhereCount)
        assertEquals(listOf(UNPLACED, RUN, ELSEWHERE), p.kinds())
        h.undoNotHere("b", A)
        assertEquals(keys("a", "b"), present(h.list).section(RUN))
        assertEquals(0, present(h.list).elsewhereCount)
    }

    @Test
    fun aPurchaseOverridesNotHereFromTheNextTrip() {
        val h = Hist().store(A).select(A).add("a", "b").notHere("a", A).tick("a").tick("b")
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE), "a tick moves nothing mid-trip")
        h.end().add("a", "b")
        val p = present(h.list)
        assertEquals(emptyList(), p.section(ELSEWHERE))
        assertEquals(keys("a", "b"), p.section(RUN))
    }

    @Test
    fun notHereSaidAfterThePurchaseIsTheLatestWord() {
        val h = Hist().store(A).select(A).add("a", "b").tick("a").tick("b").notHere("a", A).end().add("a")
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE))
    }

    @Test
    fun aPurchaseElsewhereSaysNothingHere() {
        val h = Hist().store(A).store(B).select(A).add("a").notHere("a", A).trip(B, "a", "z").select(A).add("a")
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE))
    }

    @Test
    fun chainScopeCoversTheChainAndNothingElse() {
        val h = Hist().chain(CHAIN).store(A, CHAIN).store(B, CHAIN).store(C).select(A).add("a", "b")
        h.notHere("a", A, Scope.CHAIN)
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE))
        h.select(B)
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE))
        h.select(C)
        assertEquals(emptyList(), present(h.list).section(ELSEWHERE))
        h.setChain(C, CHAIN)
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE), "chains are as they are now")
    }

    @Test
    fun aPurchaseAtOneStoreOfAChainClearsOnlyThatStore() {
        val h = Hist().chain(CHAIN).store(A, CHAIN).store(B, CHAIN).select(A).add("a")
            .notHere("a", A, Scope.CHAIN).trip(B, "a", "z").add("a")
        assertEquals(emptyList(), present(h.list).section(ELSEWHERE))
        h.select(A)
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE))
    }

    @Test
    fun chainScopeAtAStoreWithNoChainCoversThatStore() {
        val h = Hist().store(A).store(B).select(A).add("a").notHere("a", A, Scope.CHAIN)
        assertEquals(keys("a"), present(h.list).section(ELSEWHERE))
        h.select(B)
        assertEquals(emptyList(), present(h.list).section(ELSEWHERE))
    }

    @Test
    fun undoRestoresExactlyWhatStoodBefore() {
        val chain = Hist().chain(CHAIN).store(A, CHAIN).store(B, CHAIN).select(A).add("a")
            .notHere("a", B, Scope.CHAIN).notHere("a", A).undoNotHere("a", A)
        assertEquals(keys("a"), present(chain.list).section(ELSEWHERE), "the chain's word still stands")
        val twice = Hist().store(A).select(A).add("a").notHere("a", A).notHere("a", A).undoNotHere("a", A)
        assertEquals(keys("a"), present(twice.list).section(ELSEWHERE))
        val bought = Hist().store(A).select(A).add("a", "b").notHere("a", A).ticks("a", "b").end()
            .add("a").notHere("a", A).undoNotHere("a", A)
        assertEquals(emptyList(), present(bought.list).section(ELSEWHERE), "back to the purchase")
        val stray = Hist().store(A).select(A).add("a").undoNotHere("a", A).undoNotHere("a", A, Scope.CHAIN)
        assertEquals(keys("a"), present(stray.list).section(UNPLACED))
    }

    @Test
    fun theChosenRouterOrdersTheRunFromTheNextTrip() {
        val routers = Routers(listOf(FirstSeen, Named("by-name")), FirstSeen.id)
        val h = Hist().trip(A, "c", "a", "b").select(A).add("c", "a", "b")
        assertEquals(keys("c", "a", "b"), present(h.list, routers).section(RUN))
        h.tick("a").choose("by-name")
        assertEquals(keys("c", "a", "b"), present(h.list, routers).section(RUN), "not during this trip")
        h.end().add("a")
        assertEquals(keys("a", "b", "c"), present(h.list, routers).section(RUN))
        h.choose("gone")
        assertEquals(keys("c", "a", "b"), present(h.list, routers).section(RUN), "unknown falls back to the default")
    }

    @Test
    fun aBadRouterLosesNothing() {
        val h = Hist().trip(A, "a", "b", "c", "d").select(A).add("d", "b", "x", "c", "a")
        fun run(out: (Set<ItemKey>) -> List<ItemKey>): List<ItemKey> {
            val routers = Routers(listOf(Fixed("bad", out)), "bad")
            val p = present(h.list, routers)
            assertEquals(keys("d", "b", "x", "c", "a").toSet(), p.all().toSet())
            assertEquals(5, p.all().size)
            return p.section(RUN)
        }
        assertEquals(keys("c", "a", "d", "b"), run { keys("c", "zz", "c", "a") }, "missing go last, in add order")
        assertEquals(keys("d", "b", "c", "a"), run { emptyList() })
        assertEquals(keys("d", "b", "c", "a"), run { error("router failed") })
    }

    @Test
    fun theRouterIsAskedAboutEveryPlacedItem() {
        var asked = emptySet<ItemKey>()
        val spy = Fixed("spy") { asked = it; it.toList() }
        val h = Hist().trip(A, "a", "b", "c").select(A).add("b")
        present(h.list, Routers(listOf(spy), "spy"))
        assertEquals(keys("a", "b", "c").toSet(), asked)
    }
}
