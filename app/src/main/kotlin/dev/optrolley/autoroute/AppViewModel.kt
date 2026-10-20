package dev.optrolley.autoroute

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.optrolley.core.Barcode
import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.Export
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Routers
import dev.optrolley.core.Scope
import dev.optrolley.core.State
import dev.optrolley.core.StoreId
import dev.optrolley.core.TripScore
import dev.optrolley.core.nameFor
import dev.optrolley.core.present
import dev.optrolley.core.replay
import dev.optrolley.core.score
import dev.optrolley.core.settle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.UUID

data class UiState(
    val loaded: Boolean = false,
    val history: List<Observation> = emptyList(),
    val state: State? = null,
    val presentation: Presentation? = null,
    /** Set when the record cannot be read; nothing is written until it is fixed. */
    val fatal: String? = null,
    val message: UiMessage? = null,
    val lab: Map<String, List<TripScore>>? = null,
    /** An entry to bring into view, e.g. after adding a name already on the list. */
    val focus: ItemKey? = null,
    /** A scanned code that is not linked, waiting for the user to name it (F12). */
    val naming: Barcode? = null,
    /** What each scan this session did, oldest first, for the scanner to show. */
    val scans: List<ScanEvent> = emptyList(),
)

data class UiMessage(val text: String, val undo: List<Observation>? = null, val id: Long = System.nanoTime())

/**
 * One scan of a linked code: the item it means, whether it was added (or
 * already on the list), and whether it was ticked (scanned to tick, F12).
 */
data class ScanEvent(
    val code: Barcode,
    val item: ItemKey,
    val name: String,
    val added: Boolean,
    val ticked: Boolean = false,
    val id: Long = System.nanoTime(),
)

/**
 * Holds the history and derives everything shown from it. Each action is
 * settled against the clock first (O19 keeps the clock out of [present]),
 * then recorded and synced, then the presentation recomputed. Actions run one
 * at a time, in the order they were made.
 */
