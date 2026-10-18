package dev.optrolley.oracles

import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Routers
import dev.optrolley.core.Scope
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import dev.optrolley.core.replay
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Tier 3 group D: learning (T4, T3, F7–F8). O13–O17 judge a routing method,
 * so each runs once per router and reports per router. O19 is hard, and runs
 * under the default as well.
 */
class LearningOracles {

    private fun perVariant(name: String, variants: List<Variant>, body: (Variant) -> Unit): List<DynamicTest> =
        variants.map { v -> DynamicTest.dynamicTest("$name [${v.label}]") { body(v) } }

    private val store = StoreId("a")

    /** Gaps that separate ticks into different tie groups, the boundary included. */
    private fun apart(rnd: Random): Long = when (rnd.nextInt(5)) {
        0 -> Tier3.TIE_WINDOW_MS
        1 -> Tier3.TIE_WINDOW_MS + 1
        else -> rnd.nextLong(Tier3.TIE_WINDOW_MS, 120_000)
    }

    private fun anyGap(rnd: Random): Long =
        if (rnd.nextInt(4) == 0) rnd.nextLong(0, Tier3.TIE_WINDOW_MS) else apart(rnd)

    /** Ends a trip in one of the ways tier 3 allows, then comes back to [store]. */
    private fun endTrip(h: Hist, rnd: Random) {
        when (rnd.nextInt(4)) {
            0 -> h.select(null)
            1 -> h.idle()
            else -> h.end(EndReason.USER)
        }
    }

    /** One trip at [store] ticking [names] in order; whatever is not on the list is added first. */
    private fun trip(h: Hist, rnd: Random, names: List<String>, gap: (Random) -> Long, extras: List<String> = emptyList()) {
        (names + extras).shuffled(rnd).forEach { if (key(it) !in h.s.list) h.add(spelling(rnd, it)) }
        h.selectIfOther(store)
        names.forEachIndexed { i, n -> h.tick(key(n), if (i == 0) 60_000 else gap(rnd)) }
        endTrip(h, rnd)
    }

    private fun setupStore(h: Hist, rnd: Random) {
        val chain = if (rnd.nextBoolean()) ChainId("c").also { h.chain(it) } else null
        h.store(store, "Store A", chain)
        if (chain != null && rnd.nextBoolean()) h.store(StoreId("b"), "Store B", chain)
    }

    @TestFactory
    fun `O13 one trip is enough`() = perVariant("O13 one trip is enough", routerVariants) { v ->
        for (i in 0 until CASES) {
            val seed = 13_000L + i
            val rnd = Random(seed)
            val h = Hist(v.prefix())
            withCase("O13", v, i, seed, { h.history }) {
                setupStore(h, rnd)
                val names = POOL.shuffled(rnd).take(rnd.nextInt(2, 13))
                names.shuffled(rnd).forEach { h.add(spelling(rnd, it)) }
                h.select(store)
                names.forEach { h.tick(key(it), apart(rnd)) }
                endTrip(h, rnd)
                names.shuffled(rnd).forEach { h.add(spelling(rnd, it)) }
                h.selectIfOther(store)
                assertEquals(names.map(::key), present(h.history).keys(), "router ${v.label}: one trip without ties, then the same items")
            }
        }
    }

    @TestFactory
    fun `O14 consistent history is honoured`() = perVariant("O14 consistent history is honoured", routerVariants) { v ->
        for (i in 0 until CASES) {
            val seed = 14_000L + i
            val rnd = Random(seed)
            val h = Hist(v.prefix())
            withCase("O14", v, i, seed, { h.history }) {
                setupStore(h, rnd)
                val hidden = POOL.shuffled(rnd).take(rnd.nextInt(4, 13))
                repeat(rnd.nextInt(1, 7)) {
                    var walked = hidden.filter { rnd.nextInt(3) != 0 }
                    if (walked.size < 2) walked = hidden.shuffled(rnd).take(2).sortedBy { hidden.indexOf(it) }
                    trip(h, rnd, walked, { anyGap(it) }, extras = (hidden - walked.toSet()).filter { rnd.nextInt(4) == 0 })
                }
                hidden.filter { rnd.nextBoolean() }.forEach { if (key(it) !in h.s.list) h.add(spelling(rnd, it)) }
                h.selectIfOther(store)

                val forced = forcedPairs(h.s.ended.filter { it.store == store })
                val shown = present(h.history).keys()
                for ((a, b) in forced) {
                    if (a in shown && b in shown) {
                        assertTrue(shown.indexOf(a) < shown.indexOf(b), "router ${v.label}: every order agreeing with the trips puts $a before $b, but $shown")
                    }
                }
            }
        }
    }

