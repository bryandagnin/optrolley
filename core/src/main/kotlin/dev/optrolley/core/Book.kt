package dev.optrolley.core

/**
 * The state and the record as the app holds them: taken up from what was
 * kept, changed one action at a time, and asked for what to show (tier 4
 * §4). Nothing here reads the journal. Not thread-safe; the caller applies
 * actions one at a time.
 */
class Book private constructor(private val fold: Fold) {
    /** Nothing yet: a fresh install. */
    constructor() : this(Fold())

    /** What [stored] and the [record] kept with it hold. */
    constructor(stored: Stored, record: List<TripRecord>) : this(Fold(stored, record))

    /** How many actions have been applied, and how many trips they put on record. */
    val actions: Int get() = fold.count
    val trips: Int get() = fold.ended.size

    private var seen: State? = null

    /** The state now. */
    val state: State get() = seen ?: fold.state().also { seen = it }

    /** Applies [action]. If it ended a trip at a store, that trip is returned, to be appended to the record. */
    fun apply(action: Observation): TripRecord? {
        seen = null
        return fold.apply(action)
    }

    /**
     * What the clock implies before a new action at [now]: the end of a trip
     * that has idled past [Rules.IDLE_TIMEOUT_MS], to be applied first, or
     * null. Stamped when the trip went idle, not when the app noticed. This
     * keeps the clock out of the presentation (O19).
     */
    fun settle(now: Long): Observation.TripEnded? {
        val trip = fold.trip ?: return null
        if (now - trip.lastAt < Rules.IDLE_TIMEOUT_MS) return null
        return Observation.TripEnded(trip.lastAt + Rules.IDLE_TIMEOUT_MS, EndReason.IDLE)
    }

    fun present(routers: Routers = Routers.all): Presentation = present(fold, routers)

    fun confidence(routers: Routers = Routers.all): Map<ItemKey, Confidence> = confidence(fold, routers)

    fun score(routers: Routers = Routers.all): Map<String, List<TripScore>> = score(fold, routers)

    /** Drops everything learned, to be worked out again from the record: what is shown must not change (O21). */
    fun relearn() = fold.forget()

    /** Everything but the record, to be kept between runs. */
    fun stored(): Stored = fold.stored()

    /**
     * Each standing "not here" about [item] that puts it in Elsewhere at
     * [at]: said of [at] itself, or of the chain [at] is in now. One said
     * twice is listed twice, since each needs undoing (O35).
     */
    fun standingNotHere(item: ItemKey, at: StoreId): List<Pair<StoreId, Scope>> {
        val said = fold.availability[item] ?: return emptyList()
        val stores = state.stores
        val chain = stores[at]?.chain
        val out = ArrayList<Pair<StoreId, Scope>>()
        said.storeNotHere[at]?.forEach { _ -> out += at to Scope.STORE }
        for ((store, stack) in said.chainNotHere) {
            if (chain != null && stores[store]?.chain == chain) stack.forEach { _ -> out += store to Scope.CHAIN }
        }
        return out
    }

    companion object {
        /** The book a list of actions gives, applied from nothing: how a first-build journal is carried over (O62). */
        fun of(actions: List<Observation>): Pair<Book, List<TripRecord>> {
            val book = Book()
            return book to actions.mapNotNull(book::apply)
        }
    }
}
