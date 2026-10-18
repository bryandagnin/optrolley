package dev.optrolley.oracles

import dev.optrolley.core.Entry
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Routers
import dev.optrolley.core.SectionKind
import dev.optrolley.core.StoreId
import dev.optrolley.core.present
import kotlin.random.Random

const val CASES = 200

/** A routing method to run a history under: none chosen (the fresh-install default), or one by id. */
data class Variant(val label: String, val routerId: String?) {
    /** Chooses the router before anything else happens, so it orders every trip (F11). */
    fun prefix(): List<Observation> =
        if (routerId == null) emptyList() else listOf(Observation.RouterChosen(T0 - 60_000, routerId))

    override fun toString() = label
}

val routerVariants: List<Variant> get() = Routers.all.list.map { Variant(it.id, it.id) }

/** Hard oracles hold whatever the method: with none chosen, and with each one. */
val hardVariants: List<Variant> get() = listOf(Variant("default", null)) + routerVariants

fun dump(h: List<Observation>, limit: Int = 400): String {
    val lines = h.mapIndexed { i, o -> "  #$i $o" }
    return if (lines.size <= limit) lines.joinToString("\n")
    else (lines.take(limit / 2) + "  …" + lines.takeLast(limit / 2)).joinToString("\n")
}

class Case(val variant: Variant, val index: Int, val seed: Long, val rnd: Random, val hist: Hist, val gen: Gen)

/** Runs [body] for [cases] generated histories under each of [variants], deterministically. */
fun property(
    oracle: String,
    seed: Long,
    variants: List<Variant> = hardVariants,
    cases: Int = CASES,
    cfg: GenConfig = GenConfig(),
    body: (Case) -> Unit,
) {
    for (v in variants) for (i in 0 until cases) {
        val caseSeed = seed * 1_000_003 + i
        val rnd = Random(caseSeed)
        val hist = Hist(v.prefix())
        val gen = Gen(rnd, hist, cfg).setup().run()
        withCase(oracle, v, i, caseSeed, { hist.history }) { body(Case(v, i, caseSeed, rnd, hist, gen)) }
    }
}

/** Adds the router, case and history to an assertion failure; lets anything else through. */
fun withCase(oracle: String, v: Variant, case: Int, seed: Long, history: () -> List<Observation>, block: () -> Unit) {
    try {
        block()
    } catch (e: AssertionError) {
        throw AssertionError(
            "$oracle failed [router=${v.label}, case=$case, seed=$seed]: ${e.message}\nHistory:\n${dump(history())}",
            e,
        )
    }
}

fun Presentation.flat(): List<Entry> = sections.flatMap { it.entries }
fun Presentation.keys(): List<ItemKey> = flat().map { it.item }
fun Presentation.sectionOf(k: ItemKey): SectionKind? = sections.firstOrNull { sec -> sec.entries.any { it.item == k } }?.kind
fun Presentation.entry(k: ItemKey): Entry? = flat().firstOrNull { it.item == k }
fun Presentation.inSection(kind: SectionKind): List<Entry> = sections.filter { it.kind == kind }.flatMap { it.entries }

/** Sections and order, ticked marks aside. */
fun Presentation.shape(): List<Pair<SectionKind, List<Entry>>> =
    sections.filter { it.entries.isNotEmpty() }.map { sec -> sec.kind to sec.entries.map { it.copy(ticked = false) } }

/** Sections and order by item alone. */
fun Presentation.layout(): List<Pair<SectionKind, List<ItemKey>>> =
    sections.filter { it.entries.isNotEmpty() }.map { sec -> sec.kind to sec.entries.map { it.item } }

