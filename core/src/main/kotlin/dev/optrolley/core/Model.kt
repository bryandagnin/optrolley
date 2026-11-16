package dev.optrolley.core

/** An item's presence on the list. */
data class Entry(
    val item: ItemKey,
    val name: String,
    val quantity: String?,
    val note: String?,
    val ticked: Boolean,
)

data class Store(val id: StoreId, val name: String, val chain: ChainId?)

data class Chain(val id: ChainId, val name: String)

/** The trip in progress, if any. [store] is null for a session with no store. */
data class Trip(val store: StoreId?, val startedAt: Long, val lastAt: Long)

/**
 * Everything derived from a history. Built only by [replay]; never stored as
 * the record (T5).
 */
data class State(
    /** The list, in add order. */
    val entries: List<Entry>,
    val stores: Map<StoreId, Store>,
    val chains: Map<ChainId, Chain>,
    val selected: StoreId?,
    val trip: Trip?,
    /** Ended trips per store, oldest first. */
    val trips: Map<StoreId, List<TripOrder>>,
    /** Every item ever added, by its current name, for suggestions. */
    val known: Map<ItemKey, String>,
    /** The routing method that orders the current (or next) trip. */
    val router: String,
    /** Each linked code and the item its latest link names (F12). */
    val codes: Map<Barcode, ItemKey>,
    /** Each linked code's name as linked: the spelling of last resort for an item never typed. */
    internal val linkedNames: Map<Barcode, String> = emptyMap(),
    /** Every item there is, added or only linked to, by its current name (D10). */
    val items: Map<ItemKey, String> = emptyMap(),
)

/** The item a typed [name] means, or null if it would be a new one (tier 3, item match). */
fun State.itemNamed(name: String): ItemKey? {
    val wanted = ItemKey.of(name).value
    return items.entries.firstOrNull { ItemKey.of(it.value).value == wanted }?.key
}

/**
 * The name a scan of [code] adds: the latest spelling of the item it is
 * linked to, or null if it is not linked (F12).
 */
fun State.nameFor(code: Barcode): String? {
    val item = codes[code] ?: return null
    return known[item] ?: linkedNames[code]
}

/** Rebuilds the state from a history alone (O21). */
fun replay(history: List<Observation>): State = Fold(history).state()

/**
 * Appends what the clock implies before a new action at [now]: a
 * [Observation.TripEnded] with [EndReason.IDLE] if the trip in progress has
 * idled past [Rules.IDLE_TIMEOUT_MS]. The app calls this before recording
 * each action, which keeps the clock out of the presentation (O19).
 */
fun settle(history: List<Observation>, now: Long): List<Observation> {
    val trip = Fold(history).trip ?: return history
    if (now - trip.lastAt < Rules.IDLE_TIMEOUT_MS) return history
    // Stamped when the trip went idle, not when the app noticed, so the record
    // says when the trip ended whatever the user did in between.
    return history + Observation.TripEnded(trip.lastAt + Rules.IDLE_TIMEOUT_MS, EndReason.IDLE)
}

/** Items placed by [trips]: ticked in a trip that has more than one tie group. */
internal fun placedBy(trips: List<TripOrder>): Set<ItemKey> {
    val placed = HashSet<ItemKey>()
    for (trip in trips) if (trip.groups.size > 1) trip.groups.forEach { placed.addAll(it) }
    return placed
}

/**
 * What "not here" and purchases have said about one item, per store. Each
 * slot of "not here" is a stack so that undoing one restores exactly what
 * stood before it was said (O35). Values are history positions.
 */
internal class Availability {
    val storeNotHere = HashMap<StoreId, ArrayDeque<Int>>()
    val chainNotHere = HashMap<StoreId, ArrayDeque<Int>>()
    val purchased = HashMap<StoreId, Int>()
}

/** One ended trip at a store, in the order trips ended across all stores. */
internal data class EndedTrip(val store: StoreId, val index: Int)

/** The replay fold. Internal so the presentation and lab can see beyond [State]. */
internal class Fold(history: List<Observation>) {
    private class Live(var item: ItemKey, val addSeq: Int, var name: String, var quantity: String?, var note: String?)

    private class Tick(val seq: Int, val at: Long)

    private val entries = HashMap<ItemKey, Live>()

    /** Final ticks of the trip in progress; an entry is ticked iff its item is here. */
    private val ticks = HashMap<ItemKey, Tick>()
    private val stores = LinkedHashMap<StoreId, Store>()
    private val chains = LinkedHashMap<ChainId, Chain>()
    private val trips = LinkedHashMap<StoreId, MutableList<TripOrder>>()
    private val known = LinkedHashMap<ItemKey, String>()

    /** Latest link per code. A link names an item, so an entry renamed later leaves its code where it was. */
    private val codes = LinkedHashMap<Barcode, ItemKey>()
    private val linkedNames = LinkedHashMap<Barcode, String>()

    val availability = HashMap<ItemKey, Availability>()
    val ended = ArrayList<EndedTrip>()
    var selected: StoreId? = null
        private set
    var trip: Trip? = null
        private set

    /** The latest router choice, raw; null if none was ever made. */
    private var chosen: String? = null

    /** The choice in force for the trip in progress, frozen when it began. */
    private var tripChosen: String? = null

