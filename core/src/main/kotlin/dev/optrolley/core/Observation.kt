package dev.optrolley.core

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One user action, as the journal keeps it (D19). Applied one at a time they
 * give the state and the record (tier 4 §4); a list of them in the order
 * they happened builds any case from nothing.
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
     * Changes an entry's details. A new [name] makes the entry one for the
     * item that name matches, a new item if none does; the entry keeps its
     * place in add order and the item it was for is left as it was. This is
     * how the first build changed a name. To correct an item's name, keeping
     * what was learned of it, see [ItemRenamed].
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

    /** Unlinks [code] from whatever item it meant: it is then a code never linked (F13, O58). */
    @Serializable @SerialName("code-unlinked")
    data class CodeUnlinked(override val at: Long, val code: Barcode) : Observation

    /**
     * Corrects the name of [item] to [name] (D10). Everything learned of the
     * item, said of it and linked to it stays with it (O55). If [name] is
     * another item's, the two become one item under that name (O57).
     */
    @Serializable @SerialName("item-renamed")
    data class ItemRenamed(override val at: Long, val item: ItemKey, val name: String) : Observation
}

@Serializable
enum class Scope { STORE, CHAIN }

@Serializable
enum class EndReason { USER, IDLE }

/**
 * An item's identity (D10). It is the name the item was first given, trimmed
 * and case-folded, with `~2`, `~3`… added if that was taken, and it never
 * changes: renaming an item changes its name, not this (tier 4 §4.1).
 */
@Serializable
@JvmInline
value class ItemKey(val value: String) {
    companion object {
        /** The identity of the first item ever given [name]; also how two names are matched. */
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
