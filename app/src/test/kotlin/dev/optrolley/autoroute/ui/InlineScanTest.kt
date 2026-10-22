package dev.optrolley.autoroute.ui

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.HistoryStore
import dev.optrolley.autoroute.RethrowUncaught
import dev.optrolley.autoroute.scan.Scanner
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import kotlinx.coroutines.Dispatchers
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Scanning on the list, with codes fed in by hand where the camera would be (F12, D17, O46). */
@RunWith(AndroidJUnit4::class)
class InlineScanTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    /** Stands in for the camera: the test calls [scan] where a code would be read. */
    private class FakeScanner : Scanner {
        var active = false
        private var onCode: ((Barcode) -> Unit)? = null

        @Composable
        override fun Viewfinder(active: Boolean, onCode: (Barcode) -> Unit, modifier: Modifier) {
            SideEffect {
                this.active = active
                this.onCode = onCode
            }
            Box(modifier)
        }

        fun scan(code: Barcode) = onCode!!.invoke(code)
    }

    private val sugarA = Barcode("4006381333931")
    private val sugarB = Barcode("96385074")
    private val scanner = FakeScanner()
    private lateinit var vm: AppViewModel

    @Before
    fun grantCamera() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions(Manifest.permission.CAMERA)
    }

    private fun launch(history: List<Observation> = AppFixture.history, scanner: Scanner? = this.scanner) {
        val store = HistoryStore(File(tmp.root, "history.jsonl"))
        store.append(history)
        val clock = AppFixture.Clock()
        vm = AppViewModel(store, clock::read, Dispatchers.Unconfined, Dispatchers.Unconfined)
        compose.setContent { OptrolleyTheme(dark = false) { OptrolleyApp(vm, scanner) } }
        compose.waitForIdle()
    }

    private fun scan(code: Barcode) {
        compose.runOnIdle { scanner.scan(code) }
        compose.waitForIdle()
    }

    private fun entries() = vm.ui.value.state!!.entries.map { it.item }

    private fun openScanner() {
        compose.onNodeWithTag(Tags.SCAN_BUTTON).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SCAN_MINIMAP).assertIsDisplayed()
    }

    @Test
    fun `a new code is named, then added, and the camera waits meanwhile`() {
        launch()
        openScanner()
        scan(sugarA)
        compose.onNodeWithTag(Tags.NAMING_FIELD).assertIsDisplayed()
        assertFalse(scanner.active, "decoding pauses while a code is named")
        assertFalse(ItemKey("sugar") in entries(), "nothing is added before it is named (O42)")

        compose.onNodeWithTag(Tags.NAMING_FIELD).performTextInput("Sugar")
        compose.onNodeWithTag(Tags.NAMING_SAVE).performClick()
        compose.waitForIdle()

        assertTrue(scanner.active)
        assertTrue(ItemKey("sugar") in entries())
        val event = vm.ui.value.scans.single()
        compose.onNodeWithTag(Tags.scanEvent(event.id)).assertTextContains("Sugar added", substring = true)
    }

    @Test
    fun `a second brand can share the name, offered from the list`() {
        launch(AppFixture.history + Observation.CodeLinked(AppFixture.START + 55_000, sugarA, "Sugar"))
        openScanner()
        scan(sugarA)
        assertTrue(ItemKey("sugar") in entries(), "a linked code adds at once")
        scan(sugarB)
        compose.onNodeWithTag(Tags.namingSuggestion(ItemKey("sugar"))).performClick()
        compose.waitForIdle()
        assertEquals(ItemKey("sugar"), vm.ui.value.state!!.codes[sugarB])
        assertEquals(1, entries().count { it == ItemKey("sugar") })
        assertFalse(vm.ui.value.scans.last().added, "already on the list, so not added twice")
    }

    @Test
    fun `undo takes back what a scan added`() {
        launch(AppFixture.history + Observation.CodeLinked(AppFixture.START + 55_000, sugarA, "Sugar"))
        openScanner()
        scan(sugarA)
        val event = vm.ui.value.scans.single()
        compose.onNodeWithTag(Tags.scanUndo(event.id)).performClick()
        compose.waitForIdle()
        assertFalse(ItemKey("sugar") in entries())
        compose.onNodeWithTag(Tags.scanEvent(event.id)).assertTextContains("Sugar undone", substring = true)
    }

    @Test
    fun `change relinks the code and swaps the entry it added`() {
        launch(AppFixture.history + Observation.CodeLinked(AppFixture.START + 55_000, sugarA, "Sugar"))
        openScanner()
        scan(sugarA)
        val event = vm.ui.value.scans.single()
        compose.onNodeWithTag(Tags.scanChange(event.id)).performClick()
        compose.onNodeWithTag(Tags.NAMING_FIELD).performTextReplacement("Caster sugar")
        compose.onNodeWithTag(Tags.NAMING_SAVE).performClick()
        compose.waitForIdle()
        assertEquals(ItemKey("caster sugar"), vm.ui.value.state!!.codes[sugarA])
        assertTrue(ItemKey("caster sugar") in entries())
        assertFalse(ItemKey("sugar") in entries())
    }

    @Test
    fun `a scanned entry is brought into view, and the camera goes off at a second press`() {
        launch(AppFixture.history + Observation.CodeLinked(AppFixture.START + 55_000, sugarA, "Sugar"))
        openScanner()
        scan(sugarA)
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag(Tags.entry(ItemKey("sugar"))).assertIsDisplayed()
        compose.onNodeWithTag(Tags.SCAN_BUTTON).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SCAN_MINIMAP).assertDoesNotExist()
        assertTrue(ItemKey("sugar") in entries())
    }

    @Test
    fun `O3 the viewfinder never covers the list`() {
        launch()
        openScanner()
        val list = compose.onNodeWithTag(Tags.LIST).fetchSemanticsNode().boundsInRoot
        val camera = compose.onNodeWithTag(Tags.SCAN_MINIMAP).fetchSemanticsNode().boundsInRoot
        assertFalse(list.overlaps(camera), "list $list, viewfinder $camera")
    }

    @Test
    fun `finishing the trip turns the camera off`() {
        launch(midTrip)
        openScanner()
        compose.onNodeWithTag(Tags.FINISH_TRIP).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SCAN_MINIMAP).assertDoesNotExist()
    }

    @Test
    fun `O46 with no camera there is no scan button and the list still works`() {
        launch(scanner = null)
        compose.onNodeWithTag(Tags.SCAN_BUTTON).assertDoesNotExist()
        compose.onNodeWithTag(Tags.ADD_FIELD).performTextInput("Tea")
        compose.onNodeWithTag(Tags.ADD_BUTTON).performClick()
        compose.waitForIdle()
        assertTrue(ItemKey("tea") in entries())
    }

    @Test
    fun `O46 a refused camera leaves the list as it was and says why`() {
        launch()
        val before = vm.ui.value.history
        compose.runOnIdle { vm.cameraRefused() }
        compose.waitForIdle()
        assertEquals(before, vm.ui.value.history)
        compose.onNodeWithTag(Tags.SCAN_MINIMAP).assertDoesNotExist()
        compose.onNodeWithTag(Tags.LIST).assertIsDisplayed()
    }

    // --- Scanning to tick ---

    /** The fixture with a trip under way at Tesco (eggs ticked) and sugar A's code meaning bread. */
    private val midTrip = AppFixture.history + listOf(
        Observation.Ticked(AppFixture.START + 55_000, AppFixture.EGGS),
        Observation.CodeLinked(AppFixture.START + 56_000, sugarA, "Bread"),
        Observation.CodeLinked(AppFixture.START + 57_000, sugarB, "Sugar"),
    )

    private fun ticked(item: ItemKey) = vm.ui.value.state!!.entries.single { it.item == item }.ticked

    @Test
    fun `with no trip under way the scanner starts on add`() {
        launch()
        openScanner()
        compose.onNodeWithTag(Tags.SCAN_MODE_ADD).assertIsSelected()
        compose.onNodeWithTag(Tags.SCAN_MODE_TICK).assertIsNotSelected()
    }

    @Test
    fun `mid-trip the scanner starts on tick, and a scan ticks its entry`() {
        launch(midTrip)
        openScanner()
        compose.onNodeWithTag(Tags.SCAN_MODE_TICK).assertIsSelected()
        scan(sugarA)
        assertTrue(ticked(AppFixture.BREAD))
        val event = vm.ui.value.scans.single()
        compose.onNodeWithTag(Tags.scanEvent(event.id)).assertTextContains("Bread ticked", substring = true)
    }

    @Test
    fun `scanned to tick, an item not on the list is added and ticked`() {
        launch(midTrip)
        openScanner()
        scan(sugarB)
        assertTrue(ticked(ItemKey("sugar")))
    }

    @Test
    fun `undo of a tick unticks, and undo of an added tick removes`() {
        launch(midTrip)
        openScanner()
        scan(sugarA)
        val bread = vm.ui.value.scans.last()
        compose.onNodeWithTag(Tags.scanUndo(bread.id)).performClick()
        compose.waitForIdle()
        assertFalse(ticked(AppFixture.BREAD), "bread unticked")
        scan(sugarB)
        val sugar = vm.ui.value.scans.last()
        compose.onNodeWithTag(Tags.scanEvent(sugar.id)).assertIsDisplayed()
        compose.onNodeWithTag(Tags.scanUndo(sugar.id)).performClick()
        compose.waitForIdle()
        assertFalse(ticked(AppFixture.BREAD))
        assertFalse(ItemKey("sugar") in entries())
    }

    @Test
    fun `the switch decides, so add mode mid-trip adds without ticking`() {
        launch(midTrip)
        openScanner()
        compose.onNodeWithTag(Tags.SCAN_MODE_ADD).performClick()
        scan(sugarB)
        assertFalse(ticked(ItemKey("sugar")))
    }

    @Test
    fun `a new code named in tick mode is ticked`() {
        launch(midTrip)
        openScanner()
        scan(Barcode("036000291452"))
        compose.onNodeWithTag(Tags.namingSuggestion(AppFixture.MILK)).performClick()
        compose.waitForIdle()
        assertTrue(ticked(AppFixture.MILK))
    }
}
