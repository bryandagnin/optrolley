package dev.optrolley.oracles

import dev.optrolley.core.Barcode
import dev.optrolley.core.Export
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.SectionKind
import dev.optrolley.core.SectionKind.ELSEWHERE
import dev.optrolley.core.SectionKind.RUN
import dev.optrolley.core.SectionKind.UNPLACED
import dev.optrolley.core.StoreId
import dev.optrolley.core.nameFor
import dev.optrolley.core.present
import dev.optrolley.core.replay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A scan of [code] to tick at [at], as the app records it (F12, D14): decided
 * from what the app knows, the replayed state. A linked item with an entry
 * is ticked with the code read; one not on the list is added and ticked, both
 * with the code; one already ticked records nothing (O49), as does a code
 * that is not linked (O42).
 */
private fun scanTickForm(history: List<Observation>, code: Barcode, at: Long): List<Observation> {
    val state = replay(history)
    val name = state.nameFor(code) ?: return emptyList()
    val item = state.codes.getValue(code)
    val entry = state.entries.firstOrNull { it.item == item }
    return when {
        entry == null -> listOf(Observation.Added(at, name, code = code), Observation.Ticked(at, item, code = code))
        entry.ticked -> emptyList()
        else -> listOf(Observation.Ticked(at, item, code = code))
    }
}

/**
 * What tier 3 compares a scan to tick with (O48), decided from tier 3's own
 * reading of the history: tapping the tick of the item [code] is linked to,
 * or, if it is not on the list, typing its name and then tapping its tick.
 * Only for an item not already ticked.
 */
private fun tapForm(h: Hist, code: Barcode, at: Long): List<Observation> {
    val item = linkedItems(h.history).getValue(code)
    check(item !in h.s.ticked) { "$item is ticked; tapping it would untick it" }
    return if (item in h.s.list) {
        listOf(Observation.Ticked(at, item))
    } else {
        listOf(Observation.Added(at, typedNameFor(h.history, code)!!), Observation.Ticked(at, item))
    }
}

private fun Hist.scanTick(code: Barcode, gap: Long = 10_000) {
    val t = next(gap)
    scanTickForm(history, code, t).forEach(::emit)
}

private fun Hist.tapTick(code: Barcode, gap: Long = 10_000) {
    val t = next(gap)
    tapForm(this, code, t).forEach(::emit)
}

/** A gap before a tick: inside, at and either side of the tie window, or well clear of it. */
private fun tickGap(rnd: Random): Long = when (rnd.nextInt(6)) {
    0 -> rnd.nextLong(0, Tier3.TIE_WINDOW_MS)
    1 -> Tier3.TIE_WINDOW_MS - 1
    2 -> Tier3.TIE_WINDOW_MS
    3 -> Tier3.TIE_WINDOW_MS + 1
    else -> rnd.nextLong(Tier3.TIE_WINDOW_MS, 120_000)
}

/** Every name the generators use, so that bringing them all back shows everything learned. */
private val EVERY_NAME = POOL + LINK_ONLY_NAMES

/** The presentation's sections and entries with [item]'s entry taken out, empty sections dropped. */
private fun Presentation.without(item: ItemKey): List<Pair<SectionKind, List<dev.optrolley.core.Entry>>> =
    sections.map { sec -> sec.kind to sec.entries.filter { it.item != item } }.filter { it.second.isNotEmpty() }

/**
 * Drives [c]'s history with ordinary use, links and scans to add (as
 * [ScanGen] does), and scans to tick mixed in, calling [check] before each
 * scan to tick with the code about to be scanned.
 */
private fun scanTicking(c: Case, steps: Int = 50, check: (Hist, Barcode) -> Unit = { _, _ -> }): Hist {
    val sg = ScanGen(c.rnd, c.gen)
    val h = c.hist
    repeat(steps) {
        val linked = linkNames(h.history).keys.toList()
        if (linked.isNotEmpty() && c.rnd.nextInt(100) < 30) {
            // The app settles against the clock before recording any action.
            h.settleAt(h.now + 1_000)
            val code = linked.random(c.rnd)
            check(h, code)
            h.scanTick(code, tickGap(c.rnd))
        } else {
            sg.step()
        }
    }
    return c.hist
}

/** Tier 3 group H, scanning to tick: O48 and O49 (F12). */
class ScanTickOracles {

