package com.docnest.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

object Images {
    // Photos are kept at 2300 px on the long side: sharp enough for small print, small enough to store.
    const val STORE_EDGE = 2300
    const val STORE_QUALITY = 88
    const val THUMB_EDGE = 380

    class Exif(val degrees: Int, val taken: Long)

    /** Rotation and the time the photo was taken (0 when unknown), from the photo's EXIF data. */
    fun readExif(input: InputStream): Exif {
        return try {
            val exif = ExifInterface(input)
            val raw = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
            val taken = raw?.let {
                runCatching { SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).parse(it)?.time }.getOrNull()
            } ?: 0L
            Exif(exif.rotationDegrees, taken)
        } catch (e: Exception) {
            Exif(0, 0L)
        }
    }

    /** Decodes no larger than about [edge] px on the long side, using inSampleSize to save memory. */
    fun decodeSampled(open: () -> InputStream, edge: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open().use { BitmapFactory.decodeStream(it, null, bounds) }
        val long = max(bounds.outWidth, bounds.outHeight)
        if (long <= 0) return null
        var sample = 1
        while (long / (sample * 2) >= edge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return open().use { BitmapFactory.decodeStream(it, null, opts) }
    }

    /** Scales [src] to fit [edge] and turns it clockwise by [degrees], onto a new mutable bitmap. */
    fun render(src: Bitmap, edge: Int, degrees: Int): Bitmap {
        val s = min(1f, edge.toFloat() / max(src.width, src.height))
        val dw = max(1, (src.width * s).roundToInt())
        val dh = max(1, (src.height * s).roundToInt())
        val swap = degrees % 180 != 0
        val out = Bitmap.createBitmap(if (swap) dh else dw, if (swap) dw else dh, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.drawColor(Color.WHITE)
        canvas.translate(out.width / 2f, out.height / 2f)
        canvas.rotate(degrees.toFloat())
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        canvas.drawBitmap(src, null, RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f), paint)
        return out
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray {
        val bytes = ByteArrayOutputStream(256 * 1024)
        b.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        return bytes.toByteArray()
    }

    /** A photo just saved into the batch: its thumbnail, and whether it looks out of focus. */
    class Stored(val thumb: Bitmap, val blurry: Boolean)

    /**
     * Saves a new photo into the batch: upright, at most [STORE_EDGE] px, plus a small thumbnail.
     * Returns null when the image can't be opened.
     */
    fun store(open: () -> InputStream, degrees: Int, photo: File, thumb: File): Stored? {
        val src = decodeSampled(open, STORE_EDGE) ?: return null
        val upright = render(src, STORE_EDGE, degrees)
        src.recycle()
        photo.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, STORE_QUALITY, it) }
        val blurry = looksBlurry(upright)
        val small = render(upright, THUMB_EDGE, 0)
        upright.recycle()
        thumb.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        return Stored(small, blurry)
    }

    // Below this, a page photo is probably out of focus or shaken; crisp print scores far higher.
    // Kept low on purpose: a false "Blurry?" is more annoying than a missed one.
    private const val BLUR_LIMIT = 60.0

    /** Variance of the Laplacian on a 640 px greyscale copy: few sharp edges means a low score. */
    private fun looksBlurry(src: Bitmap): Boolean {
        val small = render(src, 640, 0)
        val w = small.width
        val h = small.height
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        small.recycle()
        val grey = IntArray(w * h) { i ->
            val c = px[i]
            (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }
        var sum = 0.0
        var sumSq = 0.0
        var n = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val lap = (4 * grey[i] - grey[i - 1] - grey[i + 1] - grey[i - w] - grey[i + w]).toDouble()
                sum += lap
                sumSq += lap * lap
                n++
            }
        }
        if (n == 0) return false
        val mean = sum / n
        return sumSq / n - mean * mean < BLUR_LIMIT
    }

    /**
     * A stored photo, ready for a PDF page or the viewer. Document and Vivid clean it up here;
     * [ColourMode.BW] is turned into 1-bit pixels later, by [Enhance.bitonal].
     */
    fun load(photo: File, edge: Int, degrees: Int, colour: ColourMode = ColourMode.COLOUR): Bitmap? {
        val src = decodeSampled({ photo.inputStream() }, edge) ?: return null
        val out = render(src, edge, degrees)
        src.recycle()
        if (colour == ColourMode.DOCUMENT || colour == ColourMode.VIVID) {
            Enhance.document(out, vivid = colour == ColourMode.VIVID)
        }
        return out
    }
}
