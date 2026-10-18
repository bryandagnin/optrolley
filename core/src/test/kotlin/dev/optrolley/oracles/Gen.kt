package dev.optrolley.oracles

import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Routers
import dev.optrolley.core.Scope
import dev.optrolley.core.StoreId
import kotlin.random.Random

/** Names the generator shops for; awkward characters on purpose, for the export (O22). */
val POOL = listOf(
    "Milk", "Bread", "Eggs", "Butter", "Cheddar", "Apples", "Bananas", "Tomatoes", "Onions", "Potatoes",
    "Rice", "Pasta", "Coffee", "Tea", "Washing-up liquid", "Toilet roll", "Crème fraîche", "Jalapeños",
    "Oat milk", "Trim milk", "Yoghurt", "Chicken thighs", "Frozen peas", "Bin bags", "\"Posh\" olives",
    "Salt, sea", "Back\\slash", "日本茶", "Pizza 🍕", "Dog food",
)
val POOL_KEYS: List<ItemKey> = POOL.map(::key)

/** Another way of typing [name] that tier 3 counts as the same item. */
fun spelling(rnd: Random, name: String): String = when (rnd.nextInt(6)) {
    0 -> "  $name"
    1 -> "$name "
    2 -> name.lowercase()
    3 -> name.uppercase()
    else -> name
}

data class GenConfig(
    val steps: IntRange = 40..120,
    val routerChoices: Boolean = true,
    val unticks: Boolean = true,
)

/**
 * Drives a [Hist] with realistic use: planning between trips, then shopping
 * in bursts of ticks with gaps either side of the tie window, and the clock
 * settled before long gaps as the app does. Deterministic for a given [rnd].
 */
class Gen(val rnd: Random, val h: Hist, val cfg: GenConfig = GenConfig()) {
    /** Items whose availability, presence and name the generator leaves alone. */
    val protected = HashSet<ItemKey>()
    private val s get() = h.s

    fun setup(): Gen {
        repeat(rnd.nextInt(0, 3)) { h.chain(ChainId("c${it + 1}"), "Chain ${it + 1}") }
        repeat(rnd.nextInt(2, 6)) { newStore(h.next(1_000)) }
        repeat(rnd.nextInt(2, 8)) { add(h.next(planningGap())) }
        h.select(s.stores.keys.random(rnd))
        return this
    }

    fun run(steps: Int = cfg.steps.random(rnd)): Gen {
        repeat(steps) { step() }
        return this
    }

    /** Ends the trip in progress, if any, by the user's hand. */
    fun quiet(): Gen {
        if (s.tripActive) h.end()
        return this
    }

    fun step() {
        val gap = if (s.tripActive) shoppingGap() else planningGap()
        val t = h.now + gap
        if (gap >= SETTLE_AFTER) h.settleAt(t)
        val at = maxOf(t, h.now)
        if (s.tripActive) shop(at) else plan(at)
    }

    private fun shoppingGap(): Long = when (rnd.nextInt(100)) {
        in 0..29 -> rnd.nextLong(0, Tier3.TIE_WINDOW_MS)
        in 30..34 -> Tier3.TIE_WINDOW_MS - 1
        in 35..39 -> Tier3.TIE_WINDOW_MS
        in 40..44 -> Tier3.TIE_WINDOW_MS + 1
        in 45..84 -> rnd.nextLong(Tier3.TIE_WINDOW_MS, 120_000)
        in 85..96 -> rnd.nextLong(120_000, 1_800_000)
        97 -> Tier3.IDLE_TIMEOUT_MS - 1
        else -> rnd.nextLong(Tier3.IDLE_TIMEOUT_MS + 1, 5 * Tier3.IDLE_TIMEOUT_MS)
    }

    private fun planningGap(): Long = when (rnd.nextInt(100)) {
        in 0..79 -> rnd.nextLong(1_000, 600_000)
        in 80..94 -> rnd.nextLong(600_000, 3 * 3_600_000)
        else -> rnd.nextLong(DAY, 7 * DAY)
    }

    private fun plan(t: Long) {
        val unticked = s.list.filter { it !in s.ticked }
        val c = rnd.nextInt(100)
        when {
            c < 28 -> add(t)
            c < 36 && unticked.isNotEmpty() -> editDetails(t, unticked.random(rnd))
            c < 39 && unticked.isNotEmpty() -> rename(t, unticked)
            c < 44 && unticked.isNotEmpty() -> remove(t, unticked)
            c < 56 -> selectOther(t, preferStore = false)
            c < 82 && unticked.isNotEmpty() ->
                if (s.selected == null && rnd.nextInt(3) != 0) selectOther(t, preferStore = true) else tick(t, unticked)
            c < 90 && s.selected != null -> availability(t)
            c < 93 && cfg.routerChoices -> router(t)
            c < 95 -> newStore(t)
            c < 97 -> renameStore(t)
            c < 98 && s.chains.isNotEmpty() -> h.emit(Observation.ChainRenamed(t, s.chains.keys.random(rnd), "Chain ${rnd.nextInt(1000)}"))
            else -> add(t)
        }
    }