    /** The router choice ordering the current trip, or the next one if none is in progress. */
    val routerChoice: String? get() = if (trip != null) tripChosen else chosen

    init {
        history.forEachIndexed(::apply)
    }

    fun state(): State {
        val list = entries.values.sortedBy { it.addSeq }.map {
            Entry(it.item, it.name, it.quantity, it.note, ticked = it.item in ticks)
        }
        return State(
            entries = list,
            stores = LinkedHashMap(stores),
            chains = LinkedHashMap(chains),
            selected = selected,
            trip = trip,
            trips = trips.mapValues { it.value.toList() },
            known = LinkedHashMap(known),
            router = Routers.all.byId(routerChoice ?: Routers.all.defaultId).id,
            codes = LinkedHashMap(codes),
            linkedNames = LinkedHashMap(linkedNames),
        )
    }

    private fun apply(seq: Int, o: Observation) {
        trip?.let { trip = it.copy(lastAt = o.at) }
        when (o) {
            is Observation.Added -> {
                val key = ItemKey.of(o.name)
                val name = o.name.trim()
                known[key] = name
                if (key !in entries) entries[key] = Live(key, seq, name, o.quantity, o.note)
            }
            is Observation.Edited -> edit(o)
            is Observation.Removed -> {
                entries.remove(o.item)
                ticks.remove(o.item)
            }
            is Observation.Ticked -> if (o.item in entries) {
                if (trip == null) {
                    trip = Trip(selected, o.at, o.at)
                    tripChosen = chosen
                }
                ticks[o.item] = Tick(seq, o.at)
            }
            is Observation.Unticked -> ticks.remove(o.item)
            is Observation.NotHere -> availability.getOrPut(o.item, ::Availability)
                .slot(o.scope).getOrPut(o.store, ::ArrayDeque).addLast(seq)
            is Observation.NotHereUndone -> availability[o.item]?.slot(o.scope)?.get(o.store)?.removeLastOrNull()
            is Observation.StoreCreated -> if (o.store !in stores) stores[o.store] = Store(o.store, o.name, o.chain)
            is Observation.StoreRenamed -> stores[o.store]?.let { stores[o.store] = it.copy(name = o.name) }
            is Observation.StoreChainSet -> stores[o.store]?.let { stores[o.store] = it.copy(chain = o.chain) }
            is Observation.ChainCreated -> if (o.chain !in chains) chains[o.chain] = Chain(o.chain, o.name)
            is Observation.ChainRenamed -> chains[o.chain]?.let { chains[o.chain] = it.copy(name = o.name) }
            is Observation.Selected -> if (o.store != selected) {
                endTrip()
                selected = o.store
            }
            is Observation.TripEnded -> endTrip()
            is Observation.RouterChosen -> chosen = o.router
            is Observation.CodeLinked -> {
                codes[o.code] = ItemKey.of(o.name)
                linkedNames[o.code] = o.name.trim()
            }
            is Observation.CodeUnlinked -> TODO("tier 4 §10 step 2")
            is Observation.ItemRenamed -> TODO("tier 4 §10 step 2")
        }
    }

    private fun Availability.slot(scope: Scope) = if (scope == Scope.STORE) storeNotHere else chainNotHere

    private fun edit(o: Observation.Edited) {
        var live = entries[o.item] ?: return
        val key = ItemKey.of(o.name)
        val name = o.name.trim()
        known[key] = name
        if (key != o.item) {
            entries.remove(o.item)
            val moved = ticks.remove(o.item)
            val other = entries[key]
            if (other == null) {
                if (moved != null) ticks[key] = moved
            } else {
                // Two entries become one item. The earlier place in add order
                // is kept, and it stays ticked only if both were: anything
                // else would let a trip end take away an entry not yet got (T1).
                val there = ticks[key]
                if (moved != null && there != null) {
                    ticks[key] = if (moved.seq > there.seq) moved else there
                } else {
                    ticks.remove(key)
                }
                if (other.addSeq < live.addSeq) live = other
            }
            live.item = key
            entries[key] = live
        }
        live.name = name
        live.quantity = o.quantity
        live.note = o.note
    }

    private fun endTrip() {
        val ending = trip ?: return
        trip = null
        tripChosen = null
        val store = ending.store
        if (store != null && ticks.isNotEmpty()) {
            val final = ticks.entries.sortedBy { it.value.seq }
            val groups = ArrayList<Set<ItemKey>>()
            var group = LinkedHashSet<ItemKey>()
            var prevAt = 0L
            for ((i, e) in final.withIndex()) {
                // A gap that runs backwards (clock change) is treated as a tie: it says nothing.
                if (i > 0 && e.value.at - prevAt >= Rules.TIE_WINDOW_MS) {
                    groups += group
                    group = LinkedHashSet()
                }
                group += e.key
                prevAt = e.value.at
            }
            groups += group
            val storeTrips = trips.getOrPut(store, ::ArrayList)
            ended += EndedTrip(store, storeTrips.size)
            storeTrips += TripOrder(groups)
            for (e in final) {
                val purchased = availability.getOrPut(e.key, ::Availability).purchased
                purchased[store] = maxOf(purchased[store] ?: -1, e.value.seq)
            }
        }
        for (item in ticks.keys) entries.remove(item)
        ticks.clear()
    }
}
