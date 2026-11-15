package dev.optrolley.core

import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.io.File
import kotlin.test.Test

/**
 * How long each thing the app does per action takes as the history grows
 * (H10). A measurement, not a test: it asserts nothing, runs only when
 * asked (`OPTROLLEY_TIMING=1`), and writes `build/timing.txt`. Times are
 * this machine's; the app's debug screen takes the same on a phone.
 */
class ScaleTiming {
    @Test
    @EnabledIfEnvironmentVariable(named = "OPTROLLEY_TIMING", matches = "1")
    fun `time each derivation against the length of the history`() {
        val out = StringBuilder().appendLine(Bench.header())
        for (trips in BENCH_TRIPS) {
            out.appendLine(Bench.run(Export.encode(benchHistory(trips)), lab = trips <= BENCH_LAB_UP_TO).line())
        }
        File("build").mkdirs()
        File("build/timing.txt").writeText(out.toString())
    }
}
