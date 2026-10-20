package dev.optrolley.oracles

import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Scope
import dev.optrolley.core.StoreId
import dev.optrolley.core.settle

/** Tier 3's values, restated so the oracles do not take them from the code under test. */
object Tier3 {
    const val TIE_WINDOW_MS = 5_000L
    const val IDLE_TIMEOUT_MS = 2 * 60 * 60 * 1_000L
}

/** Clock origin for generated histories. Any fixed value will do (O19). */
const val T0 = 1_760_000_000_000L

fun key(name: String): ItemKey = ItemKey.of(name)

/** A trip's final tick of [item]: when it was made and its position in the history. */
data class Tick(val item: ItemKey, val at: Long, val index: Int)

/** One ended trip as tier 3 defines it: its store (null for no store) and its final ticks. */
class EndedTrip(val store: StoreId?, val ticks: List<Tick>, val endIndex: Int) {
    val items: Set<ItemKey> = ticks.map { it.item }.toSet()

    /** Trip order: tie groups over the final ticks, chained by the tie window. */
    val groups: List<Set<ItemKey>> = tieGroups(ticks)
}

fun tieGroups(ticks: List<Tick>): List<Set<ItemKey>> {
    val out = mutableListOf<MutableSet<ItemKey>>()
    var prev: Long? = null
    for (t in ticks.sortedWith(compareBy({ it.at }, { it.index }))) {
        if (prev == null || t.at - prev >= Tier3.TIE_WINDOW_MS) out += mutableSetOf(t.item) else out.last() += t.item
        prev = t.at
    }
    return out
}

/** A "not here" as said, and whether it has since been withdrawn. */
class Statement(val item: ItemKey, val store: StoreId, val scope: Scope, val index: Int) {
    var undone = false
}

/**
 * What tiers 2 and 3 say a history means, derived independently of the code
 * under test: the list, the trips and the statements. Used to generate
 * sensible actions and to state what the oracles expect.
 */
class Shadow {
    val chains = LinkedHashMap<ChainId, String>()
    val stores = LinkedHashMap<StoreId, ChainId?>()
    val storeNames = HashMap<StoreId, String>()

    /** Item keys on the list, in add order. */
    val list = mutableListOf<ItemKey>()
    val names = HashMap<ItemKey, String>()
    val ticked = LinkedHashSet<ItemKey>()
    var selected: StoreId? = null
        private set
    var tripActive = false
        private set
    var tripStore: StoreId? = null
        private set
    private val current = LinkedHashMap<ItemKey, Tick>()
    val ended = mutableListOf<EndedTrip>()
    val everTicked = HashSet<Pair<StoreId, ItemKey>>()
    val statements = mutableListOf<Statement>()
    var size = 0
        private set
    var lastAt = Long.MIN_VALUE
        private set

    fun apply(o: Observation) {
        val index = size++
        if (o.at > lastAt) lastAt = o.at
        when (o) {
            is Observation.Added -> {
                val k = key(o.name)
                if (k !in list) {
                    list += k
                    names[k] = o.name
                }
            }
            is Observation.Edited -> {
                val i = list.indexOf(o.item)
                if (i >= 0) {
                    val k = key(o.name)
                    if (k != o.item) {
                        list[i] = k
                        names.remove(o.item)
                    }
                    names[k] = o.name
                }
            }
            is Observation.Removed -> {
                list.remove(o.item)
                names.remove(o.item)
                ticked.remove(o.item)
                current.remove(o.item)
            }
            is Observation.Ticked -> {
                if (!tripActive) {
                    tripActive = true
                    tripStore = selected
                }
                ticked += o.item
                current[o.item] = Tick(o.item, o.at, index)
                tripStore?.let { everTicked += it to o.item }
            }
            is Observation.Unticked -> {
                ticked.remove(o.item)
                current.remove(o.item)
            }
            is Observation.NotHere -> statements += Statement(o.item, o.store, o.scope, index)
            is Observation.NotHereUndone ->
                statements.lastOrNull { !it.undone && it.item == o.item && it.store == o.store && it.scope == o.scope }
                    ?.undone = true
            is Observation.StoreCreated -> {
                stores[o.store] = o.chain
                storeNames[o.store] = o.name
            }
            is Observation.StoreRenamed -> storeNames[o.store] = o.name
            is Observation.StoreChainSet -> stores[o.store] = o.chain
            is Observation.ChainCreated -> chains[o.chain] = o.name
            is Observation.ChainRenamed -> chains[o.chain] = o.name
            is Observation.Selected -> {
                if (tripActive && o.store != selected) endTrip(index)
                selected = o.store
            }
            is Observation.TripEnded -> if (tripActive) endTrip(index)
            is Observation.RouterChosen -> {}
            is Observation.CodeLinked -> {}
        }
    }

