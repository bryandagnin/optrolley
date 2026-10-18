package dev.optrolley.autoroute.ui

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.RethrowUncaught
import dev.optrolley.autoroute.ui.AppFixture.ALL
import dev.optrolley.autoroute.ui.AppFixture.BREAD
import dev.optrolley.autoroute.ui.AppFixture.CORNER
import dev.optrolley.autoroute.ui.AppFixture.EGGS
import dev.optrolley.autoroute.ui.AppFixture.MILK
import dev.optrolley.autoroute.ui.AppFixture.SAFFRON
import dev.optrolley.autoroute.ui.AppFixture.TESCO
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Rules
import dev.optrolley.core.SectionKind
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** The tier 3 oracles that are about the screen: O3, O4, O10, O39, O40. */
@RunWith(AndroidJUnit4::class)
class AppOraclesTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun scrollTo(tag: String) {
        compose.onNodeWithTag(Tags.LIST).performScrollToNode(hasTestTag(tag))
    }

    private fun selectStore(option: String) {
        compose.onNodeWithTag(Tags.STORE_PICKER).performClick()
        compose.onNodeWithTag(option).performClick()
        compose.waitForIdle()
    }

    private fun openMenu(item: ItemKey) {
        scrollTo(Tags.entry(item))
        compose.onNodeWithTag(Tags.entryMenu(item)).performClick()
    }

    private fun tickAndUntick(item: ItemKey) {
        scrollTo(Tags.entry(item))
        compose.onNodeWithTag(Tags.tick(item)).performClick()
        compose.onNodeWithTag(Tags.tick(item)).assertIsOn()
        compose.onNodeWithTag(Tags.tick(item)).performClick()
        compose.onNodeWithTag(Tags.tick(item)).assertIsOff()
    }

    /**
     * Ticks, unticks and edits each of [items], wherever it is presented,
     * then removes each. Removal comes last because its undo snackbar covers
     * the foot of the list for a while.
     */
    private fun exercise(items: List<ItemKey>) {
        for (item in items) {
            tickAndUntick(item)
            openMenu(item)
            compose.onNodeWithTag(Tags.actionEdit(item)).performClick()
            compose.onNodeWithTag(Tags.EDIT_QUANTITY).performTextReplacement("7 of them")
            compose.onNodeWithTag(Tags.EDIT_SAVE).performClick()
            compose.waitForIdle()
            scrollTo(Tags.entry(item))
            compose.onNodeWithTag(Tags.entry(item)).assertTextContains("7 of them", substring = true)
        }
        for (item in items) {
            openMenu(item)
            compose.onNodeWithTag(Tags.actionRemove(item)).performClick()
            compose.waitForIdle()
            compose.onNodeWithTag(Tags.entry(item)).assertDoesNotExist()
        }
    }

    // --- O3: reachability ---

    @Test
    fun `O3 every entry in every section at a store can be ticked, edited and removed`() {
        AppFixture.launch(compose, tmp.root)
        for (kind in listOf(SectionKind.UNPLACED, SectionKind.RUN, SectionKind.ELSEWHERE)) {
            scrollTo(Tags.section(kind))
        }
        // One entry per section: eggs unplaced, milk and bread in the run, saffron elsewhere.
        exercise(listOf(EGGS, MILK, SAFFRON, BREAD))
    }

    @Test
    fun `O3 an entry elsewhere can be ticked`() {
        AppFixture.launch(compose, tmp.root)
        tickAndUntick(SAFFRON)
    }

    @Test
    fun `O3 every entry can be ticked, edited and removed with no store selected`() {
        AppFixture.launch(compose, tmp.root)
        selectStore(Tags.STORE_OPTION_NONE)
        exercise(ALL)
    }

    @Test
    fun `O3 every entry can be ticked, edited and removed at a store that has learned nothing`() {
        AppFixture.launch(compose, tmp.root)
        selectStore(Tags.storeOption(CORNER))
        exercise(ALL)
    }

    @Test
    fun `O3 not here is offered at a store and cannot be said with none`() {
        AppFixture.launch(compose, tmp.root)
        openMenu(EGGS)
        compose.onNodeWithTag(Tags.actionNotHereStore(EGGS)).assertIsDisplayed()
        compose.onNodeWithTag(Tags.actionNotHereChain(EGGS)).assertIsDisplayed()
        compose.onNodeWithTag(Tags.actionEdit(EGGS)).performClick()
        compose.onNodeWithTag(Tags.EDIT_CANCEL).performClick()

        selectStore(Tags.STORE_OPTION_NONE)
        openMenu(EGGS)
        compose.onNodeWithTag(Tags.actionNotHereStore(EGGS)).assertIsNotEnabled()
        compose.onNodeWithTag(Tags.actionNotHereChain(EGGS)).assertDoesNotExist()
    }

    // --- O4: Elsewhere is announced ---

    @Test
    fun `O4 the number of entries elsewhere is shown with the list`() {
        AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.ELSEWHERE_BANNER).assertIsDisplayed().assertTextContains("1 item", substring = true)
        scrollTo(Tags.section(SectionKind.ELSEWHERE))
        compose.onNodeWithTag(Tags.section(SectionKind.ELSEWHERE)).assertTextContains("Elsewhere (1)", substring = true)
    }

    @Test
    fun `O4 the count follows not here as it is said and withdrawn`() {
        AppFixture.launch(compose, tmp.root)
        openMenu(EGGS)
        compose.onNodeWithTag(Tags.actionNotHereStore(EGGS)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.ELSEWHERE_BANNER).assertTextContains("2 items", substring = true)

        openMenu(SAFFRON)
        compose.onNodeWithTag(Tags.actionAvailable(SAFFRON)).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.ELSEWHERE_BANNER).assertTextContains("1 item", substring = true)
    }

    @Test
    fun `O4 the announcement stays on screen however far the list is scrolled`() {
        AppFixture.launch(compose, tmp.root)
        scrollTo(Tags.entry(MILK))
        compose.onNodeWithTag(Tags.ELSEWHERE_BANNER).assertIsDisplayed()
        scrollTo(Tags.entry(SAFFRON))
        compose.onNodeWithTag(Tags.ELSEWHERE_BANNER).assertIsDisplayed()
    }

    @Test
    fun `O4 nothing elsewhere, nothing announced`() {
        AppFixture.launch(compose, tmp.root)
        selectStore(Tags.storeOption(CORNER))
        compose.onNodeWithTag(Tags.ELSEWHERE_BANNER).assertDoesNotExist()
    }

    // --- O10: no silent store change ---

    @Test
    fun `O10 the store stays selected through a trip, an idle end and a resume`() {
        val clock = AppFixture.Clock()
        val vm = AppFixture.launch(compose, tmp.root, clock = clock)
        scrollTo(Tags.entry(MILK))
        compose.onNodeWithTag(Tags.tick(MILK)).performClick()
        compose.onNodeWithTag(Tags.FINISH_TRIP).assertIsDisplayed()
        compose.onNodeWithTag(Tags.STORE_PICKER).assertTextContains("Tesco Extra", substring = true)

        clock.now += Rules.IDLE_TIMEOUT_MS + 60_000
        vm.onResume()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.FINISH_TRIP).assertDoesNotExist()
        compose.onNodeWithTag(Tags.STORE_PICKER).assertTextContains("Tesco Extra", substring = true)

        scrollTo(Tags.entry(BREAD))
        compose.onNodeWithTag(Tags.tick(BREAD)).performClick()
        compose.onNodeWithTag(Tags.FINISH_TRIP).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.STORE_PICKER).assertTextContains("Tesco Extra", substring = true)
    }

    // --- O39: nothing is demanded ---

    @Test
    fun `O39 creating a store asks for a name and nothing else`() {
        AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_STORES).performClick()
        compose.onNodeWithTag(Tags.ADD_STORE).performClick()

        compose.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        compose.onNodeWithTag(Tags.STORE_NAME_FIELD).performTextInput("Aldi")
        compose.onNodeWithTag(Tags.STORE_SAVE).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Aldi").assertCountEquals(1)

        compose.onNodeWithTag(Tags.BACK).performClick()
        compose.onNodeWithTag(Tags.STORE_PICKER).performClick()
        compose.onAllNodesWithText("Aldi").assertCountEquals(1)
    }

    @Test
    fun `O39 a chain is optional, and a new one needs only its name`() {
        AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_STORES).performClick()
        compose.onNodeWithTag(Tags.ADD_STORE).performClick()
        compose.onNodeWithTag(Tags.STORE_NAME_FIELD).performTextInput("Pak'nSave Albany")
        compose.onNodeWithTag(Tags.CHAIN_PICKER).performClick()
        compose.onNodeWithTag(Tags.CHAIN_OPTION_NEW).performClick()
        compose.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        compose.onNodeWithTag(Tags.NEW_CHAIN_FIELD).performTextInput("Pak'nSave")
        compose.onNodeWithTag(Tags.STORE_SAVE).performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Pak'nSave Albany").assertCountEquals(1)
        compose.onAllNodesWithText("Pak'nSave").assertCountEquals(2) // the store's chain line and the chain itself
    }

    // --- O40: the store is always shown ---

    @Test
    fun `O40 the selected store, or none, is shown with the list`() {
        AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.STORE_PICKER).assertIsDisplayed().assertTextContains("Tesco Extra", substring = true)

        selectStore(Tags.STORE_OPTION_NONE)
        compose.onNodeWithTag(Tags.STORE_PICKER).assertIsDisplayed().assertTextContains("No store", substring = true)

        selectStore(Tags.storeOption(CORNER))
        compose.onNodeWithTag(Tags.STORE_PICKER).assertTextContains("Corner shop", substring = true)
        scrollTo(Tags.entry(SAFFRON))
        compose.onNodeWithTag(Tags.STORE_PICKER).assertIsDisplayed()
    }

    @Test
    fun `O40 a renamed store is shown by its new name`() {
        AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_STORES).performClick()
        compose.onNodeWithTag(Tags.store(TESCO)).performClick()
        compose.onNodeWithTag(Tags.STORE_NAME_FIELD).performTextReplacement("Tesco Metro")
        compose.onNodeWithTag(Tags.STORE_SAVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.BACK).performClick()
        compose.onNodeWithTag(Tags.STORE_PICKER).assertTextContains("Tesco Metro", substring = true)
    }
}
