package dev.optrolley.core

import dev.optrolley.sim.Noise
import dev.optrolley.sim.ShopSpec
import dev.optrolley.sim.Shopper
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import java.util.Locale
import kotlin.test.Test

/**
 * How long each thing the app does per action takes as the history grows
 * (H12). A measurement, not a test: it asserts nothing, runs only when
 * asked (`OPTROLLEY_TIMING=1`), and writes `build/timing.txt`. Times are
 * this machine's; a phone is several times slower, but the shape is the same.
 */
class ScaleTiming {
    private val spec = ShopSpec("supermarket", zones = 12, items = 96, staples = 18)

    /** A weekly shop for [trips] weeks, then a list written and a trip begun, as the app is mostly found. */
    private fun history(trips: Int, router: String?): List<Observation> {
        val run = Shopper.simulate(spec, Noise(batch = 0.1, late = 0.1, reverse = 0.25, absent = 3), seed = 7, trips = trips)
        val at = run.history.last().at + 7 * 24 * 3_600_000L
        val names = run.shop.names.values.take(40)
        return buildList {
            if (router != null) add(Observation.RouterChosen(0, router))
            addAll(run.history)
            names.forEachIndexed { i, name -> add(Observation.Added(at + i, name)) }
            names.take(5).forEachIndexed { i, name -> add(Observation.Ticked(at + 60_000L * (i + 1), ItemKey.of(name))) }
        }
    }

    /** Median milliseconds, after warming up; fewer repeats of what is slow. */
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

    @Test
    @EnabledIfEnvironmentVariable(named = "OPTROLLEY_TIMING", matches = "1")
    fun `time each derivation against the length of the history`() {
        val out = StringBuilder()
        fun line(text: String) {
            out.appendLine(text)
            println(text)
        }
        val routers = Routers.all.list.map { it.id }
        line("Milliseconds, median. One action in the app is settle + replay, then replay + present + confidence.")
        line("")
        line("The lab scores every router on every trip, on request only; it is timed once, not as a median.")
        line("")
        line("%6s %7s %8s %8s %8s %9s | %s".format("trips", "obs", "settle", "replay", "decode", "lab", routers.joinToString(" | ") { "%-18s".format(it) }))
        line("%6s %7s %8s %8s %8s %9s | %s".format("", "", "", "", "", "", routers.joinToString(" | ") { "%8s %9s".format("present", "confid.") }))
        for (trips in listOf(20, 52, 130, 260, 520, 1040)) {
            val plain = history(trips, null)
            val now = plain.last().at + 1_000
            val text = Export.encode(plain)
            val cells = routers.map { id ->
                val h = history(trips, id)
                "%8.2f %9.2f".format(Locale.ROOT, ms { present(h) }, ms { confidence(h) })
            }
            val started = System.nanoTime()
            score(plain)
            val lab = (System.nanoTime() - started) / 1e6
            line(
                "%6d %7d %8.2f %8.2f %8.2f %9.0f | %s".format(
                    Locale.ROOT, trips, plain.size, ms { settle(plain, now) }, ms { replay(plain) }, ms { Export.decode(text) }, lab,
                    cells.joinToString(" | "),
                ),
            )
        }
        File("build").mkdirs()
        File("build/timing.txt").writeText(out.toString())
    }
}
