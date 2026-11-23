package dev.optrolley.autoroute

import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * O23, O24 and O46, checked on the merged manifest — the one packaged, with every
 * library's contributions — not on the one in source.
 *
 * On a built APK the same check is:
 * `aapt2 dump permissions app/build/outputs/apk/debug/app-debug.apk`,
 * which must list CAMERA, INTERNET and VIBRATE and nothing else.
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

    /**
     * Was "no permission at all"; scanning (D15) brought the camera and the
     * library's look-ups (D20) the internet, and buzzes the phone's touch
     * vibration would silence (D22) the vibrator, each with the owner's agreement.
     */
    @Test
    fun `the package requests the camera, the internet and the vibrator and no other permission`() {
        val requested = listOf("uses-permission", "uses-permission-sdk-23", "uses-permission-sdk-m")
            .flatMap(::elements)
            .map { it.name() }
        assertEquals(listOf("android.permission.CAMERA", "android.permission.INTERNET", "android.permission.VIBRATE"), requested.sorted(), "from $manifest")
    }

    @Test
    fun `a phone without a camera can still install the app`() {
        val cameras = elements("uses-feature").filter { it.name().startsWith("android.hardware.camera") }
        assertTrue(cameras.isNotEmpty(), "the camera is declared as a feature, so it can be optional")
        assertTrue(cameras.all { it.getAttribute("android:required") == "false" }, "from $manifest")
    }

    /** The internet is held for look-ups only; nothing needs to know what the network is doing. */
    @Test
    fun `no permission beyond the internet reaches the network`() {
        assertTrue(elements("uses-permission").none { it.name() == "android.permission.ACCESS_NETWORK_STATE" }, "from $manifest")
    }

    @Test
    fun `app data is not copied off the device by backup`() {
        assertEquals("false", elements("application").single().getAttribute("android:allowBackup"))
    }
}
