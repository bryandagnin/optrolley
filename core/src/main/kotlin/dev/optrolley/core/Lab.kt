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
fun score(history: List<Observation>, routers: Routers = Routers.all): Map<String, List<TripScore>> = TODO()
