package dev.optrolley.autoroute.catalogue

import dev.optrolley.core.Barcode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** What the library shows of the catalogue. Nothing else reads it, so a look-up changes nothing else (O60). */
data class LibraryView(
    /** The user turned looking codes up on, in Settings (F14). Off, nothing is sent and nothing is offered that would send. */
    val enabled: Boolean = false,
    val known: Map<Barcode, Known> = emptyMap(),
    val pictures: Map<Barcode, File> = emptyMap(),
    /** Codes finished and asked for since the queue last stood empty, while it runs. */
    val progress: Progress? = null,
    /** The catalogue asked to slow down, or could not be reached twice running. */
    val stopped: Boolean = false,
)

data class Progress(val done: Int, val of: Int)

/**
 * The one queue every look-up joins (tier 4 §7.3): one request at a time,
 * each starting no sooner than [SPACING_MS] after the one before, which is
 * six a minute against the fifteen the catalogue allows. A code already
 * waiting or being asked about is not queued twice.
 *
 * Nothing waits on it: callers only add to it, and it runs in [scope]
 * outside the lock that orders the user's actions. Its state is touched
 * only from [scope]'s thread; [io] is for the cache's files and [pause] for
 * the wait between requests, so tests can stand in for time.
 */
class Lookups(
    private val catalogue: Catalogue,
    private val cache: CatalogueCache,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
    private val pause: suspend (Long) -> Unit,
) {
    private val _view = MutableStateFlow(LibraryView())
    val view: StateFlow<LibraryView> = _view.asStateFlow()

    private val waiting = LinkedHashSet<Barcode>()
    private var current: Barcode? = null
    private var worker: Job? = null
    private var lastStart: Long? = null
    private var failures = 0
    private var done = 0
    private var total = 0

    private val loaded = scope.async {
        val (enabled, known, pictures) = withContext(io) {
            val known = cache.answers()
            Triple(cache.enabled, known, known.keys.mapNotNull { code -> cache.picture(code)?.let { code to it } }.toMap())
        }
        _view.update { it.copy(enabled = enabled, known = known, pictures = pictures) }
    }

    /** A look-up of [code] asked for by hand: asked again even if the catalogue did not know it. */
    fun lookUp(code: Barcode) = whenLoaded { asked(setOf(code)) }

    /** Asked for by hand: every one of [codes] the cache holds nothing for. */
    fun lookUpAll(codes: Collection<Barcode>) = whenLoaded {
        asked(codes.filterTo(LinkedHashSet()) { it !in _view.value.known })
    }

    private fun asked(codes: Set<Barcode>) {
        // The library offers no look-up while the option is off; this holds even if one is asked for anyway (O63).
        if (!_view.value.enabled || codes.isEmpty()) return
        // Asking by hand is what restarts a stopped queue.
        _view.update { it.copy(stopped = false) }
        enqueue(codes)
    }

    /**
     * A code scanned to add. Looked up by itself only if the option is on,
     * the queue is not stopped, and the cache holds nothing for it.
     */
    fun scannedToAdd(code: Barcode) = whenLoaded {
        val view = _view.value
        if (view.enabled && !view.stopped && code !in view.known) enqueue(setOf(code))
    }

    /**
     * The option in Settings. Turning it on sends nothing by itself. Turning
     * it off sends nothing from then: what waits is dropped and what is under
     * way is abandoned (O63). What was fetched stays until cleared.
     */
    fun setEnabled(on: Boolean) = whenLoaded {
        if (on) {
            withContext(io) { cache.enabled = true }
            _view.update { it.copy(enabled = true, stopped = false) }
        } else {
            _view.update { it.copy(enabled = false) }
            dropAll()
            withContext(io) { cache.enabled = false }
        }
    }

    /** Forgets every answer and picture; the option stays as it is. */
    fun clear() = whenLoaded {
        withContext(io) { cache.clear() }
        _view.update { it.copy(known = emptyMap(), pictures = emptyMap()) }
    }

    /** The app was opened again: a stopped queue may be asked of by itself once more. */
    fun opened() = _view.update { it.copy(stopped = false) }

    private fun whenLoaded(block: suspend () -> Unit) {
        scope.launch {
            loaded.await()
            block()
        }
    }

    private fun enqueue(codes: Collection<Barcode>) {
        for (code in codes) {
            if (code == current || !waiting.add(code)) continue
            total++
        }
        if (worker?.isActive != true && waiting.isNotEmpty()) {
            // Known before it runs, since it may run to the end before launch returns.
            val job = scope.launch(start = CoroutineStart.LAZY) { drain() }
            worker = job
            job.start()
        }
        publishProgress()
    }

    private suspend fun drain() {
        val me = currentCoroutineContext()[Job]
        try {
            while (_view.value.enabled && !_view.value.stopped) {
                val code = waiting.firstOrNull() ?: break
                waiting.remove(code)
                current = code
                lookUpNow(code)
                current = null
                done++
                publishProgress()
            }
        } finally {
            // One abandoned by [withdraw] may finish after a new one has started; it leaves that one be.
            if (worker === me) reset()
        }
    }

    private suspend fun lookUpNow(code: Barcode) {
        when (val answer = paced { catalogue.product(code) }) {
            is Answer.Product -> {
                failures = 0
                remember(code, Known.Found(answer.name, answer.brand, answer.picture, clock()))
                val url = answer.picture ?: return
                when (val picture = paced { catalogue.picture(url) }) {
                    is Answer.Picture -> {
                        failures = 0
                        val file = withContext(io) {
                            cache.keepPicture(code, picture.bytes)
                            cache.picture(code)
                        }
                        if (file != null) _view.update { it.copy(pictures = it.pictures + (code to file)) }
                    }
                    else -> failed(picture)
                }
            }
            Answer.None -> {
                failures = 0
                remember(code, Known.NotKnown(clock()))
            }
            else -> failed(answer)
        }
    }

    /** No connection twice running, or being told to slow down, stops the queue and drops what waits. */
    private fun failed(answer: Answer) {
        when (answer) {
            Answer.SlowDown -> stop()
            Answer.NoConnection -> if (++failures >= 2) stop()
            // A picture the catalogue does not have is not a failure of the catalogue's.
            else -> failures = 0
        }
    }

    private fun stop() {
        failures = 0
        waiting.clear()
        _view.update { it.copy(stopped = true) }
    }

    private fun dropAll() {
        worker?.cancel()
        reset()
    }

    private fun reset() {
        worker = null
        current = null
        waiting.clear()
        done = 0
        total = 0
        _view.update { it.copy(progress = null) }
    }

    private suspend fun remember(code: Barcode, known: Known) {
        withContext(io) { cache.remember(code, known) }
        _view.update { it.copy(known = it.known + (code to known)) }
    }

    /** Waits until [SPACING_MS] after the last request began, whichever queue run it was in, then makes this one. */
    private suspend fun <T> paced(request: suspend () -> T): T {
        lastStart?.let { last ->
            // A clock set back must not hold the queue for longer than the spacing.
            val wait = (last + SPACING_MS - clock()).coerceAtMost(SPACING_MS)
            if (wait > 0) pause(wait)
        }
        lastStart = clock()
        return request()
    }

    private fun publishProgress() {
        val running = worker?.isActive == true && total > 0
        _view.update { it.copy(progress = if (running) Progress(done, total) else null) }
    }

    companion object {
        const val SPACING_MS = 10_000L
    }
}
