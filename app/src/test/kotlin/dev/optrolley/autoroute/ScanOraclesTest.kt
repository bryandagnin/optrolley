package dev.optrolley.autoroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.ui.AppFixture
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 3 group H through the view model: a scan is a typed name (O41), an
 * unlinked code adds nothing until named (O42), the latest link is the
 * meaning (O43). Each comparison runs two fresh installs over the same
 * history, one scanning and one typing.
 */
@RunWith(AndroidJUnit4::class)
class ScanOraclesTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = AppFixture.Clock()

    private val milkCode = Barcode("5000112637922")
    private val breadCode = Barcode("5010029000016")
    private val teaCode = Barcode("9300633603352")
    private val newCode = Barcode("4006381333931")

    private val tea = ItemKey("tea")
    private val coffee = ItemKey("coffee")

    /**
     * [AppFixture.history], with milk, bread and tea linked. Bread is placed
     * at Tesco and then removed, so a scan of it must bring its placement back;
     * tea has never been typed.
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
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    /** Same list and presentation now, and with every store selected in turn. */
    private fun assertSame(scanned: Install, typed: Install, what: String) {
        assertEquals(typed.ui.state!!.entries, scanned.ui.state!!.entries, "$what: the list differs from typing")
        assertEquals(typed.ui.presentation, scanned.ui.presentation, "$what: the presentation differs from typing")
        val stores = listOf<StoreId?>(AppFixture.CORNER, null, AppFixture.TESCO)
        for (store in stores) {
            scanned.vm.select(store); typed.vm.select(store)
            idle()
            assertEquals(typed.ui.presentation, scanned.ui.presentation, "$what: the presentation differs from typing with $store selected")
        }
    }

    /** The last thing recorded by a scan is what typing recorded, with the code it read and nothing else. */
    private fun assertScanRecordedAsTyped(scanned: Install, typed: Install, code: Barcode) {
        val s = assertIs<Observation.Added>(scanned.onDisk().last(), "a scan recorded something other than an addition")
        val t = assertIs<Observation.Added>(typed.onDisk().last())
        assertEquals(code, s.code, "the scan did not record the code it read")
        assertEquals(t, s.copy(code = null), "the scan recorded something other than typing would, besides the code")
    }

    @Test
    fun `O41 scanning a linked code is typing its item's name`() {
        for ((code, name) in listOf(breadCode to "Bread", teaCode to "Tea")) {
            val scanned = Install(linked)
            val typed = Install(linked)
            scanned.vm.scanned(code)
            typed.vm.add(name, null, null)
            idle()
            assertScanRecordedAsTyped(scanned, typed, code)
            val event = scanned.ui.scans.last()
            assertEquals(code, event.code)
            assertEquals(ItemKey.of(name), event.item)
            assertTrue(event.added, "a scan that added $name says it did not")
            assertSame(scanned, typed, "scanning $code")
        }
    }

    @Test
    fun `O41 a scan brings the item's placement with it`() {
        val scanned = Install(linked)
        scanned.vm.scanned(breadCode)
        idle()
        val run = scanned.ui.presentation!!.sections.single { it.kind == SectionKind.RUN }.entries.map { it.item }
        assertEquals(listOf(AppFixture.MILK, AppFixture.BREAD), run, "scanned bread is not back in the Run after milk at Tesco")
    }

    @Test
    fun `O41 a scan mid-trip lands as a typed name would`() {
        val scanned = Install(linked)
        val typed = Install(linked)
        for (i in listOf(scanned, typed)) i.vm.setTicked(AppFixture.MILK, true)
        idle()
        scanned.vm.scanned(breadCode)
        typed.vm.add("Bread", null, null)
        idle()
        assertScanRecordedAsTyped(scanned, typed, breadCode)
        assertEquals(typed.ui.state!!.entries, scanned.ui.state!!.entries, "mid-trip, the list differs from typing")
        assertEquals(typed.ui.presentation, scanned.ui.presentation, "mid-trip, the presentation differs from typing")
        for (i in listOf(scanned, typed)) i.vm.finishTrip()
        idle()
        assertSame(scanned, typed, "after the trip")
    }

    @Test
    fun `O41 scanning an item already on the list adds nothing twice`() {
        val scanned = Install(linked)
        val before = scanned.onDisk()
        scanned.vm.scanned(milkCode)
        idle()
        assertEquals(before, scanned.onDisk(), "scanning milk, already on the list, recorded something")
        val event = scanned.ui.scans.last()
        assertEquals(milkCode, event.code)
        assertEquals(AppFixture.MILK, event.item)
        assertTrue(!event.added, "a scan of milk, already on the list, says it added it")
        val typed = Install(linked)
        typed.vm.add("Milk", null, null)
        idle()
        assertSame(scanned, typed, "scanning milk already on the list")
    }

    @Test
    fun `O42 an unlinked code adds nothing until it is named`() {
        val scanned = Install(linked)
        val before = scanned.onDisk()
        val entries = scanned.ui.state!!.entries
        scanned.vm.scanned(newCode)
        idle()
        assertEquals(before, scanned.onDisk(), "scanning an unlinked code recorded something")
        assertEquals(entries, scanned.ui.state!!.entries)
        assertEquals(newCode, scanned.ui.naming, "an unlinked code is not waiting for a name")

        scanned.vm.namingDismissed()
        idle()
        assertNull(scanned.ui.naming)
        assertEquals(before, scanned.onDisk(), "declining to name a code recorded something")

        scanned.vm.scanned(newCode)
        idle()
        scanned.vm.link(newCode, "Coffee")
        idle()
        assertNull(scanned.ui.naming, "named, the code is still waiting for a name")
        val added = scanned.onDisk().drop(before.size)
        val link = assertIs<Observation.CodeLinked>(added.first(), "naming a code did not record the link first")
        assertEquals(newCode, link.code)
        assertEquals(coffee, ItemKey.of(link.name))
        assertEquals(coffee, scanned.ui.state!!.codes[newCode])
        val typed = Install(linked)
        typed.vm.add("Coffee", null, null)
        idle()
        assertScanRecordedAsTyped(scanned, typed, newCode)
        assertSame(scanned, typed, "naming $newCode")
    }

    @Test
    fun `O43 the latest link is the meaning, and links are independent`() {
        val i = Install(linked)
        i.vm.link(milkCode, "Oat milk")
        idle()
        val state = i.ui.state!!
        assertEquals(ItemKey("oat milk"), state.codes[milkCode], "relinked, milk's code does not mean oat milk")
        assertTrue(state.entries.any { it.item == ItemKey("oat milk") }, "relinking to oat milk did not add it")
        assertTrue(state.entries.any { it.item == AppFixture.MILK }, "relinking took milk off the list")
        assertEquals(tea, state.codes[teaCode], "relinking milk's code changed tea's")

        val before = i.onDisk().size
        i.vm.scanned(teaCode)
        idle()
        assertEquals(tea, i.ui.scans.last().item, "tea's code no longer adds tea")
        assertEquals(before + 1, i.onDisk().size)
    }

    @Test
    fun `O43 many codes mean one item, matched as typed names are`() {
        val i = Install(linked)
        val before = i.onDisk()
        i.vm.link(newCode, "  MILK ")
        idle()
        val recorded = i.onDisk().drop(before.size)
        assertEquals(1, recorded.size, "linking a second code to milk, on the list, recorded more than the link: $recorded")
        assertIs<Observation.CodeLinked>(recorded.single())
        assertEquals(AppFixture.MILK, i.ui.state!!.codes[newCode])
        assertEquals(AppFixture.MILK, i.ui.state!!.codes[milkCode])
        assertEquals(1, i.ui.state!!.entries.count { it.item == AppFixture.MILK })
        val event = i.ui.scans.last()
        assertEquals(AppFixture.MILK, event.item)
        assertTrue(!event.added, "milk was already on the list")

        i.vm.scanned(milkCode)
        idle()
        assertEquals(before.size + 1, i.onDisk().size, "scanning milk's other code added it twice")
    }
}
