package dev.optrolley.core

/**
 * The export file (F9) is the history and nothing else: the list, items and
 * stores are all derived from it, so it is enough to rebuild them (O22).
 */
object Export {
    const val FORMAT = "optrolley-history"
    const val VERSION = 1

    fun encode(history: List<Observation>): String = TODO()

    /** Throws [IllegalArgumentException] for anything that is not a valid export. */
    fun decode(text: String): List<Observation> = TODO()
}
