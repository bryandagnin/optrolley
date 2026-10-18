package dev.optrolley.core.routers

import dev.optrolley.core.ItemKey
import dev.optrolley.core.Router
import dev.optrolley.core.TripOrder
import java.util.PriorityQueue

/**
 * The precedence graph: each trip says, of every pair it ticked in different
 * tie groups, which came first. The order shown is a linear extension of as
 * many of those pairs as hold together, the most recently seen first. A
 * consistent history has no conflicts, so every pair holds (O13, O14).
 *
 * Contradictions, with recency counted in trips (O19):
 * - A pair goes the way it went the last time the two were ordered. The shop
 *   changes in pieces (tier 1 §5); a trip that has seen the new order is the
 *   best word on it, and waiting to be outvoted would leave the list wrong
 *   for trips after a rearrangement.
 * - Except a trip that runs against the order learned so far on more pairs
 *   than it runs with was walked the other way (H6): it is read as its mirror
 *   instead of unlearning everything. The orientation shown is the one most
 *   trips were walked in.
 * - A pair ordered the same way in its last [K] trips takes that order as
 *   ticked, mirrored or not, and is locked before any other pair, so the
 *   mirror reading never holds off a rearrangement seen [K] times (O17).
 * - Among the rest, newer evidence is locked first; within one trip, pairs
 *   ticked closer together first, which keeps an item next to the neighbours
 *   it was last seen with when their part of the shop moves.
 */
object Precedence : Router {
    override val id = "precedence"
    override val label = "Precedence"

    /** Tier 3's K for O17. */
    private const val K = 3

    private const val NONE: Byte = 0

    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
        if (items.isEmpty()) return emptyList()
        // Indices follow key order, so nothing depends on set iteration order.
        val universe = HashSet<ItemKey>(items)
        for (trip in trips) for (group in trip.groups) universe.addAll(group)
        val keys = universe.sortedBy { it.value }
        val n = keys.size
        val index = HashMap<ItemKey, Int>(n * 2)
        keys.forEachIndexed { i, k -> index[k] = i }

        // Pair (i, j), i < j, at i * n + j. Signs: +1 means i first.
        val read = ByteArray(n * n) // newest direction, mirrored where the trip was
        val ticked = ByteArray(n * n) // newest direction as ticked
        val run = IntArray(n * n) // trips in a row ticking it [ticked]'s way
        val standing = IntArray(n * n) // trips in a row reading it [read]'s way
        val latest = IntArray(n * n)
        val gap = IntArray(n * n) // tie groups apart, the latest time
        // Each item's place along its latest trip, 0 to 1, as read: the tie-break.
        val place = DoubleArray(n) { 2.0 }
        var mirrored = 0
        var straight = 0
        var pairs = 0

        for (t in trips.indices) {
            val groups = trips[t].groups
            if (groups.size < 2) continue
            val idx = groups.map { g -> IntArray(g.size).also { a -> g.forEachIndexed { k, item -> a[k] = index.getValue(item) } } }
            var against = 0
            for (gi in idx.indices) for (gj in gi + 1 until idx.size) for (a in idx[gi]) for (b in idx[gj]) {
                val known = if (a < b) read[a * n + b].toInt() else -read[b * n + a]
                if (known < 0) against++ else if (known > 0) against--
            }
            val mirror = against > 0
            if (against > 0) mirrored++ else if (against < 0) straight++
            for (g in idx.indices) {
                val x = g.toDouble() / (idx.size - 1)
                for (a in idx[g]) place[a] = if (mirror) 1 - x else x
            }
            for (gi in idx.indices) for (gj in gi + 1 until idx.size) for (a in idx[gi]) for (b in idx[gj]) {
                val p = if (a < b) a * n + b else b * n + a
                val sign: Byte = if (a < b) 1 else -1
                if (ticked[p] == NONE) pairs++
                run[p] = if (ticked[p] == sign) run[p] + 1 else 1
                ticked[p] = sign
                val r: Byte = if (mirror) (-sign).toByte() else sign
                standing[p] = if (read[p] == r) standing[p] + 1 else 1
                read[p] = r
                latest[p] = t
                gap[p] = gj - gi
            }
        }
        val flipAll = mirrored > straight
        if (flipAll) for (i in 0 until n) if (place[i] <= 1.0) place[i] = 1 - place[i]

        // Lock order packed into one long per pair, ascending: settled first,
        // then newest, then tightest, then longest standing, then pair index.
        val candidates = LongArray(pairs)
        var count = 0
        for (i in 0 until n) for (j in i + 1 until n) {
            val p = i * n + j
            if (ticked[p] == NONE) continue
            val settled = run[p] >= K
            val age = (trips.size - 1 - latest[p]).coerceAtMost(AGE_MAX)
            val doubt = K - standing[p].coerceAtMost(K)
            candidates[count++] = ((if (settled) 0L else 1L) shl 62) or (age.toLong() shl 44) or
                (gap[p].coerceAtMost(GAP_MAX).toLong() shl 33) or (doubt.toLong() shl 31) or p.toLong()
        }
        candidates.sort(0, count)

        // reach[u] holds everything u is locked before; an edge closing a cycle is dropped.
        val words = (n + 63) ushr 6
        val reach = LongArray(n * words)
        fun reaches(u: Int, v: Int) = reach[u * words + (v ushr 6)] and (1L shl (v and 63)) != 0L
        val successors = Array(n) { IntArray(2) }
        val outdegree = IntArray(n)
        val indegree = IntArray(n)
        for (c in 0 until count) {
            val p = (candidates[c] and PAIR_MASK).toInt()
            val i = p / n
            val j = p % n
            val settled = candidates[c] ushr 62 == 0L
            var forward = if (settled) ticked[p] > 0 else read[p] > 0
            if (!settled && flipAll) forward = !forward
            val u = if (forward) i else j
            val v = if (forward) j else i
            if (reaches(v, u) || reaches(u, v)) continue
            if (outdegree[u] == successors[u].size) successors[u] = successors[u].copyOf(outdegree[u] * 2)
            successors[u][outdegree[u]++] = v
            indegree[v]++
            val vo = v * words
            val vBit = 1L shl (v and 63)
            for (w in 0 until n) {
                if (w != u && !reaches(w, u)) continue
                val wo = w * words
                for (k in 0 until words) reach[wo + k] = reach[wo + k] or reach[vo + k]
                reach[wo + (v ushr 6)] = reach[wo + (v ushr 6)] or vBit
            }
        }

        // Any linear extension keeps the locked pairs; free choices go by place, then key.
        val ready = PriorityQueue<Int>(compareBy<Int>({ place[it] }, { it }))
        for (i in 0 until n) if (indegree[i] == 0) ready += i
        val out = ArrayList<ItemKey>(items.size)
        while (ready.isNotEmpty()) {
            val u = ready.poll()
            if (keys[u] in items) out += keys[u]
            for (k in 0 until outdegree[u]) {
                val v = successors[u][k]
                if (--indegree[v] == 0) ready += v
            }
        }
        return out
    }

    // Field widths of the packed lock order; older or wider than the cap only ties.
    private const val AGE_MAX = (1 shl 18) - 1
    private const val GAP_MAX = (1 shl 11) - 1
    private const val PAIR_MASK = (1L shl 31) - 1
}
