package dev.optrolley.autoroute.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.HistoryStore
import dev.optrolley.autoroute.RethrowUncaught
import dev.optrolley.autoroute.catalogue.Answer
import dev.optrolley.autoroute.catalogue.StandInCatalogue
import dev.optrolley.autoroute.catalogue.StandInCatalogue.Request
import dev.optrolley.autoroute.ui.AppFixture.START
import dev.optrolley.core.Barcode
import dev.optrolley.core.Observation
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Looking codes up as tapped: the option in Settings (F14), and what the library offers with it off and on (F13, O63). */
@RunWith(AndroidJUnit4::class)
class LibraryLookupScreenTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val milkCode = Barcode("5000112637922")
    private val breadCode = Barcode("5010029000016")
    private val clock = AppFixture.Clock()
    private val catalogue = StandInCatalogue(clock::read, ::reply)
    private lateinit var vm: AppViewModel

    private fun reply(request: Request): Answer = when (request) {
        Request.Product(milkCode) -> Answer.Product("Anchor Blue Top", "Anchor", null)
        else -> Answer.None
    }

    private fun launch() {
        val store = HistoryStore(File(tmp.newFolder(), "history.jsonl"))
        store.append(
            AppFixture.history + listOf(
                Observation.CodeLinked(START + 55_000, milkCode, "Milk"),
                Observation.CodeLinked(START + 55_500, breadCode, "Bread"),
            ),
        )
        vm = AppViewModel(
            store,
            clock::read,
            Dispatchers.Unconfined,
            Dispatchers.Unconfined,
            catalogue = catalogue,
            catalogueCache = tmp.newFolder(),
            pause = { clock.now += it },
        )
        compose.setContent { OptrolleyTheme(dark = false) { OptrolleyApp(vm) } }
        compose.waitForIdle()
    }

    private fun openLibrary() {
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_LIBRARY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.LIBRARY).assertIsDisplayed()
    }

    private fun tap(tag: String) {
        compose.onNodeWithTag(Tags.LIBRARY).performScrollToNode(hasTestTag(tag))
        compose.onNodeWithTag(tag).performClick()
        compose.waitForIdle()
    }

    private fun openSettings() {
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_SETTINGS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SETTINGS).assertIsDisplayed()
    }

    private fun back() {
        compose.onNodeWithTag(Tags.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.LIST).assertIsDisplayed()
    }

    private fun turnOn() {
        openSettings()
        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).performClick()
        compose.waitForIdle()
        back()
    }

    /** [code]'s row, scrolled into view, shows its number. */
    private fun showsNumber(code: Barcode) {
        compose.onNodeWithTag(Tags.LIBRARY).performScrollToNode(hasTestTag(Tags.libraryCode(code)))
        compose.onNode(hasTestTag(Tags.libraryCode(code)) and hasAnyDescendant(hasText(code.value))).assertIsDisplayed()
    }

    /** No control that would look a code up, nor any sign of one under way. */
    private fun offersNoLookUp() {
        for (tag in listOf(Tags.libraryLookUp(milkCode), Tags.libraryLookUp(breadCode), Tags.LIBRARY_LOOK_UP_ALL, Tags.LIBRARY_PROGRESS)) {
            compose.onNodeWithTag(tag).assertDoesNotExist()
        }
    }

    @Test
    fun `Settings is reached from the menu, and its switch turns looking codes up on and off`() {
        launch()
        openSettings()
        compose.onNodeWithText("Look up barcodes").assertIsDisplayed()
        // Whom, what is sent, and the licence of what comes back (tier 4 §7.3).
        val description = compose.onNodeWithText("Only the barcode’s number is sent, to Open Food Facts", substring = true)
        description.assertIsDisplayed()
        description.assertTextContains("Open Database License", substring = true)
        description.assertTextContains("CC BY-SA", substring = true)

        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).assertIsOff()
        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).assertIsOn()
        assertTrue(vm.library.value.enabled)
        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).assertIsOff()
        assertFalse(vm.library.value.enabled)
        assertEquals(emptyList(), catalogue.requests, "the switch alone sent something")
        back()
    }

    @Test
    fun `O63 with the option off, the library shows numbers, offers no look-up and sends nothing`() {
        launch()
        openLibrary()
        offersNoLookUp()
        compose.onNodeWithTag(Tags.LIBRARY_CLEAR).assertDoesNotExist()
        showsNumber(milkCode)
        compose.onNodeWithTag(Tags.libraryProduct(milkCode)).assertDoesNotExist()
        compose.onNodeWithTag(Tags.libraryUnlink(milkCode)).assertIsDisplayed()
        assertEquals(emptyList(), catalogue.requests)
    }

    @Test
    fun `with the option on, a look-up shows the product, says where it came from, and never shows it on the list`() {
        launch()
        turnOn()
        openLibrary()
        assertEquals(emptyList(), catalogue.requests, "opening the library sent something by itself")
        tap(Tags.libraryLookUp(milkCode))
        assertEquals(listOf<Request>(Request.Product(milkCode)), catalogue.requests)
        compose.onNodeWithTag(Tags.LIBRARY).performScrollToNode(hasTestTag(Tags.libraryCode(milkCode)))
        compose.onNode(hasTestTag(Tags.libraryCode(milkCode)) and hasAnyDescendant(hasText("Anchor Blue Top · Anchor")))
            .assertIsDisplayed()
        showsNumber(milkCode)
        compose.onNodeWithTag(Tags.LIBRARY_SOURCE).assertTextContains("Open Food Facts", substring = true)
        compose.onNodeWithTag(Tags.LIBRARY_SOURCE).assertTextContains("Open Database License", substring = true)
        back()
        compose.onNodeWithText("Anchor Blue Top", substring = true).assertDoesNotExist()
    }

    @Test
    fun `look up all shows how far it has got`() {
        launch()
        turnOn()
        openLibrary()
        catalogue.held = true
        tap(Tags.LIBRARY_LOOK_UP_ALL)
        compose.onNodeWithTag(Tags.LIBRARY_PROGRESS).assertTextContains("1 of 2", substring = true)
        compose.runOnIdle { catalogue.release() }
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.LIBRARY_PROGRESS).assertTextContains("2 of 2", substring = true)
        compose.runOnIdle { catalogue.release() }
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.LIBRARY_PROGRESS).assertDoesNotExist()
        compose.onNodeWithTag(Tags.LIBRARY_LOOK_UP_ALL).assertIsNotEnabled()
        assertEquals(2, catalogue.requests.size)
    }

    @Test
    fun `turned off, the library hides its look-ups and keeps what was fetched until it is cleared`() {
        launch()
        turnOn()
        openLibrary()
        tap(Tags.libraryLookUp(milkCode))
        back()
        openSettings()
        compose.onNodeWithTag(Tags.SETTING_LOOK_UPS).performClick()
        compose.waitForIdle()
        back()
        openLibrary()
        offersNoLookUp()
        compose.onNodeWithTag(Tags.LIBRARY).performScrollToNode(hasTestTag(Tags.libraryCode(milkCode)))
        compose.onNodeWithTag(Tags.libraryProduct(milkCode)).assertIsDisplayed()
        tap(Tags.LIBRARY_CLEAR)
        compose.onNodeWithTag(Tags.libraryProduct(milkCode)).assertDoesNotExist()
        compose.onNodeWithTag(Tags.LIBRARY_CLEAR).assertDoesNotExist()
        showsNumber(milkCode)
        assertEquals(1, catalogue.requests.size)
    }
}
