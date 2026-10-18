package dev.optrolley.core

import dev.optrolley.core.routers.Pairwise
import dev.optrolley.core.routers.Positional
import dev.optrolley.core.routers.Precedence

/** The routing methods built in (F11), and which one a fresh install uses. */
class Routers(val list: List<Router>, val defaultId: String) {
    init {
        require(list.any { it.id == defaultId }) { "default router $defaultId is not in the list" }
    }

    fun byId(id: String): Router = list.firstOrNull { it.id == id } ?: list.first { it.id == defaultId }

    companion object {
        /**
         * Every candidate ships until real trips choose (D12). Positional is the
         * default as the simplest that passes every oracle (tier 4 §5); pairwise
         * passes too, by a hair more; precedence misses O18 after a
         * rearrangement; first-seen is the bar.
         */
        val all = Routers(listOf(Positional, Pairwise, Precedence, FirstSeen), Positional.id)
    }
}

/**
 * A router that learns nothing useful: orders items by the first trip each
 * was seen in, then by name. The baseline the candidates must beat.
 */
object FirstSeen : Router {
    override val id = "first-seen"
    override val label = "First seen"
    override fun order(trips: List<TripOrder>, items: Set<ItemKey>): List<ItemKey> {
        val first = HashMap<ItemKey, Pair<Int, Int>>()
        trips.forEachIndexed { t, trip ->
            trip.groups.forEachIndexed { g, group ->
                for (item in group) if (item in items) first.putIfAbsent(item, t to g)
            }
        }
        val never = Int.MAX_VALUE to Int.MAX_VALUE
        return items.sortedWith(
            compareBy<ItemKey>({ (first[it] ?: never).first }, { (first[it] ?: never).second }, { it.value }),
        )
    }
}
