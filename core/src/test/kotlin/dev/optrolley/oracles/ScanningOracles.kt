package dev.optrolley.oracles

import dev.optrolley.core.Barcode
import dev.optrolley.core.Export
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Scope
import dev.optrolley.core.SectionKind.ELSEWHERE
import dev.optrolley.core.SectionKind.RUN
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Codes the scanner might read; some awkward on purpose, for the export (O45, O47). */
val CODE_POOL: List<Barcode> = listOf(
    "5000112637922", "5000112637939", "9780141036144", "0012345678905", "4006381333931", "50184453",
    "QR:https://example.invalid/p?id=1&x=\"y\"", "ABC-123 ü", "日本-42",
).map(::Barcode)

/** Names only ever given by linking, never typed by the generator. */
val LINK_ONLY_NAMES = listOf("Sugar", "Brown sugar", "Salt", "Kitchen roll")

/** What tier 2 says each code means: the name of its most recent link, as the user gave it (F12, O43). */
fun linkNames(h: List<Observation>): Map<Barcode, String> {
    val out = LinkedHashMap<Barcode, String>()
    for (o in h) if (o is Observation.CodeLinked) out[o.code] = o.name
    return out
}

/** Each linked code and the item its latest link names, by tier 3's item match (D10). */
fun linkedItems(h: List<Observation>): Map<Barcode, ItemKey> = linkNames(h).mapValues { key(it.value) }

/**
 * The name typing the item [code] is linked to would use at the end of [h]:
 * the item's latest spelling as the app knows it for suggestions, or the
 * link's own if it has never been typed. Null if [code] is not linked.
 */
fun typedNameFor(h: List<Observation>, code: Barcode): String? {
    val name = linkNames(h)[code] ?: return null
    return replay(h).known[key(name)] ?: name.trim()
}

fun Hist.linkCode(code: Barcode, name: String, gap: Long = 1_000) = emit(Observation.CodeLinked(next(gap), code, name))

/**
 * A scan of [code] as the app records it (F12): the linked item's name, with
 * the code that was read. A code that is not linked records nothing (O42).
 * Returns whether anything was recorded.
 */
fun Hist.scanCode(code: Barcode, gap: Long = 1_000): Boolean {
    val name = replay(history).nameFor(code) ?: return false
    emit(Observation.Added(next(gap), name, code = code))
    return true
}

/**
 * Drives [gen]'s history further with links and scans mixed into ordinary
 * use. Linked items are kept from being renamed or removed by the generator:
 * what a code means after its item's entry is renamed is not something this
 * file takes a view on.
 */
class ScanGen(val rnd: Random, val gen: Gen) {
    val h: Hist get() = gen.h

    fun run(steps: Int, beforeScan: (Hist, Barcode) -> Unit = { _, _ -> }): ScanGen {
        repeat(steps) { step(beforeScan) }
        return this
    }

    fun step(beforeScan: (Hist, Barcode) -> Unit = { _, _ -> }) {
        val linked = linkNames(h.history).keys.toList()
        val c = rnd.nextInt(100)
        when {
            c < 20 -> {
                h.settleAt(h.now + 2_000)
                val name = linkName()
                gen.protected += key(name)
                h.linkCode(CODE_POOL.random(rnd), name)
            }
            c < 45 && linked.isNotEmpty() -> {
                h.settleAt(h.now + 2_000)
                val code = linked.random(rnd)
                beforeScan(h, code)
                h.scanCode(code)
            }
            c < 50 -> {
                // An unlinked code, scanned: nothing is recorded (O42).
                CODE_POOL.filter { it !in linked }.randomOrNull(rnd)?.let { h.scanCode(it) }
            }
            else -> gen.step()
        }
    }

    private fun linkName(): String {
        val listed = h.s.list
        val c = rnd.nextInt(3)
        return when {
            c == 0 && listed.isNotEmpty() -> spelling(rnd, h.s.names.getValue(listed.random(rnd)).trim())
            c == 1 -> spelling(rnd, LINK_ONLY_NAMES.random(rnd))
            else -> spelling(rnd, POOL.random(rnd))
        }
    }
}