    /** The same list, and the same presentation with the store now selected. */
    private fun compareNow(a: Hist, b: Hist, what: String) {
        assertEquals(replay(b.history).entries, replay(a.history).entries, "the list differs from tapping $what")
        assertEquals(present(b.history), present(a.history), "the presentation differs from tapping $what")
    }

    /** The same list, and the same presentation at every store and none. */
    private fun compareEverywhere(a: Hist, b: Hist, what: String) {
        compareNow(a, b, what)
        for (store in a.selections()) {
            assertEquals(present(b.at(store).history), present(a.at(store).history), "the presentation differs from tapping $what, with $store selected")
        }
    }

    /**
     * [after] is [before] with [item]'s entry ticked and nothing else moved
     * (O7, O28): every other entry where it was, as it was; and [item], if
     * it was on the list, where it was too. If it was not, it has just been
     * added and ticked, which disturbs nothing either (O8).
     */
    private fun assertMovesNothingElse(before: Presentation, after: Presentation, item: ItemKey, wasOnList: Boolean, what: String) {
        assertEquals(before.store, after.store, "a scan to tick changed the selected store $what")
        assertEquals(before.without(item), after.without(item), "a scan to tick of $item moved or changed other entries $what")
        val entry = assertNotNull(after.entry(item), "$item, scanned to tick, is not on the list $what")
        assertTrue(entry.ticked, "$item, scanned to tick, is not marked as got $what")
        if (wasOnList) {
            assertEquals(before.layout(), after.layout(), "a scan to tick of $item moved it $what")
            assertEquals(before.entry(item)!!.copy(ticked = true), entry, "a scan to tick of $item changed more than its ticked mark $what")
        }
    }

    /**
     * O48 at the end of [h]: scanning [code] to tick, against tapping its
     * item's tick (or typing then tapping) at the same moment. The same list
     * and presentation straight after, with the same further ticks, after the
     * trip ends, and with every item brought back so that all that was
     * learned shows.
     */
    private fun assertScanTickIsTap(h: Hist, code: Barcode, rnd: Random, where: String) {
        val item = assertNotNull(linkedItems(h.history)[code], "$code is not linked $where")
        assertEquals(item, replay(h.history).codes[code], "$code's latest link is to $item $where")
        check(item !in h.s.ticked)
        val wasOnList = item in h.s.list
        val t = h.now + tickGap(rnd)
        val viaScan = h.fork().also { f -> scanTickForm(f.history, code, t).forEach(f::emit) }
        val viaTap = h.fork().also { f -> tapForm(f, code, t).forEach(f::emit) }
        val what = "scanning $code to tick ${if (wasOnList) "its entry" else "an item not on the list"} $where"
        assertTrue(item in viaScan.s.ticked, "$what did not tick $item")
        compareNow(viaScan, viaTap, "$what, straight after")
        assertMovesNothingElse(present(h.history), present(viaScan.history), item, wasOnList, what)

        val others = h.s.list.filter { it != item && it !in h.s.ticked }.shuffled(rnd).take(rnd.nextInt(0, 4))
        val gaps = others.map { tickGap(rnd) }
        for (arm in listOf(viaScan, viaTap)) others.zip(gaps).forEach { (k, g) -> arm.tick(k, g) }
        compareNow(viaScan, viaTap, "$what, then tapping ${others.map { it.value }}")
        for (arm in listOf(viaScan, viaTap)) arm.end()
        compareEverywhere(viaScan, viaTap, "$what, after the trip ends")
        for (arm in listOf(viaScan, viaTap)) EVERY_NAME.forEach { arm.add(it) }
        compareEverywhere(viaScan, viaTap, "$what, after the trip ends and every item is back on the list")
    }

    /** O49 at the end of [h]: scanning [code] to tick when its item is ticked records nothing. */
    private fun assertScanTickChangesNothing(h: Hist, code: Barcode, where: String) {
        val item = linkedItems(h.history).getValue(code)
        check(item in h.s.ticked)
        val p = present(h.history)
        assertEquals(true, p.entry(item)?.ticked, "$item is ticked, but not presented as ticked $where")
        val form = scanTickForm(h.history, code, h.now + 1_000)
        assertEquals(emptyList(), form, "scanning $code to tick, with $item already ticked, would record something $where")
    }

    // O48 ---------------------------------------------------------------------------------------

