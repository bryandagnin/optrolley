package dev.optrolley.autoroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.ui.AppFixture
import dev.optrolley.core.EndReason
import dev.optrolley.core.Export
import dev.optrolley.core.Observation
import dev.optrolley.core.Rules
import dev.optrolley.core.Scope
import kotlinx.coroutines.Dispatchers
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** How actions become the record: settled first, appended, synced, replayed. */
@RunWith(AndroidJUnit4::class)
class AppViewModelTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = AppFixture.Clock()

    private fun file() = File(tmp.root, "history.jsonl")

    private fun start(history: List<Observation> = AppFixture.history): AppViewModel {
        val store = HistoryStore(file())
        store.append(history)
        return AppViewModel(store, clock::read, Dispatchers.Unconfined).also { idle() }
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    private fun onDisk() = HistoryStore(file()).load()

    @Test
    fun `an action is on disk with the clock's time when it is presented`() {
        val vm = start()
        vm.add("  Tea ", " 1 box ", "")
        idle()
        val last = onDisk().last()
        assertEquals(Observation.Added(clock.now, "Tea", "1 box", null), last)
        assertEquals(onDisk(), vm.ui.value.history)
        assertTrue(vm.ui.value.state!!.entries.any { it.name == "Tea" })
    }

    @Test
    fun `an idle trip is ended before the next action is recorded`() {
        val vm = start()
        vm.setTicked(AppFixture.MILK, true)
        idle()
        clock.now += Rules.IDLE_TIMEOUT_MS + 1
        vm.add("Tea", null, null)
        idle()
        val tail = onDisk().takeLast(2)
        assertIs<Observation.TripEnded>(tail[0])
        assertEquals(EndReason.IDLE, (tail[0] as Observation.TripEnded).reason)
        assertIs<Observation.Added>(tail[1])
    }

    @Test
    fun `resuming after the idle timeout ends the trip`() {
        val vm = start()
        vm.setTicked(AppFixture.MILK, true)
        idle()
        clock.now += Rules.IDLE_TIMEOUT_MS + 1
        vm.onResume()
        idle()
        assertIs<Observation.TripEnded>(onDisk().last())
        assertEquals(null, vm.ui.value.state!!.trip)
    }

    @Test
    fun `adding a name already on the list records nothing`() {
        val vm = start()
        val before = onDisk()
        vm.add(" MILK ", null, null)
        idle()
        assertEquals(before, onDisk())
        assertEquals(AppFixture.MILK, vm.ui.value.focus)
    }

    @Test
    fun `available after all withdraws the standing not here, chain and store alike`() {
        val history = AppFixture.history + Observation.NotHere(AppFixture.START + 55_000, AppFixture.SAFFRON, AppFixture.TESCO, Scope.CHAIN)
        val vm = start(history)
        vm.availableAfterAll(AppFixture.SAFFRON)
        idle()
        val undone = onDisk().takeLast(2).map { it as Observation.NotHereUndone }.map { it.store to it.scope }.toSet()
        assertEquals(setOf(AppFixture.TESCO to Scope.STORE, AppFixture.TESCO to Scope.CHAIN), undone)
        assertEquals(0, vm.ui.value.presentation!!.elsewhereCount)
    }

    @Test
    fun `import is refused when there is already a history`() {
        val vm = start()
        val before = onDisk()
        vm.importFrom { Export.encode(AppFixture.history) }
        idle()
        assertEquals(before, onDisk())
        assertContains(vm.ui.value.message!!.text, "fresh install")
    }

    @Test
    fun `an export imported into an empty install gives the same presentation`() {
        val original = start()
        var exported = ""
        original.exportTo { exported = it }
        idle()

        val empty = File(tmp.root, "fresh").apply { mkdirs() }
        val fresh = AppViewModel(HistoryStore(File(empty, "history.jsonl")), clock::read, Dispatchers.Unconfined)
        idle()
        fresh.importFrom { exported }
        idle()
        assertEquals(original.ui.value.history, fresh.ui.value.history)
        assertEquals(original.ui.value.presentation, fresh.ui.value.presentation)
        assertEquals(original.ui.value.history, HistoryStore(File(empty, "history.jsonl")).load())
    }

    @Test
    fun `a file that is not an export is refused without writing anything`() {
        val vm = AppViewModel(HistoryStore(file()), clock::read, Dispatchers.Unconfined)
        idle()
        vm.importFrom { "{\"hello\":1}" }
        idle()
        assertContains(vm.ui.value.message!!.text, "not an Optrolley export")
        assertTrue(HistoryStore(file()).isEmpty())
    }
}
