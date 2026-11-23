package dev.optrolley.autoroute

import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.optrolley.autoroute.ui.AppFixture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/** Buzzing on adding and on ticking (F14): each its own option, off until turned on, kept on the phone and nowhere else. */
@RunWith(AndroidJUnit4::class)
class BuzzTest {
    @get:Rule(order = 0)
    val uncaught = RethrowUncaught()

    @get:Rule
    val tmp = TemporaryFolder()

    private val clock = AppFixture.Clock()
    private val felt = mutableListOf<Buzz>()
    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)

    @After
    fun stop() = scope.cancel()

    private fun options() = File(tmp.root, "options")

    private fun start(): AppViewModel {
        val store = HistoryStore(File(tmp.root, "history.jsonl"))
        if (store.isEmpty()) store.append(AppFixture.history)
        val vm = AppViewModel(store, clock::read, Dispatchers.Unconfined, Dispatchers.Unconfined, options = options())
        idle()
        scope.launch { vm.buzz.collect { felt += it } }
        return vm
    }

    private fun idle() = ShadowLooper.idleMainLooper()

    @Test
    fun `nothing buzzes until an option is turned on`() {
        val vm = start()
        assertEquals(Buzzes(onAdd = false, onTick = false), vm.buzzes.value)
        vm.add("Tea", null, null)
        vm.setTicked(AppFixture.MILK, true)
        idle()
        assertEquals(emptyList<Buzz>(), felt)
    }

    @Test
    fun `buzzing on adding buzzes for what is added and nothing else`() {
        val vm = start()
        vm.setBuzzOnAdd(true)
        vm.add("Tea", null, null)
        idle()
        vm.add("  ", null, null)
        vm.add("Tea", null, null) // already on the list
        vm.setTicked(AppFixture.MILK, true)
        idle()
        assertEquals(listOf(Buzz.ADDED), felt)
    }

    @Test
    fun `buzzing on ticking buzzes for a tick and an untick and not an add`() {
        val vm = start()
        vm.setBuzzOnTick(true)
        vm.setTicked(AppFixture.MILK, true)
        idle()
        vm.setTicked(AppFixture.MILK, false)
        idle()
        vm.add("Tea", null, null)
        idle()
        assertEquals(listOf(Buzz.TICKED, Buzz.UNTICKED), felt)
    }

    @Test
    fun `each option is kept on the phone, apart from the other`() {
        start().apply {
            setBuzzOnTick(true)
            idle()
        }
        assertEquals(Buzzes(onAdd = false, onTick = true), start().buzzes.value)
        start().apply {
            setBuzzOnAdd(true)
            setBuzzOnTick(false)
            idle()
        }
        assertEquals(Buzzes(onAdd = true, onTick = false), start().buzzes.value)
    }

    @Test
    fun `the options are not exported`() {
        val vm = start()
        vm.setBuzzOnAdd(true)
        vm.setBuzzOnTick(true)
        idle()
        var exported = ""
        vm.exportTo { exported = it }
        idle()
        assertFalse(exported.isEmpty())
        assertFalse(exported.contains("buzz", ignoreCase = true))
    }

    @Test
    fun `the options are not recorded`() {
        val vm = start()
        val before = HistoryStore(File(tmp.root, "history.jsonl")).load()
        vm.setBuzzOnAdd(true)
        vm.setBuzzOnTick(true)
        idle()
        assertEquals(before, HistoryStore(File(tmp.root, "history.jsonl")).load())
    }
}
