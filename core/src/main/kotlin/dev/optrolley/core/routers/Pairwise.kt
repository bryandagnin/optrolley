package dev.optrolley.core.routers

import dev.optrolley.core.ItemKey
import dev.optrolley.core.Router
import dev.optrolley.core.TripOrder
import java.util.PriorityQueue
import kotlin.math.ln
import kotlin.math.roundToLong

/**
 * Pairwise strength model. Every trip says, of each pair of items in different
 * tie groups, which came first; recent trips count for more. A Bradley–Terry
 * fit turns those outcomes into one strength per item, which places items that
 * were never seen together.
 *
 * Ordering by strength alone can contradict a pair that was observed directly
 * and never disputed (O14): an item can lose overall yet have beaten one
 * particular rival. So direct evidence is locked in first, strongest margin
 * first, skipping any pair that would close a cycle (Tideman's ranked pairs),
 * and the strengths only choose among orders that respect what was locked.
 * A consistent history has no cycles, so all of it is honoured.
 */
object Pairwise : Router {
    override val id = "pairwise"
    override val label = "Pairwise"

    /**
     * Weight of a trip relative to the next one at the store (O19: recency in
     * trips). O17 needs the newest K = 3 trips to outweigh any older history:
     * their margin on the flipped pair is at least (1 + r + r²) − r³/(1 − r),
     * and any locked path that could block it must cross some pair the new
     * order contradicts, whose margin is at most r³/(1 − r). The newer wins
     * whenever r³ < 1/3, i.e. r < 0.693; this is the slowest decay that does.
     */
    private const val DECAY = 0.69

    /**
     * Pseudo-games each item plays against a fixed reference, as a share of
     * its own (decayed) games, half of them won. It only keeps strengths
     * finite when an item always came first or last; being relative, it does
     * not let decay make an old item look less certain than a recent one.
     */
    private const val PRIOR = 0.1

    /** Fixed, so the fit never depends on a convergence test's rounding. */
    private const val ITERATIONS = 100

    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
        if (items.isEmpty()) return emptyList()
        // Index every item with evidence, by name: nothing below depends on
        // set or map iteration order (O15, O19).
        val keys = trips.asSequence().filter { it.groups.size > 1 }.flatMap { t -> t.groups.flatten() }
            .distinct().sortedBy { it.value }.toList()
        val n = keys.size
        val index = HashMap<ItemKey, Int>(2 * n)
        keys.forEachIndexed { i, k -> index[k] = i }

        // ahead[a * n + b]: decayed weight of trips with a ticked before b.
        val ahead = DoubleArray(n * n)
        var w = 1.0
        for (t in trips.indices.reversed()) {
            val groups = trips[t].groups
            if (groups.size > 1) {
                val gs = groups.map { g -> IntArray(g.size).also { a -> g.forEachIndexed { i, k -> a[i] = index.getValue(k) } } }
                for (i in gs.indices) for (j in i + 1 until gs.size) {
                    for (a in gs[i]) for (b in gs[j]) if (a != b) ahead[a * n + b] += w
                }
            }
            // Never zero, so an undisputed pair however old still counts (O14).
            w = maxOf(w * DECAY, Double.MIN_VALUE)
        }

        val strength = fit(n, ahead)
        val locked = lock(n, ahead)
        val ranked = linearise(n, locked, strength)

        val out = ArrayList<ItemKey>(items.size)
        for (i in ranked) if (keys[i] in items) out += keys[i]
        // Anything without evidence goes last; the presentation never asks for such.
        items.filter { it !in index }.sortedBy { it.value }.forEach { out += it }
        return out
    }

    /**
     * Bradley–Terry strengths by Hunter's MM iteration, a Jacobi update so
     * the result does not depend on the order items are visited. Returned as
     * quantised log-strengths, so that values equal but for rounding compare
     * equal and fall to the name tie-break.
     */
    private fun fit(n: Int, ahead: DoubleArray): LongArray {
        val wins = DoubleArray(n)
        val prior = DoubleArray(n)
        val rivals = arrayOfNulls<IntArray>(n)
        val games = arrayOfNulls<DoubleArray>(n)
        for (i in 0 until n) {
            var count = 0
            for (j in 0 until n) if (ahead[i * n + j] + ahead[j * n + i] > 0) count++
            val r = IntArray(count)
            val g = DoubleArray(count)
            var k = 0
            var total = 0.0
            for (j in 0 until n) {
                val played = ahead[i * n + j] + ahead[j * n + i]
                if (played > 0) {
                    r[k] = j
                    g[k] = played
                    k++
                    total += played
                    wins[i] += ahead[i * n + j]
                }
            }
            rivals[i] = r
            games[i] = g
            prior[i] = PRIOR * total
        }
        var p = DoubleArray(n) { 1.0 }
        repeat(ITERATIONS) {
            val next = DoubleArray(n)
            for (i in 0 until n) {
                val r = rivals[i]!!
                val g = games[i]!!
                var d = prior[i] / (p[i] + 1.0)
                for (k in r.indices) d += g[k] / (p[i] + p[r[k]])
                next[i] = (wins[i] + prior[i] / 2) / d
            }
            p = next
        }
        return LongArray(n) { (ln(p[it]) * 1e9).roundToLong() }
    }

    /**
     * Ranked pairs: each pair's net margin, largest first, locked unless it
     * would close a cycle with those already locked. Equal margins go by
     * index, which only matters between pairs that conflict.
     */
    private fun lock(n: Int, ahead: DoubleArray): List<IntArray> {
        val from = ArrayList<Int>()
        val to = ArrayList<Int>()
        val margin = ArrayList<Double>()
        for (a in 0 until n) for (b in a + 1 until n) {
            val m = ahead[a * n + b] - ahead[b * n + a]
            if (m > 0) {
                from += a; to += b; margin += m
            } else if (m < 0) {
                from += b; to += a; margin += -m
            }
        }
        val byMargin = margin.indices.sortedWith(
            compareByDescending<Int> { margin[it] }.thenBy { from[it] }.thenBy { to[it] },
        )
        val words = (n + 63) ushr 6
        // reach[x * words ..]: the items x is locked ahead of, transitively.
        val reach = LongArray(n * words)
        fun reaches(x: Int, y: Int) = reach[x * words + (y ushr 6)] and (1L shl (y and 63)) != 0L
        val next = Array(n) { ArrayList<Int>() }
        for (e in byMargin) {
            val u = from[e]
            val v = to[e]
            if (reaches(v, u) || reaches(u, v)) continue // closes a cycle, or already implied
            next[u] += v
            for (x in 0 until n) {
                if (x == u || reaches(x, u)) {
                    val xo = x * words
                    val vo = v * words
                    for (k in 0 until words) reach[xo + k] = reach[xo + k] or reach[vo + k]
                    reach[xo + (v ushr 6)] = reach[xo + (v ushr 6)] or (1L shl (v and 63))
                }
            }
        }
        return next.map { it.toIntArray() }
    }

    /** The locked order, choosing the strongest free item at each step, then by name. */
    private fun linearise(n: Int, next: List<IntArray>, strength: LongArray): IntArray {
        val indegree = IntArray(n)
        for (u in 0 until n) for (v in next[u]) indegree[v]++
        val free = PriorityQueue<Int>(compareByDescending<Int> { strength[it] }.thenBy { it })
        for (i in 0 until n) if (indegree[i] == 0) free += i
        val out = IntArray(n)
        var k = 0
        while (free.isNotEmpty()) {
            val u = free.poll()
            out[k++] = u
            for (v in next[u]) if (--indegree[v] == 0) free += v
        }
        return out
    }
}
