package dev.optrolley.oracles

import dev.optrolley.core.Barcode
import dev.optrolley.core.ChainId
import dev.optrolley.core.Confidence
import dev.optrolley.core.Entry
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Scope
import dev.optrolley.core.Section
import dev.optrolley.core.SectionKind
import dev.optrolley.core.SectionKind.ELSEWHERE
import dev.optrolley.core.SectionKind.LIST
import dev.optrolley.core.SectionKind.RUN
import dev.optrolley.core.SectionKind.UNPLACED
import dev.optrolley.core.StoreId
import dev.optrolley.core.confidence
import dev.optrolley.core.itemNamed
import dev.optrolley.core.nameFor
import dev.optrolley.core.present
import dev.optrolley.core.replay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun Hist.renameItem(item: ItemKey, name: String, gap: Long = 1_000) =
    emit(Observation.ItemRenamed(next(gap), item, name))

private fun Hist.unlinkCode(code: Barcode, gap: Long = 1_000) = emit(Observation.CodeUnlinked(next(gap), code))

/** A name no generated action types or links (see [POOL], [LINK_ONLY_NAMES]), so a rename to it is to a name no item has. */
private fun freshName(n: Int) = "Renamed $n ✎"

/**
 * Every item named in [h], typed, edited to or linked, by tier 3's item match,
 * with the name it was first given. Holds only for a history with no renames,
 * where a name and its item are one.
 */
private fun namedItems(h: List<Observation>): LinkedHashMap<ItemKey, String> {
    val out = LinkedHashMap<ItemKey, String>()
    for (o in h) when (o) {
        is Observation.Added -> out.putIfAbsent(key(o.name), o.name.trim())
        is Observation.Edited -> out.putIfAbsent(key(o.name), o.name.trim())
        is Observation.CodeLinked -> out.putIfAbsent(key(o.name), o.name.trim())
        else -> {}
    }
    return out
}

private fun Presentation.mapEntries(f: (Entry) -> Entry) = Presentation(store, sections.map { Section(it.kind, it.entries.map(f)) })

/** What O55 expects after renames: the same, with each renamed item's entry under its new name. */
private fun Entry.named(names: Map<ItemKey, String>): Entry = names[item]?.let { copy(name = it) } ?: this
private fun List<Entry>.named(names: Map<ItemKey, String>) = map { it.named(names) }
private fun Presentation.named(names: Map<ItemKey, String>) = mapEntries { it.named(names) }

/**
 * The joined entry with what O57 leaves open set aside: which of the two keys
 * it keeps, the spelling of its name (matched as typed names are), and which
 * quantity and note survive. [from] is the key the item has in this history.
 */
private fun Entry.joinedAs(from: ItemKey, j: ItemKey): Entry =
    if (item == from) copy(item = j, name = key(name).value, quantity = null, note = null) else this
private fun Presentation.joinedAs(from: ItemKey, j: ItemKey) = mapEntries { it.joinedAs(from, j) }
private fun Map<ItemKey, Confidence>.joinedAs(from: ItemKey, j: ItemKey) = mapKeys { if (it.key == from) j else it.key }

/** Sections and entries, leaving out [items]: what "no other entry changes" compares. */
private fun Presentation.others(items: Set<ItemKey>): List<Pair<SectionKind, List<Entry>>> =
    sections.map { s -> s.kind to s.entries.filter { it.item !in items } }.filter { it.second.isNotEmpty() }

/**
 * [h] as if [from] had always been [into]: every action on it made on
 * [into], every name typed or linked for it typed as [intoName]. For a
 * history without renames.
 */
private fun merged(h: List<Observation>, from: ItemKey, into: ItemKey, intoName: String): List<Observation> {
    fun k(x: ItemKey) = if (x == from) into else x
    fun n(s: String) = if (key(s) == from) intoName else s
    return h.map { o ->
        when (o) {
            is Observation.Added -> o.copy(name = n(o.name))
            is Observation.Edited -> o.copy(item = k(o.item), name = n(o.name))
            is Observation.Removed -> o.copy(item = k(o.item))
            is Observation.Ticked -> o.copy(item = k(o.item))
            is Observation.Unticked -> o.copy(item = k(o.item))
            is Observation.NotHere -> o.copy(item = k(o.item))
            is Observation.NotHereUndone -> o.copy(item = k(o.item))
            is Observation.CodeLinked -> o.copy(name = n(o.name))
            is Observation.ItemRenamed -> throw IllegalArgumentException("merged() is for histories without renames")
            else -> o
        }
    }
}

/**
 * Whether [merged] of [h], [a] into [b], is what O57 says a join of the two
 * leaves, judged by the shadow alone: the list in step at every action (the
 * two never both on it in a way one entry could not follow), each ended trip
 * the same with the two read as one (no trip ticked both: which tick would
 * count is not said), and no "not here" withdrawn where both had one at the
 * same store and scope (which one is withdrawn would depend on the join).
 */
