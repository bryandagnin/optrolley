package dev.optrolley.core

import dev.optrolley.core.Observation.Added
import dev.optrolley.core.Observation.ChainCreated
import dev.optrolley.core.Observation.ChainRenamed
import dev.optrolley.core.Observation.Edited
import dev.optrolley.core.Observation.NotHere
import dev.optrolley.core.Observation.NotHereUndone
import dev.optrolley.core.Observation.Removed
import dev.optrolley.core.Observation.RouterChosen
import dev.optrolley.core.Observation.Selected
import dev.optrolley.core.Observation.StoreChainSet
import dev.optrolley.core.Observation.StoreCreated
import dev.optrolley.core.Observation.StoreRenamed
import dev.optrolley.core.Observation.Ticked
import dev.optrolley.core.Observation.TripEnded
import dev.optrolley.core.Observation.Unticked
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ExportTest {
    private val every: List<Observation> = listOf(
        ChainCreated(1, CHAIN, "Chain \"one\""),
        ChainRenamed(2, CHAIN, "Chäin ✓"),
        StoreCreated(3, A, "A", CHAIN),
        StoreCreated(4, B, "B"),
        StoreRenamed(5, B, "B2"),
        StoreChainSet(6, B, null),
        Selected(7, A),
        Added(8, " Milk "),
        Added(9, "eggs", "6", "free\nrange"),
        Edited(10, key("eggs"), "Eggs", null, "large"),
        Ticked(11, key("milk")),
        Unticked(12, key("milk")),
        Ticked(Long.MAX_VALUE, key("eggs")),
        NotHere(14, key("milk"), A, Scope.CHAIN),
        NotHereUndone(15, key("milk"), A, Scope.STORE),
        TripEnded(16, EndReason.IDLE),
        TripEnded(17, EndReason.USER),
        RouterChosen(18, "first-seen"),
        Removed(19, key("milk")),
        Selected(-20, null),
    )

    @Test
    fun roundTripsExactly() {
        val text = Export.encode(every)
        assertEquals(every, Export.decode(text))
        assertEquals(text, Export.encode(Export.decode(text)))
        assertEquals(emptyList(), Export.decode(Export.encode(emptyList())))
    }

    @Test
    fun importRebuildsTheSamePresentation() {
        val h = Hist().chain(CHAIN).store(A, CHAIN).trip(A, "a", "b", "c").select(A).add("c", "x", "a").notHere("x", A)
        val back = Export.decode(Export.encode(h.list))
        assertEquals(present(h.list), present(back))
        assertEquals(replay(h.list), replay(back))
    }

    @Test
    fun theFileSaysWhatItIs() {
        val root = Json.parseToJsonElement(Export.encode(every.take(8))).jsonObject
        assertEquals("optrolley-history", root.getValue("format").jsonPrimitive.content)
        assertEquals(1, root.getValue("version").jsonPrimitive.content.toInt())
        val first = root.getValue("history").jsonArray[0].jsonObject
        assertEquals("chain-created", first.getValue("type").jsonPrimitive.content)
        val added = root.getValue("history").jsonArray[7].jsonObject
        assertEquals(setOf("type", "at", "name", "quantity", "note"), added.keys)
    }

    @Test
    fun rejectsWhatIsNotAnExport() {
        val good = Export.encode(every)
        val bad = listOf(
            "",
            "not json",
            "[]",
            "{}",
            "\"optrolley-history\"",
            good.replace("\"optrolley-history\"", "\"something-else\""),
            good.replace("\"version\":1", "\"version\":2"),
            good.replace("\"version\":1", "\"version\":\"1\""),
            good.replace("\"version\":1", "\"version\":1.5"),
            """{"format":"optrolley-history","version":1}""",
            """{"format":"optrolley-history","version":1,"history":{}}""",
            """{"format":"optrolley-history","version":1,"history":[{"type":"nonsense","at":1}]}""",
            """{"format":"optrolley-history","version":1,"history":[{"type":"ticked","at":1}]}""",
            """{"format":"optrolley-history","version":1,"history":[{"type":"ticked","at":"x","item":"a"}]}""",
            """{"format":"optrolley-history","version":1,"history":[],"extra":0}""",
            good.dropLast(1),
        )
        for (text in bad) assertFailsWith<IllegalArgumentException>(text) { Export.decode(text) }
    }
}
