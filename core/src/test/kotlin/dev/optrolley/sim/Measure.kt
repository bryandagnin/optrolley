package dev.optrolley.sim

import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Routers
import dev.optrolley.core.Rules
import dev.optrolley.core.present

/** Counted pairs, and how many of them an order gets the wrong way round. */
data class Tally(val pairs: Int, val disorder: Double) {
    val normalised: Double get() = if (pairs == 0) 0.0 else disorder / pairs
}

/** One trip of one simulated run, scored. Normalised figures are shares of [pairs]. */
data class WalkScore(
    val scenario: String,
    val shop: String,
    val seed: Long,
    val n: Int,
    /** Trip number counted from the rearrangement (its first trip is 1); null before it or without one. */
    val sinceRearrangement: Int?,
    val pairs: Int,
    val presented: Double,
    val paper: Double,
    val floor: Double,
    val bound: Double,
)

object Measure {
    /**
     * Ordered pairs within the same zone count this much against a layout
     * order: the walk picks within a zone in no order, so any order fixed
     * before the walk gets such a pair wrong half the time.
     */
    const val WITHIN_ZONE = 0.5

    /** Tie groups of a time-ordered sequence, chained (tier 3). */
    fun tieGroups(seq: List<Pick>): List<Set<ItemKey>> {
        val groups = mutableListOf<MutableSet<ItemKey>>()
        var last = Long.MIN_VALUE
        for (p in seq) {
            require(p.at >= last) { "sequence out of time order at ${p.item.value}" }
            if (groups.isEmpty() || p.at - last >= Rules.TIE_WINDOW_MS) groups += mutableSetOf<ItemKey>()
            groups.last() += p.item
            last = p.at
        }
        return groups
    }

    /**
     * Pairs of [walk]'s items in different groups, and how many [rank] puts
     * the other way round. Equal ranks count [tie] each.
     */
    fun disorder(walk: List<Set<ItemKey>>, tie: Double = 0.0, rank: (ItemKey) -> Int): Tally {
        var pairs = 0
        var disorder = 0.0
        val ranked = walk.map { g -> g.map(rank) }
        for (i in ranked.indices) for (j in i + 1 until ranked.size) {
            for (a in ranked[i]) for (b in ranked[j]) {
                pairs++
                if (a > b) disorder += 1.0 else if (a == b) disorder += tie
            }
        }
        return Tally(pairs, disorder)
    }

    /** Disorder of the order shown, which must hold every walked item (T1). */
    fun presented(walk: List<Set<ItemKey>>, shown: List<ItemKey>): Tally {
        val index = shown.withIndex().associate { (i, item) -> item to i }
        require(index.size == shown.size) { "presentation shows an entry twice" }
        return disorder(walk) { index[it] ?: error("presentation lost ${it.value}") }
    }

    /** Paper: the list as written (T3). */
    fun paper(walk: List<Set<ItemKey>>, listed: List<ItemKey>): Tally = presented(walk, listed)

    /** The noise floor: the true layout's order against the walk (O18). */
    fun floor(walk: List<Set<ItemKey>>, layout: Layout): Tally =
        disorder(walk, WITHIN_ZONE) { layout.zoneOf(it) }

    /**
     * The best any router could do: the true layout for placed items,
     * with the unplaced ahead in add order as tier 2 §6 requires. What
     * separates it from the floor is outside a router's reach.
     */
    fun bound(walk: List<Set<ItemKey>>, layout: Layout, listed: List<ItemKey>, placed: Set<ItemKey>): Tally {
        val addIndex = listed.withIndex().associate { (i, item) -> item to i }
        return disorder(walk, WITHIN_ZONE) { if (it in placed) listed.size + layout.zoneOf(it) else addIndex.getValue(it) }
    }

    /** Items placed by [trips] under tier 3's definition, from their ticks. */
    fun placed(trips: List<SimTrip>): Set<ItemKey> =
        trips.map { tieGroups(it.ticks) }.filter { it.size >= 2 }.flatMap { it.flatten() }.toSet()

    fun flatten(p: Presentation): List<ItemKey> = p.sections.flatMap { s -> s.entries.map { it.item } }

    /** The router-independent figures for every trip of [run]. */
    fun baselines(scenario: String, run: SimRun, seed: Long): List<WalkScore> =
        run.trips.map { trip ->
            val walk = tieGroups(trip.walk)
            val placed = placed(run.trips.subList(0, trip.number - 1))
            val f = floor(walk, trip.layout)
            WalkScore(
                scenario = scenario,
                shop = run.shop.spec.name,
                seed = seed,
                n = trip.number,
                sinceRearrangement = run.noise.rearrangeAt?.let { r -> (trip.number - r + 1).takeIf { it >= 1 } },
                pairs = f.pairs,
                presented = Double.NaN,
                paper = paper(walk, trip.listed).disorder,
                floor = f.disorder,
                bound = bound(walk, trip.layout, trip.listed, placed).disorder,
            )
        }

    /**
     * [baselines] with what the app presents at the start of each trip, the
     * history having chosen [routerId] before anything else.
     */
    fun score(baselines: List<WalkScore>, run: SimRun, routerId: String, routers: Routers = Routers.all): List<WalkScore> {
        val history = listOf(Observation.RouterChosen(run.history.first().at, routerId)) + run.history
        return run.trips.zip(baselines) { trip, base ->
            val shown = flatten(present(history.subList(0, trip.presentAt + 1), routers))
            base.copy(presented = presented(tieGroups(trip.walk), shown).disorder)
        }
    }
}