/** Pairs among [among], present in both orders, whose relative order differs between them. */
fun swappedPairs(before: List<ItemKey>, after: List<ItemKey>, among: Set<ItemKey>): List<Pair<ItemKey, ItemKey>> {
    val common = among.filter { it in before && it in after }.toSet()
    val b = before.filter { it in common }
    val pos = after.filter { it in common }.withIndex().associate { it.value to it.index }
    val out = mutableListOf<Pair<ItemKey, ItemKey>>()
    for (i in b.indices) for (j in i + 1 until b.size) {
        if (pos.getValue(b[i]) > pos.getValue(b[j])) out += b[i] to b[j]
    }
    return out
}

fun isSubsequence(sub: List<ItemKey>, of: List<ItemKey>): Boolean {
    var i = 0
    for (k in of) if (i < sub.size && sub[i] == k) i++
    return i == sub.size
}

/** [h], with [store] selected at the end; unchanged if it already is. */
fun Hist.at(store: StoreId?): Hist = fork().also { it.selectIfOther(store) }

/** Every store selection there is: none, and each store. */
fun Hist.selections(): List<StoreId?> = listOf<StoreId?>(null) + s.stores.keys

/** One observation applied, with the presentations either side of it. */
class Step(
    /** Number of observations in the prefix [after] presents. */
    val k: Int,
    val event: Observation?,
    val before: Presentation?,
    val after: Presentation,
    val wasTrip: Boolean,
    val wasSelected: StoreId?,
    val wasList: List<ItemKey>,
    val wasTicked: Set<ItemKey>,
    val wasNames: Map<ItemKey, String>,
    /** The shadow after [event]; valid only during the visit. */
    val s: Shadow,
    val prefix: List<Observation>,
) {
    fun where(): String = if (event == null) "at the start" else "after #${k - 1} $event"
}

/** Presents every prefix of [history] in turn. */
fun walk(history: List<Observation>, visit: (Step) -> Unit) {
    val s = Shadow()
    var before: Presentation? = null
    for (k in 0..history.size) {
        val wasTrip = s.tripActive
        val wasSelected = s.selected
        val wasList = s.list.toList()
        val wasTicked = s.ticked.toSet()
        val wasNames = s.names.toMap()
        val event = if (k > 0) history[k - 1].also { s.apply(it) } else null
        val prefix = history.subList(0, k).toList()
        val after = present(prefix)
        visit(Step(k, event, before, after, wasTrip, wasSelected, wasList, wasTicked, wasNames, s, prefix))
        before = after
    }
}

/** Prefix lengths of [history] at which no trip or session is in progress. */
fun quietPoints(history: List<Observation>): List<Int> {
    val s = Shadow()
    val out = mutableListOf<Int>()
    for (k in 0..history.size) {
        if (k > 0) s.apply(history[k - 1])
        if (!s.tripActive) out += k
    }
    return out
}

/** The user action [o] is on the entry for [k]. */
fun acts(o: Observation, k: ItemKey): Boolean = when (o) {
    is Observation.Added -> key(o.name) == k
    is Observation.Edited -> o.item == k || key(o.name) == k
    is Observation.Removed -> o.item == k
    is Observation.Ticked -> o.item == k
    is Observation.Unticked -> o.item == k
    is Observation.NotHere -> o.item == k
    is Observation.NotHereUndone -> o.item == k
    else -> false
}

/** An equal observation that is not the same object: the history rebuilt from scratch. */
fun Observation.copied(): Observation = when (this) {
    is Observation.Added -> copy()
    is Observation.Edited -> copy()
    is Observation.Removed -> copy()
    is Observation.Ticked -> copy()
    is Observation.Unticked -> copy()
    is Observation.NotHere -> copy()
    is Observation.NotHereUndone -> copy()
    is Observation.StoreCreated -> copy()
    is Observation.StoreRenamed -> copy()
    is Observation.StoreChainSet -> copy()
    is Observation.ChainCreated -> copy()
    is Observation.ChainRenamed -> copy()
    is Observation.Selected -> copy()
    is Observation.TripEnded -> copy()
    is Observation.RouterChosen -> copy()
}
