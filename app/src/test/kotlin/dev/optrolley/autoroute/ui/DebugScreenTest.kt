package dev.optrolley.autoroute.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.RethrowUncaught
import dev.optrolley.core.Bench
import dev.optrolley.core.Export
import dev.optrolley.core.Routers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The debug screen (tier 4 §7.2): reachable in a debug build, with histories to time. */
@RunWith(AndroidJUnit4::class)
class DebugScreenTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `the menu of a debug build opens the debug screen, and back returns to the list`() {
        AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_DEBUG).performClick()
        compose.onNodeWithTag(Tags.DEBUG_BENCH_RUN).assertIsDisplayed().assertIsEnabled()
        compose.onNodeWithTag(Tags.BACK).performClick()
        compose.onNodeWithTag(Tags.LIST).assertIsDisplayed()
    }

    @Test
    fun `the build carries histories the benchmark can time`() {
        val assets = RuntimeEnvironment.getApplication().assets
        val files = assets.list("bench").orEmpty().sorted()
        assertTrue(files.size >= 2, "found $files")
        val export = assets.open("bench/${files.first()}").use { String(it.readBytes(), Charsets.UTF_8) }
        val row = Bench.run(export, lab = true)
        assertEquals(Export.decode(export).size, row.observations)
        assertEquals(Routers.all.list.map { it.id }, row.present.keys.toList())
        assertTrue(row.trips > 0 && row.lab != null)
    }
}
