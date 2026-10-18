package dev.optrolley.sim

import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Scope
import kotlin.random.Random

/**
 * The noise sources of tier 4 §6, each set independently. Probabilities are
 * per pick ([batch], [late]) or per trip ([reverse]); zero turns one off.
 */
data class Noise(
    /** Chance that a pick starts a batch: it and the next few are ticked together at a pause. */
    val batch: Double = 0.0,
    val batchSize: IntRange = 2..5,
    /** Chance that a pick is ticked only after some later picks. */
    val late: Double = 0.0,
    val lateBy: IntRange = 1..4,
    /** Chance that a trip walks the zones backwards (the other door, H6). */
    val reverse: Double = 0.0,
    /** First trip walked in a rearranged shop; null for none. */
    val rearrangeAt: Int? = null,
    val rearrangeZones: Double = 0.3,
    val rearrangeItems: Double = 0.1,
    /** How many unstocked items the shopper wants (at most [Shop.UNSTOCKED]). */
    val absent: Int = 0,
    /** Per trip, chance that each wanted unstocked item is on the list. */
    val absentOnList: Double = 0.3,
    /** Chance the shopper says "not here" when an unstocked item is on the list. */
    val absentNotHere: Double = 0.5,
    /** Chance an unstocked entry is removed after the trip, bought elsewhere. */
    val absentRemoved: Double = 0.5,
) {
    init {
        require(absent in 0..Shop.UNSTOCKED)
    }
}

/** The shopper's habits: how likely each stocked item is to be on a list. */
class Habits(val propensity: Map<ItemKey, Double>) {
    private val items = propensity.keys.sortedBy { it.value }

    /** A list of stocked items, its size clamped to [LIST_SIZE]. */
    fun draw(random: Random): List<ItemKey> {
        val chosen = items.filter { random.nextDouble() < propensity.getValue(it) }.toMutableList()
        val rest = (items - chosen.toSet()).toMutableList()
        while (chosen.size < LIST_SIZE.first) {
            var r = random.nextDouble() * rest.sumOf { propensity.getValue(it) }
            val i = rest.indexOfFirst { r -= propensity.getValue(it); r < 0 }.let { if (it < 0) rest.lastIndex else it }
            chosen.add(rest.removeAt(i))
        }
        return if (chosen.size > LIST_SIZE.last) chosen.shuffled(random).take(LIST_SIZE.last) else chosen
    }

    companion object {
        val LIST_SIZE = 8..30

        /** [ShopSpec.staples] items bought most trips; the rest occasionally. */
        fun generate(shop: Shop, random: Random): Habits {
            val items = shop.layout.items.sortedBy { it.value }.shuffled(random)
            return Habits(
                items.mapIndexed { i, item ->
                    item to if (i < shop.spec.staples) random.nextDouble(0.5, 0.9) else random.nextDouble(0.02, 0.10)
                }.toMap(),
            )
        }
    }
}

/** A moment in the shop: an item picked up, or ticked. */
data class Pick(val item: ItemKey, val at: Long)

data class SimTrip(
    /** Trip number at this store, from 1. */
    val number: Int,
    /** Length of the history prefix that holds this trip's list and selection, and nothing of its walk. */
    val presentAt: Int,
    /** The layout this trip was walked in. */
    val layout: Layout,
    val reversed: Boolean,
    /** The list when the walk began, in add order. */
    val listed: List<ItemKey>,
    /** What the shopper did: items in the order picked up. The yardstick. */
    val walk: List<Pick>,
    /** What the app was told: ticks in the order recorded. The evidence. */
    val ticks: List<Pick>,
    val batches: List<List<ItemKey>>,
    val late: List<ItemKey>,
    /** Unstocked entries on the list this trip. */
    val absent: List<ItemKey>,
)

class SimRun(val shop: Shop, val noise: Noise, val history: List<Observation>, val trips: List<SimTrip>)

/**
 * The synthetic shopper. Each source of randomness has its own stream, so
 * turning one noise source on leaves every other draw as it was: the same
 * seed gives the same shop, lists and walks with and without late or batched
 * ticks.
 */
object Shopper {
    private const val START = 1_767_225_600_000L // 2026-01-01T00:00Z
    private const val S = 1_000L
    private const val MIN = 60 * S
    private const val H = 60 * MIN