    @Test
    fun `O48 a scan to tick is a tap over random histories`() {
        val judged = HashMap<String, Int>()
        fun judge(h: Hist, code: Barcode, rnd: Random, where: String) {
            val item = linkedItems(h.history).getValue(code)
            val kind = (if (item in h.s.list) "on the list" else "not on the list") + (if (h.s.tripActive) ", mid-trip" else ", no trip")
            judged.merge(kind, 1, Int::plus)
            assertScanTickIsTap(h, code, rnd, where)
        }
        property("O48", seed = 48, cases = 100) { c ->
            var checks = 0
            val h = scanTicking(c) { hist, code ->
                if (linkedItems(hist.history).getValue(code) !in hist.s.ticked && checks < 4 && c.rnd.nextInt(2) == 0) {
                    checks++
                    judge(hist, code, c.rnd, "after #${hist.size - 1}")
                }
            }
            for (code in linkNames(h.history).keys) {
                if (linkedItems(h.history).getValue(code) !in h.s.ticked) judge(h, code, c.rnd, "at the end")
            }
        }
        for (kind in listOf("on the list, mid-trip", "on the list, no trip", "not on the list, mid-trip", "not on the list, no trip")) {
            assertTrue((judged[kind] ?: 0) > 0, "no scan to tick of an item $kind was judged: $judged")
        }
    }

