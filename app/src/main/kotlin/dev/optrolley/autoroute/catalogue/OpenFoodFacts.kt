package dev.optrolley.autoroute.catalogue

import dev.optrolley.core.Barcode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open Food Facts, asked for one product at a time with the platform's own
 * HTTP client, so no library brings permissions or traffic of its own
 * (tier 4 §7.3). [agent] is the only header set: the catalogue asks every
 * client to say who it is, and that names the build, not the user.
 */
class OpenFoodFacts(
    private val agent: String,
    private val open: (URL) -> HttpURLConnection = { it.openConnection() as HttpURLConnection },
) : Catalogue {

    override suspend fun product(code: Barcode): Answer {
        // Only a number goes into the address, so nothing else can ride along in it (O61).
        if (code.value.isEmpty() || !code.value.all { it in '0'..'9' }) return Answer.None
        return get(productUrl(code), JSON_LIMIT) { body -> parse(body) }
    }

    override suspend fun picture(url: String): Answer {
        // Only to the catalogue's own picture host: an address it gave is still not a reason to call anywhere else.
        val address = try {
            URL(url)
        } catch (e: IOException) {
            return Answer.None
        }
        if (address.protocol != "https" || address.host != PICTURES) return Answer.None
        return get(address, PICTURE_LIMIT) { Answer.Picture(it) }
    }

    private suspend fun get(url: URL, limit: Int, found: (ByteArray) -> Answer): Answer = withContext(Dispatchers.IO) {
        val connection = try {
            open(url)
        } catch (e: IOException) {
            return@withContext Answer.NoConnection
        }
        try {
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.useCaches = false
            // A redirect could lead off the one host this is allowed to ask.
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", agent)
            when (connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.use { it.readUpTo(limit) }?.let(found) ?: Answer.None
                TOO_MANY_REQUESTS, HttpURLConnection.HTTP_UNAVAILABLE -> Answer.SlowDown
                else -> Answer.None
            }
        } catch (e: IOException) {
            Answer.NoConnection
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        /** The one host asked about products. */
        const val HOST = "world.openfoodfacts.org"

        /** Where its pictures are served from. */
        const val PICTURES = "images.openfoodfacts.org"

        private const val TOO_MANY_REQUESTS = 429
        private const val TIMEOUT_MS = 15_000
        private const val JSON_LIMIT = 256 * 1024
        private const val PICTURE_LIMIT = 512 * 1024

        fun productUrl(code: Barcode) = URL("https://$HOST/api/v2/product/${code.value}?fields=product_name,brands,image_front_small_url")

        /** The catalogue asks for `AppName/Version (contact)`; a build with no contact sends none. */
        fun agent(version: String, contact: String): String =
            if (contact.isBlank()) "Optrolley/$version" else "Optrolley/$version (${contact.trim()})"

        /** Anything but a found product is no answer: a miss is not documented, and one was seen as a 404. */
        internal fun parse(body: ByteArray): Answer = try {
            val json = Json.parseToJsonElement(body.decodeToString()).jsonObject
            val product = json["product"] as? JsonObject
            if (json["status"]?.jsonPrimitive?.intOrNull != 1 || product == null) {
                Answer.None
            } else {
                fun text(key: String) = (product[key]?.jsonPrimitive?.contentOrNull)?.trim()?.takeIf { it.isNotEmpty() }
                Answer.Product(text("product_name"), text("brands"), text("image_front_small_url"))
            }
        } catch (e: IllegalArgumentException) {
            // Not JSON, or not shaped as documented (SerializationException is one).
            Answer.None
        }

        /** The body, or null if it is longer than anything the catalogue should send for one product. */
        private fun InputStream.readUpTo(limit: Int): ByteArray? {
            // Not readNBytes: Android has it only from API 33.
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = read(buffer)
                if (n < 0) return out.toByteArray()
                out.write(buffer, 0, n)
                if (out.size() > limit) return null
            }
        }
    }
}
