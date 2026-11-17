package dev.optrolley.autoroute.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.HistoryStore
import dev.optrolley.autoroute.RethrowUncaught
import dev.optrolley.autoroute.ui.AppFixture.BREAD
import dev.optrolley.autoroute.ui.AppFixture.EGGS
import dev.optrolley.autoroute.ui.AppFixture.MILK
import dev.optrolley.autoroute.ui.AppFixture.SAFFRON
import dev.optrolley.autoroute.ui.AppFixture.START
import dev.optrolley.core.Barcode
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Renaming from an entry and from the library, joining, and unlinking, as tapped (F1, F13, tier 4 §7.3). */
@RunWith(AndroidJUnit4::class)
class RenameScreensTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private val milkCode = Barcode("5000112637922")
    private val breadCode = Barcode("5010029000016")
    private lateinit var vm: AppViewModel

    private fun launch() {
        vm = AppFixture.launch(
            compose,
            tmp.root,
            AppFixture.history + listOf(
                Observation.CodeLinked(START + 55_000, milkCode, "Milk"),
                Observation.CodeLinked(START + 55_500, breadCode, "Bread"),
            ),
        )
    }

    private fun onDisk() = HistoryStore(File(tmp.root, "history.jsonl")).load()

    private val state get() = vm.ui.value.state!!

    private fun openEdit(item: ItemKey) {
        compose.onNodeWithTag(Tags.LIST).performScrollToNode(hasTestTag(Tags.entry(item)))
        compose.onNodeWithTag(Tags.entryMenu(item)).performClick()
        compose.onNodeWithTag(Tags.actionEdit(item)).performClick()
        compose.waitForIdle()
    }

    private fun openLibrary() {
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_LIBRARY).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.LIBRARY).assertIsDisplayed()
    }

    /** The node tagged [tag] shows [text] somewhere within it. */
    private fun holds(tag: String, text: String) {
        compose.onNode(hasTestTag(tag) and hasAnyDescendant(hasText(text))).assertIsDisplayed()
    }

    // --- From the list ---

    @Test
    fun `a new name on an entry renames its item, and the entry shows it`() {
        launch()
        val before = onDisk().size
        openEdit(MILK)
        compose.onNodeWithTag(Tags.EDIT_NAME).performTextReplacement("Whole milk")
        compose.onNodeWithTag(Tags.EDIT_SAVE).performClick()
        compose.waitForIdle()
        val recorded = onDisk().drop(before)
        val renamed = assertIs<Observation.ItemRenamed>(recorded.single())
        assertEquals(MILK, renamed.item)
        assertEquals("Whole milk", renamed.name)
        compose.onNodeWithTag(Tags.entry(MILK)).assertTextContains("Whole milk", substring = true)
        compose.onNodeWithTag(Tags.entry(MILK)).assertTextContains("2 L", substring = true)
    }

    @Test
    fun `a name in use asks first, cancel records nothing, and confirming leaves one entry`() {
        launch()
        val before = onDisk()
        openEdit(BREAD)
        compose.onNodeWithTag(Tags.EDIT_NAME).performTextReplacement("milk")
        compose.onNodeWithTag(Tags.EDIT_SAVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("“Bread” and “Milk”", substring = true).assertIsDisplayed()

        compose.onNodeWithTag(Tags.JOIN_CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(before, onDisk(), "cancelling a join recorded something")
        compose.onNodeWithTag(Tags.JOIN_CONFIRM).assertDoesNotExist()
        compose.onNodeWithTag(Tags.EDIT_NAME).assertIsDisplayed()

        compose.onNodeWithTag(Tags.EDIT_SAVE).performClick()
        compose.onNodeWithTag(Tags.JOIN_CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.EDIT_NAME).assertDoesNotExist()
        assertEquals(listOf(MILK), state.entries.map { it.item }.filter { it == MILK || it == BREAD })
        compose.onNodeWithTag(Tags.entry(BREAD)).assertDoesNotExist()
        val renamed = assertIs<Observation.ItemRenamed>(onDisk().drop(before.size).single())
        assertEquals(BREAD, renamed.item)
    }

    @Test
    fun `a change of case asks nothing`() {
        launch()
        openEdit(MILK)
        compose.onNodeWithTag(Tags.EDIT_NAME).performTextReplacement("MILK")
        compose.onNodeWithTag(Tags.EDIT_SAVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.JOIN_CONFIRM).assertDoesNotExist()
        assertEquals("MILK", state.items[MILK])
    }

    // --- The library ---

    @Test
    fun `the library lists every item by name, each with its codes`() {
        launch()
        openLibrary()
        val tops = listOf(BREAD, EGGS, MILK).map { item ->
            compose.onNodeWithTag(Tags.libraryItem(item)).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops, "items are not in name order")
        compose.onNodeWithTag(Tags.LIBRARY).performScrollToNode(hasTestTag(Tags.libraryItem(SAFFRON)))
        compose.onNodeWithTag(Tags.libraryItem(SAFFRON)).assertIsDisplayed()
        compose.onNodeWithTag(Tags.LIBRARY).performScrollToNode(hasTestTag(Tags.libraryItem(BREAD)))
        holds(Tags.libraryItem(MILK), "Milk")
        holds(Tags.libraryCode(milkCode), milkCode.value)
        holds(Tags.libraryItem(MILK), milkCode.value)
        compose.onNodeWithTag(Tags.libraryCode(breadCode)).assertIsDisplayed()
        compose.onNodeWithTag(Tags.libraryItem(EGGS)).assertIsDisplayed()

        compose.onNodeWithTag(Tags.BACK).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.LIST).assertIsDisplayed()
    }

    @Test
    fun `renaming in the library renames the item`() {
        launch()
        openLibrary()
        compose.onNodeWithTag(Tags.libraryRename(MILK)).performClick()
        compose.onNodeWithTag(Tags.RENAME_NAME).performTextReplacement("Whole milk")
        compose.onNodeWithTag(Tags.RENAME_SAVE).performClick()
        compose.waitForIdle()
        assertEquals("Whole milk", state.items[MILK])
        assertEquals("Whole milk", state.entries.single { it.item == MILK }.name)
        holds(Tags.libraryItem(MILK), "Whole milk")
        assertTrue(onDisk().last() is Observation.ItemRenamed)
    }

    @Test
    fun `renaming in the library onto a name in use asks first`() {
        launch()
        openLibrary()
        val before = onDisk()
        compose.onNodeWithTag(Tags.libraryRename(BREAD)).performClick()
        compose.onNodeWithTag(Tags.RENAME_NAME).performTextReplacement("Milk")
        compose.onNodeWithTag(Tags.RENAME_SAVE).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.JOIN_CANCEL).performClick()
        compose.waitForIdle()
        assertEquals(before, onDisk(), "cancelling a join recorded something")

        compose.onNodeWithTag(Tags.RENAME_SAVE).performClick()
        compose.onNodeWithTag(Tags.JOIN_CONFIRM).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.libraryItem(BREAD)).assertDoesNotExist()
        compose.onNodeWithTag(Tags.RENAME_NAME).assertDoesNotExist()
        assertEquals(MILK, state.codes[breadCode])
        compose.onNodeWithTag(Tags.libraryCode(breadCode)).assertIsDisplayed()
    }

    @Test
    fun `unlinking a code in the library can be undone`() {
        launch()
        openLibrary()
        compose.onNodeWithTag(Tags.libraryUnlink(milkCode)).performClick()
        compose.waitForIdle()
        assertNull(state.codes[milkCode])
        compose.onNodeWithTag(Tags.libraryCode(milkCode)).assertDoesNotExist()
        assertEquals(BREAD, state.codes[breadCode])

        compose.onNodeWithText("Undo").performClick()
        compose.waitForIdle()
        assertEquals(MILK, state.codes[milkCode])
        compose.onNodeWithTag(Tags.libraryCode(milkCode)).assertIsDisplayed()
    }
}
