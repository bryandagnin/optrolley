package dev.optrolley.oracles

import dev.optrolley.core.Confidence
import dev.optrolley.core.Confidence.SETTLED
import dev.optrolley.core.Confidence.SETTLING
import dev.optrolley.core.Confidence.TENTATIVE
import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Router
import dev.optrolley.core.Routers
import dev.optrolley.core.Scope
import dev.optrolley.core.SectionKind
import dev.optrolley.core.StoreId
import dev.optrolley.core.TripOrder
import dev.optrolley.core.confidence
import dev.optrolley.core.present
import dev.optrolley.core.replay
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.util.TreeSet
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Tier 3 group I: confidence (F5, D16), O50–O54, written from tier 3 §I
 * before any implementation. Every one of them is hard.
 *
 * The definition is restated below as a reference, from the text alone, and
 * worked by hand in the O51 unit cases, which also check the reference.
 */
class ConfidenceOracles {

    // O50 ---------------------------------------------------------------------------------------

    @Test
    fun `O50 run only over random histories`() = property("O50", seed = 50) { c ->
        val h = c.hist.history
        for (k in sample(c.rnd, (0..h.size).toList())) runOnly(h.subList(0, k).toList(), Routers.all, "prefix $k")
        for (w in widest(c.hist, c.rnd)) runOnly(w, Routers.all, "everything on the list, at ${replay(w).selected}")
    }

    @Test
    fun `O50 unplaced, elsewhere and no store have no confidence`() {
        val h = shopped(listOf("a b c"), list = "abcxy")
        h.notHere(key("c"))
        hand("O50", h) {
            val routers = only(Fixed("fixed", letters("abc")))
            val p = present(h.history, routers)
            assertEquals(listOf(key("x"), key("y")), p.inSection(SectionKind.UNPLACED).map { it.item }, "scenario: x and y are unplaced")
            assertEquals(listOf(key("c")), p.inSection(SectionKind.ELSEWHERE).map { it.item }, "scenario: c is elsewhere")
            assertEquals(setOf(key("a"), key("b")), confidence(h.history, routers).keys, "only the Run's entries have a confidence")
            h.select(null)
            assertEquals(emptyMap(), confidence(h.history, routers), "with no store selected nothing has a confidence")
        }
    }

    private fun runOnly(h: List<Observation>, routers: Routers, where: String) {
        val p = present(h, routers)
        val got = confidence(h, routers)
        val run = p.inSection(SectionKind.RUN).map { it.item }
        assertEquals(run.toSet(), got.keys, "$where: the entries with a confidence are not the Run's (store ${p.store})")
        if (p.store == null) assertTrue(got.isEmpty(), "$where: no store is selected, yet $got")
    }

    // O51 over generated histories ------------------------------------------------------------

    @Test
    fun `O51 as defined over random histories`() {
        val tally = HashMap<Confidence, Int>()
        property("O51", seed = 51) { c -> asDefined(c, Routers.all, tally) }
        notVacuous("O51", tally)
    }

    /** Orders unrelated to what was walked: many trips run against them, so the mirror rule is exercised. */
    @Test
    fun `O51 as defined under orders unrelated to the walks`() {
        val tally = HashMap<Confidence, Int>()
        val routers = mapOf("by-name" to only(ByName("by-name")), "by-name-reversed" to only(ByNameReversed))
        property(
            "O51", seed = 5101, variants = routers.keys.map { Variant(it, null) },
            cfg = GenConfig(routerChoices = false),
        ) { c -> asDefined(c, routers.getValue(c.variant.label), tally) }
        notVacuous("O51", tally)
    }

    /**
     * Judged only with no trip in progress, so that O51 is not set against
     * O53 over what "now in the Run" means mid-trip (raised, not settled).
     */
    private fun asDefined(c: Case, routers: Routers, tally: MutableMap<Confidence, Int>) {
        val h = c.hist.history
        for (k in sample(c.rnd, quietPoints(h))) judge(h.subList(0, k).toList(), routers, tally, "prefix $k")
        for (w in widest(c.hist, c.rnd)) judge(w, routers, tally, "everything on the list, at ${replay(w).selected}")
    }

