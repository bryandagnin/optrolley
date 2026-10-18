package dev.optrolley.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** Random histories of every kind of observation, to shake out the edge combinations. */
class RandomHistoryTest {
    private fun history(rnd: Random): List<Observation> {
        val h = Hist().chain(CHAIN).store(A, CHAIN).store(B, CHAIN).store(C)
        val names = listOf("milk", "Milk ", "bread", "eggs", "tea", "jam", "rice")
        val stores = listOf(A, B, C, null)
        repeat(rnd.nextInt(5, 120)) {
            val name = names.random(rnd)
            when (rnd.nextInt(12)) {
                0, 1 -> h.add(name)
                2 -> h.edit(name, names.random(rnd), "q${rnd.nextInt(3)}", null)
                3 -> h.remove(name)
                4, 5, 6 -> h.tick(name, rnd.nextLong(0, 12_000))
                7 -> h.untick(name)
                8 -> h.select(stores.random(rnd))
                9 -> h.end()
                10 -> h.notHere(name, listOf(A, B, C).random(rnd), Scope.entries.random(rnd))
                11 -> h.undoNotHere(name, listOf(A, B, C).random(rnd), Scope.entries.random(rnd))
            }
        }
        return h.list
    }

    @Test
    fun everyEntryIsPresentedOnceAtEveryStore() {
        val rnd = Random(20261018)
        repeat(300) {
            val h = history(rnd)
            for (store in listOf(A, B, C, null)) {
                // Selecting may end a trip, so the list is the one after selecting.
                val selected = h + Observation.Selected(Long.MAX_VALUE, store)
                val entries = replay(selected).entries
                val p = present(selected)
                val shown = p.sections.flatMap { it.entries }
                assertEquals(entries.size, entries.map { it.item }.toSet().size, "one entry per item")
                assertEquals(entries.toSet(), shown.toSet())
                assertEquals(entries.size, shown.size)
                assertEquals(p.sections.map { it.kind }.distinct(), p.sections.map { it.kind })
            }
        }
    }

    @Test
    fun replayIsDeterministicAndSurvivesExport() {
        val rnd = Random(99)
        repeat(200) {
            val h = history(rnd)
            assertEquals(replay(h), replay(h.toList()))
            assertEquals(present(h), present(Export.decode(Export.encode(h))))
            score(h)
        }
    }
}
