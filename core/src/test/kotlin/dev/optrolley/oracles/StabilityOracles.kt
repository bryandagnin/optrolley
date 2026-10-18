package dev.optrolley.oracles

import dev.optrolley.core.Observation
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tier 3 group B: stability (T2). */
class StabilityOracles {

    @Test
    fun `O7 ticking moves nothing else over random histories`() = property("O7", seed = 7) { c ->
        walk(c.hist.history) { st ->
            val e = st.event
            if (e is Observation.Ticked || e is Observation.Unticked) {
                val item = if (e is Observation.Ticked) e.item else (e as Observation.Unticked).item
                val others = st.wasList.toSet() - item
                val swapped = swappedPairs(st.before!!.keys(), st.after.keys(), others)
                assertEquals(emptyList(), swapped, "${st.where()} reordered other entries")
            }
        }
    }

    @Test
    fun `O7 ticking moves nothing else in a learned store`() {
        for (v in hardVariants) withCase("O7", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            val items = listOf("Milk", "Bread", "Eggs", "Tea", "Rice").map { h.add(it) }
            h.select(StoreId("a"))
            items.reversed().forEach { h.tick(it) }
            h.end()
            listOf("Bread", "Rice", "Milk", "Tea", "Eggs", "Coffee", "Pasta").forEach { h.add(it) }
            var before = present(h.history).keys()
            for (k in listOf("Coffee", "Tea", "Milk", "Pasta").map(::key)) {
                h.tick(k)
                val after = present(h.history).keys()
                assertEquals(emptyList(), swappedPairs(before, after, before.toSet() - k), "ticking $k reordered others")
                before = after
            }
            h.untick(key("Tea"))
            assertEquals(emptyList(), swappedPairs(before, present(h.history).keys(), before.toSet() - key("Tea")), "unticking reordered others")
        }
    }

    @Test
    fun `O8 additions disturb nothing over random histories`() = property("O8", seed = 8) { c ->
        walk(c.hist.history) { st ->
            val e = st.event
            if (e is Observation.Added && st.wasTrip && key(e.name) !in st.wasList) {
                val swapped = swappedPairs(st.before!!.keys(), st.after.keys(), st.wasList.toSet())
                assertEquals(emptyList(), swapped, "adding ${key(e.name)} mid-trip ${st.where()} reordered entries already presented")
            }
        }
    }

    @Test
    fun `O9 no drift over random histories`() = property("O9", seed = 9) { c ->
        walk(c.hist.history) { st ->
            val e = st.event ?: return@walk
            // Within one trip: it was in progress before this observation and still is after.
            if (!st.wasTrip || !st.s.tripActive || e is Observation.Selected || e is Observation.TripEnded) return@walk
            val before = st.before!!
            val unticked = before.flat().filter { !it.ticked }.map { it.item }.toSet() intersect
                st.after.flat().filter { !it.ticked }.map { it.item }.toSet()
            for ((a, b) in swappedPairs(before.keys(), st.after.keys(), unticked)) {
                assertTrue(acts(e, a) || acts(e, b), "unticked $a and $b changed relative order ${st.where()}, which acts on neither")
            }
        }
    }
}
