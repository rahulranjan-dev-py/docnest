package com.docnest.app

import android.graphics.Bitmap
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.max
import kotlin.math.min

/** How a photo looks in the PDF. */
enum class ColourMode(val label: String) {
    /** The photo as taken. */
    COLOUR("Colour"),

    /** Even lighting, white paper and crisper text, like a flatbed scanner. */
    DOCUMENT("Document"),

    /** Pure black text on pure white paper, stored at 1 bit per pixel. */
    BW("B&W"),
}

/**
 * Scanner-style clean-up of page photos, done on the phone with plain pixel arithmetic (no
 * libraries). Both filters judge each spot of the page against its surroundings, which is what
 * copes with a shadow from the phone, dim corners, or yellowed newsprint.
 */
object Enhance {

    // ---------- Document ----------

    private const val CELL = 16 // px; the paper colour is estimated per cell this size
    private const val MAX_GAIN = 2.2f // keeps dark photos in the page from being blown out
    private const val BLACK_POINT = 25 // after evening out: at or below this becomes black
    private const val WHITE_POINT = 235 // ... and at or above this becomes pure white
    private const val SHARPEN = 0.35f

    /**
     * Turns [bmp] (mutable, ARGB_8888) into a "scanned" page in place.
     * 1. Finds the paper's own colour around every spot: the brightest pixel in each 16 px cell,
     *    spread over its neighbours (to bridge headlines and pictures) and smoothed.
     * 2. Divides each colour by it, so the paper becomes white everywhere: shadows and
     *    yellowing disappear, and text keeps its contrast.
     * 3. Sets black and white points, then sharpens text edges a little.
     */
    fun document(bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val gw = (w + CELL - 1) / CELL
        val gh = (h + CELL - 1) / CELL
        val paper = Array(3) { IntArray(gw * gh) }
        for (y in 0 until h) {
            val row = y * w
            val cellRow = (y / CELL) * gw
            for (x in 0 until w) {
                val c = px[row + x]
                val i = cellRow + x / CELL
                val r = (c shr 16) and 255
                val g = (c shr 8) and 255
                val b = c and 255
                if (r > paper[0][i]) paper[0][i] = r
                if (g > paper[1][i]) paper[1][i] = g
                if (b > paper[2][i]) paper[2][i] = b
            }
        }
        for (ch in 0..2) {
            paper[ch] = blur(blur(dilate(paper[ch], gw, gh), gw, gh), gw, gh)
        }

        val range = (WHITE_POINT - BLACK_POINT).toFloat()
        // Per pixel: the paper colour interpolated between cell centres, then gain and levels.
        for (y in 0 until h) {
            val fy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, (gh - 1).toFloat())
            val y0 = fy.toInt()
            val y1 = min(y0 + 1, gh - 1)
            val ty = fy - y0
            val row = y * w
            for (x in 0 until w) {
                val fx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, (gw - 1).toFloat())
                val x0 = fx.toInt()
                val x1 = min(x0 + 1, gw - 1)
                val tx = fx - x0
                val c = px[row + x]
                var out = c and 0xFF000000.toInt()
                for (ch in 0..2) {
                    val g = paper[ch]
                    val top = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * tx
                    val bottom = g[y1 * gw + x0] + (g[y1 * gw + x1] - g[y1 * gw + x0]) * tx
                    val bg = max(top + (bottom - top) * ty, 1f)
                    val gain = min(255f / bg, MAX_GAIN)
                    val shift = 16 - 8 * ch
                    val v = ((c shr shift) and 255) * gain
                    val leveled = ((v - BLACK_POINT) * 255f / range).toInt().coerceIn(0, 255)
                    out = out or (leveled shl shift)
                }
                px[row + x] = out
            }
        }

        sharpen(px, w, h)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    /** Each cell takes the brightest of itself and its 8 neighbours. */
    private fun dilate(g: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) m = max(m, g[yy * w + xx])
            }
            out[y * w + x] = m
        }
        return out
    }

    /** 3×3 average. */
    private fun blur(g: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) {
                    sum += g[yy * w + xx]
                    n++
                }
            }
            out[y * w + x] = sum / n
        }
        return out
    }

    /** Light unsharp mask with the 4 nearest neighbours: c + a·(4c − up − down − left − right). */
    private fun sharpen(px: IntArray, w: Int, h: Int) {
        val src = px.copyOf()
        for (y in 1 until h - 1) {
            val row = y * w
            for (x in 1 until w - 1) {
                val i = row + x
                val c = src[i]
                var out = c and 0xFF000000.toInt()
                for (ch in 0..2) {
                    val shift = 16 - 8 * ch
                    val v = (c shr shift) and 255
                    val around = ((src[i - w] shr shift) and 255) + ((src[i + w] shr shift) and 255) +
                        ((src[i - 1] shr shift) and 255) + ((src[i + 1] shr shift) and 255)
                    val s = (v + SHARPEN * (4 * v - around)).toInt().coerceIn(0, 255)
                    out = out or (s shl shift)
                }
                px[i] = out
            }
        }
    }

    // ---------- Black & white ----------

    /** A black & white page: 1 bit per pixel, rows packed from the left (1 = white), zlib-compressed. */
    class Bitonal(val width: Int, val height: Int, val data: ByteArray)

    private const val THRESHOLD = 15 // % darker than its surroundings before a pixel turns black
    private const val ALWAYS_BLACK = 50 // luminance below which a pixel is black whatever is around it

    /**
     * Each pixel becomes black when it is clearly darker than the average of the area around it
     * (Bradley's adaptive threshold, about 1/12 of the page wide). Judging locally keeps faint
     * print and text in a shadow readable, where one threshold for the whole page would lose it.
     */
    fun bitonal(bmp: Bitmap): Bitonal {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val lum = IntArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            lum[i] = (((c shr 16) and 255) * 77 + ((c shr 8) and 255) * 150 + (c and 255) * 29) shr 8
        }

        // Summed-area table, so any rectangle's total costs four lookups. Fits in Int up to ~8 MP.
        val stride = w + 1
        val sums = IntArray(stride * (h + 1))
        for (y in 0 until h) {
            var rowSum = 0
            for (x in 0 until w) {
                rowSum += lum[y * w + x]
                sums[(y + 1) * stride + x + 1] = sums[y * stride + x + 1] + rowSum
            }
        }

        val half = max(8, w / 24)
        val rowBytes = (w + 7) / 8
        val bits = ByteArray(rowBytes * h) // 0 = black until a pixel is found to be white
        for (y in 0 until h) {
            val ya = max(0, y - half)
            val yb = min(h - 1, y + half)
            for (x in 0 until w) {
                val xa = max(0, x - half)
                val xb = min(w - 1, x + half)
                val count = ((xb - xa + 1) * (yb - ya + 1)).toLong()
                val sum = (sums[(yb + 1) * stride + xb + 1] - sums[ya * stride + xb + 1] -
                    sums[(yb + 1) * stride + xa] + sums[ya * stride + xa]).toLong()
                val l = lum[y * w + x]
                val black = l < ALWAYS_BLACK || l * count * 100 <= sum * (100 - THRESHOLD)
                if (!black) {
                    val i = y * rowBytes + (x shr 3)
                    bits[i] = (bits[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }

        val packed = ByteArrayOutputStream(bits.size / 8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(packed, deflater).use { it.write(bits) }
        } finally {
            deflater.end() // a Deflater we created isn't freed by closing the stream
        }
        return Bitonal(w, h, packed.toByteArray())
    }
}
