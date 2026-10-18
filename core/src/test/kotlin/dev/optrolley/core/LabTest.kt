package dev.optrolley.core

import kotlin.test.Test
import kotlin.test.assertEquals

class LabTest {
    private object Backwards : Router {
        override val id = "backwards"
        override val label = id
        override fun order(trips: List<TripOrder>, items: Set<ItemKey>) = items.sortedByDescending { it.value }
    }

    private val routers = Routers(listOf(FirstSeen, Backwards), FirstSeen.id)

    @Test
    fun scoresEachTripOnTheTripsBeforeIt() {
        val h = Hist().store(A).store(B)
            .trip(A, "a", "b", "c")
            .trip(B, "x", "y")
            .trip(A, "c", "a", "b")
            .trip(A, "b", "a", "d")
            .select(null).add("q", "r").ticks("q", "r").end()
            .select(A).add("a", "b", "c").tick("a").tick("b", 1_000).tick("c").end()
        val scores = score(h.list, routers)
        assertEquals(listOf(FirstSeen.id, Backwards.id), scores.keys.toList())
        // Learned order a b c. Walks: c a b; b a (d unplaced); {a b} c.
        assertEquals(
            listOf(
                TripScore(A, 0, 0, 0),
                TripScore(B, 0, 0, 0),
                TripScore(A, 1, 3, 2),
                TripScore(A, 2, 1, 1),
                TripScore(A, 3, 2, 0),
            ),
            scores[FirstSeen.id],
        )
        // Order c b a, whatever was learned.
        assertEquals(
            listOf(
                TripScore(A, 0, 0, 0),
                TripScore(B, 0, 0, 0),
                TripScore(A, 1, 3, 1),
                TripScore(A, 2, 1, 0),
                TripScore(A, 3, 2, 2),
            ),
            scores[Backwards.id],
        )
    }

    @Test
    fun noHistoryNoScores() {
        assertEquals(mapOf(FirstSeen.id to emptyList<TripScore>(), Backwards.id to emptyList()), score(emptyList(), routers))
    }
}
