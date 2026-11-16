package dev.optrolley.core

/**
 * How one router would have done on one ended trip, trained only on the trips
 * before it (F11).
 *
 * [pairs] counts pairs of items in different tie groups of the trip, both
 * placed beforehand; [disorder] counts those the router would have presented
 * in the opposite order to the walk.
 */
data class TripScore(val store: StoreId, val tripIndex: Int, val pairs: Int, val disorder: Int)

/** Scores every router in [routers] against every ended trip in [history]. */
fun score(history: List<Observation>, routers: Routers = Routers.all): Map<String, List<TripScore>> = score(Fold(history), routers)

internal fun score(fold: Fold, routers: Routers = Routers.all): Map<String, List<TripScore>> {
    val trips = fold.state().trips
    val result = LinkedHashMap<String, List<TripScore>>()
    for (router in routers.list) {
        // Trips in the order they ended, across stores, as the lab lists them.
        result[router.id] = fold.ended.map { (store, k) ->
            val before = trips.getValue(store).subList(0, k)
            scoreTrip(router, before, trips.getValue(store)[k], store, k)
        }
    }
    return result
}

private fun scoreTrip(router: Router, before: List<TripOrder>, walked: TripOrder, store: StoreId, k: Int): TripScore {
    val placed = placedBy(before)
    // Presented as the presentation would: the router orders everything
    // placed, and whatever it fails to order goes last, by name.
    val order = safeOrder(router, before, placed)
    val rank = HashMap<ItemKey, Int>()
    for (item in order) rank[item] = rank.size
    for (item in placed.sortedBy { it.value }) if (item !in rank) rank[item] = rank.size
    val groups = walked.groups.map { g -> g.filter { it in placed }.map { rank.getValue(it) } }
    var pairs = 0
    var disorder = 0
    for (i in groups.indices) {
        for (j in i + 1 until groups.size) {
            for (a in groups[i]) {
                for (b in groups[j]) {
                    pairs++
                    if (a > b) disorder++
                }
            }
        }
    }
    return TripScore(store, k, pairs, disorder)
}
