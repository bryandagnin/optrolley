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

/** The state a list of actions gives, applied one at a time from nothing (tier 4 §4, P1). */
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

/**
 * The state and the record, taking one action at a time (tier 4 §4). Internal
 * so the presentation and lab can see beyond [State].
 */
internal class Fold() {
    constructor(history: List<Observation>) : this() {
        history.forEach(::apply)
    }

    private class Live(var item: ItemKey, val addSeq: Int, var name: String, var quantity: String?, var note: String?)

    private class Tick(val seq: Int, val at: Long)

    /** Actions applied so far; each is numbered by how many came before it. */
    var count = 0
        private set

    private val entries = HashMap<ItemKey, Live>()

    /** Final ticks of the trip in progress; an entry is ticked iff its item is here. */
    private val ticks = HashMap<ItemKey, Tick>()
    private val stores = LinkedHashMap<StoreId, Store>()
    private val chains = LinkedHashMap<ChainId, Chain>()

    /** The record: each store's ended trips as they were walked, naming items as they were then (O20). */
    private val trips = LinkedHashMap<StoreId, MutableList<TripOrder>>()

    /** [trips] with joined items read as the one they became; emptied whenever that could change. */
    private val tripsNow = HashMap<StoreId, List<TripOrder>>()

    /** Every item, by its current name (D10). */
    private val items = LinkedHashMap<ItemKey, String>()

    /** The item each current name means, by the name case-folded. */
    private val named = HashMap<String, ItemKey>()

    /** Items joined into another, and which (tier 4 §4.1). Flat: no value is itself a key (P4). */
    private val aliases = HashMap<ItemKey, ItemKey>()

    /** The items that have been on the list, for suggestions. */
    private val known = LinkedHashMap<ItemKey, String>()

    /** Latest link per code. A link is to an item, so it follows the item through a rename (O55). */
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

    /** The trips on record at [store], oldest first, with joined items read as the one they became. */
    fun tripsAt(store: StoreId): List<TripOrder> {
        val walked = trips[store] ?: return emptyList()
        if (aliases.isEmpty()) return walked
        return tripsNow.getOrPut(store) { walked.map(::joined) }
    }

    /** An item joined into another is read as that one; ticked twice in a trip, it counts at the later tick (O31). */
    private fun joined(trip: TripOrder): TripOrder {
        val seen = HashSet<ItemKey>()
        val groups = ArrayList<Set<ItemKey>>()
        for (group in trip.groups.asReversed()) {
            val now = LinkedHashSet<ItemKey>()
            for (item in group) {
                val one = now(item)
                if (seen.add(one)) now += one
            }
            if (now.isNotEmpty()) groups += now
        }
        return TripOrder(groups.asReversed())
    }

    private fun now(item: ItemKey): ItemKey = aliases[item] ?: item

