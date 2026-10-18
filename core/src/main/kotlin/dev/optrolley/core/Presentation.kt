package dev.optrolley.core

enum class SectionKind {
    /** The whole list in add order, when no store is selected. */
    LIST,
    UNPLACED,
    RUN,
    ELSEWHERE,
}

data class Section(val kind: SectionKind, val entries: List<Entry>)

/** What the user sees: non-empty sections, in tier 2 §6 order. */
data class Presentation(val store: StoreId?, val sections: List<Section>) {
    val elsewhereCount: Int
        get() = sections.firstOrNull { it.kind == SectionKind.ELSEWHERE }?.entries?.size ?: 0
}

/**
 * The presentation of [history] — a pure function of it (O19), using
 * [routers] to look up the method the history has chosen.
 */
fun present(history: List<Observation>, routers: Routers = Routers.all): Presentation = TODO()