private fun faithful(h: List<Observation>, a: ItemKey, b: ItemKey): Boolean {
    fun m(k: ItemKey) = if (k == a) b else k
    val w = merged(h, a, b, namedItems(h).getValue(b))
    val so = Shadow()
    val sw = Shadow()
    for (i in h.indices) {
        so.apply(h[i]); sw.apply(w[i])
        if (so.list.map(::m).distinct() != sw.list) return false
    }
    if (so.ended.size != sw.ended.size) return false
    for ((x, y) in so.ended.zip(sw.ended)) {
        if (a in x.items && b in x.items) return false
        if (x.store != y.store || x.groups.map { g -> g.map(::m).toSet() } != y.groups) return false
    }
    val saidOfA = so.statements.filter { it.item == a }.map { it.store to it.scope }.toSet()
    val shared = so.statements.filter { it.item == b }.map { it.store to it.scope }.filter { it in saidOfA }.toSet()
    return h.none { it is Observation.NotHereUndone && (it.item == a || it.item == b) && (it.store to it.scope) in shared }
}

/**
 * The history [orig] would be had [a] and [b] always been one item, keyed as
 * the join left it ([j]) wherever that is one of the two, under [nameB]; and
 * the key the item has in it.
 */
private fun asOneItem(orig: List<Observation>, a: ItemKey, nameA: String, b: ItemKey, nameB: String, j: ItemKey, t: Long): Pair<List<Observation>, ItemKey> =
    if (j == a) (merged(orig, b, a, nameA) + Observation.ItemRenamed(t, a, nameB)) to a
    else merged(orig, a, b, nameB) to b

private val A = StoreId("a")
private val B = StoreId("b")
private val B2 = StoreId("b2")
private val E = StoreId("e")
private val CH = ChainId("ch")

/**
 * Milk walked three times at a, said not to be at chain ch (stores b and
 * b2), linked to two codes, and on the list with a quantity and note,
 * in a trip in progress at a, ticked unless [tickMilk] is false. Store e has
 * never been shopped.
 */
private fun milkCase(v: Variant, tickMilk: Boolean = true): Hist {
    val h = Hist(v.prefix())
    h.chain(CH)
    h.store(A); h.store(B, chain = CH); h.store(B2, chain = CH); h.store(E)
    val walk = listOf("Bread", "Milk", "Eggs", "Tea")
    h.select(A)
    repeat(3) {
        walk.forEach { h.add(it) }
        walk.forEach { h.tick(key(it)) }
        h.end()
    }
    h.add("Milk", "2 L", "semi"); h.add("Bread"); h.add("Eggs"); h.add("Coffee")
    h.select(B)
    h.notHere(key("Milk"), Scope.CHAIN)
    h.linkCode(CODE_POOL[0], "milk")
    h.linkCode(CODE_POOL[1], " MILK")
    h.select(A)
    h.tick(key("Bread"))
    if (tickMilk) h.tick(key("Milk"))
    return h
}

/** Tier 3 group J: items and the library (D10, F1, F13). */
class ItemOracles {

    // O55 ---------------------------------------------------------------------------------------

