package dev.optrolley.core.routers

import dev.optrolley.core.ItemKey
import dev.optrolley.core.Router
import dev.optrolley.core.TripOrder
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.pow

/**
 * The precedence graph: every trip says, of each pair it ticked in different
 * tie groups, which came first. Each pair gets a direction from that
 * evidence, and the order shown is a linear extension of as many of those
 * directions as can hold together, the most trustworthy first ("ranked
 * pairs"). A consistent history has no conflicts, so every direction holds
 * (O13, O14).
 *
 * Contradictions are settled per pair, by recency counted in the trips that
 * ordered that pair (O19: never the clock):
 * - A pair's direction is a vote over all its trips, each worth half as much
 *   as the one [K] trips newer. That is the slowest decay at which the [K]
 *   newest trips outvote all older ones together, however many: one odd trip
 *   (a reversed walk, a late tick) is outvoted by the habit, [K] in a row win.
 * - A pair whose [K] newest trips agree is *settled*. Settled pairs are locked
 *   before all others, newest evidence first, so a rearrangement seen [K]
 *   times overrides older settled pairs that would contradict it (O17).
 */
object Precedence : Router {
    override val id = "precedence"
    override val label = "Precedence"

    /** Tier 3's K for O17. */
    private const val K = 3

    /** Weight ratio between a pair's successive trips, newest to oldest: halves every [K]. */
    private val DECAY = 0.5.pow(1.0 / K)

    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
        if (items.isEmpty()) return emptyList()
        // Indices follow key order so that nothing depends on set iteration order.
        val universe = HashSet<ItemKey>(items)
        for (trip in trips) for (group in trip.groups) universe.addAll(group)
        val keys = universe.sortedBy { it.value }
        val n = keys.size
        val index = HashMap<ItemKey, Int>(n * 2)
        keys.forEachIndexed { i, k -> index[k] = i }

        // Pair (i, j), i < j, at i * n + j; a positive vote means i first.
        val count = IntArray(n * n)
        val vote = DoubleArray(n * n)
        val latest = IntArray(n * n)
        val newestSign = ByteArray(n * n)
        val unsettled = BooleanArray(n * n)
        val weight = DoubleArray(trips.size + 1) { DECAY.pow(it) }

        for (t in trips.indices.reversed()) {
            val groups = trips[t].groups
            if (groups.size < 2) continue
            val idx = groups.map { g -> IntArray(g.size).also { a -> g.forEachIndexed { k, item -> a[k] = index.getValue(item) } } }
            for (gi in idx.indices) for (gj in gi + 1 until idx.size) {
                for (a in idx[gi]) for (b in idx[gj]) {
                    if (a == b) continue
                    val p = if (a < b) a * n + b else b * n + a
                    val sign: Byte = if (a < b) 1 else -1
                    val r = count[p]
                    vote[p] += sign * weight[r]
                    if (r == 0) {
                        latest[p] = t
                        newestSign[p] = sign
                    } else if (r < K && sign != newestSign[p]) {
                        unsettled[p] = true
                    }
                    count[p] = r + 1
                }
            }
        }

        val edges = ArrayList<Edge>()
        for (i in 0 until n) for (j in i + 1 until n) {
            val p = i * n + j
            if (count[p] == 0) continue
            val settled = count[p] >= K && !unsettled[p]
            // A settled pair takes its newest direction outright; the vote agrees
            // in exact arithmetic, and this keeps rounding out of it.
            val forward = if (settled) newestSign[p] > 0 else vote[p] > 0
            if (!settled && vote[p] == 0.0) continue
            edges += if (forward) Edge(i, j, settled, latest[p], abs(vote[p])) else Edge(j, i, settled, latest[p], abs(vote[p]))
        }
        edges.sortWith(LOCK_ORDER)

        // Locked edges must stay acyclic: reach[u] is everything u is locked before.
        val words = (n + 63) ushr 6
        val reach = LongArray(n * words)
        fun reaches(u: Int, v: Int) = reach[u * words + (v ushr 6)] and (1L shl (v and 63)) != 0L
        val successors = Array(n) { ArrayList<Int>(0) }
        val indegree = IntArray(n)
        for (e in edges) {
            val u = e.from
            val v = e.to
            if (reaches(v, u) || reaches(u, v)) continue
            successors[u] += v
            indegree[v]++
            for (w in 0 until n) {
                if (w != u && !reaches(w, u)) continue
                val wo = w * words
                val vo = v * words
                for (k in 0 until words) reach[wo + k] = reach[wo + k] or reach[vo + k]
                reach[wo + (v ushr 6)] = reach[wo + (v ushr 6)] or (1L shl (v and 63))
            }
        }

        // Any linear extension satisfies the locked edges; among the free choices take key order.
        val ready = PriorityQueue<Int>()
        for (i in 0 until n) if (indegree[i] == 0) ready += i
        val out = ArrayList<ItemKey>(items.size)
        while (ready.isNotEmpty()) {
            val u = ready.poll()
            if (keys[u] in items) out += keys[u]
            for (v in successors[u]) if (--indegree[v] == 0) ready += v
        }
        return out
    }

    private class Edge(val from: Int, val to: Int, val settled: Boolean, val latest: Int, val margin: Double)

    /** Settled pairs first, newest first; then the rest by strength of vote. */
    private val LOCK_ORDER = Comparator<Edge> { x, y ->
        when {
            x.settled != y.settled -> if (x.settled) -1 else 1
            x.settled && x.latest != y.latest -> y.latest.compareTo(x.latest)
            x.margin != y.margin -> y.margin.compareTo(x.margin)
            x.latest != y.latest -> y.latest.compareTo(x.latest)
            x.from != y.from -> x.from.compareTo(y.from)
            else -> x.to.compareTo(y.to)
        }
    }
}