    private fun judge(h: List<Observation>, routers: Routers, tally: MutableMap<Confidence, Int>, where: String) {
        val p = present(h, routers)
        val got = confidence(h, routers)
        val run = p.inSection(SectionKind.RUN).map { it.item }
        for ((k, last) in lastWitnesses(h, p)) {
            val want = rule(last) ?: continue
            tally.merge(want, 1, Int::plus)
            assertEquals(want, got[k], "$where: $k with last witnesses' agreements $last in run $run")
        }
    }

    private fun notVacuous(oracle: String, tally: Map<Confidence, Int>) {
        for (c in Confidence.entries) {
            assertTrue((tally[c] ?: 0) > 0, "$oracle: the generated histories never gave a $c entry, so they test nothing about it: $tally")
        }
    }

    // O51 by hand: Run a b c d e f unless said, presented so by a fixed router -------------------

    @Test
    fun `O51 exactly 0_8 with three witnesses is settled (4 of 5 each)`() =
        expectAt("O51 exactly 0.8, 4/5 x3", "abcdef", List(3) { "b a c d e f" }, mapOf('a' to SETTLED))

    @Test
    fun `O51 exactly 0_8 with three witnesses is settled (1, 1, 2 of 5)`() =
        expectAt("O51 exactly 0.8, 1 1 2/5", "abcdef", listOf("a b c d e f", "a b c d e f", "b c d a e f"), mapOf('a' to SETTLED))

    @Test
    fun `O51 just below 0_8 is settling`() =
        expectAt("O51 11/15", "abcdef", listOf("b a c d e f", "b a c d e f", "b c a d e f"), mapOf('a' to SETTLING))

    @Test
    fun `O51 exactly 0_6 is settling (3 of 5 each)`() =
        expectAt("O51 exactly 0.6, 3/5 x3", "abcdef", List(3) { "b c a d e f" }, mapOf('a' to SETTLING))

    @Test
    fun `O51 exactly 0_6 is settling (1, 4 of 5, 0)`() =
        expectAt("O51 exactly 0.6, 1 4/5 0", "abcdef", listOf("a b c d e f", "b a c d e f", "b c d e f a"), mapOf('a' to SETTLING))

    @Test
    fun `O51 below 0_6 is tentative even with three witnesses`() =
        expectAt("O51 8/15", "abcdef", listOf("b c a d e f", "b c a d e f", "b c d a e f"), mapOf('a' to TENTATIVE))

    @Test
    fun `O51 below 0_6 with two witnesses is tentative`() =
        expectAt("O51 2/5 x2", "abcdef", List(2) { "b c d a e f" }, mapOf('a' to TENTATIVE))

    @Test
    fun `O51 one witness is tentative even at agreement 1`() =
        expectAt("O51 one witness", "abcdef", listOf("a b c d e f"), "abcdef".associateWith { TENTATIVE })

    @Test
    fun `O51 two witnesses at agreement 1 are settling`() =
        expectAt("O51 two witnesses", "abcdef", List(2) { "a b c d e f" }, "abcdef".associateWith { SETTLING })

    @Test
    fun `O51 three witnesses at agreement 1 are settled`() =
        expectAt("O51 three witnesses", "abcdef", List(3) { "a b c d e f" }, "abcdef".associateWith { SETTLED })

    @Test
    fun `O51 only the last three witnesses count, oldest dropped`() =
        expectAt("O51 last three, old 0 dropped", "abcdef", listOf("b c d e f a") + List(3) { "a b c d e f" }, mapOf('a' to SETTLED))

    @Test
    fun `O51 only the last three witnesses count, newest kept`() =
        expectAt("O51 last three, new 0 kept", "abcdef", List(3) { "a b c d e f" } + "b c d e f a", mapOf('a' to SETTLING))

