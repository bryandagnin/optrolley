package dev.optrolley.core

import java.util.Locale

/**
 * How long each derivation takes on one history, in milliseconds. One action
 * in the app costs a settle and a replay, then a replay, a presentation and
 * a confidence (H10). [present] and [confidence] are by router id; [lab] is
 * null where it was not timed.
 */
data class BenchRow(
    val trips: Int,
    val observations: Int,
    val decode: Double,
    val settle: Double,
    val replay: Double,
    val present: Map<String, Double>,
    val confidence: Map<String, Double>,
    val lab: Double?,
) {
    /** What one action costs with [router] chosen. */
    fun action(router: String): Double = settle + 2 * replay + present.getValue(router) + confidence.getValue(router)

    fun line(): String = "%5d %6d %7.1f %6.1f %6.1f %7s | %s".format(
        Locale.ROOT, trips, observations, decode, settle, replay, lab?.let { "%.0f".format(Locale.ROOT, it) } ?: "-",
        present.keys.joinToString(" | ") { "%6.1f %6.1f %6.1f".format(Locale.ROOT, present.getValue(it), confidence.getValue(it), action(it)) },
    )
}

/**
 * Times the derivations on a history, the same way on a desk and on a phone
 * so the two can be compared. Not used by the app proper.
 */
object Bench {
    fun header(routers: Routers = Routers.all): String = listOf(
        "ms, median; lab once. action = settle + 2 replay + present + confid.",
        "%5s %6s %7s %6s %6s %7s | %s".format("trips", "obs", "decode", "settle", "replay", "lab", routers.list.joinToString(" | ") { "%-20s".format(it.id) }),
        "%5s %6s %7s %6s %6s %7s | %s".format("", "", "", "", "", "", routers.list.joinToString(" | ") { "%6s %6s %6s".format("pres.", "conf.", "action") }),
    ).joinToString("\n")

    /**
     * [export] is a history as [Export] writes it. With [lab], every router
     * is also scored on every trip, which grows with the square of the trips.
     */
    fun run(export: String, lab: Boolean, routers: Routers = Routers.all): BenchRow {
        val history = Export.decode(export)
        val now = history.last().at + 1_000
        val present = LinkedHashMap<String, Double>()
        val confidence = LinkedHashMap<String, Double>()
        for (router in routers.list) {
            // A choice made before anything else is in force for the trip in progress.
            val chosen = listOf(Observation.RouterChosen(0, router.id)) + history
            present[router.id] = ms { present(chosen, routers) }
            confidence[router.id] = ms { confidence(chosen, routers) }
        }
        return BenchRow(
            trips = replay(history).trips.values.sumOf { it.size },
            observations = history.size,
            decode = ms { Export.decode(export) },
            settle = ms { settle(history, now) },
            replay = ms { replay(history) },
            present = present,
            confidence = confidence,
            lab = if (lab) once { score(history, routers) } else null,
        )
    }

    private fun once(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1e6
    }

    /** The median, after warming up; fewer repeats of what is slow. */
    private fun ms(block: () -> Unit): Double {
        repeat(3) { block() }
        val samples = ArrayList<Long>()
        val started = System.nanoTime()
        while (samples.size < 5 || (samples.size < 41 && System.nanoTime() - started < 1_000_000_000L)) {
            val t = System.nanoTime()
            block()
            samples += System.nanoTime() - t
        }
        return samples.sorted()[samples.size / 2] / 1e6
    }
}
