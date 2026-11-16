package dev.optrolley.core

import kotlinx.serialization.Serializable

/**
 * One ended trip at a store, as the record keeps it (T5): where, when it
 * ended, and its final ticks as tie groups in the order they were made.
 * Appended when the trip ends and never rewritten (O20).
 */
@Serializable
data class TripRecord(val store: StoreId, val endedAt: Long, val groups: List<List<ItemKey>>)

/**
 * The state as it is kept between runs (tier 4 §7): everything the app
 * holds now except the record. [actions] and [trips] say how many actions
 * and how many trips on record it reflects, which is how a write cut short
 * is noticed and made good.
 */
@Serializable
data class Stored(
    val actions: Int,
    val trips: Int,
    val entries: List<Listed>,
    val ticks: List<Ticked>,
    val stores: List<Store>,
    val chains: List<Chain>,
    val items: List<Item>,
    val aliases: Map<ItemKey, ItemKey>,
    val codes: List<Code>,
    val said: List<Said>,
    val selected: StoreId?,
    val trip: Trip?,
    val chosen: String?,
    val tripChosen: String?,
) {
    /** An entry, and the number of the action that added it: the list is in that order. */
    @Serializable
    data class Listed(val item: ItemKey, val added: Int, val name: String, val quantity: String?, val note: String?)

    /** A final tick of the trip in progress, by the number of the action that made it. */
    @Serializable
    data class Ticked(val item: ItemKey, val seq: Int, val at: Long)

    /** An item and its current name; [known] if it has ever been on the list. */
    @Serializable
    data class Item(val item: ItemKey, val name: String, val known: Boolean)

    @Serializable
    data class Code(val code: Barcode, val item: ItemKey, val name: String)

    /**
     * What has been said of an item's availability: standing "not here"
     * statements per store, of the store or of its chain, and its latest
     * purchase per store, each by the number of the action that made it,
     * since the later of them stands (O5, O6).
     */
    @Serializable
    data class Said(
        val item: ItemKey,
        val store: Map<StoreId, List<Int>>,
        val chain: Map<StoreId, List<Int>>,
        val purchased: Map<StoreId, Int>,
    )
}
