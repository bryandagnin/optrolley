package dev.optrolley.core

/** One ended trip's final ticks, as tie groups in the order they were made. */
data class TripOrder(val groups: List<Set<ItemKey>>)

/**
 * A routing method: turns a store's ended trips into a walking order.
 *
 * Which items are placed is not the router's business — that is fixed by
 * tier 3's definition and computed by the presentation. The router only
 * orders them. It must be deterministic (O19).
 */
interface Router {
    /** Stable id; recorded in [Observation.RouterChosen] and in exports. */
    val id: String

    /** Short human name for the settings and lab screens. */
    val label: String

    /**
     * Orders [items] for a store whose ended trips are [trips], oldest first.
     * Returns every one of [items] exactly once.
     */
    fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey>
}