    /** Last witnesses are witnesses, not trips: later trips that witness nothing for a do not displace them. */
    @Test
    fun `O51 last witnesses skip trips that are no witness`() =
        expectAt(
            "O51 last witnesses are witnesses", "abcdef",
            listOf("b c d e f a", "a b c d e f", "a b c d e f", "a b c d e f", "a", "b c"),
            mapOf('a' to SETTLED),
        )

    @Test
    fun `O51 a reversed trip is read in mirror`() =
        expectAt("O51 reversed trip", "abcdef", listOf("a b c d e f", "a b c d e f", "f e d c b a"), "abcdef".associateWith { SETTLED })

    /** Mirroring is decided over all pairs among the Run's items, not the item's own. */
    @Test
    fun `O51 the mirror is decided by the whole run`() =
        expectAt(
            "O51 mirror over all pairs", "abcdef", listOf("a b c d e f", "a b c d e f", "a f e d c b"),
            // Mirrored, the third trip reads b c d e f a: a 0, b 4/5.
            mapOf('a' to SETTLING, 'b' to SETTLED),
        )

    /** Against the presented order for exactly half the pairs is not more than half: not mirrored. */
    @Test
    fun `O51 exactly half against is not mirrored`() =
        expectAt(
            "O51 half against", "abcd", listOf("a b c d", "a b c d", "d a b c"),
            // Unmirrored: a 1 1 2/3 = 8/9; d 1 1 0.
            mapOf('a' to SETTLED, 'd' to SETTLING),
        )

    /** Agreement is with the order presented, not with what was learned: a run the walks all reverse reads them in mirror. */
    @Test
    fun `O51 trips against a reversed run are all read in mirror`() =
        expectAt("O51 run reversed", "cba", List(3) { "a b c" }, "abc".associateWith { SETTLED })

    @Test
    fun `O51 pairs within one tie group are not counted`() =
        // b is ticked a second before a, in the same group: a's only pair is with c.
        expectAt("O51 tie pairs", "abc", List(3) { "ba c" }, mapOf('a' to SETTLED, 'b' to SETTLED))

    @Test
    fun `O51 an item ticked alone is no witness`() =
        expectAt("O51 alone", "abc", listOf("a b c", "a b c", "a"), "abc".associateWith { SETTLING })

    @Test
    fun `O51 an item only tied with others is no witness`() =
        expectAt("O51 all tied", "abc", listOf("a b c", "a b c", "abc"), "abc".associateWith { SETTLING })

    /** The third trip witnesses a only through d: while d is in the Run it counts. */
    @Test
    fun `O51 an item in the Run makes a witness`() =
        expectAt("O51 through d", "abcd", listOf("a b c", "a b c", "a d"), mapOf('a' to SETTLED, 'd' to TENTATIVE))

    @Test
    fun `O51 an item no longer on the list makes no witness`() =
        expectAt("O51 d not re-added", "abcd", listOf("a b c", "a b c", "a d"), mapOf('a' to SETTLING), list = "abc", run = "abc")

    @Test
    fun `O51 removing an entry changes another's confidence`() =
        expectAt("O51 d removed", "abcd", listOf("a b c", "a b c", "a d"), mapOf('a' to SETTLING), run = "abc") { it.remove(key("d")) }

    @Test
    fun `O51 an entry moved to Elsewhere makes no witness`() =
        expectAt("O51 d not here", "abcd", listOf("a b c", "a b c", "a d"), mapOf('a' to SETTLING), run = "abc") { it.notHere(key("d")) }

    /** Trips with no store, and at another store, witness nothing at this one. */
    @Test
    fun `O51 only trips at the store are witnesses`() = expectAt(
        "O51 other stores", "abc", List(2) { "a b c" }, "abc".associateWith { SETTLING },
        before = { h ->
            h.store(B, "Store B")
            h.select(B)
            walkLetters(h, "a b c")
            h.select(null)
            walkLetters(h, "a b c")
        },
    )

