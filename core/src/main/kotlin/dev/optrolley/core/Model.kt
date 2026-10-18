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
    /** Every name ever added, latest spelling per item, for suggestions. */
    val known: Map<ItemKey, String>,
    /** The routing method that orders the current (or next) trip. */
    val router: String,
)

/** Rebuilds the state from a history alone (O21). */
fun replay(history: List<Observation>): State = TODO()

/**
 * Appends what the clock implies before a new action at [now]: a
 * [Observation.TripEnded] with [EndReason.IDLE] if the trip in progress has
 * idled past [Rules.IDLE_TIMEOUT_MS]. The app calls this before recording
 * each action, which keeps the clock out of the presentation (O19).
 */
fun settle(history: List<Observation>, now: Long): List<Observation> = TODO()
