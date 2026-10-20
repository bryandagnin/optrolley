package dev.optrolley.autoroute.scan

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.NotFoundException
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import dev.optrolley.core.Barcode

/**
 * Reads a retail barcode from one camera frame's luminance plane, on the
 * device (tier 4 §7.1). Not thread-safe: one per analysis thread.
 */
class BarcodeDecoder {
    private val reader = MultiFormatReader().apply {
        setHints(mapOf(DecodeHintType.POSSIBLE_FORMATS to FORMATS, DecodeHintType.TRY_HARDER to true))
    }

    /**
     * [luma] holds [height] rows of [width] pixels, each row starting
     * [rowStride] bytes after the last. Tried as given and turned a quarter,
     * since a 1D code is read across its bars and the phone may be held
     * either way to it.
     */
    fun decode(luma: ByteArray, width: Int, height: Int, rowStride: Int = width): Barcode? {
        val packed = if (rowStride == width) luma else pack(luma, width, height, rowStride)
        return attempt(packed, width, height)
            ?: attempt(rotate(packed, width, height), height, width)
    }

    private fun attempt(pixels: ByteArray, width: Int, height: Int): Barcode? {
        val source = PlanarYUVLuminanceSource(pixels, width, height, 0, 0, width, height, false)
        return try {
            normalised(reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))))
        } catch (_: NotFoundException) {
            null
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }
    }

    companion object {
        /** Retail product codes only, so a QR code on a pack is not taken for the product. */
        val FORMATS = listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E)

        /**
         * ZXing reports an EAN-13 with a leading zero as UPC-A without it.
         * Writing every such code in its EAN-13 form makes a pack read the
         * same whichever reader matched.
         */
        fun normalised(result: Result): Barcode =
            Barcode.of(if (result.barcodeFormat == BarcodeFormat.UPC_A) "0" + result.text else result.text)

        internal fun pack(luma: ByteArray, width: Int, height: Int, rowStride: Int): ByteArray {
            val out = ByteArray(width * height)
            for (y in 0 until height) System.arraycopy(luma, y * rowStride, out, y * width, width)
            return out
        }

        /** A quarter turn clockwise: the result is [height] wide and [width] high. */
        internal fun rotate(pixels: ByteArray, width: Int, height: Int): ByteArray {
            val out = ByteArray(pixels.size)
            for (y in 0 until height) {
                val row = y * width
                for (x in 0 until width) out[x * height + (height - 1 - y)] = pixels[row + x]
            }
            return out
        }
    }
}

/**
 * Lets a code through once per sighting: a code still in view is not taken
 * again until it has been out of view for [quietMs], so a pack held under the
 * camera adds once.
 */
class ScanGate(private val quietMs: Long = 1_500) {
    private val lastSeen = HashMap<Barcode, Long>()

    fun accept(code: Barcode, now: Long): Boolean {
        val previous = lastSeen.put(code, now)
        return previous == null || now - previous > quietMs
    }
}