    /**
     * Shops at A, then checks [expect] against the reference (a check of this
     * file's reading of tier 3 §I) and against the code under test.
     */
    private fun expectAt(
        what: String,
        order: String,
        walks: List<String>,
        expect: Map<Char, Confidence>,
        list: String = order,
        run: String = order,
        before: (Hist) -> Unit = {},
        then: (Hist) -> Unit = {},
    ) {
        val h = Hist()
        before(h)
        shopped(walks, list, h)
        then(h)
        hand(what, h) { expectIn(what, h, order, run, expect) }
    }

    private fun expectIn(what: String, h: Hist, order: String, run: String, expect: Map<Char, Confidence>) {
        val routers = only(Fixed("fixed", letters(order)))
        val history = h.history
        val p = present(history, routers)
        assertEquals(letters(run), p.inSection(SectionKind.RUN).map { it.item }, "$what: scenario's run")
        val last = lastWitnesses(history, p)
        val want = expect.mapKeys { key(it.key.toString()) }
        for ((k, c) in want) {
            assertEquals(c, rule(last[k].orEmpty()), "$what: the reference's reading of tier 3 §I disagrees with the worked value for $k (${last[k]})")
        }
        val got = confidence(history, routers)
        for ((k, c) in want) assertEquals(c, got[k], "$what: $k, last witnesses' agreements ${last[k]}")
    }

    /** Adds the scenario's history to an assertion failure. */
    private fun hand(what: String, h: Hist, body: () -> Unit) {
        try {
            body()
        } catch (e: AssertionError) {
            throw AssertionError("$what failed: ${e.message}\nHistory:\n${dump(h.history)}", e)
        }
    }

    // O52 ---------------------------------------------------------------------------------------

    @Test
    fun `O52 a router and its echo give the same confidence`() = property(
        "O52", seed = 52, variants = Routers.all.list.map { Variant(it.id, null) }, cfg = GenConfig(routerChoices = false),
    ) { c ->
        val r = Routers.all.list.first { it.id == c.variant.label }
        sameConfidence(c, only(r), only(Echo(r)))
    }

    @Test
    fun `O52 one fixed order by different means gives the same confidence`() = property(
        "O52", seed = 5201, variants = listOf(Variant("by-name", null)), cfg = GenConfig(routerChoices = false),
    ) { c -> sameConfidence(c, only(ByName("by-name")), only(ByNameTree)) }

    private fun sameConfidence(c: Case, one: Routers, other: Routers) {
        val h = c.hist.history
        val points = sample(c.rnd, (0..h.size).toList()).map { h.subList(0, it).toList() } + widest(c.hist, c.rnd)
        for (x in points) {
            assertEquals(present(x, one), present(x, other), "the two methods must present the same (test setup)")
            assertEquals(confidence(x, one), confidence(x, other), "two methods presenting the same run gave different confidence at ${replay(x).selected}")
        }
    }

    @Test
    fun `O52 built-in routers whose runs coincide give the same confidence`() {
        var coincided = 0
        property("O52", seed = 5202, variants = listOf(Variant("each", null)), cfg = GenConfig(routerChoices = false)) { c ->
            val h = c.hist.history
            val points = sample(c.rnd, (0..h.size).toList()).map { h.subList(0, it).toList() } + widest(c.hist, c.rnd)
            for (x in points) {
                val byRun = Routers.all.list.groupBy { present(x, only(it)).inSection(SectionKind.RUN).map { e -> e.item } }
                for ((run, rs) in byRun) {
                    if (rs.size < 2 || run.isEmpty()) continue
                    coincided++
                    val first = confidence(x, only(rs[0]))
                    for (r in rs.drop(1)) {
                        assertEquals(first, confidence(x, only(r)), "${rs[0].id} and ${r.id} present the same run $run but give different confidence")
                    }
                }
            }
        }
        assertTrue(coincided > 0, "O52: no two built-in routers ever presented the same run; the test is vacuous")
    }

    // O53 ---------------------------------------------------------------------------------------