    /**
     * Renames inserted anywhere in a generated history with links and scans.
     * Every later name typed, edited to or linked for a renamed item is
     * rewritten to its new name: typed after the rename the old name would
     * mean a new item (O56), and the new name means this one (item match), so
     * the rewritten history is the same use with the item called by its new
     * name. New names are ones the generator never types.
     */
    @Test
    fun `O55 renaming moves nothing over random histories`() = property("O55", seed = 55) { c ->
        val rnd = c.rnd
        val orig = scanning(c).history
        val n = orig.size
        val positions = (1..n).shuffled(rnd).take(rnd.nextInt(1, 4)).toSet()
        val with = ArrayList<Observation>()
        val at = IntArray(n + 1)
        val alias = HashMap<ItemKey, String>()
        val aliasAt = ArrayList<Map<ItemKey, String>>()
        var fresh = 0
        for (i in 0..n) {
            if (i in positions) {
                val items = namedItems(orig.subList(0, i)).keys.toList()
                if (items.isNotEmpty()) {
                    val k = items.random(rnd)
                    val name = freshName(++fresh)
                    with += Observation.ItemRenamed(orig[i - 1].at, k, name)
                    alias[k] = name
                }
            }
            at[i] = with.size
            aliasAt += alias.toMap()
            if (i < n) {
                val o = orig[i]
                fun r(s: String) = alias[key(s)] ?: s
                with += when (o) {
                    is Observation.Added -> o.copy(name = r(o.name))
                    is Observation.Edited -> o.copy(name = r(o.name))
                    is Observation.CodeLinked -> o.copy(name = r(o.name))
                    else -> o
                }
            }
        }
        if (alias.isEmpty()) return@property
        val first = with.indexOfFirst { it is Observation.ItemRenamed }
        val firstK = (0..n).first { at[it] > first }
        withCase("O55", c.variant, c.index, c.seed, { with }) {
            for (k in ((firstK..n).shuffled(rnd).take(5) + firstK + n).toSet()) {
                val a = orig.subList(0, k).toList()
                val b = with.subList(0, at[k]).toList()
                val names = aliasAt[k]
                val what = "renames ${names.entries} by ${b.size} observations, against ${k} original"
                assertEquals(replay(a).entries.named(names), replay(b).entries, "the list changed beyond the names: $what")
                assertEquals(present(a).named(names), present(b), "the presentation changed beyond the names: $what")
                assertEquals(confidence(a), confidence(b), "confidence changed: $what")
            }
            val sa = replay(orig)
            val sb = replay(with)
            assertEquals(sa.codes, sb.codes, "renaming changed which item a code means")
            for ((code, item) in sa.codes) {
                assertEquals(alias[item] ?: sa.nameFor(code), sb.nameFor(code), "$code, linked to $item, does not add it under its current name")
            }
            for ((item, name) in alias) assertEquals(item, sb.itemNamed(name), "the new name \"$name\" does not mean the renamed $item")
            assertEquals(sa.items.mapValues { (k, v) -> alias[k] ?: v }, sb.items, "renaming changed the items beyond the renamed ones' names")
            val ha = Hist(orig)
            val hb = Hist(with)
            for (store in ha.selections()) {
                val xa = ha.at(store).history
                val xb = hb.at(store).history
                assertEquals(present(xa).named(alias), present(xb), "renames ${alias.entries} changed the presentation with $store selected")
                assertEquals(confidence(xa), confidence(xb), "renames ${alias.entries} changed confidence with $store selected")
            }
        }
    }

    @Test
    fun `O55 a renamed item keeps its place, statements, codes and entry, mid-trip`() {
        // Ticked, so the tick is seen to be kept; unticked, so it stays on the list when another store is selected.
        for (v in hardVariants) for (ticked in listOf(true, false)) withCase("O55", v, 0, 0, { emptyList() }) {
            val h = milkCase(v, tickMilk = ticked)
            val milk = key("Milk")
            val before = h.selections().associateWith { present(h.at(it).history) }
            val conf = h.selections().associateWith { confidence(h.at(it).history) }
            val entries = replay(h.history).entries
            // Not a vacuous case: milk is in the Run at a with a confidence, and in Elsewhere at b.
            assertEquals(RUN, before.getValue(A).sectionOf(milk))
            if (!ticked) assertEquals(ELSEWHERE, before.getValue(B).sectionOf(milk))
            assertTrue(milk in conf.getValue(A))
            assertEquals(Entry(milk, entries.single { it.item == milk }.name, "2 L", "semi", ticked), entries.single { it.item == milk })

            h.renameItem(milk, "Whole milk")
            val st = replay(h.history)
            val names = mapOf(milk to "Whole milk")
            assertEquals(entries.named(names), st.entries, "renaming changed the list beyond the name")
            for (store in h.selections()) {
                assertEquals(before.getValue(store).named(names), present(h.at(store).history), "renaming changed the presentation with $store selected")
                assertEquals(conf.getValue(store), confidence(h.at(store).history), "renaming changed confidence with $store selected")
            }
            assertEquals(mapOf(CODE_POOL[0] to milk, CODE_POOL[1] to milk), st.codes, "renaming changed what the codes mean")
            assertEquals("Whole milk", st.nameFor(CODE_POOL[0]), "a code linked to milk does not add it under its new name")
            assertEquals("Whole milk", st.nameFor(CODE_POOL[1]), "a code linked to milk does not add it under its new name")
            assertEquals(milk, st.itemNamed(" whole MILK"), "the new name does not mean the renamed item")
            assertEquals("Whole milk", st.items[milk], "the item is not under its new name")
        }
    }