    private fun endTrip(index: Int) {
        ended += EndedTrip(tripStore, current.values.filter { it.item in ticked }, index)
        list.removeAll(ticked)
        ticked.forEach { names.remove(it) }
        ticked.clear()
        current.clear()
        tripActive = false
        tripStore = null
    }

    fun chainOf(store: StoreId): ChainId? = stores[store]

    /** Whether [st] is a statement about [store] or its chain. */
    fun applies(st: Statement, store: StoreId): Boolean = when (st.scope) {
        Scope.STORE -> st.store == store
        Scope.CHAIN -> chainOf(st.store).let { it != null && it == chainOf(store) }
    }

    /** Statements about [item] at [store] or its chain that have not been withdrawn. */
    fun standing(item: ItemKey, store: StoreId): List<Statement> =
        statements.filter { !it.undone && it.item == item && applies(it, store) }

    /** History index of the latest final tick of [item] in an ended trip at [store], or -1. */
    fun lastPurchase(item: ItemKey, store: StoreId): Int =
        ended.filter { it.store == store }.flatMap { it.ticks }.filter { it.item == item }.maxOfOrNull { it.index } ?: -1

    /** Whether the most recent explicit statement about [item] at [store] says it is not there. */
    fun saidNotHere(item: ItemKey, store: StoreId): Boolean =
        standing(item, store).any { it.index > lastPurchase(item, store) }

    /** Tier 3's "placed": some ended trip at [store] ticked it in a different tie group from another item. */
    fun placed(item: ItemKey, store: StoreId): Boolean =
        ended.any { it.store == store && it.groups.size >= 2 && item in it.items }
}

/** A history under construction, with its [Shadow] kept in step. */
class Hist(initial: List<Observation> = emptyList(), start: Long = T0) {
    private val obs = ArrayList<Observation>()
    val s = Shadow()
    var now = start

    init {
        initial.forEach { emit(it) }
    }

    val history: List<Observation> get() = obs.toList()
    val size: Int get() = obs.size

    fun emit(o: Observation) {
        obs += o
        s.apply(o)
        if (o.at > now) now = o.at
    }

    fun next(gap: Long): Long {
        now += gap
        return now
    }

    fun fork(): Hist = Hist(obs, now)

    fun chain(id: ChainId, name: String = id.value, gap: Long = 1_000) =
        emit(Observation.ChainCreated(next(gap), id, name))

    fun store(id: StoreId, name: String = id.value, chain: ChainId? = null, gap: Long = 1_000) =
        emit(Observation.StoreCreated(next(gap), id, name, chain))

    fun add(name: String, quantity: String? = null, note: String? = null, gap: Long = 1_000): ItemKey {
        emit(Observation.Added(next(gap), name, quantity, note))
        return key(name)
    }

    fun tick(item: ItemKey, gap: Long = 10_000) = emit(Observation.Ticked(next(gap), item))
    fun untick(item: ItemKey, gap: Long = 10_000) = emit(Observation.Unticked(next(gap), item))
    fun remove(item: ItemKey, gap: Long = 1_000) = emit(Observation.Removed(next(gap), item))

    fun edit(item: ItemKey, name: String, quantity: String?, note: String?, gap: Long = 1_000) =
        emit(Observation.Edited(next(gap), item, name, quantity, note))

    fun notHere(item: ItemKey, scope: Scope = Scope.STORE, store: StoreId = s.selected!!, gap: Long = 1_000) =
        emit(Observation.NotHere(next(gap), item, store, scope))

    fun undoNotHere(item: ItemKey, scope: Scope = Scope.STORE, store: StoreId = s.selected!!, gap: Long = 1_000) =
        emit(Observation.NotHereUndone(next(gap), item, store, scope))

    fun select(store: StoreId?, gap: Long = 1_000) = emit(Observation.Selected(next(gap), store))

    /** Selects [store] unless it already is; re-selecting is not an action the oracles speak of. */
    fun selectIfOther(store: StoreId?, gap: Long = 1_000) {
        if (store != s.selected) select(store, gap)
    }

    fun end(reason: EndReason = EndReason.USER, gap: Long = 1_000) = emit(Observation.TripEnded(next(gap), reason))
    fun router(id: String, gap: Long = 1_000) = emit(Observation.RouterChosen(next(gap), id))

    /** What the app does before recording an action at [t]: let the clock end an idle trip. */
    fun settleAt(t: Long) {
        val before = history
        val after = settle(before, t)
        if (after.size < before.size || after.subList(0, before.size) != before) {
            throw AssertionError("settle altered or removed recorded observations (O20)")
        }
        after.drop(before.size).forEach { emit(it) }
        if (t > now) now = t
    }

    /** Leaves the trip in progress idle past the timeout, then settles. */
    fun idle() = settleAt(now + Tier3.IDLE_TIMEOUT_MS + 1)
}
