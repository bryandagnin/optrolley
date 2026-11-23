package dev.optrolley.autoroute.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.Buzzes
import dev.optrolley.autoroute.RethrowUncaught
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import kotlin.test.assertEquals

/** The two buzz options in Settings (F14): off at first, and each switched without the other. */
@RunWith(AndroidJUnit4::class)
class BuzzSettingsTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `each buzz is switched on and off by itself`() {
        val vm = AppFixture.launch(compose, tmp.root)
        compose.onNodeWithTag(Tags.OVERFLOW).performClick()
        compose.onNodeWithTag(Tags.MENU_SETTINGS).performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SETTINGS).assertIsDisplayed()

        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_ADD).assertIsOff()
        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_TICK).assertIsOff()

        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_TICK).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_TICK).assertIsOn()
        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_ADD).assertIsOff()
        assertEquals(Buzzes(onAdd = false, onTick = true), vm.buzzes.value)

        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_ADD).performScrollTo().performClick()
        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_TICK).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_ADD).assertIsOn()
        compose.onNodeWithTag(Tags.SETTING_BUZZ_ON_TICK).assertIsOff()
        assertEquals(Buzzes(onAdd = true, onTick = false), vm.buzzes.value)
    }
}
