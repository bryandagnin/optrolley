package dev.optrolley.autoroute.catalogue

import dev.optrolley.core.Barcode

/**
 * What a product catalogue says of a code (F13, tier 4 §7.3). Each call is
 * one request, made only by [Lookups], which paces them; it carries the code,
 * or a picture's address the catalogue itself gave for one, and nothing
 * about the user (O61).
 */
interface Catalogue {
    /** The product [code] is, if the catalogue knows it. */
    suspend fun product(code: Barcode): Answer

    /** The picture at [url], which [product] gave. */
    suspend fun picture(url: String): Answer

    /** Asks nowhere: what a build or test that names no catalogue gets, so nothing can reach the network by default. */
    object Offline : Catalogue {
        override suspend fun product(code: Barcode): Answer = Answer.NoConnection
        override suspend fun picture(url: String): Answer = Answer.NoConnection
    }
}

sealed interface Answer {
    /** Found. Any part may be missing; the catalogue is filled in by its users. */
    data class Product(val name: String?, val brand: String?, val picture: String?) : Answer

    class Picture(val bytes: ByteArray) : Answer

    /** The catalogue answered and had nothing: remembered, so it is not asked again by itself. */
    data object None : Answer

    /** No answer at all: nothing is remembered, and it can be asked another day. */
    data object NoConnection : Answer

    /** The catalogue asked to be asked less (429 or 503): the queue stops. */
    data object SlowDown : Answer
}
