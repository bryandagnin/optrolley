package dev.optrolley.autoroute.catalogue

import dev.optrolley.core.Barcode
import kotlinx.coroutines.channels.Channel

/**
 * Stands in for the network: records every request, when it began and how
 * many were under way at once, and answers with [reply]. With [held], each
 * request waits until [release] lets one through, so a test can look at the
 * queue while a request is under way; [never] makes none ever return.
 */
class StandInCatalogue(
    private val clock: () -> Long,
    var reply: (Request) -> Answer = { Answer.None },
) : Catalogue {
    sealed interface Request {
        data class Product(val code: Barcode) : Request
        data class Picture(val url: String) : Request
    }

    val requests = mutableListOf<Request>()
    val starts = mutableListOf<Long>()
    var underWay = 0
        private set
    var mostAtOnce = 0
        private set

    var held = false
    var never = false
    private val gate = Channel<Unit>(Channel.UNLIMITED)

    fun release() {
        gate.trySend(Unit)
    }

    override suspend fun product(code: Barcode): Answer = request(Request.Product(code))

    override suspend fun picture(url: String): Answer = request(Request.Picture(url))

    private suspend fun request(request: Request): Answer {
        requests += request
        starts += clock()
        underWay++
        mostAtOnce = maxOf(mostAtOnce, underWay)
        try {
            if (never) kotlinx.coroutines.awaitCancellation()
            if (held) gate.receive()
            return reply(request)
        } finally {
            underWay--
        }
    }

    companion object {
        fun pictureOf(code: Barcode) = "https://images.openfoodfacts.org/images/products/${code.value}/front_en.200.jpg"

        /** Knows every code, as "Product <code>" by "Brand", with a picture. */
        val knowsAll: (Request) -> Answer = { request ->
            when (request) {
                is Request.Product -> Answer.Product("Product ${request.code.value}", "Brand", pictureOf(request.code))
                is Request.Picture -> Answer.Picture(byteArrayOf(1, 2, 3))
            }
        }
    }
}
