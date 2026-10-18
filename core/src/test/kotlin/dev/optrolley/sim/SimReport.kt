package dev.optrolley.sim

import java.util.Locale

/** Disorder curves per router and scenario, as markdown, for comparing routers. */
object SimReport {
    /** [results] maps a router id to its scores, or to why it could not be scored. */
    fun render(results: Map<String, Result<List<WalkScore>>>): String = buildString {
        val suite = ReferenceSuite
        appendLine("# Simulation report")
        appendLine()
        appendLine(
            "Reference suite (tier 5 §3): ${suite.scenarios.size} scenarios × ${suite.shops.size} shops × " +
                "${suite.seeds.size} seeds, ${suite.TRIPS} trips each; rearrangement at trip ${suite.REARRANGE_AT}.",
        )
        appendLine()
        appendLine("Per trip number n, means over the walks pooled in the table. *router*, *paper*, *floor*: mean")
        appendLine("disorder in pairs. The % columns: mean share of the walk's counted pairs. *bound*: the true")
        appendLine("layout with tier 3's unplaced entries ahead in add order — the best any router could do.")
        appendLine("O18 needs router ≤ paper at every n, and router % within ${pct(O18.MARGIN)} of floor % at n = ${O18.N}")
        appendLine("(and at n = ${O18.N} counted from the rearrangement).")
        appendLine()
        appendLine("## Baselines")
        appendLine()
        appendLine("Router-independent; the same in every router's tables below.")
        tables(suite.baselines)
        for ((id, result) in results) {
            appendLine()
            appendLine("## Router `$id`")
            appendLine()
            val scores = result.getOrElse { e ->
                appendLine("Not scored: `${e::class.simpleName}: ${e.message}`")
                null
            } ?: continue
            val failures = O18.check(scores)
            if (failures.isEmpty()) {
                appendLine("O18: **holds**.")
            } else {
                appendLine("O18: **fails**")
                failures.forEach { appendLine("- $it") }
            }
            appendLine()
            appendLine("Against the bound, not part of O18:")
            for ((what, at) in listOf(
                "n = ${O18.N}" to scores.filter { it.n == O18.N },
                "n = ${O18.N} from the rearrangement" to scores.filter { it.sinceRearrangement == O18.N },
            )) {
                if (at.isEmpty()) continue
                val shown = O18.meanNormalised(at) { it.presented }
                val bound = O18.meanNormalised(at) { it.bound }
                appendLine("- $what: router ${pct(shown)}, bound ${pct(bound)}, excess ${pct(shown - bound)}")
            }
            tables(scores)
        }
    }

    private fun StringBuilder.tables(scores: List<WalkScore>) {
        table("all scenarios", scores) { it.n }
        for (sc in ReferenceSuite.scenarios) {
            val these = scores.filter { it.scenario == sc.name }
            table(sc.name, these) { it.n }
            if (sc.noise.rearrangeAt != null) {
                table("${sc.name}, counted from the rearrangement", these.filter { it.sinceRearrangement != null }) {
                    it.sinceRearrangement!!
                }
            }
        }
    }

    private fun StringBuilder.table(title: String, scores: List<WalkScore>, n: (WalkScore) -> Int) {
        appendLine()
        appendLine("### $title")
        appendLine()
        appendLine("| n | walks | pairs | router | paper | floor | router % | paper % | floor % | bound % |")
        appendLine("|--:|--:|--:|--:|--:|--:|--:|--:|--:|--:|")
        for ((k, at) in scores.groupBy(n).toSortedMap()) {
            val w = at.size.toDouble()
            appendLine(
                listOf(
                    "$k", "${at.size}", num(at.sumOf { it.pairs } / w),
                    num(at.sumOf { it.presented } / w), num(at.sumOf { it.paper } / w), num(at.sumOf { it.floor } / w),
                    pct(O18.meanNormalised(at) { it.presented }), pct(O18.meanNormalised(at) { it.paper }),
                    pct(O18.meanNormalised(at) { it.floor }), pct(O18.meanNormalised(at) { it.bound }),
                ).joinToString(" | ", "| ", " |"),
            )
        }
    }

    private fun num(x: Double) = if (x.isNaN()) "–" else String.format(Locale.ROOT, "%.1f", x)
    private fun pct(x: Double) = if (x.isNaN()) "–" else String.format(Locale.ROOT, "%.1f%%", 100 * x)
}
