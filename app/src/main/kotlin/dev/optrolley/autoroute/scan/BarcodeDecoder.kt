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
 * device (tier 4 §7.1). Only the middle of the frame is read: a square
 * [zone] of its shorter side across, which the viewfinder marks, so a code
 * has to be aimed at and the shelf behind it is not scanned by accident.
 * Not thread-safe: one per analysis thread.
 */
class BarcodeDecoder(private val zone: Float = ZONE) {
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
        val side = (minOf(width, height) * zone).toInt()
        val square = crop(luma, rowStride, (width - side) / 2, (height - side) / 2, side)
        return attempt(square, side, side)
            ?: attempt(rotate(square, side, side), side, side)
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
        /**
         * The share of the frame's shorter side that is read. Small enough
         * that a neighbouring pack falls outside it, large enough that an
         * EAN-13 filling it has several pixels to a bar.
         */
        const val ZONE = 0.7f

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

        /** The [side]-wide square whose corner is at ([left], [top]). */
        internal fun crop(luma: ByteArray, rowStride: Int, left: Int, top: Int, side: Int): ByteArray {
            val out = ByteArray(side * side)
            for (y in 0 until side) System.arraycopy(luma, (top + y) * rowStride + left, out, y * side, side)
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
 *
 * A code is taken only once it has been read [sightings] times running, each
 * within [steadyMs] of the last: one frame's misreading of a code, or a
 * glimpse of another in passing, is not a scan. After one is taken nothing
 * else is for [restMs], which is the time it takes to lower the phone.
 */
class ScanGate(
    private val quietMs: Long = 1_500,
    private val sightings: Int = 1,
    private val steadyMs: Long = 0,
    private val restMs: Long = 0,
) {
    private val lastSeen = HashMap<Barcode, Long>()
    private var candidate: Barcode? = null
    private var streak = 0
    private var candidateAt = 0L
    private var taken: Barcode? = null
    private var restUntil = Long.MIN_VALUE

    fun accept(code: Barcode, now: Long): Boolean {
        val previous = lastSeen[code]
        if (previous != null && now - previous <= quietMs) {
            lastSeen[code] = now
            candidate = null
            return false
        }
        streak = if (code == candidate && now - candidateAt <= steadyMs) streak + 1 else 1
        candidate = code
        candidateAt = now
        if (streak < sightings || now < restUntil) return false
        lastSeen[code] = now
        taken = code
        restUntil = now + restMs
        candidate = null
        return true
    }

    /**
     * Scanning is paused at [now], e.g. while the code just taken is being
     * named. That code counts as still in view, so it is not taken a second
     * time when scanning resumes with the pack where it was.
     */
    fun hold(now: Long) {
        taken?.let { lastSeen[it] = now }
        candidate = null
        restUntil = maxOf(restUntil, now + restMs)
    }
}
