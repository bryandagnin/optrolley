package dev.optrolley.autoroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.catalogue.Answer
import dev.optrolley.autoroute.catalogue.Known
import dev.optrolley.autoroute.catalogue.LibraryView
import dev.optrolley.autoroute.catalogue.Lookups
import dev.optrolley.autoroute.catalogue.StandInCatalogue
import dev.optrolley.autoroute.catalogue.StandInCatalogue.Request
import dev.optrolley.autoroute.ui.AppFixture
import dev.optrolley.autoroute.ui.AppFixture.BREAD
import dev.optrolley.autoroute.ui.AppFixture.CORNER
import dev.optrolley.autoroute.ui.AppFixture.EGGS
import dev.optrolley.autoroute.ui.AppFixture.MILK
import dev.optrolley.autoroute.ui.AppFixture.START
import dev.optrolley.autoroute.ui.AppFixture.TESCO
import dev.optrolley.core.Barcode
import dev.optrolley.core.Observation
import dev.optrolley.core.State
import dev.optrolley.core.StoreId
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The library's look-ups through the view model (F13, tier 4 §7.3), with a
 * stand-in for the network and a clock the test moves: a look-up changes
 * nothing but the library (O60), sends only a code (O61), and nothing at all
 * while the option is off (O63); the queue is paced, one at a time, and stops when
 * told to; what was fetched is a cache, never the user's data.
 */
