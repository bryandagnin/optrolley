package dev.optrolley.core

enum class SectionKind {
    /** The whole list in add order, when no store is selected. */
    LIST,
    UNPLACED,
    RUN,
    ELSEWHERE,
}

data class Section(val kind: SectionKind, val entries: List<Entry>)

/** What the user sees: non-empty sections, in tier 2 §6 order. */
data class Presentation(val store: StoreId?, val sections: List<Section>) {
    val elsewhereCount: Int
        get() = sections.firstOrNull { it.kind == SectionKind.ELSEWHERE }?.entries?.size ?: 0
}

/**
 * The presentation of [history] — a pure function of it (O19), using
 * [routers] to look up the method the history has chosen.
 */
fun present(history: List<Observation>, routers: Routers = Routers.all): Presentation {
    val fold = Fold(history)
    val state = fold.state()
    val store = state.selected ?: return Presentation(null, listOf(Section(SectionKind.LIST, state.entries)))
    val trips = state.trips[store].orEmpty()
    val placed = placedBy(trips)
    val unplaced = ArrayList<Entry>()
    val run = ArrayList<Entry>()
    val elsewhere = ArrayList<Entry>()
    for (e in state.entries) {
        when {
            isElsewhere(fold.availability[e.item], store, state.stores) -> elsewhere += e
            e.item in placed -> run += e
            else -> unplaced += e
        }
    }
    // The router orders every item placed at the store, not just those on the
    // list, so adding or moving an entry cannot reorder the rest (O8, O30).
    val router = routers.byId(fold.routerChoice ?: routers.defaultId)
    val rank = HashMap<ItemKey, Int>()
    for (item in safeOrder(router, trips, placed)) rank[item] = rank.size
    // Whatever the router failed to order is still shown (T1), in add order:
    // the sort is stable and the run starts in add order.
    run.sortBy { rank[it.item] ?: Int.MAX_VALUE }
    val sections = listOf(
        Section(SectionKind.UNPLACED, unplaced),
        Section(SectionKind.RUN, run),
        Section(SectionKind.ELSEWHERE, elsewhere),
    ).filter { it.entries.isNotEmpty() }
    return Presentation(store, sections)
}

/**
 * [router]'s order of [items], less anything it should not have returned:
 * duplicates, items not asked for, or everything if it throws. Placing what
 * it left out is the caller's job.
 */
internal fun safeOrder(router: Router, trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
    if (items.isEmpty()) return emptyList()
    val out = try {
        router.order(trips, items)
    } catch (e: Exception) {
        emptyList()
    }
    val seen = LinkedHashSet<ItemKey>()
    for (item in out) if (item in items) seen += item
    return seen.toList()
}

/**
 * Whether the latest standing word on an item at [store] is "not here" (O5):
 * said of the store, or of its chain from any store in it (O34), and not
 * since overridden by a purchase there (O6). Unknown is not unavailable (O25).
 */
internal fun isElsewhere(availability: Availability?, store: StoreId, stores: Map<StoreId, Store>): Boolean {
    if (availability == null) return false
    var notHere = availability.storeNotHere[store]?.lastOrNull() ?: -1
    val chain = stores[store]?.chain
    for ((said, stack) in availability.chainNotHere) {
        if (said == store || (chain != null && stores[said]?.chain == chain)) {
            notHere = maxOf(notHere, stack.lastOrNull() ?: -1)
        }
    }
    return notHere > (availability.purchased[store] ?: -1)
}
