package dev.optrolley.core

import kotlin.test.Test
import kotlin.test.assertEquals

class FirstSeenTest {
    private fun trip(vararg groups: List<String>) = TripOrder(groups.map { g -> g.map(::key).toSet() })

    @Test
    fun ordersByFirstTripThenPlaceThenName() {
        val trips = listOf(
            trip(listOf("m"), listOf("k", "b"), listOf("a")),
            trip(listOf("z"), listOf("a"), listOf("c")),
        )
        val items = keys("c", "a", "z", "b", "k", "m", "never", "also").toSet()
        assertEquals(keys("m", "b", "k", "a", "z", "c", "also", "never"), FirstSeen.order(trips, items))
    }

    @Test
    fun returnsExactlyTheItemsAskedFor() {
        val trips = listOf(trip(listOf("a"), listOf("b"), listOf("c")))
        assertEquals(keys("c", "a"), FirstSeen.order(trips, keys("a", "c").toSet()).reversed())
        assertEquals(emptyList(), FirstSeen.order(trips, emptySet()))
    }
}