/** A generated history with links and scans in it. */
fun scanning(c: Case, steps: Int = 40, beforeScan: (Hist, Barcode) -> Unit = { _, _ -> }): Hist {
    ScanGen(c.rnd, c.gen).run(steps, beforeScan)
    return c.hist
}

/** Tier 3 group H: scanning (F12, D14). */
class ScanningOracles {

    // O41 ---------------------------------------------------------------------------------------

    /**
     * Scanning [code] at the end of [h], against typing its item's name at the
     * same moment: the same list and presentation now, at every store, and
     * after the same tick and trip end in both.
     */
    private fun assertScanIsTyping(h: Hist, code: Barcode, where: String) {
        val typed = assertNotNull(typedNameFor(h.history, code), "$code is not linked $where")
        val item = key(typed)
        val read = assertNotNull(replay(h.history).nameFor(code), "$code is linked to $item, but a scan of it names nothing $where")
        assertEquals(item, key(read), "a scan of $code names ${key(read)}, but its latest link is to $item $where")
        val t = h.now + 1_000
        val viaScan = h.fork().also { it.emit(Observation.Added(t, read, code = code)) }
        val viaType = h.fork().also { it.emit(Observation.Added(t, typed)) }
        fun compare(a: Hist, b: Hist, what: String) {
            assertEquals(replay(b.history).entries, replay(a.history).entries, "scanning $code gave a different list from typing \"$typed\" $where, $what")
            assertEquals(present(b.history), present(a.history), "scanning $code gave a different presentation from typing \"$typed\" $where, $what")
            for (store in a.selections()) {
                assertEquals(
                    present(b.at(store).history), present(a.at(store).history),
                    "scanning $code gave a different presentation from typing \"$typed\" $where, $what, with $store selected",
                )
            }
        }
        compare(viaScan, viaType, "straight after")
        // What is learned from the entry is the same too: tick it, end the trip.
        for (arm in listOf(viaScan, viaType)) {
            if (item in arm.s.list && item !in arm.s.ticked) arm.tick(item)
            if (arm.s.tripActive) arm.end()
        }
        compare(viaScan, viaType, "after ticking it and ending the trip")
    }

    @Test
    fun `O41 a scan is a typed name over random histories`() = property("O41", seed = 41) { c ->
        var checks = 0
        val h = scanning(c) { hist, code ->
            if (checks < 3 && c.rnd.nextInt(3) == 0) {
                checks++
                assertScanIsTyping(hist, code, "after #${hist.size - 1}")
            }
        }
        for (code in linkNames(h.history).keys) assertScanIsTyping(h, code, "at the end")
    }

    @Test
    fun `O41 a scan carries the item's placement over`() {
        for (v in hardVariants) withCase("O41", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a); h.store(StoreId("b"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(a)
            listOf("Bread", "Milk", "Eggs").forEach { h.tick(key(it)) }
            h.end()
            // The list is empty now; every item is placed at a.
            val code = CODE_POOL[0]
            h.linkCode(code, "MILK ")
            h.add("Eggs")
            assertScanIsTyping(h, code, "with milk placed and off the list")
            val scanned = h.fork().also { assertTrue(it.scanCode(code), "a linked code recorded nothing") }
            assertEquals(RUN, present(scanned.history).sectionOf(key("Milk")), "scanned milk is not in the Run where it is placed")
        }
    }

    @Test
    fun `O41 scanning an item already on the list adds nothing twice`() {
        for (v in hardVariants) withCase("O41", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(StoreId("a"))
            h.add("Milk", "2 L", "semi")
            h.add("Bread")
            val code = CODE_POOL[1]
            h.linkCode(code, "milk")
            assertScanIsTyping(h, code, "with milk on the list")
            val before = replay(h.history).entries
            h.scanCode(code)
            assertEquals(before, replay(h.history).entries, "scanning milk, already on the list, changed the list")
        }
    }

    @Test
    fun `O41 a scan mid-trip lands as a typed name would`() {
        for (v in hardVariants) withCase("O41", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a)
            listOf("Milk", "Bread", "Eggs", "Tea").forEach { h.add(it) }
            h.select(a)
            listOf("Bread", "Milk", "Tea", "Eggs").forEach { h.tick(key(it)) }
            h.end()
            h.add("Bread"); h.add("Eggs")
            h.linkCode(CODE_POOL[2], "Tea")
            h.linkCode(CODE_POOL[3], "Sugar")
            h.tick(key("Bread"))
            assertTrue(h.s.tripActive)
            assertScanIsTyping(h, CODE_POOL[2], "mid-trip, for a placed item")
            assertScanIsTyping(h, CODE_POOL[3], "mid-trip, for an item never typed")
        }
    }

