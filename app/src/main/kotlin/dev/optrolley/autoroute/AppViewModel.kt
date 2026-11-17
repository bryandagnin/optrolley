package dev.optrolley.autoroute

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.optrolley.core.Backup
import dev.optrolley.core.Barcode
import dev.optrolley.core.Book
import dev.optrolley.core.ChainId
import dev.optrolley.core.Confidence
import dev.optrolley.core.EndReason
import dev.optrolley.core.Entry
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.Routers
import dev.optrolley.core.Scope
import dev.optrolley.core.State
import dev.optrolley.core.StoreId
import dev.optrolley.core.TripRecord
import dev.optrolley.core.TripScore
import dev.optrolley.core.itemNamed
import dev.optrolley.core.nameFor
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
    /** The journal, read from its file only if something asks for it. */
    val history: List<Observation> = emptyList(),
    val state: State? = null,
    val presentation: Presentation? = null,
    /** How well each Run entry's place is borne out (D16), by item. */
    val confidence: Map<ItemKey, Confidence> = emptyMap(),
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

/**
 * A word to the user. [undo] makes what takes it back from the state when
 * Undo is pressed, not when the message is shown: the item it names may have
 * been renamed in between, and its old name would then make a new one (O56).
 */
data class UiMessage(val text: String, val undo: ((State, Long) -> List<Observation>)? = null, val id: Long = System.nanoTime())

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
 * Holds the state and the record, and derives everything shown from them.
 * Each action is settled against the clock first (O19 keeps the clock out of
 * the presentation), applied, kept and synced, then the presentation
 * recomputed. Actions run one at a time, in the order they were made. The
 * journal is written to and not read (tier 4 §7).
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
    private val kept = Kept(store)
    private var book = Book()

    init {
        serially {
            try {
                book = kept.open()
            } catch (e: CorruptHistoryException) {
                _ui.update { it.copy(loaded = true, fatal = e.message) }
                return@serially
            }
            _ui.update { it.copy(loaded = true) }
            settled()
        }
    }

    // --- The list ---

    fun add(name: String, quantity: String?, note: String?) = inSteps(
        { state ->
            val n = name.trim()
            if (n.isEmpty()) return@inSteps emptyList()
            val there = state.itemNamed(n)?.takeIf { item -> state.entries.any { it.item == item } }
            if (there != null) {
                _ui.update { it.copy(focus = there, message = UiMessage("$n is already on the list")) }
                return@inSteps emptyList()
            }
            listOf(Observation.Added(clock(), n, quantity.clean(), note.clean()))
        },
        // A new item's id is the state's to give, since an old name may hold the obvious one (O56).
        { state ->
            state.itemNamed(name)?.let { item -> _ui.update { it.copy(focus = item) } }
            emptyList()
        },
    )

    fun setTicked(item: ItemKey, ticked: Boolean) = act {
        listOf(if (ticked) Observation.Ticked(clock(), item) else Observation.Unticked(clock(), item))
    }

    /**
     * Changes an entry. A new name renames its item, here and everywhere
     * (F1); `Edited` carries only the quantity and note, under the name the
     * item has by then, since a different name there would move the entry to
     * another item instead (tier 4 §4.1). Joining is not asked about here:
     * the screen asks first (tier 4 §7.3).
     */
    fun edit(item: ItemKey, name: String, quantity: String?, note: String?) {
        val n = name.trim()
        val q = quantity.clean()
        val o = note.clean()
        var was: Entry? = null
        var survivor = item
        inSteps(
            { state ->
                val entry = state.entries.firstOrNull { it.item == item }
                if (entry == null || n.isEmpty()) return@inSteps emptyList()
                was = entry
                survivor = state.joinedBy(item, n) ?: item
                if (n == entry.name) emptyList() else listOf(Observation.ItemRenamed(clock(), item, n))
            },
            // After a join the entry is the survivor's, holding what O57 kept of
            // both; only what the user changed in the dialog is laid over that.
            { state ->
                val before = was ?: return@inSteps emptyList()
                val entry = state.entries.firstOrNull { it.item == survivor } ?: return@inSteps emptyList()
                val quantityNow = if (q != before.quantity) q else entry.quantity
                val noteNow = if (o != before.note) o else entry.note
                if (quantityNow == entry.quantity && noteNow == entry.note) emptyList()
                else listOf(Observation.Edited(clock(), survivor, entry.name, quantityNow, noteNow))
            },
        )
    }

    fun remove(item: ItemKey) = act { state ->
        val entry = state.entries.firstOrNull { it.item == item } ?: return@act emptyList()
        val undo = { now: State, at: Long -> listOf(Observation.Added(at, now.items[item] ?: entry.name, entry.quantity, entry.note)) }
        _ui.update { it.copy(message = UiMessage("Removed ${entry.name}", undo = undo)) }
        listOf(Observation.Removed(clock(), item))
    }

    /** Does what a message's undo makes of the state now. */
    fun undo(message: UiMessage) = act { state ->
        message.undo?.invoke(state, clock()).orEmpty()
    }

    // --- The library (F13) ---

    /** Renames [item] as changing its entry's name would (F1); the screen asks first if it joins two. */
    fun renameItem(item: ItemKey, name: String) = act { state ->
        val n = name.trim()
        val current = state.items[item]
        if (n.isEmpty() || current == null || n == current) emptyList()
        else listOf(Observation.ItemRenamed(clock(), item, n))
    }

    /** Unlinks [code]: its next scan asks for a name (O58). Undo links it back to the same item. */
    fun unlink(code: Barcode) = act { state ->
        val item = state.codes[code] ?: return@act emptyList()
        val name = state.items[item] ?: state.nameFor(code).orEmpty()
        val undo = { now: State, at: Long ->
            // Not over a link made since, and not to an item joined away meanwhile.
            val to = now.items[item]
            if (to == null || code in now.codes) emptyList() else listOf<Observation>(Observation.CodeLinked(at, code, to))
        }
        _ui.update { it.copy(message = UiMessage("Unlinked ${code.value} from $name", undo = undo)) }
        listOf(Observation.CodeUnlinked(clock(), code))
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
    fun link(code: Barcode, name: String, tick: Boolean = false) {
        val n = name.trim()
        if (n.isEmpty()) return
        var now = 0L
        inSteps(
            {
                now = clock()
                _ui.update { it.copy(naming = null) }
                listOf(Observation.CodeLinked(now, code, n))
            },
            // Scanned once linked, so the item the link names, made or found, is known by its id.
            { state -> logged(scan(state, code, n, tick, now)) },
        )
    }

    /** The user declined to name the code in [UiState.naming]; nothing is recorded. */
    fun namingDismissed() = _ui.update { it.copy(naming = null) }

    /**
     * The code of [event] means [name] instead. What that scan did is taken
     * back if it still stands, the new item is added or ticked in its place
     * as the scan did, and the log shows the new outcome where the old one was.
     */
    fun relink(event: ScanEvent, name: String) {
        val n = name.trim()
        if (n.isEmpty()) return
        var now = 0L
        inSteps(
            { state ->
                now = clock()
                val takeBack = if (state.itemNamed(n) == event.item) emptyList() else undone(state, event, now)
                takeBack + Observation.CodeLinked(now, event.code, n)
            },
            { state ->
                val (replaced, again) = scan(state, event.code, n, event.ticked, now)
                _ui.update { ui -> ui.copy(scans = ui.scans.map { if (it.id == event.id) replaced else it }) }
                again
            },
        )
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
     * with it what the trip teaches (O49). [name] is linked by now, so its
     * item exists, and is known by its id rather than by the name, which a
     * rename may have taken from it.
     */
    private fun scan(state: State, code: Barcode, name: String, tick: Boolean, now: Long): Pair<ScanEvent, List<Observation>> {
        val key = state.itemNamed(name) ?: ItemKey.of(name)
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
        book.standingNotHere(item, selected).map { (store, scope) ->
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
        val scores = withContext(compute) { book.score(routers) }
        _ui.update { it.copy(lab = scores) }
    }

    // --- Export and import ---

    fun exportTo(write: (String) -> Unit) = serially {
        try {
            write(Backup.encode(book.stored(), kept.readRecord(), store.load()))
            say("Exported ${book.actions} recorded actions")
        } catch (e: IOException) {
            say("Export failed: ${e.message}")
        }
    }

    fun importFrom(read: () -> String) = serially {
        if (book.actions > 0 || !kept.isEmpty()) {
            say("Import only works on a fresh install. This one already has ${book.actions} recorded actions; export them first if you need them, then clear the app's data.")
            return@serially
        }
        val imported = try {
            Backup.decode(read())
        } catch (e: IllegalArgumentException) {
            say("That file is not an Optrolley export")
            return@serially
        } catch (e: IOException) {
            say("Could not read that file: ${e.message}")
            return@serially
        }
        book = kept.import(imported)
        recompute()
        say("Imported ${book.actions} recorded actions")
    }

    // --- Lifecycle ---

    /** A trip that idled out while the app was away ends now (tier 4 §7). */
    fun onResume() = serially { if (_ui.value.fatal == null) settled() }

    fun messageShown(message: UiMessage) = _ui.update { if (it.message?.id == message.id) it.copy(message = null) else it }

    fun focusShown() = _ui.update { it.copy(focus = null) }

    // --- Internals ---

    private fun act(make: (State) -> List<Observation>) = inSteps(make)

    /**
     * One thing the user did, made in [steps] and kept as one. Each step is
     * made from the state the steps before it leave, so it can name an item
     * by the id an earlier one gave or found: an id is not the name's to
     * tell once names can change (tier 4 §4.1).
     */
    private fun inSteps(vararg steps: (State) -> List<Observation>) = serially {
        if (_ui.value.fatal != null) return@serially
        val trips = ArrayList<TripRecord>()
        val settled = settle(trips)
        val made = ArrayList<Observation>()
        for (step in steps) {
            for (action in step(book.state)) {
                book.apply(action)?.let { trips += it }
                made += action
            }
        }
        keep(settled + made, trips)
    }

    /** Ends a trip that has idled out, as of when it did, before anything new is applied. */
    private fun settle(trips: MutableList<TripRecord>): List<Observation> {
        val ended = book.settle(clock()) ?: return emptyList()
        book.apply(ended)?.let { trips += it }
        return listOf(ended)
    }

    /** Settles the clock and keeps whatever that did, deriving the view the first time even if it did nothing. */
    private suspend fun settled() {
        val trips = ArrayList<TripRecord>()
        keep(settle(trips), trips)
    }

    /** Keeps [applied], already applied to the book, with the [trips] they ended, then derives the new view. */
    private suspend fun keep(applied: List<Observation>, trips: List<TripRecord>) {
        if (applied.isEmpty() && _ui.value.state != null) return
        kept.keep(book, applied, trips)
        recompute()
    }

    private suspend fun recompute() {
        val (state, presentation, sureness) = withContext(compute) { Triple(book.state, book.present(routers), book.confidence(routers)) }
        _ui.update { it.copy(history = Journal(store), state = state, presentation = presentation, confidence = sureness, lab = null) }
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

/** The journal as a list, read from its file the first time anything looks, since the app itself never does. */
private class Journal(store: HistoryStore) : AbstractList<Observation>() {
    private val read by lazy { store.load() }
    override val size: Int get() = read.size
    override fun get(index: Int): Observation = read[index]
}

/**
 * The other item that calling [item] [name] would join it into, or null for
 * a plain rename: a change of its own name's case or spacing is one (D10).
 */
fun State.joinedBy(item: ItemKey, name: String): ItemKey? = itemNamed(name)?.takeIf { it != item }