@RunWith(AndroidJUnit4::class)
class LookupTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val tmp = TemporaryFolder()

    private val milkCode = Barcode("5000112637922")
    private val breadCode = Barcode("5010029000016")
    private val eggsCode = Barcode("5000000000017")
    private val saffronCode = Barcode("5000000000024")
    private val milkToo = Barcode("5000000000031")
    private val breadToo = Barcode("5000000000048")
    private val tea = Barcode("4006381333931")
    private val linkedCodes = listOf(milkCode, breadCode, eggsCode, saffronCode, milkToo, breadToo)

    private val linked: List<Observation> = AppFixture.history + listOf(
        Observation.CodeLinked(START + 55_000, milkCode, "Milk"),
        Observation.CodeLinked(START + 55_100, breadCode, "Bread"),
        Observation.CodeLinked(START + 55_200, eggsCode, "Eggs"),
        Observation.CodeLinked(START + 55_300, saffronCode, "Saffron"),
        Observation.CodeLinked(START + 55_400, milkToo, "Milk"),
        Observation.CodeLinked(START + 55_500, breadToo, "Bread"),
    )

    /**
     * An install over [linked]. [pacingMovesClock] makes the wait between
     * look-ups pass on the test's clock; without it the wait is recorded and
     * skipped, so the clock the actions are stamped with is the same whatever
     * the catalogue does.
     */
    private inner class Install(
        reply: (Request) -> Answer = StandInCatalogue.knowsAll,
        val cache: File = tmp.newFolder(),
        on: Boolean = false,
        pacingMovesClock: Boolean = true,
        val clock: AppFixture.Clock = AppFixture.Clock(),
    ) {
        private val dir = tmp.newFolder()
        private val file = File(dir, "history.jsonl")
        val catalogue = StandInCatalogue(clock::read, reply)
        val pauses = mutableListOf<Long>()
        val vm: AppViewModel

        init {
            HistoryStore(file).append(linked)
            vm = AppViewModel(
                HistoryStore(file),
                clock::read,
                Dispatchers.Unconfined,
                Dispatchers.Unconfined,
                catalogue = catalogue,
                catalogueCache = cache,
                pause = { ms ->
                    pauses += ms
                    if (pacingMovesClock) clock.now += ms
                },
            )
            idle()
            if (on) turn(true)
        }

        /** The option in Settings. */
        fun turn(on: Boolean) {
            vm.setLookUps(on)
            idle()
        }

        fun onDisk() = HistoryStore(file).load()
        fun record(): String = File(dir, Kept.RECORD_FILE).takeIf { it.isFile }?.readText().orEmpty()
        val state: State get() = vm.ui.value.state!!
        val library: LibraryView get() = vm.library.value
        val products get() = catalogue.requests.filterIsInstance<Request.Product>().map { it.code }

        fun export(): String {
            var text: String? = null
            vm.exportTo { text = it }
            idle()
            return assertNotNull(text)
        }

        /** State, record, journal and what is shown at every store: everything a look-up must not touch (O60). */
        fun everything(): List<Any?> {
            val selected = state.selected
            val shown = ArrayList<Any?>()
            for (store in listOf<StoreId?>(TESCO, CORNER, null)) {
                vm.select(store)
                idle()
                shown += vm.ui.value.presentation
                shown += vm.ui.value.confidence
            }
            vm.select(selected)
            idle()
            return listOf(state, onDisk(), record(), shown, export())
        }
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    /** Scanning, naming, ticking, asking and finishing a trip, with look-ups asked for along the way. */
    private fun Install.shop() {
        vm.scanned(milkCode)
        vm.scanned(tea)
        vm.link(tea, "Tea")
        vm.lookUpAll()
        vm.scanned(breadCode, tick = true)
        vm.setTicked(EGGS, true)
        vm.lookUp(milkCode)
        vm.finishTrip()
        vm.add("Jam", null, null)
        vm.scanned(eggsCode)
        idle()
    }

    // --- O60 ---

    @Test
    fun `O60 a catalogue that answers, knows nothing, fails, slows or never returns changes nothing but the library`() {
        val unasked = Install(pacingMovesClock = false).apply { shop() }.everything()
        val replies = mapOf<String, (Request) -> Answer>(
            "answers" to StandInCatalogue.knowsAll,
            "knows nothing" to { Answer.None },
            "fails" to { Answer.NoConnection },
            "slows" to { Answer.SlowDown },
        )
        for ((what, reply) in replies) {
            val i = Install(reply, on = true, pacingMovesClock = false)
            i.shop()
            assertTrue(i.catalogue.requests.isNotEmpty(), "a catalogue that $what was never asked")
            assertEquals(unasked, i.everything(), "a catalogue that $what changed something")
        }
        val never = Install(on = true, pacingMovesClock = false).apply { catalogue.never = true }
        never.shop()
        assertEquals(1, never.catalogue.underWay, "nothing was left waiting on the catalogue")
        assertEquals(unasked, never.everything(), "a catalogue that never returns changed something")
    }

    @Test
    fun `O60 the user's actions are not held up by a look-up under way`() {
        val i = Install(on = true)
        i.catalogue.never = true
        i.vm.lookUpAll()
        idle()
        assertEquals(1, i.catalogue.underWay)
        i.vm.add("Jam", null, null)
        i.vm.setTicked(MILK, true)
        idle()
        assertEquals("Jam", assertIs<Observation.Added>(i.onDisk()[i.onDisk().size - 2]).name)
        assertTrue(i.state.entries.single { it.item == MILK }.ticked)
    }

    // --- O61 ---

    @Test
    fun `O61 every request is for one code's product, or the picture the catalogue gave for it`() {
        val i = Install(on = true)
        i.shop()
        assertTrue(i.catalogue.requests.size >= 2 * linkedCodes.size, "too few requests to judge: ${i.catalogue.requests}")
        val asked = mutableSetOf<Barcode>()
        for (request in i.catalogue.requests) {
            when (request) {
                is Request.Product -> {
                    assertTrue(request.code in i.state.codes, "asked about ${request.code}, which is not a code of the user's")
                    asked += request.code
                }
                is Request.Picture -> assertTrue(
                    asked.any { request.url == StandInCatalogue.pictureOf(it) },
                    "asked for ${request.url}, which the catalogue gave for no code asked about",
                )
            }
        }
    }

    // --- O63 ---

    @Test
    fun `O63 with the option never turned on nothing is sent, whatever is scanned or asked`() {
        val i = Install()
        assertFalse(i.library.enabled, "a fresh install starts with looking codes up on")
        i.shop()
        i.vm.scanned(breadCode)
        i.vm.lookUp(milkCode)
        i.vm.lookUpAll()
        idle()
        assertEquals(emptyList(), i.catalogue.requests)
        assertNull(i.library.progress)
    }

    @Test
    fun `O63 with the option on, a look-up asked for and a scan to add are sent`() {
        val i = Install(reply = { Answer.None })
        i.turn(true)
        assertTrue(i.library.enabled)
        assertEquals(emptyList(), i.catalogue.requests, "turning the option on sent something by itself")
        i.vm.lookUp(milkCode)
        idle()
        assertEquals(listOf(milkCode), i.products)
        i.vm.scanned(breadCode)
        idle()
        assertEquals(listOf(milkCode, breadCode), i.products)
    }

    @Test
    fun `O63 after turning the option off nothing more is sent, and what was under way is abandoned`() {
        val i = Install(on = true)
        i.catalogue.held = true
        i.vm.lookUpAll()
        idle()
        assertEquals(1, i.catalogue.requests.size)
        i.turn(false)
        repeat(linkedCodes.size * 2) { i.catalogue.release() }
        idle()
        assertEquals(1, i.catalogue.requests.size, "something was sent after the option was turned off")
        assertEquals(emptyMap(), i.library.known, "an answer that came after the option was turned off was kept")
        assertNull(i.library.progress)

        i.vm.scanned(tea)
        i.vm.link(tea, "Tea")
        i.vm.scanned(breadCode)
        i.vm.lookUp(breadCode)
        i.vm.lookUpAll()
        idle()
        assertEquals(1, i.catalogue.requests.size)
    }

    @Test
    fun `turning the option off keeps what was fetched until it is cleared`() {
        val i = Install(on = true)
        i.vm.lookUp(milkCode)
        idle()
        i.turn(false)
        assertIs<Known.Found>(i.library.known[milkCode])
        assertNotNull(i.library.pictures[milkCode])
    }

    @Test
    fun `the option is kept with the cache, so a fresh install starts with it off`() {
        val cache = tmp.newFolder()
        Install(cache = cache, on = true)
        assertTrue(Install(cache = cache).library.enabled, "the option was forgotten on the next start")
        assertTrue(cache.listFiles()!!.isNotEmpty(), "the option is not kept in the cache directory")
        assertFalse(Install(cache = tmp.newFolder()).library.enabled)
        Install(cache = cache).turn(false)
        assertFalse(Install(cache = cache).library.enabled, "turning it off was forgotten on the next start")
    }

    // --- The queue ---

    @Test
    fun `look-ups start ten seconds apart, one at a time, the first at once`() {
        val i = Install(on = true)
        val asked = i.clock.now
        i.vm.lookUpAll()
        idle()
        assertEquals(linkedCodes.size * 2, i.catalogue.requests.size, "each code's product and picture")
        assertEquals(asked, i.catalogue.starts.first(), "the first look-up waited")
        i.catalogue.starts.zipWithNext().forEach { (a, b) -> assertTrue(b - a >= Lookups.SPACING_MS, "requests began ${b - a} ms apart") }
        assertEquals(1, i.catalogue.mostAtOnce)
        assertNull(i.library.progress)
    }

    @Test
    fun `the spacing holds from one run of the queue to the next`() {
        val i = Install(on = true, reply = { Answer.None })
        i.vm.lookUp(milkCode)
        idle()
        i.clock.now += 3_000
        i.vm.lookUp(breadCode)
        idle()
        assertEquals(listOf(milkCode, breadCode), i.products)
        assertEquals(Lookups.SPACING_MS, i.catalogue.starts[1] - i.catalogue.starts[0])
        assertEquals(listOf(7_000L), i.pauses)
    }

    @Test
    fun `one request is under way at a time, and a code waiting or under way is not queued twice`() {
        val i = Install(on = true, reply = { Answer.None })
        i.catalogue.held = true
        i.vm.lookUp(milkCode)
        i.vm.lookUp(milkCode)
        i.vm.lookUp(breadCode)
        i.vm.lookUp(breadCode)
        i.vm.lookUpAll()
        idle()
        assertEquals(listOf(milkCode), i.products)
        assertEquals(0, assertNotNull(i.library.progress).done)
        assertEquals(linkedCodes.size, i.library.progress!!.of)

        i.catalogue.release()
        idle()
        assertEquals(1, i.library.progress!!.done)
        assertEquals(2, i.catalogue.requests.size)
        repeat(linkedCodes.size) { i.catalogue.release() }
        idle()
        assertEquals(linkedCodes.toSet(), i.products.toSet())
        assertEquals(linkedCodes.size, i.products.size, "a code was asked about twice: ${i.products}")
        assertEquals(1, i.catalogue.mostAtOnce)
        assertNull(i.library.progress)
    }

    @Test
    fun `the catalogue saying slow down stops the queue and drops what was waiting`() {
        val i = Install(on = true, reply = { Answer.SlowDown })
        i.vm.lookUpAll()
        idle()
        assertEquals(1, i.catalogue.requests.size, "the queue went on after being told to slow down")
        assertTrue(i.library.stopped)
        assertNull(i.library.progress)

        i.vm.scanned(breadCode)
        idle()
        assertEquals(1, i.catalogue.requests.size, "a stopped queue looked up a scan by itself")

        i.catalogue.reply = { Answer.None }
        i.vm.lookUp(breadCode)
        idle()
        assertEquals(2, i.catalogue.requests.size, "asking by hand did not restart the queue")
        assertFalse(i.library.stopped)
        i.vm.lookUp(eggsCode)
        idle()
        assertEquals(3, i.catalogue.requests.size, "what was dropped came back")
    }

    @Test
    fun `two failures running stop the queue, and one alone does not`() {
        var n = 0
        // Fails, answers, fails, fails: only the last two run together.
        val i = Install(on = true, reply = { if (++n == 2) Answer.None else Answer.NoConnection })
        i.vm.lookUpAll()
        idle()
        assertEquals(4, i.catalogue.requests.size, "stopped at the wrong failure")
        assertTrue(i.library.stopped)
        assertNull(i.library.progress)
    }

    @Test
    fun `a failed picture and a failed product running stop the queue`() {
        var products = 0
        // The first product is found and its picture fails; then the next product fails.
        val i = Install(on = true, reply = { request ->
            when (request) {
                is Request.Product ->
                    if (++products == 1) Answer.Product("Something", null, StandInCatalogue.pictureOf(request.code))
                    else Answer.NoConnection
                is Request.Picture -> Answer.NoConnection
            }
        })
        i.vm.lookUpAll()
        idle()
        assertEquals(3, i.catalogue.requests.size, "${i.catalogue.requests}")
        assertTrue(i.library.stopped)
        assertEquals(1, i.library.known.size, "a product found was not kept for want of its picture")
        assertEquals(emptyMap(), i.library.pictures)
    }

    @Test
    fun `a stopped queue runs by itself again once the app is opened again`() {
        val i = Install(on = true, reply = { Answer.SlowDown })
        i.vm.lookUp(milkCode)
        idle()
        assertTrue(i.library.stopped)
        i.catalogue.reply = { Answer.None }
        i.vm.onResume()
        idle()
        assertFalse(i.library.stopped)
        i.vm.scanned(breadCode)
        idle()
        assertEquals(listOf(milkCode, breadCode), i.products)
    }

    // --- What is remembered ---

    @Test
    fun `a code the catalogue did not know is remembered, skipped by all and by a scan, and asked again by hand`() {
        val i = Install(on = true, reply = { request ->
            if (request == Request.Product(milkCode)) Answer.None else StandInCatalogue.knowsAll(request)
        })
        i.vm.lookUp(milkCode)
        idle()
        assertIs<Known.NotKnown>(i.library.known[milkCode])
        i.vm.lookUpAll()
        idle()
        assertEquals(1, i.products.count { it == milkCode }, "look up all asked again about a code not known")
        assertEquals(linkedCodes.toSet(), i.products.toSet())
        i.vm.scanned(milkCode)
        idle()
        assertEquals(1, i.products.count { it == milkCode }, "a scan asked again about a code not known")
        i.vm.lookUp(milkCode)
        idle()
        assertEquals(2, i.products.count { it == milkCode }, "asking by hand did not ask again")

        val before = i.catalogue.requests.size
        i.vm.lookUpAll()
        idle()
        assertEquals(before, i.catalogue.requests.size, "look up all asked about codes the cache holds")
    }

    @Test
    fun `no connection remembers nothing, so the code is asked about another day`() {
        val i = Install(on = true, reply = { Answer.NoConnection })
        i.vm.lookUp(milkCode)
        idle()
        assertEquals(emptyMap(), i.library.known)
        assertFalse(i.library.stopped, "one failure stopped the queue")
        i.vm.scanned(milkCode)
        idle()
        assertEquals(listOf(milkCode, milkCode), i.products)
        assertFalse(File(i.cache, "answers.json").exists())
    }

    @Test
    fun `a product and its picture are kept in the cache directory until cleared`() {
        val cache = tmp.newFolder()
        val i = Install(cache = cache, on = true)
        val asked = i.clock.now
        i.vm.lookUp(milkCode)
        idle()
        val found = assertIs<Known.Found>(i.library.known[milkCode])
        assertEquals("Product ${milkCode.value}", found.name)
        assertEquals("Brand", found.brand)
        assertEquals(asked, found.at)
        val picture = assertNotNull(i.library.pictures[milkCode])
        assertTrue(picture.startsWith(cache), "the picture is not in the cache directory: $picture")
        assertTrue(picture.readBytes().contentEquals(byteArrayOf(1, 2, 3)))

        val again = Install(cache = cache)
        assertEquals(found, again.library.known[milkCode], "the answer was not kept")
        assertEquals(picture, again.library.pictures[milkCode])
        again.vm.clearFetched()
        idle()
        assertEquals(emptyMap(), again.library.known)
        assertEquals(emptyMap(), again.library.pictures)
        assertFalse(picture.exists())
        assertTrue(again.library.enabled, "clearing what was fetched turned the option off")
        assertEquals(emptyMap(), Install(cache = cache).library.known)
    }

    // --- When a code is looked up by itself ---

    @Test
    fun `a scan to tick never looks anything up`() {
        val i = Install(on = true)
        i.vm.scanned(milkCode, tick = true)
        i.vm.scanned(tea, tick = true)
        i.vm.link(tea, "Tea", tick = true)
        idle()
        assertTrue(i.state.entries.single { it.item == MILK }.ticked, "the scan did not tick")
        assertEquals(emptyList(), i.catalogue.requests)
    }

    @Test
    fun `a scan to add is looked up after it is recorded, named for the first time or not`() {
        var recordedFirst = true
        lateinit var i: Install
        i = Install(on = true, reply = { request ->
            if (request == Request.Product(tea) && i.onDisk().none { it is Observation.Added && it.code == tea }) recordedFirst = false
            Answer.None
        })
        i.vm.scanned(breadCode)
        i.vm.scanned(tea)
        idle()
        assertEquals(listOf(breadCode), i.products, "a code not yet named was looked up")
        i.vm.link(tea, "Tea")
        idle()
        assertEquals(listOf(breadCode, tea), i.products)
        assertTrue(recordedFirst, "the look-up was made before the scan was recorded")
    }

    @Test
    fun `a scan cancelled at naming looks nothing up`() {
        val i = Install(on = true)
        i.vm.scanned(tea)
        i.vm.namingDismissed()
        idle()
        assertEquals(emptyList(), i.catalogue.requests)
    }

    // --- Never the user's data ---

    @Test
    fun `nothing fetched, nor the option, is in the export or the state`() {
        val i = Install(on = true)
        i.vm.lookUpAll()
        idle()
        assertTrue(i.library.known.isNotEmpty())
        val export = i.export()
        for (fetched in listOf("Product ", "Brand", "openfoodfacts", "look-ups")) {
            assertFalse(fetched in export, "the export holds \"$fetched\"")
        }
        assertEquals(Install().export(), export)
    }

    @Test
    fun `a product's name never names an item or shows on the list`() {
        val i = Install(on = true)
        i.vm.scanned(tea)
        i.vm.link(tea, "Tea")
        i.vm.lookUpAll()
        idle()
        assertIs<Known.Found>(i.library.known[tea])
        assertTrue(i.state.items.values.none { "Product" in it }, "a product's name became an item's: ${i.state.items}")
        val shown = i.vm.ui.value.presentation!!.sections.flatMap { s -> s.entries.map { it.name } }
        assertTrue(shown.none { "Product" in it }, "a product's name is on the list: $shown")
        assertEquals("Tea", i.state.entries.last().name)
    }
}