    @Test
    fun `O41 a scan of an item not here lands in Elsewhere as a typed name would`() {
        for (v in hardVariants) withCase("O41", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a)
            h.select(a)
            val saffron = h.add("Saffron")
            h.notHere(saffron, Scope.STORE)
            h.remove(saffron)
            h.linkCode(CODE_POOL[4], "saffron")
            assertScanIsTyping(h, CODE_POOL[4], "with saffron said not to be at a")
            h.scanCode(CODE_POOL[4])
            assertEquals(ELSEWHERE, present(h.history).sectionOf(saffron), "scanned saffron is not in Elsewhere at a")
        }
    }

    // O42 ---------------------------------------------------------------------------------------

    @Test
    fun `O42 no name, no entry over random histories`() = property("O42", seed = 42) { c ->
        val h = scanning(c)
        val linked = linkNames(h.history).keys
        val state = replay(h.history)
        for (code in CODE_POOL + Barcode("never-seen")) {
            if (code in linked) continue
            assertNull(state.nameFor(code), "$code was never linked, but a scan of it names something")
            assertTrue(code !in state.codes, "$code was never linked, but is among the linked codes")
        }
    }

    @Test
    fun `O42 a code adds nothing until it is named`() {
        val h = Hist()
        h.store(StoreId("a"))
        h.add("Milk")
        val code = CODE_POOL[5]
        assertNull(replay(h.history).nameFor(code), "an unlinked code names something")
        val before = h.history
        assertTrue(!h.scanCode(code), "scanning an unlinked code recorded something")
        assertEquals(before, h.history)
        h.linkCode(code, "Bread")
        assertEquals(key("Bread"), replay(h.history).nameFor(code)?.let(::key), "named, the code does not name bread")
    }

    // O43 ---------------------------------------------------------------------------------------

    @Test
    fun `O43 the latest link is the meaning over random histories`() = property("O43", seed = 43) { c ->
        val h = scanning(c)
        val expected = linkedItems(h.history)
        val state = replay(h.history)
        assertEquals(expected, state.codes, "the linked codes are not those of each code's latest link")
        for ((code, item) in expected) {
            assertEquals(item, state.nameFor(code)?.let(::key), "$code's latest link is to $item")
        }
        // Linking one code never changes what another adds.
        val code = CODE_POOL.random(c.rnd)
        val name = spelling(c.rnd, (POOL + LINK_ONLY_NAMES).random(c.rnd))
        val after = h.fork().also { it.linkCode(code, name) }
        val then = replay(after.history)
        assertEquals(key(name), then.nameFor(code)?.let(::key), "$code, just linked to \"$name\", does not name it")
        for (other in CODE_POOL - code) {
            assertEquals(state.nameFor(other)?.let(::key), then.nameFor(other)?.let(::key), "linking $code to \"$name\" changed what $other adds")
            assertEquals(state.codes[other], then.codes[other], "linking $code to \"$name\" changed $other's link")
        }
    }

    @Test
    fun `O43 relinking changes the meaning to the latest`() {
        val h = Hist()
        h.store(StoreId("a"))
        val code = CODE_POOL[0]
        h.linkCode(code, "Sugar")
        assertTrue(h.scanCode(code))
        h.linkCode(code, "Salt")
        assertEquals(mapOf(code to key("Salt")), replay(h.history).codes)
        assertTrue(h.scanCode(code))
        assertEquals(listOf(key("Sugar"), key("Salt")), replay(h.history).entries.map { it.item }, "relinked, the code did not add salt")
        h.linkCode(code, "Sugar")
        assertEquals(key("Sugar"), replay(h.history).nameFor(code)?.let(::key), "linked back, the code does not mean sugar")
    }

    @Test
    fun `O43 linking one code leaves another alone`() {
        val h = Hist()
        val (a, b) = CODE_POOL[0] to CODE_POOL[1]
        h.linkCode(b, "Milk")
        h.linkCode(a, "Bread")
        h.linkCode(a, "Eggs")
        h.linkCode(a, "milk")
        val state = replay(h.history)
        assertEquals(mapOf(b to key("Milk"), a to key("Milk")), state.codes)
        h.linkCode(a, "Tea")
        assertEquals(key("Milk"), replay(h.history).nameFor(b)?.let(::key), "relinking $a changed what $b adds")
    }

    @Test
    fun `O43 many codes add one item, matched as typed names are`() {
        val h = Hist()
        h.store(StoreId("a"))
        val codes = CODE_POOL.take(3)
        listOf("Sugar", "  sugar", "SUGAR ").zip(codes).forEach { (name, code) -> h.linkCode(code, name) }
        h.linkCode(CODE_POOL[3], "Brown sugar")
        val state = replay(h.history)
        assertEquals(codes.associateWith { key("Sugar") } + (CODE_POOL[3] to key("Brown sugar")), state.codes)
        codes.forEach { h.scanCode(it) }
        assertEquals(listOf(key("Sugar")), replay(h.history).entries.map { it.item }, "three codes for sugar gave more than one entry")
        h.scanCode(CODE_POOL[3])
        assertEquals(listOf(key("Sugar"), key("Brown sugar")), replay(h.history).entries.map { it.item }, "brown sugar is not sugar (D10)")
        val typed = Hist().also { it.store(StoreId("a")); it.add("Sugar"); it.add("Brown sugar") }
        assertEquals(replay(typed.history).entries, replay(h.history).entries, "scanning gave a different list from typing")
    }

    // O44 ---------------------------------------------------------------------------------------

    @Test
    fun `O44 links move nothing over random histories`() = property("O44", seed = 44) { c ->
        val rnd = c.rnd
        val orig = scanning(c).history
        val n = orig.size
        val positions = (0..n).shuffled(rnd).take(rnd.nextInt(1, 6)).toSet()
        val with = ArrayList<Observation>()
        val at = IntArray(n + 1)
        for (i in 0..n) {
            if (i in positions) {
                val t = if (i > 0) orig[i - 1].at else T0
                with += Observation.CodeLinked(t, CODE_POOL.random(rnd), spelling(rnd, (POOL + LINK_ONLY_NAMES).random(rnd)))
            }
            at[i] = with.size
            if (i < n) with += orig[i]
        }
        val inserted = positions.sorted()
        withCase("O44", c.variant, c.index, c.seed, { with }) {
            for (k in ((0..n).shuffled(rnd).take(6) + n).toSet()) {
                val a = orig.subList(0, k).toList()
                val b = with.subList(0, if (k == n) with.size else at[k]).toList()
                val what = "links inserted at $inserted, after ${k} original observations"
                assertEquals(replay(a).entries, replay(b).entries, "the list changed: $what")
                assertEquals(present(a), present(b), "the presentation changed: $what")
            }
            val ha = Hist(orig)
            val hb = Hist(with)
            for (store in ha.selections()) {
                assertEquals(present(ha.at(store).history), present(hb.at(store).history), "links inserted at $inserted changed the presentation with $store selected")
            }
        }
    }

    @Test
    fun `O44 linking mid-trip moves nothing`() {
        for (v in hardVariants) withCase("O44", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            val a = StoreId("a")
            h.store(a); h.store(StoreId("b"))
            listOf("Milk", "Bread", "Eggs").forEach { h.add(it) }
            h.select(a)
            listOf("Eggs", "Milk", "Bread").forEach { h.tick(key(it)) }
            h.end()
            listOf("Milk", "Bread", "Eggs", "Tea").forEach { h.add(it) }
            h.tick(key("Milk"))
            val before = h.selections().associateWith { present(h.at(it).history) }
            val entries = replay(h.history).entries
            h.linkCode(CODE_POOL[0], "Coffee")
            h.linkCode(CODE_POOL[1], "eggs")
            h.linkCode(CODE_POOL[1], "Bread")
            assertEquals(entries, replay(h.history).entries, "linking changed the list")
            // Not because the links were ignored.
            assertEquals(mapOf(CODE_POOL[0] to key("Coffee"), CODE_POOL[1] to key("Bread")), replay(h.history).codes, "the links did not take")
            for ((store, p) in before) assertEquals(p, present(h.at(store).history), "linking changed the presentation with $store selected")
        }
    }

    // O45 ---------------------------------------------------------------------------------------

    private fun assertLinksSurvive(h: List<Observation>, rebuilt: List<Observation>, how: String) {
        val a = replay(h)
        val b = replay(rebuilt)
        assertEquals(linkedItems(h), a.codes, "the linked codes are not those of each code's latest link")
        assertEquals(a.codes, b.codes, "the linked codes differ $how")
        for (code in CODE_POOL) {
            assertEquals(a.nameFor(code), b.nameFor(code), "$code names something different $how")
            val name = a.nameFor(code) ?: continue
            val t = (h.lastOrNull()?.at ?: T0) + 1_000
            assertEquals(
                present(h + Observation.Added(t, name, code = code)),
                present(rebuilt + Observation.Added(t, b.nameFor(code)!!, code = code)),
                "scanning $code presents differently $how",
            )
        }
    }

    @Test
    fun `O45 links survive a rebuild over random histories`() = property("O45", seed = 45) { c ->
        val h = scanning(c).history
        assertLinksSurvive(h, h.map { it.copied() }, "after a rebuild")
    }

    @Test
    fun `O45 links survive export and import over random histories`() = property("O45", seed = 145) { c ->
        val h = scanning(c).history
        val imported = Export.decode(Export.encode(h))
        // Each scan records the code it read (D14), and that is part of the record too.
        assertEquals(h.map { (it as? Observation.Added)?.code }, imported.map { (it as? Observation.Added)?.code }, "the codes recorded with scans did not survive export")
        assertLinksSurvive(h, imported, "after export and import")
    }

    // O47 ---------------------------------------------------------------------------------------

    @Test
    fun `O47 a scan contributes only the code it read`() = property("O47", seed = 47, cases = 50) { c ->
        val h = scanning(c, steps = 60).history
        val scans = h.filterIsInstance<Observation.Added>().filter { it.code != null }
        val json = Json.parseToJsonElement(Export.encode(h)).jsonObject
        val records = json.getValue("history").jsonArray
        assertEquals(h.size, records.size)
        for ((o, r) in h.zip(records)) {
            val fields = r.jsonObject
            for ((name, value) in fields) {
                assertTrue(value is JsonPrimitive, "field $name of $o is exported as $value, not a single value")
            }
            if (o is Observation.Added && o.code != null) {
                // Exactly what typing the same name would write, plus the code as read.
                val typed = Json.parseToJsonElement(Export.encode(listOf(o.copy(code = null)))).jsonObject.getValue("history").jsonArray.single().jsonObject
                assertEquals(typed.keys + "code", fields.keys, "a scan exported fields beyond the code: $fields")
                for (k in typed.keys) assertEquals(typed.getValue(k), fields.getValue(k), "a scan exported $k differently from typing")
                assertEquals(o.code!!.value, fields.getValue("code").jsonPrimitive.content, "a scan exported something other than the code it read")
            }
            if (o is Observation.CodeLinked) {
                assertEquals(o.code.value, fields.getValue("code").jsonPrimitive.content, "a link exported something other than the code")
            }
        }
        assertNotEquals(0, scans.size, "no scans to judge")
    }
}