    @Test
    fun `O53 deterministic`() = property("O53", seed = 5301) { c ->
        val rnd = c.rnd
        val hist = c.hist.history
        val ks = (0..hist.size).shuffled(rnd).take(20) + hist.size
        val first = ks.associateWith { confidence(hist.subList(0, it).toList()) }
        repeat(3) {
            val o = Hist(start = T0 + rnd.nextLong(0, 1_000_000_000))
            Gen(Random(rnd.nextLong()), o).setup().run()
            confidence(o.history)
            present(o.history)
        }
        val fresh = Routers(Routers.all.list, Routers.all.defaultId)
        for (k in ks.shuffled(rnd)) {
            val copy = hist.subList(0, k).map { it.copied() }
            assertEquals(first[k], confidence(copy), "the same history gave a different confidence, prefix $k")
            assertEquals(first[k], confidence(copy, fresh), "an equal set of routers gave a different confidence, prefix $k")
        }
    }

    /**
     * Ticks, unticks, detail edits and additions of items not placed at the
     * store, inside the trip in progress. Additions and removals of Run
     * entries, and "not here" on them, are left out: O51 ("now in the Run")
     * and O53 disagree about them (raised, not settled).
     */
    @Test
    fun `O53 unchanged inside the trip in progress`() = property("O53", seed = 5302) { c ->
        val rnd = c.rnd
        val h = c.hist.fork()
        Gen(rnd, h).quiet()
        if (h.s.selected == null) h.select(h.s.stores.keys.random(rnd))
        val store = h.s.selected!!
        POOL.shuffled(rnd).take(3).forEach { if (key(it) !in h.s.list) h.add(it) }
        val base = confidence(h.history)
        val f = h.fork()
        f.tick(f.s.list.random(rnd), gap = 30_000)
        assertEquals(base, confidence(f.history), "the trip's first tick changed confidence")
        repeat(rnd.nextInt(1, 3 * f.s.list.size + 2)) {
            val unticked = f.s.list.filter { it !in f.s.ticked }
            val fresh = POOL.filter { key(it) !in f.s.list && !f.s.placed(key(it), store) }
            val gap = tieish(rnd)
            when (rnd.nextInt(10)) {
                5 -> if (f.s.ticked.isNotEmpty()) f.untick(f.s.ticked.random(rnd), gap) else return@repeat
                6 -> if (fresh.isNotEmpty()) f.add(spelling(rnd, fresh.random(rnd)), gap = gap) else return@repeat
                7 -> unticked.randomOrNull(rnd)?.let { f.edit(it, f.s.names.getValue(it), "2", "ripe", gap) } ?: return@repeat
                8 -> unticked.randomOrNull(rnd)?.let { f.add(spelling(rnd, f.s.names.getValue(it)), gap = gap) } ?: return@repeat
                else -> if (unticked.isNotEmpty()) f.tick(unticked.random(rnd), gap) else return@repeat
            }
            assertEquals(base, confidence(f.history), "inside the trip, after #${f.size - 1} ${f.history.last()}")
        }
    }

