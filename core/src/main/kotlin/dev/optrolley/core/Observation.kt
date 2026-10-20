package dev.optrolley.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One recorded user action (T5). The history is a list of these in the order
 * they happened; nothing in it is ever rewritten (O20), and everything else is
 * derived from it (O21).
 *
 * [at] is wall-clock epoch milliseconds. It is used only to measure gaps
 * between ticks (tie window) and idleness; order is the position in the list.
 */
@Serializable
sealed interface Observation {
    val at: Long

    /**
     * Adds an entry for the item [name] matches (see [ItemKey.of]). [code] is
     * the barcode whose scan added it, or null if the name was typed (D14).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("added")
    data class Added(
        override val at: Long,
        val name: String,
        val quantity: String? = null,
        val note: String? = null,
        // Left out when absent, so a typed add is written as it was before scanning existed.
        @EncodeDefault(EncodeDefault.Mode.NEVER) val code: Barcode? = null,
    ) : Observation

    /**
     * Changes an entry's details. A new [name] that matches another item
     * relinks the entry to that item; the entry keeps its place in add order.
     */
    @Serializable @SerialName("edited")
    data class Edited(override val at: Long, val item: ItemKey, val name: String, val quantity: String?, val note: String?) : Observation

    @Serializable @SerialName("removed")
    data class Removed(override val at: Long, val item: ItemKey) : Observation

    /** Marks [item] as got. [code] is the barcode whose scan ticked it, or null for a tap (D14). */
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable @SerialName("ticked")
    data class Ticked(
        override val at: Long,
        val item: ItemKey,
        @EncodeDefault(EncodeDefault.Mode.NEVER) val code: Barcode? = null,
    ) : Observation

    @Serializable @SerialName("unticked")
    data class Unticked(override val at: Long, val item: ItemKey) : Observation

    /** "Not here", said of [store] alone or of its whole chain. */
    @Serializable @SerialName("not-here")
    data class NotHere(override val at: Long, val item: ItemKey, val store: StoreId, val scope: Scope) : Observation

    /** Withdraws "not here" for the same item, store and scope. */
    @Serializable @SerialName("not-here-undone")
    data class NotHereUndone(override val at: Long, val item: ItemKey, val store: StoreId, val scope: Scope) : Observation

    @Serializable @SerialName("store-created")
    data class StoreCreated(override val at: Long, val store: StoreId, val name: String, val chain: ChainId? = null) : Observation

    @Serializable @SerialName("store-renamed")
    data class StoreRenamed(override val at: Long, val store: StoreId, val name: String) : Observation

    @Serializable @SerialName("store-chain-set")
    data class StoreChainSet(override val at: Long, val store: StoreId, val chain: ChainId?) : Observation

    @Serializable @SerialName("chain-created")
    data class ChainCreated(override val at: Long, val chain: ChainId, val name: String) : Observation

    @Serializable @SerialName("chain-renamed")
    data class ChainRenamed(override val at: Long, val chain: ChainId, val name: String) : Observation

    /** Selects a store, or none. Selecting a different one ends a trip in progress. */
    @Serializable @SerialName("selected")
    data class Selected(override val at: Long, val store: StoreId?) : Observation

    /** Ends the trip in progress, by the user or because it went idle. */
    @Serializable @SerialName("trip-ended")
    data class TripEnded(override val at: Long, val reason: EndReason) : Observation

    /** Chooses the routing method (F11). Takes effect from the next trip. */
    @Serializable @SerialName("router-chosen")
    data class RouterChosen(override val at: Long, val router: String) : Observation

    /**
     * Links [code] to the item [name] matches, replacing any earlier link for
     * that code (F12). Adds nothing to the list by itself.
     */
    @Serializable @SerialName("code-linked")
    data class CodeLinked(override val at: Long, val code: Barcode, val name: String) : Observation
}

@Serializable
enum class Scope { STORE, CHAIN }

@Serializable
enum class EndReason { USER, IDLE }

/** Item identity: the typed name, trimmed and case-folded (D10). */
@Serializable
@JvmInline
value class ItemKey(val value: String) {
    companion object {
        fun of(name: String): ItemKey = ItemKey(name.trim().lowercase())
    }
}

@Serializable
@JvmInline
value class StoreId(val value: String)

@Serializable
@JvmInline
value class ChainId(val value: String)

/** A scanned barcode, as the decoder read it, trimmed. Means nothing until linked (F12). */
@Serializable
@JvmInline
value class Barcode(val value: String) {
    companion object {
        fun of(raw: String): Barcode = Barcode(raw.trim())
    }
}
