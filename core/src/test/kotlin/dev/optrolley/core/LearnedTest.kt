package dev.optrolley.core

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What is kept between trip ends (tier 4 §4.2) is the record's and nothing more (O21), and makes a tap cheap (P3). */
class LearnedTest {
    @Test
    fun `O21 dropping what was learned and working it out again changes nothing shown`() {
        repeat(40) { seed ->
            val actions = ItemsTest().randomActions(Random(500 + seed), 120)
            val fold = Fold()
            for ((i, action) in actions.withIndex()) {
                fold.apply(action)
                if (i % 5 != 0) continue
                // Asked first, so that what is kept is what is compared.
                val shown = Triple(present(fold), confidence(fold), score(fold))
                fold.forget()
                assertEquals(shown, Triple(present(fold), confidence(fold), score(fold)), "seed $seed after ${i + 1} actions")
            }
        }
    }

    @Test
    fun `what is kept is what a fold of the same actions with nothing kept would show`() {
        repeat(40) { seed ->
            val actions = ItemsTest().randomActions(Random(900 + seed), 120)
            val fold = Fold()
            for ((i, action) in actions.withIndex()) {
                fold.apply(action)
                // Asked after every action, so anything kept too long is caught at the next one.
                val prefix = actions.subList(0, i + 1)
                assertEquals(present(prefix), present(fold), "seed $seed after ${i + 1} actions")
                assertEquals(confidence(prefix), confidence(fold), "seed $seed after ${i + 1} actions")
                if (i % 10 == 0) assertEquals(score(prefix), score(fold), "seed $seed after ${i + 1} actions")
            }
        }
    }

    @Test
    fun `P3 a tap costs no more after twenty years of trips than after one`() {
        val short = Bench.run(Export.encode(benchHistory(52)), lab = false)
        val long = Bench.run(Export.encode(benchHistory(1040)), lab = false)
        assertTrue(long.tap <= 2 * short.tap, "a tap took ${long.tap} ms with 1040 trips on record and ${short.tap} ms with 52")
    }
}