    /** The item [name] means (tier 3, item match), made if there is none. */
    private fun itemFor(name: String): ItemKey {
        val folded = ItemKey.of(name).value
        named[folded]?.let { return it }
        var id = ItemKey(folded)
        var n = 2
        while (id in items || id in aliases) id = ItemKey("$folded~${n++}")
        items[id] = name.trim()
        named[folded] = id
        return id
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
            trips = trips.keys.associateWith { tripsAt(it).toList() },
            known = LinkedHashMap(known),
            router = Routers.all.byId(routerChoice ?: Routers.all.defaultId).id,
            codes = LinkedHashMap(codes),
            linkedNames = LinkedHashMap(linkedNames),
            items = LinkedHashMap(items),
        )
    }

    fun apply(o: Observation) {
        val seq = count++
        trip?.let { trip = it.copy(lastAt = o.at) }
        when (o) {
            is Observation.Added -> {
                val key = itemFor(o.name)
                val name = o.name.trim()
                items[key] = name
                known[key] = name
                if (key !in entries) entries[key] = Live(key, seq, name, o.quantity, o.note)
            }
            is Observation.Edited -> edit(o)
            is Observation.Removed -> {
                entries.remove(now(o.item))
                ticks.remove(now(o.item))
            }
            is Observation.Ticked -> if (now(o.item) in entries) {
                if (trip == null) {
                    trip = Trip(selected, o.at, o.at)
                    tripChosen = chosen
                }
                ticks[now(o.item)] = Tick(seq, o.at)
            }
            is Observation.Unticked -> ticks.remove(now(o.item))
            is Observation.NotHere -> availability.getOrPut(now(o.item), ::Availability)
                .slot(o.scope).getOrPut(o.store, ::ArrayDeque).addLast(seq)
            is Observation.NotHereUndone -> availability[now(o.item)]?.slot(o.scope)?.get(o.store)?.removeLastOrNull()
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
                codes[o.code] = itemFor(o.name)
                linkedNames[o.code] = o.name.trim()
            }
            is Observation.CodeUnlinked -> {
                codes.remove(o.code)
                linkedNames.remove(o.code)
            }
            is Observation.ItemRenamed -> rename(o)
        }
    }

    private fun Availability.slot(scope: Scope) = if (scope == Scope.STORE) storeNotHere else chainNotHere

    private fun edit(o: Observation.Edited) {
        val from = now(o.item)
        if (from !in entries) return
        val key = itemFor(o.name)
        val name = o.name.trim()
        items[key] = name
        known[key] = name
        val live = moved(from, key)!!
        live.name = name
        live.quantity = o.quantity
        live.note = o.note
    }

    /**
     * The entry for [from] becomes the entry for [to], and is returned; null
     * if there was none. If [to] had an entry too the two become one: the
     * earlier place in add order is kept, and it stays ticked only if both
     * were, since anything else would let a trip end take away an entry not
     * yet got (T1, O57).
     */
    private fun moved(from: ItemKey, to: ItemKey): Live? {
        var live = entries[from] ?: return null
        if (to == from) return live
        entries.remove(from)
        val tick = ticks.remove(from)
        val other = entries[to]
        if (other == null) {
            if (tick != null) ticks[to] = tick
        } else {
            val there = ticks[to]
            if (tick != null && there != null) {
                ticks[to] = if (tick.seq > there.seq) tick else there
            } else {
                ticks.remove(to)
            }
            if (other.addSeq < live.addSeq) {
                other.quantity = other.quantity ?: live.quantity
                other.note = other.note ?: live.note
                live = other
            } else {
                live.quantity = live.quantity ?: other.quantity
                live.note = live.note ?: other.note
            }
        }
        live.item = to
        entries[to] = live
        return live
    }

    private fun rename(o: Observation.ItemRenamed) {
        val item = now(o.item)
        val old = items[item] ?: return
        val name = o.name.trim()
        if (name.isEmpty()) return
        val folded = ItemKey.of(name).value
        val other = named[folded]
        if (other == null || other == item) {
            named.remove(ItemKey.of(old).value)
            named[folded] = item
            call(item, name)
        } else {
            join(item, other, old)
            call(other, name)
        }
    }

    /** [item] is now called [name], wherever its name is kept. */
    private fun call(item: ItemKey, name: String) {
        items[item] = name
        if (item in known) known[item] = name
        entries[item]?.name = name
        for ((code, linked) in codes) if (linked == item) linkedNames[code] = name
    }

    /**
     * [from], till now called [old], becomes [to] (O57): what was walked,
     * said and linked of either is then true of the one. The record is left
     * as it was walked and read through [aliases] from here on (O20).
     */
    private fun join(from: ItemKey, to: ItemKey, old: String) {
        named.remove(ItemKey.of(old).value)
        items.remove(from)
        aliases[from] = to
        for (alias in aliases.keys.toList()) if (aliases[alias] == from) aliases[alias] = to
        tripsNow.clear()
        if (known.remove(from) != null && to !in known) known[to] = items.getValue(to)
        for (code in codes.keys.toList()) if (codes[code] == from) codes[code] = to
        availability.remove(from)?.let { said ->
            val both = availability.getOrPut(to, ::Availability)
            for ((mine, theirs) in listOf(both.storeNotHere to said.storeNotHere, both.chainNotHere to said.chainNotHere)) {
                for ((store, stack) in theirs) {
                    val merged = (mine[store].orEmpty() + stack).sorted()
                    mine[store] = ArrayDeque(merged)
                }
            }
            for ((store, seq) in said.purchased) both.purchased[store] = maxOf(both.purchased[store] ?: -1, seq)
        }
        moved(from, to)
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
            tripsNow.remove(store)
            for (e in final) {
                val purchased = availability.getOrPut(e.key, ::Availability).purchased
                purchased[store] = maxOf(purchased[store] ?: -1, e.value.seq)
            }
        }
        for (item in ticks.keys) entries.remove(item)
        ticks.clear()
    }
}
