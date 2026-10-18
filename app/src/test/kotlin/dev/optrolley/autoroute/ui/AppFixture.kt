package dev.optrolley.autoroute.ui

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import dev.optrolley.autoroute.AppViewModel
import dev.optrolley.autoroute.HistoryStore
import dev.optrolley.core.ChainId
import dev.optrolley.core.EndReason
import dev.optrolley.core.ItemKey
import dev.optrolley.core.Observation
import dev.optrolley.core.Scope
import dev.optrolley.core.StoreId
import kotlinx.coroutines.Dispatchers
import java.io.File

/**
 * A history whose presentation at [TESCO] has an entry in every section:
 * milk and bread were walked in that order on an ended trip, so they are in
 * the Run; eggs were never ticked there, so Unplaced; saffron is said not to
 * be there, so Elsewhere.
 */
object AppFixture {
    val TESCO = StoreId("tesco-extra")
    val CORNER = StoreId("corner")
    val TESCO_CHAIN = ChainId("tesco")

    val MILK = ItemKey("milk")
    val BREAD = ItemKey("bread")
    val EGGS = ItemKey("eggs")
    val SAFFRON = ItemKey("saffron")
    val ALL = listOf(MILK, BREAD, EGGS, SAFFRON)

    const val START = 1_000_000L

    val history: List<Observation> = listOf(
        Observation.ChainCreated(START, TESCO_CHAIN, "Tesco"),
        Observation.StoreCreated(START + 1, TESCO, "Tesco Extra", TESCO_CHAIN),
        Observation.StoreCreated(START + 2, CORNER, "Corner shop"),
        Observation.Selected(START + 3, TESCO),
        Observation.Added(START + 4, "Milk"),
        Observation.Added(START + 5, "Bread"),
        Observation.Ticked(START + 10_000, MILK),
        Observation.Ticked(START + 30_000, BREAD),
        Observation.TripEnded(START + 40_000, EndReason.USER),
        Observation.Added(START + 50_000, "Milk", "2 L"),
        Observation.Added(START + 51_000, "Bread"),
        Observation.Added(START + 52_000, "Eggs", note = "free range"),
        Observation.Added(START + 53_000, "Saffron"),
        Observation.NotHere(START + 54_000, SAFFRON, TESCO, Scope.STORE),
    )

    /** A clock the test moves; well inside the idle timeout unless moved on. */
    class Clock(var now: Long = START + 60_000) {
        fun read() = now
    }

    /**
     * Starts the app over [history] in [dir]. Work runs unconfined, so each
     * action is recorded and presented before the click that made it returns.
     */
    fun launch(
        rule: ComposeContentTestRule,
        dir: File,
        history: List<Observation> = AppFixture.history,
        clock: Clock = Clock(),
    ): AppViewModel {
        val store = HistoryStore(File(dir, "history.jsonl"))
        store.append(history)
        val vm = AppViewModel(store, clock::read, Dispatchers.Unconfined, Dispatchers.Unconfined)
        rule.setContent { OptrolleyTheme(dark = false) { OptrolleyApp(vm) } }
        rule.waitForIdle()
        return vm
    }
}
