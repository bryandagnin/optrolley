package dev.optrolley.autoroute.ui

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.RethrowUncaught
import dev.optrolley.autoroute.ui.AppFixture.BREAD
import dev.optrolley.autoroute.ui.AppFixture.EGGS
import dev.optrolley.autoroute.ui.AppFixture.MILK
import dev.optrolley.autoroute.ui.AppFixture.SAFFRON
import dev.optrolley.core.ItemKey
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/** Confidence on screen (F5, D16): on Run rows only, and not by colour alone. */
@RunWith(AndroidJUnit4::class)
class ConfidenceDisplayTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @get:Rule
    val tmp = TemporaryFolder()

    private fun row(item: ItemKey) = compose.onNodeWithTag(Tags.LIST).performScrollToNode(hasTestTag(Tags.entry(item))).let {
        compose.onNodeWithTag(Tags.entry(item))
    }

    @Test
    fun `Run rows say how sure their place is, and no other row does`() {
        AppFixture.launch(compose, tmp.root)
        // Milk and bread were walked once at Tesco, so each has one witness.
        row(MILK).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Place tentative"))
        row(BREAD).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Place tentative"))
        row(EGGS).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
        row(SAFFRON).assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }
}
