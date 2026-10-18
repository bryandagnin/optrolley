package dev.optrolley.core.routers

import dev.optrolley.core.ItemKey
import dev.optrolley.core.Router
import dev.optrolley.core.TripOrder
import java.util.PriorityQueue

/**
 * Orders items by their mean relative position across trips: 0 for the first
 * tick of a trip, 1 for the last. Three things keep the mean honest:
 *
 * - A trip that runs against what has been learned is taken as walked from
 *   the other end (the other door) and counted reversed, so it adds evidence
 *   instead of cancelling it. The run follows whichever direction most trips
 *   were walked.
 * - When the previous trip predicts a trip better than the whole learned
 *   order does, the shop has changed: the mean starts again from those two
 *   trips. Older evidence still places items not seen since. This is how
 *   recent trips come to outweigh old ones, counted in trips (O19).
 * - The mean only chooses among the orders the evidence allows. A consistent
 *   history is honoured outright (O14); otherwise any pair that each of the
 *   last [K] trips put the same way round is (O17).
 */
object Positional : Router {
    override val id = "positional"
    override val label = "Positional"

    /** Tier 3's O17: this many trips in a new order must win a pair. */
    private const val K = 3

    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
        if (items.isEmpty()) return emptyList()
        // Every item in the history, not just those asked for: a forced pair
        // can run through an item that is not.
        val all = HashSet<ItemKey>(items)
        for (trip in trips) for (g in trip.groups) all.addAll(g)
        val keys = all.sortedBy { it.value }
        val index = HashMap<ItemKey, Int>(keys.size * 2)
        keys.forEachIndexed { i, key -> index[key] = i }
        val n = keys.size

        val score = scores(trips, index, n)
        val rank = IntArray(n)
        (0 until n).sortedWith(compareBy({ score[it] }, { it })).forEachIndexed { r, i -> rank[i] = r }

