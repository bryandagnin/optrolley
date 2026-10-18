package dev.optrolley.core

/** Values frozen in tier 3. */
object Rules {
    /** Ticks closer than this to the previous one share its tie group. */
    const val TIE_WINDOW_MS: Long = 5_000

    /** A trip with no observation for this long has ended. */
    const val IDLE_TIMEOUT_MS: Long = 2 * 60 * 60 * 1_000
}