    @Test
    fun `O48 a scan to tick teaches what a tap teaches`() {
        for (v in hardVariants) withCase("O48", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a); h.store(StoreId("b"))
            val names = listOf("Milk", "Bread", "Eggs", "Tea", "Coffee")
            names.forEach { h.add(it) }
            h.select(a)
            listOf("Bread", "Milk", "Tea", "Eggs", "Coffee").forEach { h.tick(key(it), gap = 30_000) }
            h.end()
            val codes = mapOf("Milk" to CODE_POOL[0], "Eggs" to CODE_POOL[1], "Coffee" to CODE_POOL[2]).mapValues { (n, c) -> h.linkCode(c, n); c }
            names.forEach { h.add(it) }
            val viaScan = h.fork()
            val viaTap = h.fork()
            // Walked in a new order, partly by scan, with a tie in the middle.
            val walk = listOf("Eggs" to 30_000L, "Bread" to 30_000L, "Milk" to 2_000L, "Coffee" to 30_000L, "Tea" to 30_000L)
            for ((name, gap) in walk) {
                val code = codes[name]
                if (code != null) {
                    viaScan.scanTick(code, gap)
                    viaTap.tapTick(code, gap)
                } else {
                    viaScan.tick(key(name), gap)
                    viaTap.tick(key(name), gap)
                }
                compareNow(viaScan, viaTap, "after ticking $name")
            }
            val scanTicks = viaScan.history.filterIsInstance<Observation.Ticked>().filter { it.code != null }
            assertEquals(codes.size, scanTicks.size, "the scans to tick were not recorded as ticks with their codes")
            assertEquals(codes.values.toSet(), scanTicks.map { it.code }.toSet())
            for (arm in listOf(viaScan, viaTap)) arm.end()
            compareEverywhere(viaScan, viaTap, "after the trip")
            for (arm in listOf(viaScan, viaTap)) names.forEach { arm.add(it) }
            compareEverywhere(viaScan, viaTap, "with every item back on the list")
        }
    }

    @Test
    fun `O48 a trip ticked wholly by scan is learned as tapped`() {
        for (v in hardVariants) withCase("O48", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a)
            val names = listOf("Milk", "Bread", "Eggs", "Tea")
            names.forEach { h.add(it) }
            val codes = names.zip(CODE_POOL).onEach { (n, c) -> h.linkCode(c, n) }.toMap()
            h.select(a)
            val viaScan = h.fork()
            val viaTap = h.fork()
            val walked = listOf("Tea", "Milk", "Eggs", "Bread")
            walked.forEach { viaScan.scanTick(codes.getValue(it), gap = 30_000) }
            walked.forEach { viaTap.tapTick(codes.getValue(it), gap = 30_000) }
            assertEquals(walked.map(::key).toSet(), viaScan.s.ticked, "scanning to tick did not tick everything")
            for (arm in listOf(viaScan, viaTap)) {
                arm.end()
                names.forEach { arm.add(it) }
            }
            compareEverywhere(viaScan, viaTap, "after a trip ticked wholly by scan")
            // Learned at all: ticked apart from each other, every item is now placed (O27).
            assertEquals(listOf(RUN), present(viaScan.history).layout().map { it.first }, "a trip ticked by scan placed nothing")
        }
    }

    @Test
    fun `O48 an item not on the list is added and ticked`() {
        for (v in hardVariants) withCase("O48", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a); h.store(StoreId("b"))
            listOf("Milk", "Bread", "Eggs", "Saffron").forEach { h.add(it) }
            h.select(a)
            h.notHere(key("Saffron"))
            listOf("Bread", "Milk", "Eggs").forEach { h.tick(key(it), gap = 30_000) }
            h.end()
            // Saffron stays, unticked, in Elsewhere; the rest have left the list.
            h.remove(key("Saffron"))
            h.add("Eggs")
            h.add("Tea")
            h.linkCode(CODE_POOL[0], "MILK")
            h.linkCode(CODE_POOL[1], "Sugar")
            h.linkCode(CODE_POOL[2], "saffron")
            h.tick(key("Eggs"), gap = 30_000)
            assertTrue(h.s.tripActive)
            val viaScan = h.fork()
            val viaTap = h.fork()
            for (code in CODE_POOL.take(3)) {
                val item = linkedItems(h.history).getValue(code)
                val before = present(viaScan.history)
                viaScan.scanTick(code, 30_000)
                viaTap.tapTick(code, 30_000)
                assertMovesNothingElse(before, present(viaScan.history), item, wasOnList = false, "for $item, mid-trip")
                compareNow(viaScan, viaTap, "after scanning $code to tick, mid-trip")
                val recorded = viaScan.history.takeLast(2)
                val added = recorded.first() as? Observation.Added
                assertEquals(code, added?.code, "scanning $code to tick did not record the addition with the code read")
                assertEquals(Observation.Ticked(added!!.at, item, code = code), recorded.last(), "scanning $code to tick did not record the tick with the code read")
            }
            val p = present(viaScan.history)
            assertEquals(RUN, p.sectionOf(key("Milk")), "milk, placed at a and scanned to tick, is not in the Run")
            assertEquals(UNPLACED, p.sectionOf(key("Sugar")), "sugar, never typed, scanned to tick, is not unplaced")
            assertEquals(ELSEWHERE, p.sectionOf(key("Saffron")), "saffron, said not to be at a, scanned to tick, has moved mid-trip")
            for (arm in listOf(viaScan, viaTap)) arm.end()
            compareEverywhere(viaScan, viaTap, "after the trip")
            for (arm in listOf(viaScan, viaTap)) EVERY_NAME.forEach { arm.add(it) }
            compareEverywhere(viaScan, viaTap, "with every item back on the list")
            // Got here now: a purchase is the latest statement (O6).
            assertNotEquals(ELSEWHERE, present(viaScan.history).sectionOf(key("Saffron")), "saffron, scanned to tick at a, is still in Elsewhere there")
        }
    }

    @Test
    fun `O48 a scan to tick moves nothing else`() {
        for (v in hardVariants) withCase("O48", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a)
            val names = listOf("Milk", "Bread", "Eggs", "Tea", "Coffee")
            names.forEach { h.add(it) }
            h.select(a)
            listOf("Coffee", "Eggs", "Milk", "Bread").forEach { h.tick(key(it), gap = 30_000) }
            h.end()
            names.forEach { h.add(it) }
            h.add("Rice")
            h.linkCode(CODE_POOL[0], "milk")
            h.linkCode(CODE_POOL[1], "Rice")
            h.linkCode(CODE_POOL[2], "Coffee")
            // Before the trip starts, during it, and for an item never placed.
            for (code in listOf(CODE_POOL[0], CODE_POOL[1], CODE_POOL[2])) {
                val item = linkedItems(h.history).getValue(code)
                val before = present(h.history)
                h.scanTick(code, 30_000)
                assertMovesNothingElse(before, present(h.history), item, wasOnList = true, "for $item")
            }
            assertTrue(h.s.tripActive, "scanning to tick did not start a trip")
            // Nor does a tap after it move the scanned entries.
            val before = present(h.history)
            h.tick(key("Tea"), 30_000)
            assertMovesNothingElse(before, present(h.history), key("Tea"), wasOnList = true, "for a tap after scans")
        }
    }

    @Test
    fun `O48 a scan to tick starts a trip as a tap does`() {
        for (v in hardVariants) withCase("O48", v, 0, 0, { emptyList() }) {
            for (selected in listOf(StoreId("a"), null)) {
                val h = Hist(v.prefix())
                h.store(StoreId("a")); h.store(StoreId("b"))
                listOf("Milk", "Bread").forEach { h.add(it) }
                h.select(StoreId("b"))
                listOf("Bread", "Milk").forEach { h.tick(key(it), gap = 30_000) }
                h.end()
                h.selectIfOther(selected)
                listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
                h.linkCode(CODE_POOL[0], "Milk")
                h.linkCode(CODE_POOL[1], "Tea")
                assertTrue(!h.s.tripActive)
                val viaScan = h.fork()
                val viaTap = h.fork()
                for (code in CODE_POOL.take(2)) {
                    viaScan.scanTick(code, 30_000)
                    viaTap.tapTick(code, 30_000)
                }
                assertTrue(replay(viaScan.history).trip != null, "scanning to tick with $selected selected started no trip")
                assertEquals(replay(viaTap.history).trip, replay(viaScan.history).trip, "scanning to tick started a different trip from tapping, with $selected selected")
                compareNow(viaScan, viaTap, "with $selected selected")
                for (arm in listOf(viaScan, viaTap)) arm.end()
                compareEverywhere(viaScan, viaTap, "after the trip, with $selected selected")
                for (arm in listOf(viaScan, viaTap)) EVERY_NAME.forEach { arm.add(it) }
                compareEverywhere(viaScan, viaTap, "with every item back, with $selected selected")
                if (selected == null) {
                    // No store, no learning (O32): b's run is as it was, and nothing is placed at a.
                    val base = h.fork().also { b -> EVERY_NAME.forEach { b.add(it) } }
                    assertEquals(
                        present(base.at(StoreId("b")).history).inSection(RUN).map { it.item },
                        present(viaScan.at(StoreId("b")).history).inSection(RUN).map { it.item },
                        "ticks by scan with no store selected changed b's run",
                    )
                    assertEquals(
                        emptyList(),
                        present(viaScan.at(StoreId("a")).history).inSection(RUN),
                        "ticks by scan with no store selected placed something at a",
                    )
                }
            }
        }
    }

    @Test
    fun `O48 a scan to tick after an untick counts once`() {
        // O31: tick, untick and re-tick (here by scan) teaches as the final tick alone.
        for (v in hardVariants) withCase("O48", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a)
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.linkCode(CODE_POOL[0], "Milk")
            h.select(a)
            val start = h.fork()
            h.tick(key("Milk"), 30_000)
            h.tick(key("Eggs"), 30_000)
            h.untick(key("Milk"), 30_000)
            h.tick(key("Bread"), 30_000)
            val viaScan = h.fork().also { it.scanTick(CODE_POOL[0], 30_000) }
            val viaTap = h.fork().also { it.tapTick(CODE_POOL[0], 30_000) }
            // The final ticks alone, at the same moments.
            val finalOnly = start.fork().also { f ->
                viaScan.history.drop(start.size).filterIsInstance<Observation.Ticked>()
                    .filter { t -> t.item != key("Milk") || t.code != null }
                    .forEach { t -> f.emit(Observation.Ticked(t.at, t.item)) }
            }
            assertEquals(listOf(key("Eggs"), key("Bread"), key("Milk")), finalOnly.history.drop(start.size).map { (it as Observation.Ticked).item })
            compareNow(viaScan, viaTap, "re-ticking milk")
            for (arm in listOf(viaScan, viaTap, finalOnly)) {
                arm.end()
                listOf("Milk", "Bread", "Eggs").forEach { arm.add(it) }
            }
            compareEverywhere(viaScan, viaTap, "after the trip, with every item back")
            for (store in viaScan.selections()) {
                assertEquals(
                    present(finalOnly.at(store).history), present(viaScan.at(store).history),
                    "milk ticked, unticked and re-ticked by scan taught other than its final tick alone, with $store selected",
                )
            }
        }
    }

    // O49 ---------------------------------------------------------------------------------------

    @Test
    fun `O49 a scan never unticks over random histories`() {
        var checks = 0
        property("O49", seed = 49, cases = 100) { c ->
            val h = scanTicking(c) { hist, code ->
                if (linkedItems(hist.history).getValue(code) in hist.s.ticked) {
                    checks++
                    assertScanTickChangesNothing(hist, code, "after #${hist.size - 1}")
                }
            }
            for (code in linkNames(h.history).keys) {
                if (linkedItems(h.history).getValue(code) in h.s.ticked) assertScanTickChangesNothing(h, code, "at the end")
            }
        }
        assertNotEquals(0, checks, "no scan to tick of a ticked item was judged")
    }

    @Test
    fun `O49 a scan of a ticked item changes nothing, then or after`() {
        for (v in hardVariants) withCase("O49", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a)
            listOf("Milk", "Eggs", "Bread").forEach { h.add(it) }
            h.linkCode(CODE_POOL[0], "Milk")
            h.linkCode(CODE_POOL[1], "Tea")
            h.select(a)
            h.tick(key("Milk"), 30_000)
            h.tick(key("Eggs"), 30_000)
            // Tea is not on the list: the first scan adds and ticks it, the second finds it ticked.
            h.scanTick(CODE_POOL[1], 30_000)
            assertTrue(key("Tea") in h.s.ticked)
            val before = h.history
            val presented = present(before)
            h.scanTick(CODE_POOL[0], 30_000)
            h.scanTick(CODE_POOL[1], 30_000)
            assertEquals(before, h.history, "scanning ticked items to tick recorded something")
            assertEquals(presented, present(h.history))
            assertTrue(h.s.ticked.containsAll(listOf(key("Milk"), key("Tea"))), "a scan unticked something")
        }
    }

    // The record ---------------------------------------------------------------------------------

    @Test
    fun `O47 a scan to tick contributes only the code it read`() {
        var judged = 0
        property("O47", seed = 147, cases = 50) { c -> judged += assertTickScansExportOnlyTheCode(scanTicking(c, steps = 60).history) }
        assertNotEquals(0, judged, "no scans to tick to judge")
    }

    /** Returns how many scans to tick it judged. */
    private fun assertTickScansExportOnlyTheCode(h: List<Observation>): Int {
        val ticks = h.filterIsInstance<Observation.Ticked>().filter { it.code != null }
        val records = Json.parseToJsonElement(Export.encode(h)).jsonObject.getValue("history").jsonArray
        assertEquals(h.size, records.size)
        for ((o, r) in h.zip(records)) {
            if (o !is Observation.Ticked || o.code == null) continue
            val fields = r.jsonObject
            for ((name, value) in fields) assertTrue(value is JsonPrimitive, "field $name of $o is exported as $value, not a single value")
            val tapped = Json.parseToJsonElement(Export.encode(listOf(o.copy(code = null)))).jsonObject.getValue("history").jsonArray.single().jsonObject
            assertEquals(tapped.keys + "code", fields.keys, "a scan to tick exported fields beyond the code: $fields")
            for (k in tapped.keys) assertEquals(tapped.getValue(k), fields.getValue(k), "a scan to tick exported $k differently from a tap")
            assertEquals(o.code.value, fields.getValue("code").jsonPrimitive.content, "a scan to tick exported something other than the code it read")
        }
        return ticks.size
    }

    @Test
    fun `O45 scans to tick survive export and import`() = property("O45", seed = 245, cases = 50) { c ->
        val h = scanTicking(c).history
        val imported = Export.decode(Export.encode(h))
        assertEquals(
            h.map { (it as? Observation.Ticked)?.code },
            imported.map { (it as? Observation.Ticked)?.code },
            "the codes recorded with scans to tick did not survive export",
        )
        val a = Hist(h)
        val b = Hist(imported)
        assertEquals(replay(h).entries, replay(imported).entries, "the list differs after export and import")
        for (store in a.selections()) {
            assertEquals(present(a.at(store).history), present(b.at(store).history), "the presentation with $store selected differs after export and import")
        }
    }

    @Test
    fun `O42 an unlinked code scanned to tick records nothing`() {
        val h = Hist()
        h.store(StoreId("a"))
        h.select(StoreId("a"))
        h.add("Milk")
        h.tick(key("Milk"))
        val code = CODE_POOL[5]
        assertEquals(emptyList(), scanTickForm(h.history, code, h.now + 1_000), "an unlinked code scanned to tick would record something")
        h.linkCode(code, "Bread")
        assertEquals(key("Bread"), replay(h.history).nameFor(code)?.let(::key))
        h.scanTick(code)
        assertEquals(listOf(key("Milk"), key("Bread")), replay(h.history).entries.filter { it.ticked }.map { it.item }, "named, the code did not add and tick bread")
    }
}
