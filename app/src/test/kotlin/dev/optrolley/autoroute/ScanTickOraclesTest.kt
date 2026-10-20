package dev.optrolley.autoroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.ui.AppFixture
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.SectionKind
import dev.optrolley.core.StoreId
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Tier 3 group H through the view model, scanning to tick: a scan to tick is
 * a tap (O48), a scan never unticks (O49), and an unlinked code waits for a
 * name (O42). Each comparison runs two fresh installs over the same history
 * on one clock, one scanning and one tapping (or typing, then tapping).
 */
@RunWith(AndroidJUnit4::class)
class ScanTickOraclesTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = AppFixture.Clock()

    private val milkCode = Barcode("5000112637922")
    private val breadCode = Barcode("5010029000016")
    private val teaCode = Barcode("9300633603352")
    private val newCode = Barcode("4006381333931")
    private val otherCode = Barcode("50184453")

    private val tea = ItemKey("tea")
    private val coffee = ItemKey("coffee")

    /**
     * [AppFixture.history], with milk, bread and tea linked. Milk and eggs
     * are on the list at Tesco, no trip in progress. Bread is placed at Tesco
     * and then removed; tea has never been typed.
     */
    private val linked: List<Observation> = AppFixture.history + listOf(
        Observation.CodeLinked(AppFixture.START + 55_000, milkCode, "Milk"),
        Observation.CodeLinked(AppFixture.START + 55_500, breadCode, "bread"),
        Observation.CodeLinked(AppFixture.START + 56_000, teaCode, "Tea"),
        Observation.Removed(AppFixture.START + 57_000, AppFixture.BREAD),
    )

    private inner class Install(history: List<Observation>) {
        private val file = File(tmp.newFolder(), "history.jsonl")
        val vm: AppViewModel

        init {
            val store = HistoryStore(file)
            store.append(history)
            vm = AppViewModel(store, clock::read, Dispatchers.Unconfined, Dispatchers.Unconfined)
            idle()
        }

        fun onDisk() = HistoryStore(file).load()
        val ui get() = vm.ui.value
        val presentation get() = ui.presentation!!
        fun entry(item: ItemKey) = ui.state!!.entries.firstOrNull { it.item == item }
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    /** Moves the clock on, so that what follows is a separate tick (outside the tie window). */
    private fun later(ms: Long = 30_000) {
        clock.now += ms
    }

    /** Same list, presentation and trip now, with the store as selected. */
    private fun assertSameNow(scanned: Install, tapped: Install, what: String) {
        assertEquals(tapped.ui.state!!.entries, scanned.ui.state!!.entries, "$what: the list differs from tapping")
        assertEquals(tapped.ui.presentation, scanned.ui.presentation, "$what: the presentation differs from tapping")
        // Whether a trip is in progress, and where. (A link recorded in one arm alone is a later observation, so the trip's last one may differ.)
        assertEquals(tapped.ui.state!!.trip?.let { it.store to it.startedAt }, scanned.ui.state!!.trip?.let { it.store to it.startedAt }, "$what: the trip differs from tapping")
    }

    /** Same list and presentation now, and with every store and none selected in turn. Ends a trip in progress. */
    private fun assertSameEverywhere(scanned: Install, tapped: Install, what: String) {
        assertSameNow(scanned, tapped, what)
        for (store in listOf<StoreId?>(AppFixture.CORNER, null, AppFixture.TESCO)) {
            scanned.vm.select(store); tapped.vm.select(store)
            idle()
            assertEquals(tapped.ui.presentation, scanned.ui.presentation, "$what: the presentation differs from tapping with $store selected")
        }
    }

    /** Ends the trip in both, then brings every item back so that everything learned shows. */
    private fun endAndCompare(scanned: Install, tapped: Install, what: String) {
        later()
        for (i in listOf(scanned, tapped)) i.vm.finishTrip()
        idle()
        assertNull(scanned.ui.state!!.trip, "$what: finishing did not end the trip")
        assertSameEverywhere(scanned, tapped, "$what, after the trip")
        for (name in listOf("Milk", "Bread", "Eggs", "Tea", "Coffee")) {
            later(1_000)
            for (i in listOf(scanned, tapped)) i.vm.add(name, null, null)
            idle()
        }
        assertSameEverywhere(scanned, tapped, "$what, after the trip, with every item back on the list")
    }

    /**
     * What a scan to tick recorded is what tapping (or typing, then tapping)
     * recorded, observation for observation, with the code read on each and
     * nothing else.
     */
    private fun assertRecordedAsTapped(scanned: List<Observation>, tapped: List<Observation>, code: Barcode, what: String) {
        assertEquals(tapped.map { it::class }, scanned.map { it::class }, "$what recorded $scanned, where tapping recorded $tapped")
        for ((s, t) in scanned.zip(tapped)) {
            when (s) {
                is Observation.Added -> {
                    assertEquals(code, s.code, "$what did not record the code it read with the addition")
                    assertEquals(t, s.copy(code = null), "$what recorded an addition other than typing would, besides the code")
                }
                is Observation.Ticked -> {
                    assertEquals(code, s.code, "$what did not record the code it read with the tick")
                    assertEquals(t, s.copy(code = null), "$what recorded a tick other than tapping would, besides the code")
                }
                else -> fail("$what recorded $s")
            }
        }
    }

    /** [after] is [before] with [item]'s entry ticked and nothing else moved (O7, O28, O8). */
    private fun assertMovesNothingElse(before: Presentation, after: Presentation, item: ItemKey, wasOnList: Boolean, what: String) {
        fun Presentation.without() = sections.map { s -> s.kind to s.entries.filter { it.item != item } }.filter { it.second.isNotEmpty() }
        fun Presentation.layout() = sections.filter { it.entries.isNotEmpty() }.map { s -> s.kind to s.entries.map { it.item } }
        fun Presentation.entry() = sections.flatMap { it.entries }.firstOrNull { it.item == item }
        assertEquals(before.store, after.store, "$what changed the selected store")
        assertEquals(before.without(), after.without(), "$what moved or changed other entries")
        val e = assertNotNull(after.entry(), "$what: $item is not on the list")
        assertTrue(e.ticked, "$what: $item is not marked as got")
        if (wasOnList) {
            assertEquals(before.layout(), after.layout(), "$what moved $item")
            assertEquals(before.entry()!!.copy(ticked = true), e, "$what changed more than $item's ticked mark")
        }
    }

    private fun assertEvent(i: Install, code: Barcode, item: ItemKey, added: Boolean, ticked: Boolean, what: String) {
        val event = assertNotNull(i.ui.scans.lastOrNull(), "$what logged no scan")
        assertEquals(code, event.code, "$what logged the wrong code")
        assertEquals(item, event.item, "$what logged the wrong item")
        assertEquals(added, event.added, "$what says it ${if (event.added) "added" else "did not add"} $item")
        assertEquals(ticked, event.ticked, "$what says it ${if (event.ticked) "ticked" else "did not tick"} $item")
    }

    // O48 ---------------------------------------------------------------------------------------

    @Test
    fun `O48 scanning a linked code to tick is tapping its tick`() {
        val scanned = Install(linked)
        val tapped = Install(linked)
        val disk = scanned.onDisk()
        val before = scanned.presentation
        assertNull(scanned.ui.state!!.trip)
        later()
        scanned.vm.scanned(milkCode, tick = true)
        tapped.vm.setTicked(AppFixture.MILK, true)
        idle()
        assertRecordedAsTapped(scanned.onDisk().drop(disk.size), tapped.onDisk().drop(disk.size), milkCode, "scanning milk to tick")
        assertEvent(scanned, milkCode, AppFixture.MILK, added = false, ticked = true, what = "scanning milk to tick")
        assertNotNull(scanned.ui.state!!.trip, "scanning to tick at Tesco started no trip")
        assertSameNow(scanned, tapped, "scanning milk to tick")
        assertMovesNothingElse(before, scanned.presentation, AppFixture.MILK, wasOnList = true, what = "scanning milk to tick")

        // The trip goes on by tap, and is learned from the same in both.
        later()
        for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.EGGS, true)
        idle()
        assertSameNow(scanned, tapped, "then tapping eggs")
        endAndCompare(scanned, tapped, "milk scanned to tick, eggs tapped")
    }

    @Test
    fun `O48 scanning to tick an item not on the list is typing then tapping it`() {
        for ((code, name) in listOf(breadCode to "Bread", teaCode to "Tea")) for (midTrip in listOf(false, true)) {
            val what = "scanning $name to tick, not on the list${if (midTrip) ", mid-trip" else ""}"
            val item = ItemKey.of(name)
            val scanned = Install(linked)
            val tapped = Install(linked)
            if (midTrip) {
                later()
                for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.EGGS, true)
                idle()
            }
            val disk = scanned.onDisk()
            val before = scanned.presentation
            later()
            scanned.vm.scanned(code, tick = true)
            tapped.vm.add(name, null, null)
            tapped.vm.setTicked(item, true)
            idle()
            assertRecordedAsTapped(scanned.onDisk().drop(disk.size), tapped.onDisk().drop(disk.size), code, what)
            assertEvent(scanned, code, item, added = true, ticked = true, what = what)
            assertSameNow(scanned, tapped, what)
            assertMovesNothingElse(before, scanned.presentation, item, wasOnList = false, what = what)
            if (item == AppFixture.BREAD) {
                assertEquals(SectionKind.RUN, scanned.presentation.sections.single { s -> s.entries.any { it.item == item } }.kind, "$what: bread, placed at Tesco, is not in the Run")
            }
            later()
            for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.MILK, true)
            idle()
            assertSameNow(scanned, tapped, "$what, then tapping milk")
            endAndCompare(scanned, tapped, what)
        }
    }

    @Test
    fun `O48 scanning to tick after an untick ticks again, as a tap would`() {
        val scanned = Install(linked)
        val tapped = Install(linked)
        later()
        for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.MILK, true)
        idle()
        later()
        for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.EGGS, true)
        idle()
        later()
        for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.MILK, false)
        idle()
        val disk = scanned.onDisk()
        later()
        scanned.vm.scanned(milkCode, tick = true)
        tapped.vm.setTicked(AppFixture.MILK, true)
        idle()
        assertRecordedAsTapped(scanned.onDisk().drop(disk.size), tapped.onDisk().drop(disk.size), milkCode, "scanning milk to tick after unticking it")
        assertEvent(scanned, milkCode, AppFixture.MILK, added = false, ticked = true, what = "scanning milk to tick after unticking it")
        assertSameNow(scanned, tapped, "scanning milk to tick after unticking it")
        endAndCompare(scanned, tapped, "milk re-ticked by scan")
    }

    @Test
    fun `O48 naming a code to tick ticks as a scan of it would`() {
        // Not on the list: linked, added and ticked.
        run {
            val scanned = Install(linked)
            val tapped = Install(linked)
            val disk = scanned.onDisk()
            later()
            scanned.vm.scanned(newCode, tick = true)
            idle()
            assertEquals(disk, scanned.onDisk(), "scanning an unlinked code to tick recorded something")
            assertEquals(newCode, scanned.ui.naming, "an unlinked code scanned to tick is not waiting for a name")
            scanned.vm.link(newCode, "Coffee", tick = true)
            tapped.vm.add("Coffee", null, null)
            tapped.vm.setTicked(coffee, true)
            idle()
            assertNull(scanned.ui.naming, "named, the code is still waiting for a name")
            val recorded = scanned.onDisk().drop(disk.size)
            val link = assertIs<Observation.CodeLinked>(recorded.firstOrNull(), "naming a code to tick did not record the link first: $recorded")
            assertEquals(newCode, link.code)
            assertEquals(coffee, ItemKey.of(link.name))
            assertEquals(coffee, scanned.ui.state!!.codes[newCode])
            assertRecordedAsTapped(recorded.drop(1), tapped.onDisk().drop(disk.size), newCode, "naming a code coffee to tick")
            assertEvent(scanned, newCode, coffee, added = true, ticked = true, what = "naming a code coffee to tick")
            assertSameNow(scanned, tapped, "naming a code coffee to tick")
            endAndCompare(scanned, tapped, "coffee named to tick")
        }
        // On the list, unticked: linked and ticked.
        run {
            val scanned = Install(linked)
            val tapped = Install(linked)
            val disk = scanned.onDisk()
            later()
            scanned.vm.link(newCode, " eggs", tick = true)
            tapped.vm.setTicked(AppFixture.EGGS, true)
            idle()
            val recorded = scanned.onDisk().drop(disk.size)
            assertIs<Observation.CodeLinked>(recorded.firstOrNull(), "naming a code to tick did not record the link first: $recorded")
            assertRecordedAsTapped(recorded.drop(1), tapped.onDisk().drop(disk.size), newCode, "naming a code eggs to tick")
            assertEvent(scanned, newCode, AppFixture.EGGS, added = false, ticked = true, what = "naming a code eggs to tick")
            assertSameNow(scanned, tapped, "naming a code eggs to tick")
            endAndCompare(scanned, tapped, "eggs named to tick")
        }
    }

    // O49 ---------------------------------------------------------------------------------------

    @Test
    fun `O49 scanning a ticked item to tick changes nothing`() {
        val scanned = Install(linked)
        val tapped = Install(linked)
        later()
        for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.MILK, true)
        idle()
        later()
        for (i in listOf(scanned, tapped)) i.vm.setTicked(AppFixture.EGGS, true)
        idle()

        // Milk was ticked first; scanning it now must not make it the latest tick.
        val disk = scanned.onDisk()
        val presented = scanned.presentation
        later()
        scanned.vm.scanned(milkCode, tick = true)
        idle()
        assertEquals(disk, scanned.onDisk(), "scanning milk to tick, already ticked, recorded something")
        assertEquals(presented, scanned.presentation, "scanning milk to tick, already ticked, changed the presentation")
        assertEquals(true, scanned.entry(AppFixture.MILK)?.ticked, "scanning milk to tick unticked it")
        assertEvent(scanned, milkCode, AppFixture.MILK, added = false, ticked = false, what = "scanning milk, already ticked, to tick")

        // Tea, not on the list: the first scan adds and ticks it, the second finds it ticked.
        later()
        scanned.vm.scanned(teaCode, tick = true)
        tapped.vm.add("Tea", null, null)
        tapped.vm.setTicked(tea, true)
        idle()
        assertEvent(scanned, teaCode, tea, added = true, ticked = true, what = "scanning tea to tick")
        val disk2 = scanned.onDisk()
        val presented2 = scanned.presentation
        later()
        scanned.vm.scanned(teaCode, tick = true)
        idle()
        assertEquals(disk2, scanned.onDisk(), "scanning tea to tick a second time recorded something")
        assertEquals(presented2, scanned.presentation)
        assertEvent(scanned, teaCode, tea, added = false, ticked = false, what = "scanning tea, already ticked, to tick")

        // Naming another code for a ticked item to tick records only the link.
        later()
        scanned.vm.link(otherCode, "MILK ", tick = true)
        idle()
        val recorded = scanned.onDisk().drop(disk2.size)
        assertEquals(1, recorded.size, "naming a code to tick for milk, already ticked, recorded more than the link: $recorded")
        assertIs<Observation.CodeLinked>(recorded.single())
        assertEvent(scanned, otherCode, AppFixture.MILK, added = false, ticked = false, what = "naming a code to tick for milk, already ticked")
        assertEquals(true, scanned.entry(AppFixture.MILK)?.ticked)

        assertSameNow(scanned, tapped, "after scanning ticked items")
        endAndCompare(scanned, tapped, "after scanning ticked items")
    }

    // O42 ---------------------------------------------------------------------------------------

    @Test
    fun `O42 an unlinked code scanned to tick adds and ticks nothing until named`() {
        val i = Install(linked)
        later()
        i.vm.setTicked(AppFixture.EGGS, true)
        idle()
        val disk = i.onDisk()
        val entries = i.ui.state!!.entries
        val presented = i.presentation
        later()
        i.vm.scanned(newCode, tick = true)
        idle()
        assertEquals(disk, i.onDisk(), "scanning an unlinked code to tick recorded something")
        assertEquals(entries, i.ui.state!!.entries)
        assertEquals(presented, i.presentation)
        assertEquals(newCode, i.ui.naming, "an unlinked code scanned to tick is not waiting for a name")

        // Another unlinked code read meanwhile is let go; the first still waits.
        i.vm.scanned(otherCode, tick = true)
        idle()
        assertEquals(newCode, i.ui.naming)
        assertEquals(disk, i.onDisk())

        i.vm.namingDismissed()
        idle()
        assertNull(i.ui.naming)
        assertEquals(disk, i.onDisk(), "declining to name a code scanned to tick recorded something")
    }
}