    @Test
    fun `O53 unchanged by permuting ticks within a tie window`() = property(
        "O53", seed = 5303, cfg = GenConfig(routerChoices = false, unticks = false),
    ) { c ->
        val rnd = c.rnd
        val original = c.hist.history
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
                confidence(original.subList(0, k).toList()), confidence(permuted.subList(0, k).toList()),
                "permuting ticks within tie windows changed confidence after #${k - 1}",
            )
        }
        val a = Hist(original).also { Gen(rnd, it).quiet() }
        val b = Hist(permuted).also { Gen(rnd, it).quiet() }
        for (x in listOf(a, b)) POOL.forEach { if (key(it) !in x.s.list) x.add(it) }
        for (s in a.selections()) {
            assertEquals(confidence(a.at(s).history), confidence(b.at(s).history), "permuting ticks within tie windows changed confidence at $s")
        }
    }

    @Test
    fun `O53 unchanged at one store by anything done at another`() = property(
        "O53", seed = 5304, cfg = GenConfig(routerChoices = false),
    ) { c ->
        val rnd = c.rnd
        val cfg = GenConfig(routerChoices = false)
        val h = c.hist.fork()
        Gen(rnd, h).quiet()
        if (h.s.selected == null) h.select(h.s.stores.keys.random(rnd))
        val home = h.s.selected!!
        val other = h.s.stores.keys.filter { it != home }.random(rnd)
        val base = h.fork()
        val w = h.fork()

        // Trips and a store-scoped "not here" at the other store, leaving the list as it was.
        w.select(other, gap = 60_000)
        val fresh = POOL.filter { key(it) !in w.s.list }.shuffled(rnd).take(rnd.nextInt(2, 7))
        fresh.forEach { w.add(spelling(rnd, it)) }
        val prior = h.s.list.filter { !w.s.saidNotHere(it, other) }.randomOrNull(rnd)
        if (prior != null && rnd.nextBoolean()) w.notHere(prior, Scope.STORE)
        fresh.shuffled(rnd).forEach { w.tick(key(it), apartOrTied(rnd)) }
        if (fresh.isNotEmpty()) w.end()
        w.select(home)
        assertEquals(h.s.list, w.s.list, "the inserted history must leave the list as it was")
        assertEquals(confidence(base.history), confidence(w.history), "trips at $other changed confidence at $home")

        // The same continuation after both.
        val resume = w.now + Gen.DAY
        base.now = resume
        Gen(rnd, base, cfg).run(rnd.nextInt(10, 60))
        val tail = base.history.drop(h.size)
        tail.forEach { w.emit(it) }
        val s = Shadow().also { sh -> h.history.forEach { sh.apply(it) } }
        val block = w.size - base.size
        for (j in 0..tail.size) {
            if (j > 0) s.apply(tail[j - 1])
            if (s.selected == other) continue
            assertEquals(
                confidence(base.history.subList(0, h.size + j).toList()),
                confidence(w.history.subList(0, h.size + block + j).toList()),
                "trips at $other changed confidence at ${s.selected} after tail #$j",
            )
        }
    }

    // O54 ---------------------------------------------------------------------------------------

    /**
     * The same items walked in one order every trip, then once with an end
     * item moved to the other end, the rest in their order. At least four
     * items: with two or three, the move runs against more than half the
     * pairs, and the mirror rule reads it as agreeing (raised, not settled).
     */
    @TestFactory
    fun `O54 it can fall`() = (listOf(Variant("default", null)) + routerVariants).map { v ->
        DynamicTest.dynamicTest("O54 it can fall [${v.label}]") {
            for (i in 0 until CASES) {
                val seed = 54_000L + i
                val rnd = Random(seed)
                val h = Hist(v.prefix())
                withCase("O54", v, i, seed, { h.history }) {
                    h.store(A, "Store A")
                    val items = POOL.shuffled(rnd).take(rnd.nextInt(4, 11))
                    val trips = when (i) {
                        0 -> 100
                        1 -> 1
                        2 -> 2
                        else -> rnd.nextInt(1, 13)
                    }
                    repeat(trips) { walkNames(h, rnd, items) }
                    val firstToLast = rnd.nextBoolean()
                    val moved = if (firstToLast) items.drop(1) + items.first() else listOf(items.last()) + items.dropLast(1)
                    val x = key(if (firstToLast) items.first() else items.last())
                    walkNames(h, rnd, moved)
                    items.shuffled(rnd).forEach { if (key(it) !in h.s.list) h.add(spelling(rnd, it)) }
                    h.selectIfOther(A)
                    val got = confidence(h.history)
                    assertTrue(x in got, "$x is in the Run and must have a confidence (O50): $got")
                    assertNotEquals(
                        SETTLED, got[x],
                        "after $trips trips walking $items and one with $x moved, $x is settled; run ${present(h.history).inSection(SectionKind.RUN).map { it.item }}",
                    )
                }
            }
        }
    }

    // Helpers -----------------------------------------------------------------------------------

    /** One trip at A ticking [names] in order, never tied; ended in one of the ways tier 3 allows. */
    private fun walkNames(h: Hist, rnd: Random, names: List<String>) {
        names.shuffled(rnd).forEach { if (key(it) !in h.s.list) h.add(spelling(rnd, it)) }
        h.selectIfOther(A)
        names.forEachIndexed { i, n -> h.tick(key(n), if (i == 0) 60_000 else rnd.nextLong(Tier3.TIE_WINDOW_MS, 120_000)) }
        when (rnd.nextInt(4)) {
            0 -> h.select(null)
            1 -> h.idle()
            else -> h.end(EndReason.USER)
        }
    }

    private fun tieish(rnd: Random): Long =
        if (rnd.nextBoolean()) rnd.nextLong(0, Tier3.TIE_WINDOW_MS) else rnd.nextLong(Tier3.TIE_WINDOW_MS, 60_000)

    private fun apartOrTied(rnd: Random): Long =
        if (rnd.nextInt(4) == 0) rnd.nextLong(0, Tier3.TIE_WINDOW_MS) else rnd.nextLong(Tier3.TIE_WINDOW_MS, 120_000)

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

    private companion object {
        val A = StoreId("a")
        val B = StoreId("b")
    }
}