    @Test
    fun `O55 an item renamed off the list comes back with everything learned`() {
        for (v in hardVariants) withCase("O55", v, 0, 0, { emptyList() }) {
            val h = milkCase(v)
            h.end()
            val milk = key("Milk")
            assertTrue(replay(h.history).entries.none { it.item == milk }, "milk was ticked; the trip's end should have taken it off")
            val plain = h.fork().also { it.add("Milk") }
            h.renameItem(milk, "Whole milk")
            h.add("whole milk ")
            assertEquals(milk, replay(h.history).entries.last().item, "typing the new name did not add the renamed item")
            // By item: which spelling of the new name the entry shows, typed as "whole milk ", is not O55's concern.
            for (store in h.selections()) {
                assertEquals(
                    present(plain.at(store).history).layout(),
                    present(h.at(store).history).layout(),
                    "the renamed item, typed by its new name, is presented differently from the item typed by its old name with $store selected",
                )
                assertEquals(confidence(plain.at(store).history), confidence(h.at(store).history), "confidence differs with $store selected")
            }
            assertEquals(RUN, present(h.at(A).history).sectionOf(milk))
            assertEquals(ELSEWHERE, present(h.at(B2).history).sectionOf(milk))
            // A code linked to it adds it: nothing twice while it is on the list, then the entry once removed.
            val list = replay(h.history).entries
            h.scanCode(CODE_POOL[0])
            assertEquals(list, replay(h.history).entries, "scanning the renamed item, on the list, changed the list")
            h.remove(milk)
            assertTrue(h.scanCode(CODE_POOL[1]), "a code linked to the renamed item recorded nothing")
            assertEquals(milk, replay(h.history).entries.last().item, "a code linked to the renamed item added something else")
            assertEquals(RUN, present(h.at(A).history).sectionOf(milk), "the renamed item, scanned, is not in the Run where it is placed")
        }
    }

    // O56 ---------------------------------------------------------------------------------------

    /** Renaming happens at the end of the history, so no later generated action types the old name. */
    @Test
    fun `O56 the old name starts cold over random histories`() = property("O56", seed = 56) { c ->
        val rnd = c.rnd
        val h = scanning(c)
        val s = h.s
        val named = namedItems(h.history)
        val codes = linkedItems(h.history)
        // Prefer an item with something to forget: placed, said not here, linked.
        fun evidence(k: ItemKey) =
            s.stores.keys.count { s.placed(k, it) || s.standing(k, it).isNotEmpty() } + codes.values.count { it == k }
        val k = named.keys.shuffled(rnd).maxBy(::evidence)
        val old = named.getValue(k)
        h.renameItem(k, freshName(1))
        val renamed = replay(h.history)
        assertNull(renamed.itemNamed(spelling(rnd, old)), "after renaming $k, its old name \"$old\" still means an item")
        val count = renamed.entries.size
        h.emit(Observation.Added(h.next(1_000), spelling(rnd, old)))
        val st = replay(h.history)
        val fresh = assertNotNull(st.itemNamed(old), "the old name \"$old\", typed again, means no item")
        assertNotEquals(k, fresh, "the old name \"$old\", typed again, means the renamed item")
        assertEquals(count + 1, st.entries.size, "the old name typed again did not add one entry")
        assertTrue(st.entries.any { it.item == fresh }, "the old name typed again is not on the list")
        assertTrue(fresh !in st.codes.values, "a code adds the new item \"$old\"")
        assertEquals(freshName(1), st.items[k], "the renamed item lost its name")
        for (store in h.selections()) {
            val x = h.at(store).history
            assertEquals(if (store == null) LIST else UNPLACED, present(x).sectionOf(fresh), "the new item \"$old\" is not unplaced with $store selected")
            assertTrue(fresh !in confidence(x), "the new item \"$old\" has a confidence with $store selected")
        }
    }

    @Test
    fun `O56 the old name typed again is a new item, placed nowhere, said nowhere, linked to nothing`() {
        for (v in hardVariants) withCase("O56", v, 0, 0, { emptyList() }) {
            val h = milkCase(v)
            h.end()
            val milk = key("Milk")
            h.renameItem(milk, "Whole milk")
            h.add("Whole milk")
            assertNull(replay(h.history).itemNamed("Milk"), "after the rename, \"Milk\" still means an item")
            h.add(" milk")
            val st = replay(h.history)
            val fresh = assertNotNull(st.itemNamed("MILK"), "\"milk\" typed again means no item")
            assertNotEquals(milk, fresh, "\"milk\" typed again means the renamed item")
            assertTrue(st.entries.any { it.item == milk } && st.entries.any { it.item == fresh }, "both items should be on the list: ${st.entries}")
            for (store in h.selections()) {
                val x = h.at(store).history
                assertEquals(if (store == null) LIST else UNPLACED, present(x).sectionOf(fresh), "the new milk is not unplaced with $store selected")
                assertTrue(fresh !in confidence(x))
            }
            // What was learned stayed with the renamed item.
            assertEquals(RUN, present(h.at(A).history).sectionOf(milk))
            assertEquals(ELSEWHERE, present(h.at(B).history).sectionOf(milk))
            assertTrue(fresh !in st.codes.values, "a code adds the new milk")
            assertEquals("Whole milk", st.nameFor(CODE_POOL[0]))
        }
    }