class AppViewModel(
    private val store: HistoryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val compute: CoroutineDispatcher = Dispatchers.Default,
    private val routers: Routers = Routers.all,
) : ViewModel() {
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private val lock = Mutex()
    private var history: List<Observation> = emptyList()

    init {
        serially {
            try {
                history = store.load()
            } catch (e: CorruptHistoryException) {
                _ui.update { it.copy(loaded = true, fatal = e.message) }
                return@serially
            }
            _ui.update { it.copy(loaded = true) }
            settleAndRecord(emptyList())
        }
    }

    // --- The list ---

    fun add(name: String, quantity: String?, note: String?) = act { state ->
        val key = ItemKey.of(name)
        if (key.value.isEmpty()) return@act emptyList()
        if (state.entries.any { it.item == key }) {
            _ui.update { it.copy(focus = key, message = UiMessage("${name.trim()} is already on the list")) }
            return@act emptyList()
        }
        _ui.update { it.copy(focus = key) }
        listOf(Observation.Added(clock(), name.trim(), quantity.clean(), note.clean()))
    }

    fun setTicked(item: ItemKey, ticked: Boolean) = act {
        listOf(if (ticked) Observation.Ticked(clock(), item) else Observation.Unticked(clock(), item))
    }

    fun edit(item: ItemKey, name: String, quantity: String?, note: String?) = act { state ->
        val entry = state.entries.firstOrNull { it.item == item } ?: return@act emptyList()
        val n = name.trim()
        if (n.isEmpty()) return@act emptyList()
        val q = quantity.clean()
        val o = note.clean()
        if (n == entry.name && q == entry.quantity && o == entry.note) return@act emptyList()
        listOf(Observation.Edited(clock(), item, n, q, o))
    }

    fun remove(item: ItemKey) = act { state ->
        val entry = state.entries.firstOrNull { it.item == item } ?: return@act emptyList()
        val undo = Observation.Added(0, entry.name, entry.quantity, entry.note)
        _ui.update { it.copy(message = UiMessage("Removed ${entry.name}", undo = listOf(undo))) }
        listOf(Observation.Removed(clock(), item))
    }

    /** Replays a message's undo, restamped with the time it is actually done. */
    fun undo(message: UiMessage) = act {
        message.undo.orEmpty().map { it.restamped(clock()) }
    }

    // --- Scanning (F12) ---

    /**
     * A code read by the scanner. A linked one adds its item as typing its
     * name would, or says it is already on the list; either way it is logged
     * in [UiState.scans]. One that is not linked adds nothing and waits in
     * [UiState.naming] for a name.
     *
     * With [tick], a linked code ticks its entry instead, or adds and ticks
     * it if it is not on the list; one already ticked is left as it is.
     */
    fun scanned(code: Barcode, tick: Boolean = false) = act { state ->
        val name = state.nameFor(code)
        if (name == null) {
            // One code is named at a time; another read meanwhile is let go.
            _ui.update { if (it.naming == null) it.copy(naming = code) else it }
            return@act emptyList()
        }
        logged(scan(state, code, name, tick, clock()))
    }

    /** Links [code] to the item [name] matches, then adds (or with [tick], ticks) that item as a scan of [code] would. */
    fun link(code: Barcode, name: String, tick: Boolean = false) = act { state ->
        val n = name.trim()
        if (n.isEmpty()) return@act emptyList()
        _ui.update { it.copy(naming = null) }
        val now = clock()
        listOf(Observation.CodeLinked(now, code, n)) + logged(scan(state, code, n, tick, now))
    }

    /** The user declined to name the code in [UiState.naming]; nothing is recorded. */
    fun namingDismissed() = _ui.update { it.copy(naming = null) }

    /**
     * The code of [event] means [name] instead. What that scan did is taken
     * back if it still stands, the new item is added or ticked in its place
     * as the scan did, and the log shows the new outcome where the old one was.
     */
    fun relink(event: ScanEvent, name: String) = act { state ->
        val n = name.trim()
        if (n.isEmpty()) return@act emptyList()
        val now = clock()
        val takeBack = if (ItemKey.of(n) == event.item) emptyList() else undone(state, event, now)
        val (replaced, again) = scan(state, event.code, n, event.ticked, now)
        _ui.update { ui -> ui.copy(scans = ui.scans.map { if (it.id == event.id) replaced else it }) }
        takeBack + Observation.CodeLinked(now, event.code, n) + again
    }

    /** Takes back what [event] did, if it still stands: unticks what it ticked, removes what it added. */
    fun undoScan(event: ScanEvent) = act { state -> undone(state, event, clock()) }

    /** The camera was refused: nothing changes but a word on why scanning will not open (O46). */
    fun cameraRefused() = say("Scanning needs the camera. Everything else works without it.")

    /**
     * What a scan of [code], meaning [name], does: added as typing it would,
     * or with [tick] ticked as a tap would, added first if not on the list.
     * An item already on the list is not added twice (F1). One already
     * ticked records nothing: a second tick would move its final tick, and
     * with it what the trip teaches (O49).
     */
    private fun scan(state: State, code: Barcode, name: String, tick: Boolean, now: Long): Pair<ScanEvent, List<Observation>> {
        val key = ItemKey.of(name)
        val entry = state.entries.firstOrNull { it.item == key }
        val add = Observation.Added(now, name, code = code)
        val tap = Observation.Ticked(now, key, code)
        return when {
            !tick -> ScanEvent(code, key, name, added = entry == null) to listOfNotNull(add.takeIf { entry == null })
            entry == null -> ScanEvent(code, key, name, added = true, ticked = true) to listOf(add, tap)
            entry.ticked -> ScanEvent(code, key, name, added = false) to emptyList()
            else -> ScanEvent(code, key, name, added = false, ticked = true) to listOf(tap)
        }
    }

    private fun logged(result: Pair<ScanEvent, List<Observation>>): List<Observation> {
        _ui.update { it.copy(scans = it.scans + result.first) }
        return result.second
    }

    /** What takes back [event], as far as it still stands. A tick already undone, or an add since ticked by hand, is left. */
    private fun undone(state: State, event: ScanEvent, now: Long): List<Observation> {
        val entry = state.entries.firstOrNull { it.item == event.item } ?: return emptyList()
        return when {
            event.added && (event.ticked || !entry.ticked) -> listOf(Observation.Removed(now, event.item))
            event.ticked && entry.ticked -> listOf(Observation.Unticked(now, event.item))
            else -> emptyList()
        }
    }

    // --- Availability ---

    fun notHere(item: ItemKey, scope: Scope) = act { state ->
        val selected = state.selected ?: return@act emptyList()
        if (scope == Scope.CHAIN && state.stores[selected]?.chain == null) return@act emptyList()
        listOf(Observation.NotHere(clock(), item, selected, scope))
    }

    /**
     * Withdraws every standing "not here" that applies to [item] at the
     * selected store: one said of this store, or one said of its chain from
     * any store in it.
     */
    fun availableAfterAll(item: ItemKey) = act { state ->
        val selected = state.selected ?: return@act emptyList()
        val now = clock()
        standingNotHere(history, item, selected, state).map { (store, scope) ->
            Observation.NotHereUndone(now, item, store, scope)
        }
    }

    // --- Stores and trips ---

    fun select(store: StoreId?) = act { state ->
        if (state.selected == store) emptyList() else listOf(Observation.Selected(clock(), store))
    }

    fun finishTrip() = act { state ->
        if (state.trip == null) emptyList() else listOf(Observation.TripEnded(clock(), EndReason.USER))
    }

    fun createStore(name: String, chain: ChainChoice) = act {
        val n = name.trim()
        if (n.isEmpty()) return@act emptyList()
        val now = clock()
        val (chainObs, chainId) = chain.resolve(now)
        chainObs + Observation.StoreCreated(now, StoreId(UUID.randomUUID().toString()), n, chainId)
    }

    fun updateStore(id: StoreId, name: String, chain: ChainChoice) = act { state ->
        val current = state.stores[id] ?: return@act emptyList()
        val now = clock()
        val out = mutableListOf<Observation>()
        val n = name.trim()
        if (n.isNotEmpty() && n != current.name) out += Observation.StoreRenamed(now, id, n)
        val (chainObs, chainId) = chain.resolve(now)
        if (chainId != current.chain) {
            out += chainObs
            out += Observation.StoreChainSet(now, id, chainId)
        }
        out
    }

    fun renameChain(id: ChainId, name: String) = act { state ->
        val n = name.trim()
        if (n.isEmpty() || state.chains[id]?.name == n) emptyList()
        else listOf(Observation.ChainRenamed(clock(), id, n))
    }

    // --- Routing ---

    fun chooseRouter(id: String) = act { state ->
        if (state.router == id) emptyList() else listOf(Observation.RouterChosen(clock(), id))
    }

    fun computeLab() = serially {
        val scores = withContext(compute) { score(history, routers) }
        _ui.update { it.copy(lab = scores) }
    }

    // --- Export and import ---

    fun exportTo(write: (String) -> Unit) = serially {
        try {
            write(Export.encode(history))
            say("Exported ${history.size} recorded actions")
        } catch (e: IOException) {
            say("Export failed: ${e.message}")
        }
    }

    fun importFrom(read: () -> String) = serially {
        if (history.isNotEmpty() || !store.isEmpty()) {
            say("Import only works on a fresh install. This one already has ${history.size} recorded actions; export them first if you need them, then clear the app's data.")
            return@serially
        }
        val imported = try {
            Export.decode(read())
        } catch (e: IllegalArgumentException) {
            say("That file is not an Optrolley export")
            return@serially
        } catch (e: IOException) {
            say("Could not read that file: ${e.message}")
            return@serially
        }
        store.importAll(imported)
        history = imported
        recompute()
        say("Imported ${imported.size} recorded actions")
    }

    // --- Lifecycle ---

    /** A trip that idled out while the app was away ends now (tier 4 §7). */
    fun onResume() = serially { settleAndRecord(emptyList()) }

    fun messageShown(message: UiMessage) = _ui.update { if (it.message?.id == message.id) it.copy(message = null) else it }

    fun focusShown() = _ui.update { it.copy(focus = null) }

    // --- Internals ---

    private fun act(make: (State) -> List<Observation>) = serially {
        if (_ui.value.fatal != null) return@serially
        val settled = settle(history, clock())
        val state = replay(settled)
        record(settled, make(state))
    }

    private suspend fun settleAndRecord(extra: List<Observation>) {
        if (_ui.value.fatal != null) return
        record(settle(history, clock()), extra)
    }

    /** Persists what settling appended plus [extra], then derives the new view. */
    private suspend fun record(settled: List<Observation>, extra: List<Observation>) {
        val appended = settled.drop(history.size) + extra
        if (appended.isEmpty() && _ui.value.state != null) return
        store.append(appended)
        history = history + appended
        recompute()
    }

    private suspend fun recompute() {
        val h = history
        val (state, presentation) = withContext(compute) { replay(h) to present(h, routers) }
        _ui.update { it.copy(history = h, state = state, presentation = presentation, lab = null) }
    }

    private fun say(text: String) = _ui.update { it.copy(message = UiMessage(text)) }

    private fun serially(block: suspend () -> Unit) {
        viewModelScope.launch {
            lock.withLock { withContext(io) { block() } }
        }
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
}

/** The chain picked for a store: none, one that exists, or a new one by name. */
sealed interface ChainChoice {
    data object None : ChainChoice
    data class Existing(val id: ChainId) : ChainChoice
    data class New(val name: String) : ChainChoice

    fun resolve(now: Long): Pair<List<Observation>, ChainId?> = when (this) {
        None -> emptyList<Observation>() to null
        is Existing -> emptyList<Observation>() to id
        is New -> {
            val n = name.trim()
            if (n.isEmpty()) {
                emptyList<Observation>() to null
            } else {
                val id = ChainId(UUID.randomUUID().toString())
                listOf<Observation>(Observation.ChainCreated(now, id, n)) to id
            }
        }
    }
}

/**
 * The (store, scope) pairs whose latest statement about [item] is "not here"
 * and that cover [at]: said of [at] itself, or of the chain [at] is in now.
 */
internal fun standingNotHere(
    history: List<Observation>,
    item: ItemKey,
    at: StoreId,
    state: State,
): List<Pair<StoreId, Scope>> {
    val standing = linkedMapOf<Pair<StoreId, Scope>, Boolean>()
    for (o in history) {
        when (o) {
            is Observation.NotHere -> if (o.item == item) standing[o.store to o.scope] = true
            is Observation.NotHereUndone -> if (o.item == item) standing[o.store to o.scope] = false
            else -> {}
        }
    }
    val chain = state.stores[at]?.chain
    return standing.filter { (key, on) ->
        on && when (key.second) {
            Scope.STORE -> key.first == at
            Scope.CHAIN -> chain != null && state.stores[key.first]?.chain == chain
        }
    }.keys.toList()
}

private fun Observation.restamped(at: Long): Observation = when (this) {
    is Observation.Added -> copy(at = at)
    else -> this
}
