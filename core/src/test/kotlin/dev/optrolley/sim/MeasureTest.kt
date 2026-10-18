package dev.optrolley.sim

import dev.optrolley.core.ItemKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class MeasureTest {
    private val a = ItemKey("a")
    private val b = ItemKey("b")
    private val c = ItemKey("c")
    private val d = ItemKey("d")

    @Test
    fun `tie groups chain, and 5 s apart is not a tie`() {
        val seq = listOf(Pick(a, 0), Pick(b, 4_999), Pick(c, 9_998), Pick(d, 14_998))
        assertEquals(listOf(setOf(a, b, c), setOf(d)), Measure.tieGroups(seq))
        assertEquals(listOf(setOf(a), setOf(b)), Measure.tieGroups(listOf(Pick(a, 0), Pick(b, 5_000))))
        assertEquals(emptyList(), Measure.tieGroups(emptyList()))
    }

    @Test
    fun `disorder counts pairs across tie groups shown the wrong way round`() {
        // Walk a | b c | d; shown d a c b. Counted: ab ac ad bd cd (bc tied). Wrong: ad bd cd.
        val walk = listOf(setOf(a), setOf(b, c), setOf(d))
        assertEquals(Tally(5, 3.0), Measure.presented(walk, listOf(d, a, c, b)))
        assertEquals(Tally(5, 0.0), Measure.presented(walk, listOf(a, c, b, d)))
        assertEquals(Tally(5, 5.0), Measure.presented(walk, listOf(d, b, c, a)))
        assertEquals(0.6, Tally(5, 3.0).normalised)
    }

    @Test
    fun `entries the walk did not touch are ignored, a lost one is an error`() {
        val walk = listOf(setOf(a), setOf(b))
        assertEquals(Tally(1, 1.0), Measure.presented(walk, listOf(c, b, d, a)))
        assertFailsWith<IllegalStateException> { Measure.presented(walk, listOf(b)) }
        assertFailsWith<IllegalArgumentException> { Measure.presented(walk, listOf(a, b, a)) }
    }

    @Test
    fun `paper is the list as written`() {
        val walk = listOf(setOf(a), setOf(b), setOf(c))
        assertEquals(Tally(3, 2.0), Measure.paper(walk, listOf(c, a, b)))
    }

    @Test
    fun `the floor is the layout's zone order, half a pair within a zone`() {
        // Zones [a b] [c] [d]; walk b, a, d, c. Pairs: ba within zone 0.5; dc against the layout 1.
        val layout = Layout(listOf(listOf(a, b), listOf(c), listOf(d)))
        val walk = listOf(setOf(b), setOf(a), setOf(d), setOf(c))
        assertEquals(Tally(6, 1.5), Measure.floor(walk, layout))
        // A reversed walk gets every cross-zone pair wrong.
        val back = listOf(setOf(d), setOf(c), setOf(a), setOf(b))
        assertEquals(Tally(6, 5.5), Measure.floor(back, layout))
    }

    @Test
    fun `the bound puts the unplaced ahead in add order`() {
        val layout = Layout(listOf(listOf(a, b), listOf(c), listOf(d)))
        val walk = listOf(setOf(a), setOf(b), setOf(c), setOf(d))
        // d unplaced: shown first though walked last, wrong against a, b and c.
        assertEquals(Tally(6, 3.5), Measure.bound(walk, layout, listOf(c, d, a, b), setOf(a, b, c)))
        // c and d unplaced, in add order d c: wrong against a, b, each other.
        assertEquals(Tally(6, 5.5), Measure.bound(walk, layout, listOf(d, c, a, b), setOf(a, b)))
        assertEquals(Measure.floor(walk, layout), Measure.bound(walk, layout, listOf(d, c, b, a), setOf(a, b, c, d)))
    }

    @Test
    fun `placement follows tier 3, from ticks`() {
        val trip = { ticks: List<Pick> ->
            SimTrip(1, 0, Layout(listOf(listOf(a, b, c, d))), false, emptyList(), ticks, ticks, emptyList(), emptyList(), emptyList())
        }
        val allTied = trip(listOf(Pick(a, 0), Pick(b, 1_000)))
        val apart = trip(listOf(Pick(c, 0), Pick(d, 1_000), Pick(a, 60_000)))
        assertEquals(emptySet(), Measure.placed(listOf(allTied)))
        assertEquals(setOf(a, c, d), Measure.placed(listOf(allTied, apart)))
    }

    @Test
    fun `normalised means skip walks with nothing to count`() {
        fun w(pairs: Int, shown: Double) = WalkScore("s", "x", 1, 1, null, pairs, shown, 0.0, 0.0, 0.0)
        assertEquals(0.35, O18.meanNormalised(listOf(w(10, 2.0), w(4, 2.0), w(0, 0.0))) { it.presented }, 1e-12)
    }

    @Test
    fun `O18 check flags paper beaten, and the margin at trip 5 and after a rearrangement`() {
        fun w(n: Int, since: Int?, shown: Double, paper: Double, floor: Double) =
            WalkScore("s", "x", 1, n, since, 100, shown, paper, floor, floor)
        val good = (1..6).map { w(it, null, if (it == 1) 50.0 else 10.0, 50.0, 6.0) }
        assertEquals(emptyList(), O18.check(good))
        // 10 against a floor of 5 is exactly the margin.
        assertEquals(emptyList(), O18.check(good.map { if (it.n == 5) it.copy(floor = 5.0) else it }))
        val tooFar = O18.check(good.map { if (it.n == 5) it.copy(floor = 4.9) else it })
        assertEquals(1, tooFar.size)
        assertTrue(tooFar.single().startsWith("trip 5:"))
        val worseThanPaper = O18.check(good.map { if (it.n == 3) it.copy(presented = 50.5) else it })
        assertEquals(1, worseThanPaper.size)
        assertTrue(worseThanPaper.single().startsWith("trip 3:"))

        val rearranged = (1..12).map { n -> w(n, (n - 6).takeIf { it >= 1 }, if (n == 11) 20.0 else 10.0, 50.0, 6.0) }
        val failures = O18.check(rearranged)
        assertEquals(1, failures.size)
        assertTrue(failures.single().startsWith("trip 5 after the rearrangement"))
    }
}
