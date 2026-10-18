package dev.optrolley.autoroute

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O23 and O24, checked on the merged manifest — the one packaged, with every
 * library's contributions — not on the one in source.
 *
 * On a built APK the same check is:
 * `aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk`,
 * which must list no uses-permission.
 */
class ManifestTest {
    /** Passed in by app/build.gradle.kts; not the unit-test manifest, which test libraries add to. */
    private val manifest: File by lazy {
        val path = System.getProperty("optrolley.mergedManifest")
            ?: error("optrolley.mergedManifest is not set; run this through Gradle")
        File(path).also { check(it.isFile) { "No merged manifest at $it" } }
    }

    private fun elements(tag: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(manifest)
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun Element.name() = getAttribute("android:name")

    @Test
    fun `the package requests no permission at all`() {
        val requested = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
            .flatMap(::elements)
            .map { it.name() }
        assertEquals(emptyList(), requested, "from $manifest")
    }

    @Test
    fun `nothing can reach the network`() {
        val network = setOf("android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE")
        assertTrue(elements("uses-permission").none { it.name() in network })
    }

    @Test
    fun `app data is not copied off the device by backup`() {
        assertEquals("false", elements("application").single().getAttribute("android:allowBackup"))
    }
}
