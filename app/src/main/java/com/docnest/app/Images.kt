package com.docnest.app

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
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

    // Black & white with a little extra contrast: v = (luma - 128) * 1.12 + 128.
    private val GREY = ColorMatrix(
        floatArrayOf(
            0.299f * 1.12f, 0.587f * 1.12f, 0.114f * 1.12f, 0f, -15.36f,
            0.299f * 1.12f, 0.587f * 1.12f, 0.114f * 1.12f, 0f, -15.36f,
            0.299f * 1.12f, 0.587f * 1.12f, 0.114f * 1.12f, 0f, -15.36f,
            0f, 0f, 0f, 1f, 0f,
        )
    )

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

    /** Scales [src] to fit [edge], turns it clockwise by [degrees] and optionally makes it black & white. */
    fun render(src: Bitmap, edge: Int, degrees: Int, grey: Boolean): Bitmap {
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
        if (grey) paint.colorFilter = ColorMatrixColorFilter(GREY)
        canvas.drawBitmap(src, null, RectF(-dw / 2f, -dh / 2f, dw / 2f, dh / 2f), paint)
        return out
    }

    fun jpeg(b: Bitmap, quality: Int): ByteArray {
        val bytes = ByteArrayOutputStream(256 * 1024)
        b.compress(Bitmap.CompressFormat.JPEG, quality, bytes)
        return bytes.toByteArray()
    }

    /**
     * Saves a new photo into the batch: upright, at most [STORE_EDGE] px, plus a small thumbnail.
     * Returns the thumbnail, or null when the image can't be opened.
     */
    fun store(open: () -> InputStream, degrees: Int, photo: File, thumb: File): Bitmap? {
        val src = decodeSampled(open, STORE_EDGE) ?: return null
        val upright = render(src, STORE_EDGE, degrees, false)
        src.recycle()
        photo.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, STORE_QUALITY, it) }
        val small = render(upright, THUMB_EDGE, 0, false)
        upright.recycle()
        thumb.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        return small
    }

    /** A stored photo, ready for a PDF page or the viewer. */
    fun load(photo: File, edge: Int, degrees: Int, grey: Boolean): Bitmap? {
        val src = decodeSampled({ photo.inputStream() }, edge) ?: return null
        val out = render(src, edge, degrees, grey)
        src.recycle()
        return out
    }
}