    /** Pairs (a, b) that every total order agreeing with [trips] puts a before b: the transitive closure. */
    private fun forcedPairs(trips: List<EndedTrip>): List<Pair<ItemKey, ItemKey>> {
        val items = trips.flatMap { it.items }.distinct()
        val ix = items.withIndex().associate { it.value to it.index }
        val n = items.size
        val before = Array(n) { BooleanArray(n) }
        for (t in trips) for (i in t.groups.indices) for (j in i + 1 until t.groups.size) {
            for (a in t.groups[i]) for (b in t.groups[j]) before[ix.getValue(a)][ix.getValue(b)] = true
        }
        for (k in 0 until n) for (i in 0 until n) if (before[i][k]) for (j in 0 until n) if (before[k][j]) before[i][j] = true
        return (0 until n).flatMap { i -> (0 until n).filter { j -> before[i][j] }.map { j -> items[i] to items[j] } }
    }

    @TestFactory
    fun `O15 ties teach nothing`() = perVariant("O15 ties teach nothing", routerVariants) { v ->
        for (i in 0 until CASES) {
            val seed = 15_000L + i
            val rnd = Random(seed)
            val h = Hist(v.prefix())
            withCase("O15", v, i, seed, { h.history }) {
                Gen(rnd, h, GenConfig(routerChoices = false, unticks = false)).setup().run()
                val original = h.history
                val runs = tickRuns(original)
                val permuted = original.toMutableList()
                for (r in runs) {
                    val items = r.map { (original[it] as Observation.Ticked).item }.shuffled(rnd)
                    r.forEachIndexed { n, j -> permuted[j] = Observation.Ticked(original[j].at, items[n]) }
                }
                val cut = runs.flatMap { (it.first + 1)..it.last }.toSet()
                for (k in 0..original.size) {
                    if (k in cut) continue
                    assertEquals(
                        present(original.subList(0, k).toList()), present(permuted.subList(0, k).toList()),
                        "router ${v.label}: permuting ticks within tie windows changed the presentation after #${k - 1}",
                    )
                }
                val a = Hist(original).also { Gen(rnd, it).quiet() }
                val b = Hist(permuted).also { Gen(rnd, it).quiet() }
                for (x in listOf(a, b)) POOL.forEach { if (key(it) !in x.s.list) x.add(it) }
                for (s in a.selections()) {
                    assertEquals(present(a.at(s).history), present(b.at(s).history), "router ${v.label}: permuting ticks within tie windows changed what $s learned")
                }
            }
        }
    }

    /** Runs of consecutive ticks, each less than the tie window after the one before. */
    private fun tickRuns(h: List<Observation>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var start = -1
        for (j in h.indices) {
            val o = h[j]
            val prev = h.getOrNull(j - 1)
            if (o is Observation.Ticked && prev is Observation.Ticked && o.at - prev.at < Tier3.TIE_WINDOW_MS) {
                if (start < 0) start = j - 1
            } else if (start >= 0) {
                runs += start until j
                start = -1
            }
        }
        if (start >= 0) runs += start until h.size
        return runs
    }

    @TestFactory
    fun `O16 stores are independent`() = perVariant("O16 stores are independent", routerVariants) { v ->
        for (i in 0 until CASES) {
            val seed = 16_000L + i
            val rnd = Random(seed)
            val h = Hist(v.prefix())
            var w = h
            withCase("O16", v, i, seed, { w.history }) {
                val cfg = GenConfig(routerChoices = false)
                Gen(rnd, h, cfg).setup().run(rnd.nextInt(20, 90)).quiet()
                val prev = h.s.selected
                val other = h.s.stores.keys.filter { it != prev }.random(rnd)
                val base = h.fork()
                w = h.fork()

                // Arbitrary history at the other store, leaving the list as it was.
                w.select(other, gap = 60_000)
                val fresh = POOL.filter { key(it) !in w.s.list }.shuffled(rnd).take(rnd.nextInt(2, 7))
                fresh.forEach { w.add(spelling(rnd, it)) }
                val prior = h.s.list.filter { !w.s.saidNotHere(it, other) }.randomOrNull(rnd)
                if (prior != null && rnd.nextBoolean()) w.notHere(prior, Scope.STORE)
                fresh.shuffled(rnd).forEach { w.tick(key(it), anyGap(rnd)) }
                if (fresh.isNotEmpty()) w.end()
                w.select(prev)
                assertEquals(h.s.list, w.s.list, "the inserted history must leave the list as it was")

                val resume = w.now + Gen.DAY
                base.now = resume
                Gen(rnd, base, cfg).run(rnd.nextInt(10, 60))
                val tail = base.history.drop(h.size)
                tail.forEach { w.emit(it) }
                w.now = base.now

                val s = Shadow().also { sh -> h.history.forEach { sh.apply(it) } }
                val blockSize = w.size - base.size
                for (j in 0..tail.size) {
                    if (j > 0) s.apply(tail[j - 1])
                    if (s.selected == other) continue
                    assertEquals(
                        present(base.history.subList(0, h.size + j).toList()),
                        present(w.history.subList(0, h.size + blockSize + j).toList()),
                        "router ${v.label}: trips at $other changed the presentation at ${s.selected} after tail #$j",
                    )
                }
                for (x in listOf(base, w)) {
                    if (x.s.tripActive) x.end()
                    POOL.forEach { if (key(it) !in x.s.list) x.add(it) }
                }
                w.now = base.now
                for (sel in base.selections().filter { it != other }) {
                    assertEquals(present(base.at(sel).history), present(w.at(sel).history), "router ${v.label}: trips at $other changed what $sel learned")
                }
            }
        }
    }

