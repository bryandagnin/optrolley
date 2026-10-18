package dev.optrolley.sim

import dev.optrolley.core.Routers
import java.util.Locale

data class Scenario(val name: String, val noise: Noise)

/** The reference simulation suite (tier 5 §3). Changing it changes what O18 measures. */
object ReferenceSuite {
    const val TRIPS = 20
    const val REARRANGE_AT = 10

    val shops = listOf(
        ShopSpec("corner", zones = 6, items = 36, staples = 10),
        ShopSpec("supermarket", zones = 12, items = 96, staples = 18),
        ShopSpec("superstore", zones = 20, items = 180, staples = 22),
    )

    val seeds = (1L..8L).toList()

    private const val BATCH = 0.1
    private const val LATE = 0.1
    private const val REVERSE = 0.25
    private const val ABSENT = 3

    val scenarios = listOf(
        Scenario("clean", Noise()),
        Scenario("batched", Noise(batch = BATCH)),
        Scenario("late", Noise(late = LATE)),
        Scenario("reversed", Noise(reverse = REVERSE)),
        Scenario("rearranged", Noise(rearrangeAt = REARRANGE_AT)),
        Scenario("absent", Noise(absent = ABSENT)),
        Scenario("everything", Noise(BATCH, late = LATE, reverse = REVERSE, rearrangeAt = REARRANGE_AT, absent = ABSENT)),
    )

    class Case(val scenario: Scenario, val seed: Long, val run: SimRun, val baselines: List<WalkScore>)

    /** Every run of the suite. A shop's seed is shared across scenarios, so they differ only by noise. */
    val cases: List<Case> by lazy {
        scenarios.flatMap { sc ->
            shops.flatMapIndexed { s, spec ->
                seeds.map { seed ->
                    val runSeed = seed * 100 + s
                    val run = Shopper.simulate(spec, sc.noise, runSeed, TRIPS)
                    Case(sc, runSeed, run, Measure.baselines(sc.name, run, runSeed))
                }
            }
        }
    }

    val baselines: List<WalkScore> get() = cases.flatMap { it.baselines }

    fun score(routerId: String, routers: Routers = Routers.all): List<WalkScore> =
        cases.flatMap { Measure.score(it.baselines, it.run, routerId, routers) }
}

/** O18 over a suite's scores: what fails, empty if it holds. */
object O18 {
    const val N = 5
    const val MARGIN = 0.05

    fun check(scores: List<WalkScore>): List<String> {
        val failures = mutableListOf<String>()
        for ((n, at) in scores.groupBy { it.n }.toSortedMap()) {
            val shown = at.sumOf { it.presented }
            val paper = at.sumOf { it.paper }
            if (shown > paper) {
                failures += "trip $n: mean disorder ${f(shown / at.size)} > paper ${f(paper / at.size)}"
            }
        }
        failures += margin("trip $N", scores.filter { it.n == N })
        val again = scores.filter { it.sinceRearrangement == N }
        if (scores.any { it.sinceRearrangement != null }) failures += margin("trip $N after the rearrangement", again)
        return failures
    }

    /** The margin is judged over placed pairs only: the rest no router can order (amended O18). */
    private fun margin(what: String, at: List<WalkScore>): List<String> {
        if (at.none { it.placedPairs > 0 }) return listOf("$what: no placed pairs to measure")
        val shown = meanPlaced(at) { it.presentedPlaced }
        val floor = meanPlaced(at) { it.floorPlaced }
        return if (shown - floor > MARGIN) {
            listOf("$what: mean normalised disorder over placed pairs ${f(shown)} exceeds noise floor ${f(floor)} by more than $MARGIN")
        } else {
            emptyList()
        }
    }

    /** Mean over walks with any counted pair of [value] as a share of the walk's pairs. */
    fun meanNormalised(at: List<WalkScore>, value: (WalkScore) -> Double): Double = mean(at, { it.pairs }, value)

    /** The same over placed pairs. */
    fun meanPlaced(at: List<WalkScore>, value: (WalkScore) -> Double): Double = mean(at, { it.placedPairs }, value)

    private fun mean(at: List<WalkScore>, pairs: (WalkScore) -> Int, value: (WalkScore) -> Double): Double {
        val counted = at.filter { pairs(it) > 0 }
        return if (counted.isEmpty()) 0.0 else counted.sumOf { value(it) / pairs(it) } / counted.size
    }

    private fun f(x: Double) = String.format(Locale.ROOT, "%.3f", x)
}