    fun simulate(spec: ShopSpec, noise: Noise, seed: Long, trips: Int): SimRun {
        fun stream(k: Int) = Random(seed * 1_000_003 + k)
        val shop = Shop.generate(spec, stream(0))
        val habits = Habits.generate(shop, stream(1))
        val lists = stream(2)
        val clock = stream(3)
        val walking = stream(4)
        val ticking = stream(5)
        val batching = stream(6)
        val lateness = stream(7)
        val reversing = stream(8)
        val rearranging = stream(9)
        val absence = stream(10)

        val history = mutableListOf<Observation>()
        val simTrips = mutableListOf<SimTrip>()
        val store = shop.store
        val wantedUnstocked = shop.unstocked.take(noise.absent)
        val onList = LinkedHashSet<ItemKey>()
        val saidNotHere = mutableSetOf<ItemKey>()
        var layout = shop.layout
        var now = START

        history += Observation.StoreCreated(now, store, spec.name)

        for (n in 1..trips) {
            if (n == noise.rearrangeAt) layout = layout.rearranged(rearranging, noise.rearrangeZones, noise.rearrangeItems)

            // The list, written at home in whatever order things come to mind.
            val wanted = habits.draw(lists).shuffled(lists).toMutableList()
            for (item in wantedUnstocked) {
                if (absence.nextDouble() < noise.absentOnList && item !in onList) {
                    wanted.add(absence.nextInt(wanted.size + 1), item)
                }
            }
            now += clock.nextLong(12 * H, 96 * H)
            for (item in wanted) {
                now += clock.nextLong(10 * S, 3 * H)
                history += Observation.Added(now, shop.names.getValue(item))
                onList += item
            }
            now += clock.nextLong(1 * H, 24 * H)
            history += Observation.Selected(now, store)
            val presentAt = history.size
            val listed = onList.toList()

            // The walk: every zone in order, picking within a zone in no order.
            val reversed = reversing.nextDouble() < noise.reverse
            val zoneOrder = layout.zones.indices.let { if (reversed) it.reversed() else it.toList() }
            val walk = mutableListOf<Pick>()
            now += clock.nextLong(1 * MIN, 5 * MIN)
            for (z in zoneOrder) {
                now += walking.nextLong(15 * S, 60 * S)
                val here = layout.zones[z].filter { it in onList }.shuffled(walking)
                here.forEachIndexed { i, item ->
                    if (i > 0) now += walking.nextLong(2 * S, 12 * S)
                    walk += Pick(item, now)
                }
            }

            // The ticks: just after each pick, unless batched or late.
            val tickAt = LongArray(walk.size) { walk[it].at + ticking.nextLong(300, 2_500) }
            val batches = mutableListOf<List<ItemKey>>()
            val inBatch = BooleanArray(walk.size)
            var i = 0
            while (i < walk.size) {
                if (batching.nextDouble() < noise.batch) {
                    val size = batching.nextInt(noise.batchSize.first, noise.batchSize.last + 1)
                    val members = (i until minOf(i + size, walk.size)).toList()
                    var t = walk[members.last()].at + batching.nextLong(1 * S, 4 * S)
                    for (m in members.shuffled(batching)) {
                        tickAt[m] = t
                        inBatch[m] = true
                        t += batching.nextLong(400, 2_000)
                    }
                    batches += members.map { walk[it].item }
                    i = members.last() + 1
                } else {
                    i++
                }
            }
            val onTime = tickAt.copyOf()
            val late = mutableListOf<ItemKey>()
            for (p in walk.indices) {
                if (lateness.nextDouble() < noise.late && !inBatch[p]) {
                    val anchor = p + lateness.nextInt(noise.lateBy.first, noise.lateBy.last + 1)
                    tickAt[p] = if (anchor < walk.size) {
                        onTime[anchor] + lateness.nextLong(1 * S, 10 * S)
                    } else {
                        onTime.max() + lateness.nextLong(20 * S, 2 * MIN) // remembered at the till
                    }
                    late += walk[p].item
                }
            }
            val ticks = walk.indices.sortedWith(compareBy({ tickAt[it] }, { it })).map { Pick(walk[it].item, tickAt[it]) }

            // Unstocked entries: looked for, never found, sometimes said so.
            val absentHere = listed.filter { it in wantedUnstocked }
            val during = mutableListOf<Observation>()
            ticks.mapTo(during) { Observation.Ticked(it.at, it.item) }
            for (item in absentHere) {
                if (item !in saidNotHere && absence.nextDouble() < noise.absentNotHere) {
                    val at = absence.nextLong(walk.first().at, ticks.last().at + 1)
                    during += Observation.NotHere(at, item, store, Scope.STORE)
                    saidNotHere += item
                }
            }
            history += during.sortedBy { it.at } // stable: ticks keep their order
            now = maxOf(now, ticks.last().at) + clock.nextLong(30 * S, 5 * MIN)
            history += Observation.TripEnded(now, EndReason.USER)
            onList -= walk.map { it.item }.toSet()

            for (item in absentHere) {
                if (absence.nextDouble() < noise.absentRemoved) {
                    now += clock.nextLong(1 * H, 12 * H)
                    history += Observation.Removed(now, item)
                    onList -= item
                }
            }

            simTrips += SimTrip(n, presentAt, layout, reversed, listed, walk, ticks, batches, late, absentHere)
        }
        return SimRun(shop, noise, history, simTrips)
    }
}