    @TestFactory
    fun `O17 a rearrangement is absorbed`() = perVariant("O17 a rearrangement is absorbed", routerVariants) { v ->
        for (i in 0 until CASES) {
            val seed = 17_000L + i
            val rnd = Random(seed)
            val h = Hist(v.prefix())
            withCase("O17", v, i, seed, { h.history }) {
                setupStore(h, rnd)
                val old = POOL.shuffled(rnd).take(rnd.nextInt(4, 11))
                var new = old.shuffled(rnd)
                while (new == old) new = old.shuffled(rnd)
                if (i == 0) new = old.reversed()
                val oldTrips = when (i) {
                    0 -> 100
                    1 -> 1
                    else -> if (rnd.nextInt(4) == 0) rnd.nextInt(50, 101) else rnd.nextInt(1, 30)
                }
                repeat(oldTrips) {
                    var walked = if (i == 0) old else old.filter { rnd.nextInt(3) != 0 }
                    if (walked.size < 2) walked = old.shuffled(rnd).take(2).sortedBy { old.indexOf(it) }
                    trip(h, rnd, walked, { anyGap(it) })
                }
                // A pair the rearrangement reverses is in every new trip.
                val (x, y) = old.indices.flatMap { a -> old.indices.filter { it > a }.map { b -> old[a] to old[b] } }
                    .filter { (a, b) -> new.indexOf(a) > new.indexOf(b) }.random(rnd)
                val core = setOf(x, y)
                val newTrips = List(K) { if (i == 0) new else new.filter { it in core || rnd.nextBoolean() } }
                newTrips.forEach { trip(h, rnd, it, { apart(it) }) }
                old.forEach { if (key(it) !in h.s.list) h.add(spelling(rnd, it)) }
                h.selectIfOther(store)

                val shown = present(h.history).keys()
                val common = newTrips.map { it.toSet() }.reduce { a, b -> a intersect b }
                for (a in common) for (b in common) {
                    if (new.indexOf(a) < new.indexOf(b)) {
                        assertTrue(
                            shown.indexOf(key(a)) < shown.indexOf(key(b)),
                            "router ${v.label}: after $oldTrips trips one way and $K the new way, ${key(a)} should come before ${key(b)}: $shown",
                        )
                    }
                }
            }
        }
    }

    @TestFactory
    fun `O19 deterministic`() = perVariant("O19 deterministic", hardVariants) { v ->
        for (i in 0 until CASES) {
            val seed = 19_000L + i
            val rnd = Random(seed)
            val h = Hist(v.prefix())
            withCase("O19", v, i, seed, { h.history }) {
                Gen(rnd, h).setup().run()
                val hist = h.history
                val ks = (0..hist.size).shuffled(rnd).take(20) + hist.size
                val first = ks.associateWith { present(hist.subList(0, it).toList()) }
                // Unrelated work in between, so that nothing carried over can pass for a function of the inputs.
                repeat(3) {
                    val o = Hist(start = T0 + rnd.nextLong(0, 1_000_000_000))
                    Gen(Random(rnd.nextLong()), o).setup().run()
                    present(o.history)
                    replay(o.history)
                }
                val fresh = Routers(Routers.all.list, Routers.all.defaultId)
                for (k in ks.shuffled(rnd)) {
                    val copy = hist.subList(0, k).map { it.copied() }
                    assertEquals(first[k], present(copy), "router ${v.label}: the same history presented differently, prefix $k")
                    assertEquals(first[k], present(copy, fresh), "router ${v.label}: an equal set of routers presented differently, prefix $k")
                }
            }
        }
    }

    private companion object {
        /** Tier 3's K for O17. */
        const val K = 3
    }
}
