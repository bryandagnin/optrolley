package dev.optrolley.core

import dev.optrolley.sim.Noise
import dev.optrolley.sim.ShopSpec
import dev.optrolley.sim.Shopper
import java.io.File

/** Weekly shops: a few months, one year, then up to twenty. */
internal val BENCH_TRIPS = listOf(20, 52, 130, 260, 520, 1040)

/** Above this the lab takes too long on a phone to be worth waiting for. */
internal const val BENCH_LAB_UP_TO = 260

/**
 * A synthetic history to time against (H10): a weekly supermarket shop for
 * [trips] weeks with every kind of noise, then a list written and a trip
 * begun, as the app is mostly found.
 */
internal fun benchHistory(trips: Int): List<Observation> {
    val spec = ShopSpec("supermarket", zones = 12, items = 96, staples = 18)
    val run = Shopper.simulate(spec, Noise(batch = 0.1, late = 0.1, reverse = 0.25, absent = 3), seed = 7, trips = trips)
    val at = run.history.last().at + 7 * 24 * 3_600_000L
    val names = run.shop.names.values.take(40)
    return buildList {
        addAll(run.history)
        names.forEachIndexed { i, name -> add(Observation.Added(at + i, name)) }
        names.take(5).forEachIndexed { i, name -> add(Observation.Ticked(at + 60_000L * (i + 1), ItemKey.of(name))) }
    }
}

/** Writes each history as an export into the directory given, for the app's debug screen to time on a phone. */
fun main(args: Array<String>) {
    val dir = File(args[0]).apply { mkdirs() }
    for (trips in BENCH_TRIPS) {
        val lab = if (trips <= BENCH_LAB_UP_TO) "-lab" else ""
        File(dir, "weekly-%04d%s.json".format(trips, lab)).writeText(Export.encode(benchHistory(trips)))
    }
}
