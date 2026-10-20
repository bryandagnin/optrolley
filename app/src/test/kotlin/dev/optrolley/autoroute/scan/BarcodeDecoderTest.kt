package dev.optrolley.autoroute.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.QRCodeWriter
import dev.optrolley.core.Barcode
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The frame-to-code path, on barcodes ZXing itself draws: no camera needed. */
class BarcodeDecoderTest {
    private class Frame(val luma: ByteArray, val width: Int, val height: Int, val rowStride: Int)

    /** [code] drawn as [format] in the middle of a grey frame, as a camera's luminance plane would hold it. */
    private fun frame(format: BarcodeFormat, code: String, width: Int = 640, height: Int = 480, padding: Int = 0): Frame {
        val matrix = MultiFormatWriter().encode(code, format, width / 2, height / 3)
        val stride = width + padding
        val luma = ByteArray(stride * height) { 0xB0.toByte() }
        val left = (width - matrix.width) / 2
        val top = (height - matrix.height) / 2
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            luma[(top + y) * stride + left + x] = if (matrix[x, y]) 0x10 else 0xF0.toByte()
        }
        return Frame(luma, width, height, stride)
    }

    private fun Frame.turned(): Frame = Frame(BarcodeDecoder.rotate(BarcodeDecoder.pack(luma, width, height, rowStride), width, height), height, width, height)

    private fun BarcodeDecoder.read(f: Frame) = decode(f.luma, f.width, f.height, f.rowStride)

    @Test
    fun `reads an EAN-13 held across the camera`() {
        assertEquals(Barcode("4006381333931"), BarcodeDecoder().read(frame(BarcodeFormat.EAN_13, "4006381333931")))
    }

    @Test
    fun `reads it held the other way round to the sensor`() {
        assertEquals(Barcode("4006381333931"), BarcodeDecoder().read(frame(BarcodeFormat.EAN_13, "4006381333931").turned()))
    }

    @Test
    fun `reads rows with padding past their width`() {
        assertEquals(Barcode("96385074"), BarcodeDecoder().read(frame(BarcodeFormat.EAN_8, "96385074", padding = 64)))
    }

    @Test
    fun `a UPC-A and its EAN-13 form read as one code`() {
        val decoder = BarcodeDecoder()
        val upc = decoder.read(frame(BarcodeFormat.UPC_A, "036000291452"))
        val ean = decoder.read(frame(BarcodeFormat.EAN_13, "0036000291452"))
        assertEquals(Barcode("0036000291452"), upc)
        assertEquals(upc, ean)
    }

    @Test
    fun `ignores a QR code`() {
        val matrix = QRCodeWriter().encode("https://example.com/4006381333931", BarcodeFormat.QR_CODE, 300, 300)
        val luma = ByteArray(640 * 480) { 0xB0.toByte() }
        for (y in 0 until matrix.height) for (x in 0 until matrix.width) {
            luma[(90 + y) * 640 + 170 + x] = if (matrix[x, y]) 0x10 else 0xF0.toByte()
        }
        assertNull(BarcodeDecoder().decode(luma, 640, 480))
    }

    @Test
    fun `finds nothing in a blank frame`() {
        assertNull(BarcodeDecoder().decode(ByteArray(640 * 480) { 0x80.toByte() }, 640, 480))
    }

    @Test
    fun `rotation is a quarter turn clockwise`() {
        // 1 2 3        4 1
        // 4 5 6   ->   5 2
        //              6 3
        val turned = BarcodeDecoder.rotate(byteArrayOf(1, 2, 3, 4, 5, 6), 3, 2)
        assertEquals(listOf<Byte>(4, 1, 5, 2, 6, 3), turned.toList())
    }

    @Test
    fun `a code held in view is taken once, and again after it has been away`() {
        val gate = ScanGate(quietMs = 1_500)
        val milk = Barcode("4006381333931")
        val tea = Barcode("96385074")
        assertTrue(gate.accept(milk, 0))
        assertFalse(gate.accept(milk, 100))
        assertFalse(gate.accept(milk, 1_500))
        assertTrue(gate.accept(tea, 1_600))
        assertFalse(gate.accept(milk, 2_900), "seen 1.4 s ago, so still in view")
        assertTrue(gate.accept(milk, 4_500))
    }
}
