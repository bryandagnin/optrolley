package dev.optrolley.autoroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.ui.AppFixture
import dev.optrolley.autoroute.ui.AppFixture.BREAD
import dev.optrolley.autoroute.ui.AppFixture.CORNER
import dev.optrolley.autoroute.ui.AppFixture.MILK
import dev.optrolley.autoroute.ui.AppFixture.SAFFRON
import dev.optrolley.autoroute.ui.AppFixture.START
import dev.optrolley.autoroute.ui.AppFixture.TESCO
import dev.optrolley.core.Barcode
import dev.optrolley.core.Confidence
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Presentation
import dev.optrolley.core.SectionKind
import dev.optrolley.core.State
import dev.optrolley.core.StoreId
import dev.optrolley.core.itemNamed
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tier 3 group J through the view model: a changed name renames the item
 * (O55), the old name then starts cold (O56), a name in use joins two (O57),
 * and an unlinked code is a code never seen (O58). What is recorded is read
 * back from the journal on disk.
 */
@RunWith(AndroidJUnit4::class)
class ItemRenameTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = AppFixture.Clock()

    private val milkCode = Barcode("5000112637922")
    private val breadCode = Barcode("5010029000016")

    /** [AppFixture.history] with a code linked to milk and one to bread. */
    private val linked: List<Observation> = AppFixture.history + listOf(
        Observation.CodeLinked(START + 55_000, milkCode, "Milk"),
        Observation.CodeLinked(START + 55_500, breadCode, "Bread"),
    )

    private inner class Install(history: List<Observation> = linked) {
        private val file = File(tmp.newFolder(), "history.jsonl")
        val vm: AppViewModel

        init {
            val store = HistoryStore(file)
            store.append(history)
            vm = AppViewModel(store, clock::read, Dispatchers.Unconfined, Dispatchers.Unconfined)
            idle()
        }

        fun onDisk() = HistoryStore(file).load()
        val state: State get() = vm.ui.value.state!!
        val presentation: Presentation get() = vm.ui.value.presentation!!

        /** What [act] recorded, read from the journal on disk. */
        fun recorded(act: AppViewModel.() -> Unit): List<Observation> {
            val before = onDisk().size
            vm.act()
            idle()
            return onDisk().drop(before)
        }

        fun entry(item: ItemKey) = state.entries.single { it.item == item }

        /** Sections, order and confidence at every store, by item and without names, which a rename is meant to change. */
        fun everywhere(): Map<StoreId?, Pair<List<Pair<SectionKind, List<Any?>>>, Map<ItemKey, Confidence>>> {
            val selected = state.selected
            val out = LinkedHashMap<StoreId?, Pair<List<Pair<SectionKind, List<Any?>>>, Map<ItemKey, Confidence>>>()
            for (store in listOf(TESCO, CORNER, null)) {
                vm.select(store)
                idle()
                out[store] = shape(presentation) to vm.ui.value.confidence
            }
            vm.select(selected)
            idle()
            return out
        }
    }

    private fun shape(p: Presentation) = p.sections.map { s -> s.kind to s.entries.map { listOf(it.item, it.quantity, it.note, it.ticked) } }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun section(i: Install, item: ItemKey) = i.presentation.sections.single { s -> s.entries.any { it.item == item } }.kind

    // --- O55 ---

    @Test
    fun `O55 changing an entry's name renames its item and moves nothing at any store`() {
        val i = Install()
        val before = i.everywhere()
        val recorded = i.recorded { edit(MILK, "Whole milk", "2 L", null) }
        assertEquals(listOf<Observation>(Observation.ItemRenamed(clock.now, MILK, "Whole milk")), recorded)
        val milk = i.entry(MILK)
        assertEquals("Whole milk", milk.name)
        assertEquals("2 L", milk.quantity)
        assertEquals(before, i.everywhere(), "a rename moved something")
    }

    @Test
    fun `O55 a code linked to a renamed item adds it under the new name, in its old place`() {
        val i = Install()
        i.vm.edit(MILK, "Whole milk", "2 L", null)
        i.vm.remove(MILK)
        idle()
        val added = i.recorded { scanned(milkCode) }
        val add = assertIs<Observation.Added>(added.single())
        assertEquals("Whole milk", add.name)
        assertEquals(milkCode, add.code)
        assertEquals("Whole milk", i.entry(MILK).name)
        assertEquals(MILK, i.vm.ui.value.scans.last().item)
        val run = i.presentation.sections.single { it.kind == SectionKind.RUN }.entries.map { it.item }
        assertEquals(listOf(MILK, BREAD), run, "renamed milk lost its place in the Run at Tesco")
    }

    @Test
    fun `O55 a rename mid-trip keeps the tick and the frozen order`() {
        val i = Install()
        i.vm.setTicked(MILK, true)
        idle()
        val order = shape(i.presentation)
        val sure = i.vm.ui.value.confidence
        i.vm.edit(MILK, "Whole milk", "2 L", null)
        idle()
        assertNotNull(i.state.trip, "a rename ended the trip")
        assertTrue(i.entry(MILK).ticked, "a rename unticked the entry")
        assertEquals(order, shape(i.presentation))
        assertEquals(sure, i.vm.ui.value.confidence)
    }

    @Test
    fun `a code scanned to tick after a rename ticks the renamed entry`() {
        val i = Install()
        i.vm.setTicked(MILK, true)
        i.vm.edit(BREAD, "Sourdough", null, null)
        idle()
        val recorded = i.recorded { scanned(breadCode, tick = true) }
        assertEquals(listOf<Observation>(Observation.Ticked(clock.now, BREAD, breadCode)), recorded)
        assertTrue(i.entry(BREAD).ticked)
        assertEquals("Sourdough", i.entry(BREAD).name)
    }

    @Test
    fun `O55 renaming in the library is renaming from the entry`() {
        val fromEntry = Install()
        val fromLibrary = Install()
        val a = fromEntry.recorded { edit(MILK, "Whole milk", "2 L", null) }
        val b = fromLibrary.recorded { renameItem(MILK, "Whole milk") }
        assertEquals(a, b)
        assertEquals(fromEntry.state, fromLibrary.state)
        assertEquals(fromEntry.everywhere(), fromLibrary.everywhere())
    }

    // --- O56 ---

    @Test
    fun `O56 the old name typed again is a new item, unplaced and added by no code`() {
        val i = Install()
        i.vm.edit(MILK, "Whole milk", "2 L", null)
        i.vm.add("Milk", null, null)
        idle()
        val again = assertNotNull(i.state.itemNamed("Milk"))
        assertNotEquals(MILK, again)
        assertEquals(2, i.state.entries.count { it.item == MILK || it.item == again }, "the old name did not make a second entry")
        assertEquals(again, i.vm.ui.value.focus, "the new entry is not the one brought into view")
        for (store in listOf(TESCO, CORNER)) {
            i.vm.select(store)
            idle()
            assertEquals(SectionKind.UNPLACED, section(i, again), "the old name is placed at $store")
        }
        assertFalse(again in i.state.codes.values, "a code adds the old name")
        assertEquals(MILK, i.state.codes[milkCode])
    }

    @Test
    fun `O56 the old name of an item said not to be here is not elsewhere`() {
        val i = Install()
        i.vm.edit(SAFFRON, "Saffron threads", null, null)
        i.vm.add("Saffron", null, null)
        idle()
        val again = assertNotNull(i.state.itemNamed("Saffron"))
        assertNotEquals(SAFFRON, again)
        assertEquals(SectionKind.UNPLACED, section(i, again))
        assertEquals(SectionKind.ELSEWHERE, section(i, SAFFRON))
    }

    // --- O57 ---

    @Test
    fun `O57 renaming onto another entry's name leaves one entry, in the earlier place`() {
        val i = Install()
        val recorded = i.recorded { edit(BREAD, "Milk", null, null) }
        assertEquals(listOf<Observation>(Observation.ItemRenamed(clock.now, BREAD, "Milk")), recorded, "joining recorded more than the rename")
        assertTrue(i.state.entries.none { it.item == BREAD })
        val milk = i.entry(MILK)
        assertEquals("Milk", milk.name)
        assertEquals("2 L", milk.quantity, "the joined entry lost what the earlier one held")
        assertEquals(MILK, i.state.entries.first().item, "the joined entry is not in the earlier one's place")
        assertEquals(MILK, i.state.codes[breadCode], "bread's code does not add the joined item")
    }

    @Test
    fun `O57 a join with a changed quantity edits the surviving entry`() {
        val i = Install()
        val recorded = i.recorded { edit(BREAD, "milk", "3", null) }
        assertEquals(
            listOf(Observation.ItemRenamed(clock.now, BREAD, "milk"), Observation.Edited(clock.now, MILK, "milk", "3", null)),
            recorded,
        )
        assertEquals(listOf(MILK), i.state.entries.map { it.item }.filter { it == MILK || it == BREAD })
        assertEquals("3", i.entry(MILK).quantity)
    }

    // --- Details only ---

    @Test
    fun `editing only the quantity and note records one Edited and no rename`() {
        val i = Install()
        val recorded = i.recorded { edit(MILK, " Milk ", "1 L", "blue top") }
        assertEquals(listOf<Observation>(Observation.Edited(clock.now, MILK, "Milk", "1 L", "blue top")), recorded)
        assertEquals(emptyList(), i.recorded { edit(MILK, "Milk", "1 L", "blue top") }, "an unchanged edit recorded something")
    }

    @Test
    fun `a change of case is a plain rename`() {
        val i = Install()
        assertNull(i.state.joinedBy(MILK, "MILK"))
        val recorded = i.recorded { edit(MILK, "MILK", "2 L", null) }
        assertEquals(listOf<Observation>(Observation.ItemRenamed(clock.now, MILK, "MILK")), recorded)
        assertEquals("MILK", i.entry(MILK).name)
        assertEquals(BREAD, i.state.joinedBy(MILK, " bread "))
    }

    // --- O58 ---

    @Test
    fun `O58 an unlinked code asks for a name at its next scan, and nothing else changes`() {
        val i = Install()
        val entries = i.state.entries
        val before = i.everywhere()
        val recorded = i.recorded { unlink(milkCode) }
        assertEquals(listOf<Observation>(Observation.CodeUnlinked(clock.now, milkCode)), recorded)
        assertNull(i.state.codes[milkCode])
        assertEquals(BREAD, i.state.codes[breadCode], "unlinking milk's code changed bread's")
        assertEquals(entries, i.state.entries)
        assertEquals("Milk", i.state.items[MILK])
        assertEquals(before, i.everywhere())

        assertEquals(emptyList(), i.recorded { scanned(milkCode) }, "a scan of an unlinked code recorded something")
        assertEquals(milkCode, i.vm.ui.value.naming)
    }

    @Test
    fun `undoing an unlink links the code back to its item, even renamed since`() {
        val i = Install()
        i.vm.unlink(milkCode)
        idle()
        val message = assertNotNull(i.vm.ui.value.message)
        assertNotNull(message.undo)
        i.vm.renameItem(MILK, "Whole milk")
        idle()
        val recorded = i.recorded { undo(message) }
        assertEquals(listOf<Observation>(Observation.CodeLinked(clock.now, milkCode, "Whole milk")), recorded)
        assertEquals(MILK, i.state.codes[milkCode])
        assertNull(i.state.itemNamed("Milk"), "undo made a new item of the old name")
    }
}
