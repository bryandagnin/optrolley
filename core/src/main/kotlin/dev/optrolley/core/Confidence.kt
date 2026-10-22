package dev.optrolley.core

/** How well a Run entry's place is borne out by recent walks (D16, tier 3 §I). */
enum class Confidence { TENTATIVE, SETTLING, SETTLED }

/** Values frozen in tier 3 §I. */
internal object ConfidenceRules {
    /** Witnesses averaged: K, the trips a rearrangement takes to absorb (O17). */
    const val WITNESSES = 3

    /** Mean agreement at or above which three witnesses settle a place, as a fraction. */
    const val SETTLED_NUM = 4L
    const val SETTLED_DEN = 5L

    /** Mean agreement below which a place is tentative, as a fraction. */
    const val TENTATIVE_NUM = 3L
    const val TENTATIVE_DEN = 5L
}

/**
 * The confidence of each entry in the Run at the selected store, by item;
 * empty with no store selected. A pure function of the history (O53), and of
 * the presented run rather than anything a router keeps to itself, so one
 * rule for every router (O52).
 */
fun confidence(history: List<Observation>, routers: Routers = Routers.all): Map<ItemKey, Confidence> {
    val presentation = present(history, routers)
    val store = presentation.store ?: return emptyMap()
    val run = presentation.sections.firstOrNull { it.kind == SectionKind.RUN }?.entries ?: return emptyMap()
    val rank = HashMap<ItemKey, Int>()
    run.forEach { rank[it.item] = rank.size }
    // Ended trips only, so it holds still for a trip, as the run does (O53).
    val trips = Fold(history).state().trips[store].orEmpty()

    val witnesses = HashMap<ItemKey, MutableList<Agreement>>()
    for (trip in trips.asReversed()) {
        val groups = trip.groups.map { g -> g.filter { it in rank } }.filter { it.isNotEmpty() }
        if (groups.size < 2) continue
        var total = 0
        var against = 0
        forEachPair(groups) { a, b ->
            total++
            if (rank.getValue(a) > rank.getValue(b)) against++
        }
        // Walked from the other door: read in mirror, as learning reads it (H6).
        val mirrored = 2 * against > total
        val agreed = HashMap<ItemKey, Int>()
        val counted = HashMap<ItemKey, Int>()
        forEachPair(groups) { a, b ->
            val ok = (rank.getValue(a) < rank.getValue(b)) != mirrored
            for (x in listOf(a, b)) {
                counted.merge(x, 1, Int::plus)
                if (ok) agreed.merge(x, 1, Int::plus)
            }
        }
        for ((item, n) in counted) {
            val last = witnesses.getOrPut(item, ::ArrayList)
            if (last.size < ConfidenceRules.WITNESSES) last += Agreement(agreed[item] ?: 0, n)
        }
    }
    return run.associate { it.item to level(witnesses[it.item].orEmpty()) }
}

/** [agreed] of [counted] pairs ticked in the presented order. */
private data class Agreement(val agreed: Int, val counted: Int)

/** Calls [each] for every pair ticked in different tie groups, earlier group first. */
private inline fun forEachPair(groups: List<List<ItemKey>>, each: (ItemKey, ItemKey) -> Unit) {
    for (i in groups.indices) for (j in i + 1 until groups.size) for (a in groups[i]) for (b in groups[j]) each(a, b)
}

private fun level(witnesses: List<Agreement>): Confidence {
    if (witnesses.size <= 1) return Confidence.TENTATIVE
    // Compared exactly: mean ≥ num/den ⇔ Σ agreed·(D/counted) · den ≥ num · n · D, D = Π counted.
    val d = witnesses.fold(1L) { acc, w -> acc * w.counted }
    val sum = witnesses.sumOf { it.agreed.toLong() * (d / it.counted) }
    fun atLeast(num: Long, den: Long) = sum * den >= num * witnesses.size * d
    return when {
        witnesses.size == ConfidenceRules.WITNESSES && atLeast(ConfidenceRules.SETTLED_NUM, ConfidenceRules.SETTLED_DEN) -> Confidence.SETTLED
        !atLeast(ConfidenceRules.TENTATIVE_NUM, ConfidenceRules.TENTATIVE_DEN) -> Confidence.TENTATIVE
        else -> Confidence.SETTLING
    }
}