// The definition, restated -------------------------------------------------------------------

/** Tier 3 §I's values, restated so the oracles do not take them from the code under test. */
private object TierI {
    const val LAST_WITNESSES = 3
    const val SETTLED_NUM = 4L
    const val SETTLED_DEN = 5L
    const val TENTATIVE_BELOW_NUM = 3L
    const val TENTATIVE_BELOW_DEN = 5L
}

/** One witness's agreement, kept exact so the thresholds are met to the letter. */
private data class Share(val agree: Long, val of: Long) {
    override fun toString() = "$agree/$of"
}

/**
 * Tier 3 §I worked from the text alone: for each entry in [p]'s Run, the
 * agreements of its last witnesses, oldest first. Trips and tie groups are
 * the shadow's, independent of the code under test; the Run and its order
 * are the presentation's.
 */
private fun lastWitnesses(history: List<Observation>, p: Presentation): Map<ItemKey, List<Share>> {
    val store = p.store ?: return emptyMap()
    val run = p.inSection(SectionKind.RUN).map { it.item }
    val pos = run.withIndex().associate { it.value to it.index }
    val shadow = Shadow().also { s -> history.forEach { s.apply(it) } }
    val shares = run.associateWith { mutableListOf<Share>() }
    for (trip in shadow.ended) {
        if (trip.store != store) continue
        val group = HashMap<ItemKey, Int>()
        trip.groups.forEachIndexed { g, items -> items.forEach { group[it] = g } }
        val here = run.filter { it in group }
        // Mirror: against the presented order for more than half of all pairs among the Run's items ticked apart.
        var pairs = 0
        var against = 0
        for (i in here.indices) for (j in i + 1 until here.size) {
            val gi = group.getValue(here[i])
            val gj = group.getValue(here[j])
            if (gi == gj) continue
            pairs++
            if (gi > gj) against++
        }
        val mirrored = 2 * against > pairs
        for (x in here) {
            var n = 0L
            var agree = 0L
            for (y in here) {
                if (y == x || group[y] == group[x]) continue
                n++
                val walkedFirst = group.getValue(x) < group.getValue(y)
                val shownFirst = pos.getValue(x) < pos.getValue(y)
                if ((walkedFirst == shownFirst) != mirrored) agree++
            }
            if (n > 0) shares.getValue(x) += Share(agree, n)
        }
    }
    return shares.mapValues { it.value.takeLast(TierI.LAST_WITNESSES) }
}

/** Whether the mean of [shares] is at least num/den, in exact arithmetic. */
private fun meanAtLeast(shares: List<Share>, num: Long, den: Long): Boolean {
    val d = shares.fold(1L) { acc, s -> acc * s.of }
    val sum = shares.sumOf { it.agree * (d / it.of) }
    return den * sum >= num * shares.size * d
}