        val history = Graph(n)
        for (trip in trips) {
            val gs = trip.groups.map { g -> g.map { index.getValue(it) } }
            for (j in 1 until gs.size) for (a in gs[j - 1]) for (b in gs[j]) history.add(a, b)
        }
        val order = history.sort(rank) ?: settled(trips, index, n).sort(rank)!!
        return order.map { keys[it] }.filter { it in items }
    }

    /** A trip's items as (index, relative position), ties at their midpoint, by index. */
    private fun positions(trip: TripOrder, index: Map<ItemKey, Int>): List<Pair<Int, Double>> {
        val size = trip.groups.sumOf { it.size }
        val out = ArrayList<Pair<Int, Double>>(size)
        var before = 0
        for (g in trip.groups) {
            val pos = (2.0 * before + g.size - 1) / (2.0 * (size - 1))
            for (item in g) out += index.getValue(item) to pos
            before += g.size
        }
        // Sorted so that no sum depends on the order ticks were made in a tie (O15).
        return out.sortedBy { it.first }
    }

    /** Each item's mean position since the latest change that saw it; +inf if never placed. */
    private fun scores(trips: List<TripOrder>, index: Map<ItemKey, Int>, n: Int): DoubleArray {
        val sum = DoubleArray(n)
        val count = IntArray(n)
        val epochOf = IntArray(n) { -1 }
        var epoch = 0
        var straight = 0
        var reversed = 0
        fun add(ps: List<Pair<Int, Double>>) {
            for ((i, pos) in ps) {
                if (epochOf[i] != epoch) {
                    epochOf[i] = epoch
                    sum[i] = 0.0
                    count[i] = 0
                }
                sum[i] += pos
                count[i]++
            }
        }
        val learned = { i: Int -> if (count[i] > 0) sum[i] / count[i] else null }
        var prev: List<Pair<Int, Double>>? = null
        for (trip in trips) {
            // A trip of one tie group says nothing about order.
            if (trip.groups.size < 2) continue
            val raw = positions(trip, index)
            val flipped = against(raw, learned)
            if (flipped) reversed++ else straight++
            val ps = if (flipped) raw.map { (i, p) -> i to 1 - p } else raw
            val last = prev
            if (last != null && changed(last, ps, learned)) {
                epoch++
                add(last)
            }
            add(ps)
            prev = ps
        }
        val score = DoubleArray(n) { if (count[it] == 0) Double.POSITIVE_INFINITY else sum[it] / count[it] }
        if (reversed > straight) for (i in 0 until n) if (count[i] > 0) score[i] = 1 - score[i]
        return score
    }

    /** Whether a trip runs against [ref]'s positions: negative covariance with them. */
    private fun against(ps: List<Pair<Int, Double>>, ref: (Int) -> Double?): Boolean {
        val shared = ps.mapNotNull { (i, p) -> ref(i)?.let { p to it } }
        if (shared.size < 2) return false
        val mp = shared.sumOf { it.first } / shared.size
        val mr = shared.sumOf { it.second } / shared.size
        return shared.sumOf { (p, r) -> (p - mp) * (r - mr) } < 0
    }

    /**
     * Whether [prev] gets more of [cur]'s pairs right that the learned order
     * gets wrong than the other way about. No margin: a needless restart
     * costs a little noise tolerance, a missed one leaves a stale order.
     */
    private fun changed(prev: List<Pair<Int, Double>>, cur: List<Pair<Int, Double>>, learnedAt: (Int) -> Double?): Boolean {
        val before = HashMap<Int, Double>()
        for ((i, p) in prev) before[i] = p
        val shared = cur.filter { it.first in before }
        var prevBetter = 0
        var learnedBetter = 0
        for (u in shared.indices) for (v in u + 1 until shared.size) {
            val (a, pa) = shared[u]
            val (b, pb) = shared[v]
            val walked = Math.signum(pb - pa)
            if (walked == 0.0) continue
            val learned = Math.signum(learnedAt(b)!! - learnedAt(a)!!)
            val last = Math.signum(before.getValue(b) - before.getValue(a))
            if (learned == -walked && last == walked) prevBetter++
            if (learned == walked && last == -walked) learnedBetter++
        }
        return prevBetter > learnedBetter
    }

    /**
     * Pairs that each of the last [K] trips held, never in the other order and
     * at least once in this one. Acyclic: around a cycle every one of those
     * trips would tie every member.
     */
    private fun settled(trips: List<TripOrder>, index: Map<ItemKey, Int>, n: Int): Graph {
        val graph = Graph(n)
        if (trips.size < K) return graph
        val recent = trips.subList(trips.size - K, trips.size).map { trip ->
            val group = HashMap<Int, Int>()
            trip.groups.forEachIndexed { g, items -> for (item in items) group[index.getValue(item)] = g }
            group
        }
        val common = recent.first().keys.filter { i -> recent.all { i in it } }.sorted()
        for (a in common) for (b in common) {
            if (a == b) continue
            if (recent.all { it.getValue(a) <= it.getValue(b) } && recent.any { it.getValue(a) < it.getValue(b) }) {
                graph.add(a, b)
            }
        }
        return graph
    }

    private class Graph(val n: Int) {
        val succ = Array(n) { ArrayList<Int>() }
        val indegree = IntArray(n)

        fun add(a: Int, b: Int) {
            succ[a] += b
            indegree[b]++
        }

        /** Kahn's sort taking the best-ranked free item each step; null if cyclic. */
        fun sort(rank: IntArray): List<Int>? {
            val left = indegree.copyOf()
            val free = PriorityQueue<Int>(compareBy { rank[it] })
            for (i in 0 until n) if (left[i] == 0) free += i
            val out = ArrayList<Int>(n)
            while (free.isNotEmpty()) {
                val i = free.poll()
                out += i
                for (j in succ[i]) if (--left[j] == 0) free += j
            }
            return if (out.size == n) out else null
        }
    }
}