    @Test
    fun `O56 after a join the old name starts cold`() {
        for (v in hardVariants) withCase("O56", v, 0, 0, { emptyList() }) {
            val h = milkCase(v)
            h.end()
            val milk = key("Milk")
            h.add("Oat milk")
            h.renameItem(milk, "Oat milk")
            assertNull(replay(h.history).itemNamed("Milk"), "after joining milk into oat milk, \"Milk\" still means an item")
            h.add("Milk")
            val st = replay(h.history)
            val fresh = assertNotNull(st.itemNamed("milk"))
            assertNotEquals(st.itemNamed("Oat milk"), fresh, "\"milk\" typed again means the joined item")
            for (store in h.selections()) {
                assertEquals(if (store == null) LIST else UNPLACED, present(h.at(store).history).sectionOf(fresh), "the new milk is not unplaced with $store selected")
            }
            assertTrue(fresh !in st.codes.values, "a code adds the new milk")
        }
    }

    // O57 ---------------------------------------------------------------------------------------

    /**
     * Join two items at a quiet end of a generated history, and compare with
     * the history in which they were always one item. Pairs are taken only
     * where that history is what O57 describes (see [faithful]).
     */
    @Test
    fun `O57 a joined item counts the trips, statements and codes of both over random histories`() {
        var joins = 0
        var bothListed = 0
        property("O57", seed = 57, cases = 80) { c ->
            val rnd = c.rnd
            val h = scanning(c)
            c.gen.quiet()
            val orig = h.history
            val s = h.s
            val named = namedItems(orig)
            val codes = linkedItems(orig)
            fun weight(k: ItemKey) = minOf(2, s.stores.keys.count { s.placed(k, it) } + s.statements.count { it.item == k } + codes.values.count { it == k })
            val pairs = named.keys.flatMap { a -> named.keys.filter { it != a }.map { a to it } }
                .shuffled(rnd)
                .sortedByDescending { (a, b) -> (if (a in s.list && b in s.list) 4 else 0) + weight(a) + weight(b) }
            val (a, b) = pairs.take(30).firstOrNull { (a, b) -> faithful(orig, a, b) } ?: return@property
            joins++
            if (a in s.list && b in s.list) bothListed++
            val before = replay(orig)
            val nameA = before.items[a] ?: named.getValue(a)
            val nameB = before.items[b] ?: named.getValue(b)
            val t = h.now + 1_000
            val joined = orig + Observation.ItemRenamed(t, a, nameB)
            withCase("O57", c.variant, c.index, c.seed, { joined }) {
                val st = replay(joined)
                val j = assertNotNull(st.itemNamed(nameB), "after joining $a into \"$nameB\", that name means no item")
                assertEquals(1, st.items.values.count { key(it) == key(nameB) }, "more than one item under \"$nameB\": ${st.items}")
                assertEquals(before.items.keys - a - b + j, st.items.keys, "joining $a and $b did not leave one item of the two")
                assertEquals(before.codes.mapValues { (_, k) -> if (k == a || k == b) j else k }, st.codes, "a code linked to $a or $b does not add the joined item")
                for ((code, k) in st.codes) if (k == j) assertEquals(key(nameB), st.nameFor(code)?.let(::key), "$code does not add \"$nameB\"")
                val (w, wKey) = asOneItem(orig, a, nameA, b, nameB, j, t)
                assertEquals(
                    replay(w).entries.map { it.joinedAs(wKey, j) }, st.entries.map { it.joinedAs(j, j) },
                    "the list after joining $a into $b is not the list had they always been one item",
                )
                val hj = Hist(joined)
                val hw = Hist(w)
                for (store in hj.selections()) {
                    val xj = hj.at(store).history
                    val xw = hw.at(store).history
                    assertEquals(
                        present(xw).joinedAs(wKey, j), present(xj).joinedAs(j, j),
                        "joining $a into $b is presented otherwise than had they always been one item, with $store selected",
                    )
                    assertEquals(
                        confidence(xw).joinedAs(wKey, j), confidence(xj),
                        "joining $a into $b gave other confidence than had they always been one item, with $store selected",
                    )
                }
            }
        }
        assertTrue(joins > 0, "no generated history had a pair of items to join")
        assertTrue(bothListed > 0, "no generated join had both items on the list")
    }

