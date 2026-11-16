package dev.optrolley.core

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/** The state kept between runs (tier 4 §7): put away and taken up again, nothing is different. */
class StoredTest {
    private val json = Json

    @Test
    fun `a fold taken up from what was stored goes on as the one that never stopped`() {
        repeat(60) { seed ->
            val rnd = Random(seed)
            val actions = ItemsTest().randomActions(rnd, 90)
            val cut = rnd.nextInt(actions.size + 1)

            val whole = Fold()
            val first = Fold()
            val record = ArrayList<TripRecord>()
            actions.forEach(whole::apply)
            actions.subList(0, cut).forEach { a -> first.apply(a)?.let { record += it } }

            // Through text, as the files hold them.
            val stored = json.decodeFromString(Stored.serializer(), json.encodeToString(Stored.serializer(), first.stored()))
            val trips = json.decodeFromString(ListSerializer(TripRecord.serializer()), json.encodeToString(ListSerializer(TripRecord.serializer()), record))
            val resumed = Fold(stored, trips)
            assertEquals(first.state(), resumed.state(), "seed $seed, taken up after $cut actions")
            assertEquals(first.stored(), resumed.stored(), "seed $seed, stored again after $cut actions")

            actions.subList(cut, actions.size).forEach { a -> resumed.apply(a)?.let { record += it } }
            assertEquals(whole.state(), resumed.state(), "seed $seed, cut at $cut")
            assertEquals(present(whole), present(resumed), "seed $seed, cut at $cut")
            assertEquals(confidence(whole), confidence(resumed), "seed $seed, cut at $cut")
            assertEquals(score(actions), score(resumed), "seed $seed, cut at $cut")
            assertEquals(whole.stored().trips, record.size, "seed $seed: every trip ended was handed to the record once")
        }
    }

    @Test
    fun `the record alone gives back every store's trips`() {
        val actions = ItemsTest().randomActions(Random(7), 200)
        val fold = Fold()
        val record = actions.mapNotNull(fold::apply)
        val again = Fold(fold.stored(), record)
        assertEquals(fold.state().trips, again.state().trips)
        assertEquals(record.map { it.endedAt }, record.map { it.endedAt }.sorted(), "trips are on record in the order they ended")
    }
}
