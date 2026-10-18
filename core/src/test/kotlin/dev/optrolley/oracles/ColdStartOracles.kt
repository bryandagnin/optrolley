package dev.optrolley.oracles

import dev.optrolley.core.SectionKind
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Tier 3 group C: cold start (T3). */
class ColdStartOracles {

    @Test
    fun `O11 paper order with no store selected over random histories`() = property("O11", seed = 11) { c ->
        walk(c.hist.history) { st ->
            if (st.s.selected == null) assertEquals(st.s.list, st.after.keys(), "with no store selected ${st.where()}, not in add order")
        }
        val f = c.hist.at(null)
        assertEquals(f.s.list, present(f.history).keys(), "with no store selected at the end, not in add order")
    }

    @Test
    fun `O11 paper order at a store with no observations over random histories`() = property("O11", seed = 111) { c ->
        val f = c.hist.fork()
        f.store(StoreId("fresh"), "Fresh")
        f.select(StoreId("fresh"))
        assertEquals(f.s.list, present(f.history).keys(), "at a store with no observations, not in add order")
        // Still so after things happen elsewhere and the list changes.
        Gen(c.rnd, f, GenConfig()).quiet()
        f.selectIfOther(StoreId("s1"))
        f.add("Coffee"); f.add("Tea"); f.add("Milk")
        f.select(StoreId("fresh"))
        assertEquals(f.s.list, present(f.history).keys(), "at a store with no observations, after more history, not in add order")
    }

    @Test
    fun `O11 paper order on a fresh list`() {
        for (v in hardVariants) withCase("O11", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val names = listOf("Tea", "Milk", "Apples", "Bread", "Eggs")
            names.forEach { h.add(it) }
            assertEquals(names.map(::key), present(h.history).keys(), "no store selected")
            h.store(StoreId("a"))
            h.select(StoreId("a"))
            assertEquals(names.map(::key), present(h.history).keys(), "a store with no observations")
        }
    }

    @Test
    fun `O12 unplaced stay together in entry order over random histories`() = property("O12", seed = 12) { c ->
        walk(c.hist.history) { st ->
            if (st.s.selected == null) return@walk
            val unplaced = st.after.sections.filter { it.kind == SectionKind.UNPLACED && it.entries.isNotEmpty() }
            assertTrue(unplaced.size <= 1, "unplaced entries are split across ${unplaced.size} groups ${st.where()}")
            val keys = unplaced.flatMap { s -> s.entries.map { it.item } }
            assertTrue(isSubsequence(keys, st.s.list), "unplaced entries $keys are not in add order ${st.s.list} ${st.where()}")
        }
    }

    @Test
    fun `O12 unplaced stay together among placed entries`() {
        for (v in hardVariants) withCase("O12", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(StoreId("a"))
            listOf("Eggs", "Milk", "Bread").forEach { h.tick(key(it)) }
            h.end()
            listOf("Tea", "Milk", "Rice", "Eggs", "Coffee", "Bread").forEach { h.add(it) }
            val p = present(h.history)
            val unplaced = p.sections.filter { it.kind == SectionKind.UNPLACED }
            assertEquals(1, unplaced.size, "one group of unplaced entries")
            assertEquals(listOf("Tea", "Rice", "Coffee").map(::key), unplaced.single().entries.map { it.item }, "in the order added")
        }
    }
}