    /**
     * Join at the end of a generated history with a trip in progress (one is
     * begun if none is), at least one of the two on the list.
     */
    @Test
    fun `O57 joining mid-trip leaves one entry and moves no other over random histories`() = property("O57", seed = 157) { c ->
        val rnd = c.rnd
        val h = scanning(c)
        if (!h.s.tripActive) {
            val unticked = h.s.list.filter { it !in h.s.ticked }
            if (unticked.isEmpty()) return@property
            h.tick(unticked.random(rnd))
        }
        val list = h.s.list.toList()
        val named = namedItems(h.history)
        val x = list.random(rnd)
        val y = (if (rnd.nextInt(3) != 0) list else named.keys.toList()).filter { it != x }.randomOrNull(rnd) ?: return@property
        val (a, b) = if (rnd.nextBoolean()) x to y else y to x
        val st0 = replay(h.history)
        val nameB = st0.items[b] ?: named.getValue(b)
        val entries = st0.entries
        val p0 = present(h.history)
        h.renameItem(a, nameB)
        val st = replay(h.history)
        val j = assertNotNull(st.itemNamed(nameB), "after joining $a into \"$nameB\", that name means no item")
        val ea = entries.firstOrNull { it.item == a }
        val eb = entries.firstOrNull { it.item == b }
        val firstOfTwo = entries.indexOfFirst { it.item == a || it.item == b }
        val expected = entries.mapIndexedNotNull { i, e -> if (e.item != a && e.item != b) e.item else if (i == firstOfTwo) j else null }
        assertEquals(expected, st.entries.map { it.item }, "joining $a into $b: one entry, in the earlier one's place, and the rest as they were")
        assertEquals(entries.filter { it.item != a && it.item != b }, st.entries.filter { it.item != j }, "joining $a into $b changed another entry")
        if (ea != null && eb != null) {
            assertEquals(ea.ticked && eb.ticked, st.entries.single { it.item == j }.ticked, "the joined entry is ticked unless both were ticked, or not when both were")
        }
        assertEquals(p0.others(setOf(a, b)), present(h.history).others(setOf(j)), "joining $a into $b mid-trip moved another entry")
        if (ea != null && eb != null) {
            val bothTicked = ea.ticked && eb.ticked
            h.end()
            assertEquals(!bothTicked, replay(h.history).entries.any { it.item == j }, "the trip's end took the joined entry off with not both ticked, or left it with both")
        }
    }

    @Test
    fun `O57 a joined item has the trips, statements and codes of both`() {
        val x = StoreId("x")
        val y = StoreId("y")
        val c1 = StoreId("c1")
        val c2 = StoreId("c2")
        for (v in hardVariants) withCase("O57", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.chain(CH)
            listOf(A, B, x, y, E).forEach { h.store(it) }
            h.store(c1, chain = CH); h.store(c2, chain = CH)
            val semi = key("Semi-skimmed")
            val milk = key("Milk")
            fun trip(store: StoreId, vararg names: String) {
                h.selectIfOther(store)
                names.forEach { h.add(it) }
                names.forEach { h.tick(key(it)) }
                h.end()
            }
            fun saidNotHere(store: StoreId, scope: Scope) {
                h.selectIfOther(store)
                h.add("Semi-skimmed"); h.notHere(semi, scope); h.remove(semi)
            }
            trip(A, "Semi-skimmed", "Bread")              // placed at a only as semi-skimmed
            trip(B, "Eggs", "Milk")                       // placed at b only as milk
            saidNotHere(c1, Scope.CHAIN)                  // semi-skimmed not at chain ch
            saidNotHere(x, Scope.STORE)
            trip(x, "Milk", "Tea")                        // milk bought at x after semi-skimmed said not there
            trip(y, "Tea", "Milk")
            saidNotHere(y, Scope.STORE)                   // semi-skimmed said not at y after milk bought there
            h.linkCode(CODE_POOL[0], "Semi-skimmed")
            h.linkCode(CODE_POOL[1], "milk")
            h.select(null)
            val orig = h.history
            val before = replay(orig)

            h.renameItem(semi, "Milk")
            val st = replay(h.history)
            val j = assertNotNull(st.itemNamed("milk"), "after the join \"milk\" means no item")
            assertEquals(1, st.items.values.count { key(it) == milk }, "more than one item under \"Milk\": ${st.items}")
            assertEquals(before.items.size - 1, st.items.size, "the join did not leave one item of the two")
            assertEquals(mapOf(CODE_POOL[0] to j, CODE_POOL[1] to j), st.codes, "a code linked to either does not add the joined item")
            assertEquals(milk, st.nameFor(CODE_POOL[0])?.let(::key))

            h.add("Milk")
            val expected = mapOf(
                null to LIST, A to RUN, B to RUN, x to RUN, y to ELSEWHERE, c1 to ELSEWHERE, c2 to ELSEWHERE, E to UNPLACED,
            )
            for ((store, section) in expected) {
                assertEquals(section, present(h.at(store).history).sectionOf(j), "the joined milk with $store selected")
            }
            // And as a whole: as if the two had always been one item.
            val t = h.history[orig.size].at
            val (w, wKey) = asOneItem(orig, semi, "Semi-skimmed", milk, "Milk", j, t)
            val hw = Hist(w).also { it.now = h.now - 1_000; it.add("Milk") }
            for (store in h.selections()) {
                assertEquals(
                    present(hw.at(store).history).joinedAs(wKey, j), present(h.at(store).history).joinedAs(j, j),
                    "the joined item is presented otherwise than had it always been one, with $store selected",
                )
                assertEquals(
                    confidence(hw.at(store).history).joinedAs(wKey, j), confidence(h.at(store).history),
                    "the joined item has other confidence than had it always been one, with $store selected",
                )
            }
        }
    }

