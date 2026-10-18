package dev.optrolley.oracles

import dev.optrolley.core.ChainId
import dev.optrolley.core.Entry
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Scope
import dev.optrolley.core.SectionKind.ELSEWHERE
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import dev.optrolley.core.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Tier 3 group A: list integrity (T1). */
class ListIntegrityOracles {

    private fun sorted(entries: List<Entry>) = entries.sortedBy { it.item.value }

    private fun assertConserved(p: Presentation, list: List<ItemKey>, h: List<Observation>, where: String) {
        val keys = p.keys()
        assertEquals(keys.size, keys.toSet().size, "an entry is presented more than once $where: $keys")
        assertEquals(list.toSet(), keys.toSet(), "the presented entries are not the list's $where")
        assertEquals(sorted(replay(h).entries), sorted(p.flat()), "the presented entries are not the replayed list's $where")
    }

    @Test
    fun `O1 conservation over random histories`() = property("O1", seed = 1) { c ->
        walk(c.hist.history) { st -> assertConserved(st.after, st.s.list, st.prefix, st.where()) }
        for (store in c.hist.selections()) {
            val f = c.hist.at(store)
            assertConserved(present(f.history), f.s.list, f.history, "with $store selected at the end")
        }
    }

    @Test
    fun `O2 selection only rearranges`() = property("O2", seed = 2) { c ->
        val h = c.hist.history
        val bases = quietPoints(h).shuffled(c.rnd).take(4).map { Hist(h.subList(0, it)) } + c.hist.fork().also { Gen(c.rnd, it).quiet() }
        for (base in bases) {
            val shown = base.selections().associateWith { sorted(present(base.at(it).history).flat()) }
            val (first, reference) = shown.entries.first()
            for ((store, entries) in shown) {
                assertEquals(reference, entries, "selecting $store instead of $first changed more than order and sectioning, after #${base.size - 1}")
            }
        }
    }

    @Test
    fun `O5 positive evidence only over random histories`() = property("O5", seed = 5) { c ->
        fun check(p: Presentation, s: Shadow, store: StoreId, where: String) {
            for (e in p.inSection(ELSEWHERE)) {
                assertTrue(
                    s.standing(e.item, store).any { it.index > s.lastPurchase(e.item, store) },
                    "${e.item} is in Elsewhere at $store $where, but the most recent statement about it there does not say it is not available",
                )
            }
        }
        walk(c.hist.history) { st -> st.s.selected?.let { check(st.after, st.s, it, st.where()) } }
        for (store in c.hist.s.stores.keys) {
            val f = c.hist.at(store)
            check(present(f.history), f.s, store, "at the end")
        }
    }

    @Test
    fun `O5 an entry left unticked says nothing about availability`() {
        for (v in hardVariants) withCase("O5", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a")); h.store(StoreId("b"))
            val milk = h.add("Milk"); val eggs = h.add("Eggs"); val bread = h.add("Bread")
            h.select(StoreId("a"))
            h.tick(milk); h.tick(eggs)
            h.end()
            h.add("Milk"); h.add("Eggs")
            for (store in listOf(StoreId("a"), StoreId("b"))) {
                val p = present(h.at(store).history)
                assertEquals(emptyList(), p.inSection(ELSEWHERE).map { it.item }, "nothing was said, yet something is in Elsewhere at $store")
                assertTrue(bread in p.keys())
            }
        }
    }

    @Test
    fun `O5 not-here follows the most recent statement, store scope at that store alone`() {
        for (v in hardVariants) withCase("O5", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val chain = ChainId("c")
            h.chain(chain)
            h.store(StoreId("a"), chain = chain); h.store(StoreId("b"), chain = chain); h.store(StoreId("x"))
            val olives = h.add("Olives"); h.add("Milk")
            h.select(StoreId("a"))
            h.notHere(olives, Scope.STORE)
            assertEquals(ELSEWHERE, present(h.history).sectionOf(olives), "not-here at a, seen at a")
            for (store in listOf(StoreId("b"), StoreId("x"), null)) {
                assertNotEquals(ELSEWHERE, present(h.at(store).history).sectionOf(olives), "a store-scoped not-here at a moved it at $store")
            }
            h.undoNotHere(olives, Scope.STORE)
            assertNotEquals(ELSEWHERE, present(h.history).sectionOf(olives), "withdrawn, yet still in Elsewhere at a")
        }
    }

    @Test
    fun `O6 purchase overrides over random histories`() = property("O6", seed = 6) { c ->
        fun check(p: Presentation, s: Shadow, store: StoreId, where: String) {
            for (e in p.flat()) {
                val bought = s.lastPurchase(e.item, store)
                if (bought >= 0 && s.standing(e.item, store).none { it.index > bought }) {
                    assertNotEquals(ELSEWHERE, p.sectionOf(e.item), "${e.item} was bought at $store (#$bought) and nothing since says otherwise, yet it is in Elsewhere $where")
                }
            }
        }
        walk(c.hist.history) { st -> st.s.selected?.let { check(st.after, st.s, it, st.where()) } }
        for (store in c.hist.s.stores.keys) {
            val f = c.hist.at(store)
            check(present(f.history), f.s, store, "at the end")
        }
    }

    @Test
    fun `O6 a purchase clears not-here from the next trip, at either scope`() {
        for (v in hardVariants) for (scope in Scope.entries) withCase("O6", v, scope.ordinal, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val chain = ChainId("c")
            h.chain(chain)
            h.store(StoreId("a"), chain = chain)
            val olives = h.add("Olives"); val milk = h.add("Milk")
            h.select(StoreId("a"))
            h.notHere(olives, scope)
            h.tick(milk); h.tick(olives)
            h.end()
            h.add("olives ")
            assertNotEquals(ELSEWHERE, present(h.history).sectionOf(olives), "bought at a after a $scope not-here, yet in Elsewhere there")
        }
    }
}