    private fun shop(t: Long) {
        val unticked = s.list.filter { it !in s.ticked }
        val c = rnd.nextInt(100)
        when {
            c < 55 && unticked.isNotEmpty() -> tick(t, unticked)
            c < 61 && cfg.unticks && s.ticked.isNotEmpty() -> h.emit(Observation.Unticked(t, s.ticked.random(rnd)))
            c < 67 -> add(t)
            c < 73 && s.selected != null -> availability(t)
            c < 75 && unticked.isNotEmpty() -> editDetails(t, unticked.random(rnd))
            c < 77 && unticked.isNotEmpty() -> remove(t, unticked)
            c < 79 && unticked.isNotEmpty() -> rename(t, unticked)
            c < 87 -> h.emit(Observation.TripEnded(t, EndReason.USER))
            c < 92 -> selectOther(t, preferStore = false)
            c < 96 && cfg.routerChoices -> router(t)
            c < 97 -> renameStore(t)
            else -> h.emit(Observation.TripEnded(t, EndReason.USER))
        }
    }

    fun add(t: Long) {
        val off = POOL.indices.filter { POOL_KEYS[it] !in s.list }
        val unticked = s.list.filter { it !in s.ticked }
        if (unticked.isNotEmpty() && (off.isEmpty() || rnd.nextInt(12) == 0)) {
            // A name already on the list adds nothing; tier 2 shows the entry that is there.
            val k = unticked.random(rnd)
            h.emit(Observation.Added(t, spelling(rnd, s.names.getValue(k))))
            return
        }
        if (off.isEmpty()) return
        val i = off.random(rnd)
        h.emit(Observation.Added(t, spelling(rnd, POOL[i]), quantity(), note()))
    }

    private fun quantity(): String? = when (rnd.nextInt(4)) {
        0 -> "${rnd.nextInt(1, 7)}"
        1 -> "500 g"
        else -> null
    }

    private fun note(): String? = when (rnd.nextInt(5)) {
        0 -> "ripe"
        1 -> "the big one, \"not\" the small"
        else -> null
    }

    private fun editDetails(t: Long, k: ItemKey) =
        h.emit(Observation.Edited(t, k, s.names.getValue(k), quantity(), note()))

    private fun rename(t: Long, unticked: List<ItemKey>) {
        val from = unticked.filter { it !in protected }.randomOrNull(rnd) ?: return
        val to = POOL.indices.filter { POOL_KEYS[it] !in s.list && POOL_KEYS[it] !in protected }.randomOrNull(rnd) ?: return
        h.emit(Observation.Edited(t, from, spelling(rnd, POOL[to]), quantity(), note()))
    }

    private fun remove(t: Long, unticked: List<ItemKey>) {
        val k = unticked.filter { it !in protected }.randomOrNull(rnd) ?: return
        h.emit(Observation.Removed(t, k))
    }

    private fun tick(t: Long, unticked: List<ItemKey>) = h.emit(Observation.Ticked(t, unticked.random(rnd)))

    private fun selectOther(t: Long, preferStore: Boolean) {
        val options = (listOf<StoreId?>(null) + s.stores.keys).filter { it != s.selected }
            .filter { !preferStore || it != null }
        if (options.isEmpty()) return
        h.emit(Observation.Selected(t, options.random(rnd)))
    }

    /** "Not here", or undoing one, at the selected store — only where the user could. */
    private fun availability(t: Long) {
        val store = s.selected ?: return
        val undoable = s.list.filter { it !in protected }.mapNotNull { k ->
            s.standing(k, store).lastOrNull { it.store == store && it.index > s.lastPurchase(k, store) }
        }
        if (undoable.isNotEmpty() && rnd.nextInt(3) == 0) {
            val st = undoable.random(rnd)
            h.emit(Observation.NotHereUndone(t, st.item, store, st.scope))
            return
        }
        val k = s.list.filter { it !in protected && it !in s.ticked && !s.saidNotHere(it, store) }.randomOrNull(rnd)
            ?: return
        val scope = if (s.chainOf(store) != null && rnd.nextBoolean()) Scope.CHAIN else Scope.STORE
        h.emit(Observation.NotHere(t, k, store, scope))
    }

    private fun router(t: Long) = h.emit(Observation.RouterChosen(t, Routers.all.list.random(rnd).id))

    private fun newStore(t: Long) {
        val n = s.stores.size + 1
        val chain = if (s.chains.isEmpty() || rnd.nextInt(3) == 0) null else s.chains.keys.random(rnd)
        h.emit(Observation.StoreCreated(t, StoreId("s$n"), "Store $n", chain))
    }

    private fun renameStore(t: Long) {
        val store = s.stores.keys.randomOrNull(rnd) ?: return
        h.emit(Observation.StoreRenamed(t, store, "Store ${rnd.nextInt(1000)}"))
    }

    companion object {
        const val DAY = 24 * 60 * 60 * 1_000L

        /** Gaps this long let the app's clock check run, as it does before every action. */
        const val SETTLE_AFTER = 10 * 60 * 1_000L
    }
}