    @Test
    fun `O57 two entries joined leave one, in the earlier one's place`() {
        for (v in hardVariants) for (earlierOntoLater in listOf(true, false)) withCase("O57", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(A)
            val bread = h.add("Bread")
            val semi = h.add("Semi-skimmed", "2 L")
            val eggs = h.add("Eggs")
            val milk = h.add("Milk", note = "blue top")
            val tea = h.add("Tea")
            val target = if (earlierOntoLater) "Milk" else "Semi-skimmed"
            if (earlierOntoLater) h.renameItem(semi, "Milk") else h.renameItem(milk, "Semi-skimmed")
            val st = replay(h.history)
            val j = assertNotNull(st.itemNamed(target))
            assertEquals(listOf(bread, j, eggs, tea), st.entries.map { it.item }, "joined onto \"$target\"")
            assertEquals(key(target), key(st.entries[1].name), "the joined entry is not under \"$target\"")
            assertEquals(listOf(bread, j, eggs, tea), present(h.history).keys(), "with no store selected")
            assertEquals(listOf(bread, j, eggs, tea), present(h.at(A).history).keys(), "at a store with nothing learned")
        }
    }

    @Test
    fun `O57 a joined entry is ticked only if both were, and nothing else moves`() {
        val cases = listOf(false to false, true to false, false to true, true to true)
        for (v in hardVariants) for ((tickSemi, tickMilk) in cases) withCase("O57", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(A)
            h.select(A)
            val walk = listOf("Bread", "Semi-skimmed", "Eggs", "Milk", "Tea", "Coffee")
            walk.forEach { h.add(it) }
            walk.forEach { h.tick(key(it)) }
            h.end()
            walk.forEach { h.add(it) }
            val semi = key("Semi-skimmed")
            val milk = key("Milk")
            h.tick(key("Bread"))
            if (tickSemi) h.tick(semi)
            if (tickMilk) h.tick(milk)
            h.tick(key("Tea"))
            val before = present(h.history)
            h.renameItem(semi, "Milk")
            val after = present(h.history)
            val j = assertNotNull(replay(h.history).itemNamed("Milk"))
            val e = assertNotNull(after.entry(j), "the joined entry is not presented")
            val what = "semi-skimmed ${if (tickSemi) "ticked" else "unticked"}, milk ${if (tickMilk) "ticked" else "unticked"}"
            assertEquals(tickSemi && tickMilk, e.ticked, "the joined entry's tick, $what")
            assertEquals(before.others(setOf(semi, milk)), after.others(setOf(j)), "joining mid-trip moved or changed another entry, $what")
            h.end()
            val left = replay(h.history).entries.map { it.item }
            assertEquals(!(tickSemi && tickMilk), j in left, "the trip's end and the joined entry, $what")
            assertEquals(listOf(key("Eggs"), key("Coffee")), left.filter { it != j }, "the trip's end left the wrong entries, $what")
        }
    }

    // O58 ---------------------------------------------------------------------------------------

    /**
     * An unlink inserted anywhere a code is linked. The two histories agree
     * until the code is next scanned (unlinked, the scan would have added
     * nothing, so it has no counterpart); a new link first makes them agree
     * again for good. They are compared over that stretch.
     */
    @Test
    fun `O58 unlinking forgets the link and nothing else over random histories`() {
        var unlinks = 0
        property("O58", seed = 58) { c ->
            val rnd = c.rnd
            val orig = scanning(c).history
            val n = orig.size
            val firstLink = orig.indexOfFirst { it is Observation.CodeLinked }
            if (firstLink < 0) return@property
            val i = rnd.nextInt(firstLink + 1, n + 1)
            val code = linkNames(orig.subList(0, i)).keys.random(rnd)
            val with = orig.subList(0, i) + Observation.CodeUnlinked(orig[i - 1].at, code) + orig.subList(i, n)
            var end = n
            for (x in i until n) {
                val o = orig[x]
                if (o is Observation.CodeLinked && o.code == code) break
                if (o is Observation.Added && o.code == code) { end = x; break }
            }
            unlinks++
            withCase("O58", c.variant, c.index, c.seed, { with }) {
                for (k in ((i..end).shuffled(rnd).take(5) + i + end).toSet()) {
                    val a = orig.subList(0, k).toList()
                    val b = with.subList(0, k + 1).toList()
                    val sa = replay(a)
                    val sb = replay(b)
                    val what = "$code unlinked after #${i - 1}, compared after ${k} original observations"
                    val relinked = orig.subList(i, k).any { it is Observation.CodeLinked && it.code == code }
                    if (relinked) {
                        assertEquals(sa.codes, sb.codes, "relinked, the codes differ: $what")
                    } else {
                        assertEquals(sa.codes - code, sb.codes, "the linked codes are not the same less the one unlinked: $what")
                        assertNull(sb.nameFor(code), "the unlinked code still names something: $what")
                    }
                    for (other in CODE_POOL - code) assertEquals(sa.nameFor(other), sb.nameFor(other), "unlinking $code changed what $other adds: $what")
                    assertEquals(sa.entries, sb.entries, "the list changed: $what")
                    assertEquals(sa.items, sb.items, "the items changed: $what")
                    assertEquals(present(a), present(b), "the presentation changed: $what")
                    assertEquals(confidence(a), confidence(b), "confidence changed: $what")
                }
                val ha = Hist(orig.subList(0, end))
                val hb = Hist(with.subList(0, end + 1))
                for (store in ha.selections()) {
                    assertEquals(present(ha.at(store).history), present(hb.at(store).history), "unlinking $code changed the presentation with $store selected")
                    assertEquals(confidence(ha.at(store).history), confidence(hb.at(store).history), "unlinking $code changed confidence with $store selected")
                }
                if (orig.subList(i, end).none { it is Observation.CodeLinked && it.code == code }) {
                    val f = hb.fork()
                    assertFalse(f.scanCode(code), "the unlinked $code, scanned, recorded something")
                }
            }
        }
        assertTrue(unlinks > 0, "no generated history linked a code")
    }