/** The confidence tier 3 §I gives these last witnesses; null with none, where it says nothing clear. */
private fun rule(last: List<Share>): Confidence? = when {
    last.isEmpty() -> null
    last.size == TierI.LAST_WITNESSES && meanAtLeast(last, TierI.SETTLED_NUM, TierI.SETTLED_DEN) -> SETTLED
    last.size == 1 || !meanAtLeast(last, TierI.TENTATIVE_BELOW_NUM, TierI.TENTATIVE_BELOW_DEN) -> TENTATIVE
    else -> SETTLING
}

// Routers of the tests' own -------------------------------------------------------------------

private fun only(r: Router) = Routers(listOf(r), r.id)

/** Presents a fixed order whatever was walked: items in [order] first, in that order, then the rest by key. */
private class Fixed(override val id: String, private val order: List<ItemKey>) : Router {
    override val label: String get() = id
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> =
        items.sortedWith(compareBy<ItemKey>({ order.indexOf(it).let { i -> if (i < 0) Int.MAX_VALUE else i } }, { it.value }))
}

private class ByName(override val id: String) : Router {
    override val label: String get() = id
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> = items.sortedBy { it.value }
}

private object ByNameReversed : Router {
    override val id = "by-name-reversed"
    override val label = id
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> = items.sortedByDescending { it.value }
}

/** The by-name order reached another way, reading the trips as it goes. */
private object ByNameTree : Router {
    override val id = "by-name-tree"
    override val label = id
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
        val tree = TreeSet<ItemKey>(compareBy { it.value })
        for (t in trips.asReversed()) for (g in t.groups) for (k in g) if (k in items) tree += k
        tree += items
        return tree.toList()
    }
}

/** Another method that presents exactly what [inner] does. */
private class Echo(private val inner: Router) : Router {
    override val id = "echo-${inner.id}"
    override val label = "Echo of ${inner.label}"
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> = inner.order(trips, items)
}

// Scenarios ---------------------------------------------------------------------------------

private val STORE_A = StoreId("a")

private fun letters(s: String): List<ItemKey> = s.map { key(it.toString()) }

/** Groups of a walk written as letters: "ba c" is b and a tied (b first), then c. */
private fun groupsOf(walk: String): List<List<String>> =
    walk.trim().split(Regex("\\s+")).map { g -> g.map { it.toString() } }

/** Ticks [walk] at the selected store, a second apart within a group and a minute between, then ends it. */
private fun walkLetters(h: Hist, walk: String) {
    val gs = groupsOf(walk)
    gs.flatten().forEach { if (key(it) !in h.s.list) h.add(it) }
    for (g in gs) g.forEachIndexed { i, n -> h.tick(key(n), if (i == 0) 60_000 else 1_000) }
    h.end()
}

/** Trips at store A walking [walks] in turn; then [list] on the list and A selected. */
private fun shopped(walks: List<String>, list: String, h: Hist = Hist()): Hist {
    if (STORE_A !in h.s.stores) h.store(STORE_A, "Store A")
    h.selectIfOther(STORE_A)
    for (w in walks) walkLetters(h, w)
    list.forEach { if (key(it.toString()) !in h.s.list) h.add(it.toString()) }
    h.selectIfOther(STORE_A)
    return h
}

/** Prefix lengths to judge at: a sample of [points], and the last. */
private fun sample(rnd: Random, points: List<Int>, n: Int = 12): List<Int> =
    (points.shuffled(rnd).take(n) + points.last()).distinct()

/** [h] with no trip in progress and every pool item on the list, at each selection: the widest Run each store shows. */
private fun widest(h: Hist, rnd: Random): List<List<Observation>> {
    val a = h.fork()
    Gen(rnd, a).quiet()
    POOL.forEach { if (key(it) !in a.s.list) a.add(it) }
    return a.selections().map { a.at(it).history }
}
