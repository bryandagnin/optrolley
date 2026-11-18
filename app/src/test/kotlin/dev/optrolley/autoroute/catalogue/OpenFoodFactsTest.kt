package dev.optrolley.autoroute.catalogue

import dev.optrolley.core.Barcode
import kotlinx.coroutines.runBlocking
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The real catalogue client, with its connections stood in for, so no test
 * reaches the network: what each request carries (O61), and what each kind
 * of reply is taken to mean (tier 4 §7.3). The replies are as the catalogue
 * sent them on 2026-11-17.
 */
class OpenFoodFactsTest {
    private class Connection(
        url: URL,
        private val status: Int = HTTP_OK,
        private val body: String = "",
        private val fails: IOException? = null,
    ) : HttpURLConnection(url) {
        val headers = mutableMapOf<String, String>()
        var disconnected = false

        override fun setRequestProperty(key: String, value: String) {
            headers[key] = value
            super.setRequestProperty(key, value)
        }

        override fun addRequestProperty(key: String, value: String) {
            headers[key] = value
            super.addRequestProperty(key, value)
        }

        override fun getResponseCode(): Int {
            fails?.let { throw it }
            return status
        }

        override fun getInputStream(): InputStream = ByteArrayInputStream(body.toByteArray())
        override fun connect() {}
        override fun disconnect() {
            disconnected = true
        }

        override fun usingProxy() = false
    }

    private val opened = mutableListOf<Connection>()

    private fun client(status: Int = 200, body: String = "", fails: IOException? = null, contact: String = "someone@example.org") =
        OpenFoodFacts(OpenFoodFacts.agent("0.1.0", contact)) { url -> Connection(url, status, body, fails).also { opened += it } }

    private val yoghurt = Barcode("9414967698780")
    private val picture = "https://images.openfoodfacts.org/images/products/941/496/769/8780/front_en.39.200.jpg"
    private val found = """{"code":"9414967698780","product":{"brands":"Anchor","code":"9414967698780","image_front_small_url":"$picture","product_name":"Greek Style Yoghurt with Caramel"},"status":1,"status_verbose":"product found"}"""
    private val missing = """{"code":"9310441001025","status":0,"status_verbose":"product not found"}"""

    @Test
    fun `O61 a look-up asks for one product by its number, at the one host, saying only who built the app`() {
        runBlocking { client(body = found).product(yoghurt) }
        val request = opened.single()
        // Compared as text: URL.equals resolves host names, which is a network request.
        assertEquals(
            "https://world.openfoodfacts.org/api/v2/product/9414967698780?fields=product_name,brands,image_front_small_url",
            request.url.toString(),
        )
        assertEquals("GET", request.requestMethod)
        assertFalse(request.doOutput, "a look-up sends a body")
        assertEquals(mapOf("User-Agent" to "Optrolley/0.1.0 (someone@example.org)"), request.headers)
        assertFalse(request.useCaches)
        assertFalse(request.instanceFollowRedirects, "a redirect could take the request to another host")
        assertTrue(request.disconnected)
    }

    @Test
    fun `a build with no contact names none`() {
        assertEquals("Optrolley/0.1.0", OpenFoodFacts.agent("0.1.0", ""))
        assertEquals("Optrolley/0.1.0", OpenFoodFacts.agent("0.1.0", "  "))
        runBlocking { client(body = found, contact = "").product(yoghurt) }
        assertEquals(mapOf("User-Agent" to "Optrolley/0.1.0"), opened.single().headers)
    }

    @Test
    fun `O61 a code that is not a number is never sent`() {
        for (code in listOf("", "12a4", "1234?x=1", "../1234", "1234 5")) {
            assertEquals(Answer.None, runBlocking { client(body = found).product(Barcode(code)) })
        }
        assertEquals(emptyList(), opened)
    }

    @Test
    fun `a found product gives its name, brand and small picture`() {
        val answer = assertIs<Answer.Product>(runBlocking { client(body = found).product(yoghurt) })
        assertEquals(Answer.Product("Greek Style Yoghurt with Caramel", "Anchor", picture), answer)
    }

    @Test
    fun `a product with parts missing gives what it has`() {
        val body = """{"product":{"product_name":"  ","brands":"Anchor"},"status":1}"""
        assertEquals(Answer.Product(null, "Anchor", null), runBlocking { client(body = body).product(yoghurt) })
    }

    @Test
    fun `anything but a found product is no answer`() {
        val replies = listOf(
            404 to missing,
            200 to missing,
            200 to """{"status":1}""",
            200 to "not json",
            200 to """{"status":1,"product":{"product_name":{"en":"x"}}}""",
            400 to "",
            500 to "",
            301 to "",
        )
        for ((status, body) in replies) {
            assertEquals(Answer.None, runBlocking { client(status, body).product(yoghurt) }, "$status $body")
        }
    }

    @Test
    fun `429 and 503 mean slow down`() {
        assertEquals(Answer.SlowDown, runBlocking { client(429).product(yoghurt) })
        assertEquals(Answer.SlowDown, runBlocking { client(503).product(yoghurt) })
        assertEquals(Answer.SlowDown, runBlocking { client(429).picture(picture) })
    }

    @Test
    fun `no connection, a timeout or no such host are no connection`() {
        for (failure in listOf(IOException("refused"), SocketTimeoutException(), UnknownHostException("world.openfoodfacts.org"))) {
            assertEquals(Answer.NoConnection, runBlocking { client(fails = failure).product(yoghurt) }, "$failure")
        }
        val unopenable = OpenFoodFacts("Optrolley/0.1.0") { throw IOException("no network") }
        assertEquals(Answer.NoConnection, runBlocking { unopenable.product(yoghurt) })
    }

    @Test
    fun `a picture is fetched only from the catalogue's picture host, over HTTPS`() {
        val got = assertIs<Answer.Picture>(runBlocking { client(body = "jpeg").picture(picture) })
        assertContentEquals("jpeg".toByteArray(), got.bytes)
        assertEquals(picture, opened.single().url.toString())
        assertEquals(mapOf("User-Agent" to "Optrolley/0.1.0 (someone@example.org)"), opened.single().headers)

        for (elsewhere in listOf(
            "http://images.openfoodfacts.org/images/products/1/front.jpg",
            "https://example.org/front.jpg",
            "https://images.openfoodfacts.org.example.org/front.jpg",
            "not a url",
        )) {
            assertEquals(Answer.None, runBlocking { client(body = "jpeg").picture(elsewhere) }, elsewhere)
        }
        assertEquals(1, opened.size, "a picture was fetched from somewhere else")
    }

    @Test
    fun `a reply far larger than one product's is not taken`() {
        val huge = "x".repeat(600 * 1024)
        assertEquals(Answer.None, runBlocking { client(body = huge).picture(picture) })
    }
}