    @Test
    fun `O58 an unlinked code is as never linked, and nothing else changes`() {
        for (v in hardVariants) withCase("O58", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(A); h.store(B)
            h.select(A)
            val (c0, c1, c2) = Triple(CODE_POOL[0], CODE_POOL[1], CODE_POOL[2])
            h.linkCode(c0, "Sugar"); h.linkCode(c1, "sugar "); h.linkCode(c2, "Salt")
            val sugar = key("Sugar")
            assertTrue(h.scanCode(c0))
            h.add("Bread"); h.add("Tea")
            listOf(sugar, key("Bread"), key("Tea")).forEach { h.tick(it) }
            h.end()
            assertTrue(h.scanCode(c1))
            h.add("Bread"); h.add("Tea")
            h.tick(key("Bread"))
            // Mid-trip, with sugar in the Run.
            val before = h.selections().associateWith { present(h.at(it).history) }
            val conf = h.selections().associateWith { confidence(h.at(it).history) }
            val s0 = replay(h.history)
            assertEquals(RUN, before.getValue(A).sectionOf(sugar))

            h.unlinkCode(c0)
            val st = replay(h.history)
            assertNull(st.nameFor(c0), "the unlinked code still names something")
            assertTrue(c0 !in st.codes, "the unlinked code is still among the linked")
            assertEquals(mapOf(c1 to sugar, c2 to key("Salt")), st.codes, "unlinking one code changed another")
            assertEquals(s0.nameFor(c1), st.nameFor(c1))
            assertEquals(s0.entries, st.entries, "unlinking changed the list")
            assertEquals(s0.items, st.items, "unlinking changed the items")
            assertEquals(sugar, st.itemNamed("SUGAR"), "unlinking changed the item the code meant")
            for (store in h.selections()) {
                assertEquals(before.getValue(store), present(h.at(store).history), "unlinking changed the presentation with $store selected")
                assertEquals(conf.getValue(store), confidence(h.at(store).history), "unlinking changed confidence with $store selected")
            }
            // Its next scan adds nothing until it is named.
            val recorded = h.history
            assertFalse(h.scanCode(c0), "the unlinked code, scanned, recorded something")
            assertEquals(recorded, h.history)
            h.linkCode(c0, "Brown sugar")
            assertEquals(key("Brown sugar"), replay(h.history).nameFor(c0)?.let(::key), "named again, the code does not mean the new name")
            assertTrue(h.scanCode(c0))
            assertEquals(key("Brown sugar"), replay(h.history).entries.last().item, "named again, the code added something else")
            assertEquals(mapOf(c1 to sugar, c2 to key("Salt"), c0 to key("Brown sugar")), replay(h.history).codes)
        }
    }

    @Test
    fun `O58 an unlinked code named again for its old item adds it with everything learned`() {
        for (v in hardVariants) withCase("O58", v, 0, 0, { emptyList() }) {
            val h = Hist(v.prefix())
            h.store(A)
            h.select(A)
            val code = CODE_POOL[3]
            h.linkCode(code, "Sugar")
            assertTrue(h.scanCode(code))
            h.add("Bread")
            h.tick(key("Bread")); h.tick(key("Sugar"))
            h.end()
            h.add("Bread")
            h.unlinkCode(code)
            assertNull(replay(h.history).nameFor(code))
            assertEquals(key("Sugar"), replay(h.history).itemNamed("sugar"), "unlinking took the item with it")
            h.linkCode(code, "sugar")
            assertTrue(h.scanCode(code))
            assertEquals(RUN, present(h.history).sectionOf(key("Sugar")), "sugar, scanned after relinking, is not in the Run where it is placed")
        }
    }
}
