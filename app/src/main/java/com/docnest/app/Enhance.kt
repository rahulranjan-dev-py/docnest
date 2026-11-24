package com.docnest.app

import android.graphics.Bitmap
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** How a photo looks in the PDF. */
enum class ColourMode(val label: String) {
    /** The photo as taken. */
    COLOUR("Original"),

    /** Even lighting, white paper and crisper text, like a flatbed scanner. */
    DOCUMENT("Document"),

    /** Document, plus stronger colour and contrast so ink and pictures stand out. */
    VIVID("Vivid"),

    /** Pure black text on pure white paper, stored at 1 bit per pixel. */
    BW("B&W"),
}

/**
 * Scanner-style clean-up of page photos, done on the phone with plain pixel arithmetic (no
 * libraries). Both filters judge each spot of the page against the bare paper around it, which
 * is what copes with a shadow from the phone, dim corners, or yellowed newsprint.
 *
 * Both start from [paperOf], which also tells the paper apart from what is printed on it in
 * large areas: photos, coloured boxes and banners, highlighter. Those keep their own tones, and
 * B&W prints photos as fine dots, like a newspaper, rather than as black blotches.
 */
object Enhance {

    // ---------- the bare paper ----------

    private const val CELL = 16 // px; the paper is estimated per cell this size

    /**
     * The bare paper's colour per cell ([colour], one grid per channel, [gw] × [gh] cells); the
     * cells that aren't paper ([content]: photos, boxes, highlighter, the desk) and those of them
     * textured like a photo ([picture]); and how bright the paper typically is ([level], 0–255):
     * low for a page photographed in dim light.
     */
    private class Paper(
        val gw: Int,
        val gh: Int,
        val colour: Array<FloatArray>,
        val content: BooleanArray,
        val picture: BooleanArray,
        val level: Float,
    )

    // A cell this much darker than the paper around it may be a picture or a box…
    private const val DARKER = 0.80f

    // … and is one if under this share of it is near its brightest (paper with text is mostly
    // paper; a photo is all tones) …
    private const val TEXTURED = 0.30f

    // … or its colour differs from the paper's this much: a coloured box, a desk. Shadows tint
    // the paper a little (bluish, in daylight), well under this.
    private const val COLOURED_DARK = 0.35f

    // Colour this far from the paper's is never paper, however light: highlighter, a pale box.
    private const val COLOURED = 0.45f

    // How far colour is carried in over a photo or a box, from the paper around it.
    private const val SPREAD_STEPS = 80

    private fun lum(r: Int, g: Int, b: Int) = (r * 77 + g * 150 + b * 29) shr 8

    private fun lum(c: Int) = lum((c shr 16) and 255, (c shr 8) and 255, c and 255)

    /**
     * What the bare paper looks like at every spot, under the light that falls there.
     *
     * 1. Per 16 px cell, the brightest colour: on paper with text, that is the paper itself.
     * 2. A cell clearly darker than the paper around it, and textured like a photo or coloured
     *    unlike the paper, is a picture or a box; so is a strongly coloured cell, like
     *    highlighter. Shadows are neither: they are paper-coloured, and mostly paper with text.
     * 3. Pictures and boxes get the paper's colour carried in from the paper around them, so the
     *    lighting is still corrected over them, but their own tones are kept.
     * 4. Spread a little and smoothed, to bridge thick strokes and soften the edges.
     */
    private fun paperOf(px: IntArray, w: Int, h: Int): Paper {
        val gw = (w + CELL - 1) / CELL
        val gh = (h + CELL - 1) / CELL
        val n = gw * gh

        // 1. Brightest per cell, of neighbouring pixels averaged in pairs: that halves the camera
        // noise that would otherwise make the brightest pixel brighter than the paper is.
        val top = Array(3) { IntArray(n) }
        val topLum = IntArray(n)
        for (y in 0 until h) {
            val row = y * w
            val cellRow = (y / CELL) * gw
            for (x in 0 until w) {
                val c = px[row + x]
                val d = px[row + min(x + 1, w - 1)]
                val r = (((c shr 16) and 255) + ((d shr 16) and 255)) shr 1
                val g = (((c shr 8) and 255) + ((d shr 8) and 255)) shr 1
                val b = ((c and 255) + (d and 255)) shr 1
                val i = cellRow + x / CELL
                if (r > top[0][i]) top[0][i] = r
                if (g > top[1][i]) top[1][i] = g
                if (b > top[2][i]) top[2][i] = b
                val l = lum(r, g, b)
                if (l > topLum[i]) topLum[i] = l
            }
        }

        // How much of each cell is near its brightest: within 12%, or 10 levels in dark cells,
        // where camera noise is relatively larger.
        val near = IntArray(n)
        val count = IntArray(n)
        for (y in 0 until h) {
            val row = y * w
            val cellRow = (y / CELL) * gw
            for (x in 0 until w) {
                val i = cellRow + x / CELL
                count[i]++
                if (lum(px[row + x]) >= min(topLum[i] * 88 / 100, topLum[i] - 10)) near[i]++
            }
        }

        // 2. The paper's brightness around each cell: dark areas up to most of the page across are
        // closed over (a morphological closing), so a photo is judged against the paper beside
        // it, while slow changes of the light across the page are followed.
        val reach = max(2, (min(gw, gh) * 0.45f).roundToInt())
        val around = slide(slide(topLum, gw, gh, reach, takeMax = true), gw, gh, reach, takeMax = false)

        // The paper's colour, as a ratio to its brightness, and its typical brightness, from cells
        // that are plainly paper.
        val tint = DoubleArray(3)
        val levels = IntArray(256)
        var plain = 0
        for (i in 0 until n) {
            if (topLum[i] > 20 && topLum[i] >= around[i] * 0.92f && near[i] * 2 > count[i]) {
                for (ch in 0..2) tint[ch] += top[ch][i] / topLum[i].toDouble()
                levels[topLum[i].coerceIn(0, 255)]++
                plain++
            }
        }
        for (ch in 0..2) tint[ch] = if (plain == 0) 1.0 else tint[ch] / plain
        var level = 255f
        var seen = 0
        for (v in 0..255) {
            seen += levels[v]
            if (plain > 0 && seen * 2 >= plain) {
                level = v.toFloat()
                break
            }
        }

        val content = BooleanArray(n)
        val picture = BooleanArray(n)
        for (i in 0 until n) {
            val l = topLum[i]
            if (l <= 0) {
                content[i] = true
                continue
            }
            var differs = 0.0
            for (ch in 0..2) differs += abs(top[ch][i] / l.toDouble() - tint[ch])
            val dark = l < around[i] * DARKER
            val textured = near[i] < count[i] * TEXTURED
            content[i] = (dark && (textured || differs > COLOURED_DARK)) || differs > COLOURED
            picture[i] = dark && textured
        }
        tidy(content, gw, gh)
        tidy(picture, gw, gh)

        // 3. Paper cells keep their own colour; pictures and boxes start from the paper's colour
        // at the brightness around them, then take on their neighbours' until it settles.
        val colour = Array(3) { ch ->
            FloatArray(n) { i -> if (content[i]) (around[i] * tint[ch]).toFloat() else top[ch][i].toFloat() }
        }
        val inside = (0 until n).filter { content[it] }.toIntArray()
        if (inside.isNotEmpty() && inside.size < n) {
            repeat(SPREAD_STEPS) {
                for (ch in 0..2) {
                    val g = colour[ch]
                    for (i in inside) {
                        val x = i % gw
                        var s = 0f
                        var k = 0
                        if (x > 0) { s += g[i - 1]; k++ }
                        if (x < gw - 1) { s += g[i + 1]; k++ }
                        if (i >= gw) { s += g[i - gw]; k++ }
                        if (i + gw < n) { s += g[i + gw]; k++ }
                        g[i] = s / k
                    }
                }
            }
        }

        // 4.
        for (ch in 0..2) colour[ch] = blur(blur(dilate(colour[ch], gw, gh), gw, gh), gw, gh)
        return Paper(gw, gh, colour, content, picture, level)
    }

    // A photo has at least this many photo-like cells (16 px each, about 2 mm square)…
    private const val MIN_PHOTO_CELLS = 40

    // … and fills most of the rectangle round it: a shadow's edge, a long thin diagonal, doesn't.
    private const val MIN_PHOTO_FILL = 0.6f

    /**
     * Where B&W prints dots: the photos, as whole rectangles. The cells that aren't paper are
     * thinned of stray cells and thin lines (shadow edges), and grouped. A group that holds
     * enough photo-like cells and fills most of its rectangle is a photo: its rectangle is
     * dotted, plus a cell round it so its edge is too. Coloured boxes and highlighter have no
     * photo-like cells, so they keep the sharp black and white of text. A group touching the edge
     * of the picture is left out: that is the desk round a page.
     */
    private fun photoAreas(paper: Paper): BooleanArray {
        val gw = paper.gw
        val gh = paper.gh
        val n = gw * gh
        val opened = spread(spread(paper.content, gw, gh, grow = false), gw, gh, grow = true)
        val out = BooleanArray(n)
        val seen = BooleanArray(n)
        val stack = IntArray(n)
        for (start in 0 until n) {
            if (!opened[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var size = 0
            var photoLike = 0
            var x0 = gw
            var x1 = -1
            var y0 = gh
            var y1 = -1
            var atEdge = false
            while (top > 0) {
                val i = stack[--top]
                size++
                if (paper.picture[i]) photoLike++
                val x = i % gw
                val y = i / gw
                x0 = min(x0, x)
                x1 = max(x1, x)
                y0 = min(y0, y)
                y1 = max(y1, y)
                if (x == 0 || y == 0 || x == gw - 1 || y == gh - 1) atEdge = true
                for (j in intArrayOf(if (x > 0) i - 1 else -1, if (x < gw - 1) i + 1 else -1, i - gw, i + gw)) {
                    if (j in 0 until n && opened[j] && !seen[j]) {
                        seen[j] = true
                        stack[top++] = j
                    }
                }
            }
            val box = (x1 - x0 + 1) * (y1 - y0 + 1)
            if (atEdge || photoLike < MIN_PHOTO_CELLS || size < box * MIN_PHOTO_FILL) continue
            for (y in max(0, y0 - 1)..min(gh - 1, y1 + 1)) for (x in max(0, x0 - 1)..min(gw - 1, x1 + 1)) out[y * gw + x] = true
        }
        return out
    }

    /** A cell mask grown by one cell all round ([grow]), or shrunk by one (a cell needs all 8 neighbours). */
    private fun spread(m: BooleanArray, w: Int, h: Int, grow: Boolean): BooleanArray {
        val out = BooleanArray(m.size)
        for (y in 0 until h) for (x in 0 until w) {
            var any = false
            var all = true
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                val v = yy in 0 until h && xx in 0 until w && m[yy * w + xx]
                any = any || v
                all = all && v
            }
            out[y * w + x] = if (grow) any else all
        }
        return out
    }

    /** Max (or min) over a square of ±[r] cells: along rows, then along columns. */
    private fun slide(g: IntArray, w: Int, h: Int, r: Int, takeMax: Boolean): IntArray {
        val line = IntArray(max(w, h))
        val res = IntArray(max(w, h))
        val queue = IntArray(max(w, h))
        val rows = IntArray(g.size)
        for (y in 0 until h) {
            for (x in 0 until w) line[x] = g[y * w + x]
            window(line, w, r, takeMax, res, queue)
            for (x in 0 until w) rows[y * w + x] = res[x]
        }
        val out = IntArray(g.size)
        for (x in 0 until w) {
            for (y in 0 until h) line[y] = rows[y * w + x]
            window(line, h, r, takeMax, res, queue)
            for (y in 0 until h) out[y * w + x] = res[y]
        }
        return out
    }

    /** res[i] = the max (or min) of v[i - r .. i + r], kept in a queue of candidates as it moves. */
    private fun window(v: IntArray, n: Int, r: Int, takeMax: Boolean, res: IntArray, queue: IntArray) {
        var head = 0
        var tail = 0
        var next = 0
        for (i in 0 until n) {
            val last = min(n - 1, i + r)
            while (next <= last) {
                while (tail > head && (if (takeMax) v[next] >= v[queue[tail - 1]] else v[next] <= v[queue[tail - 1]])) tail--
                queue[tail++] = next++
            }
            while (queue[head] < i - r) head++
            res[i] = v[queue[head]]
        }
    }

    /** A cell that disagrees with 7 or more of its 8 neighbours follows them: stray cells are noise. */
    private fun tidy(m: BooleanArray, w: Int, h: Int) {
        val src = m.copyOf()
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            var same = 0
            for (dy in -1..1) for (dx in -1..1) {
                if ((dx != 0 || dy != 0) && src[(y + dy) * w + x + dx] == src[y * w + x]) same++
            }
            if (same <= 1) m[y * w + x] = !src[y * w + x]
        }
    }

    /** Each cell takes the brightest of itself and its 8 neighbours. */
    private fun dilate(g: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            var m = 0f
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
    private fun blur(g: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            var sum = 0f
            var k = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = y + dy
                val xx = x + dx
                if (yy in 0 until h && xx in 0 until w) {
                    sum += g[yy * w + xx]
                    k++
                }
            }
            out[y * w + x] = sum / k
        }
        return out
    }

    /** [g] at pixel (x, y): between the four nearest cell centres. */
    private fun at(g: FloatArray, gw: Int, gh: Int, x: Int, y: Int): Float {
        val fy = ((y + 0.5f) / CELL - 0.5f).coerceIn(0f, (gh - 1).toFloat())
        val fx = ((x + 0.5f) / CELL - 0.5f).coerceIn(0f, (gw - 1).toFloat())
        val y0 = fy.toInt()
        val x0 = fx.toInt()
        val y1 = min(y0 + 1, gh - 1)
        val x1 = min(x0 + 1, gw - 1)
        val ty = fy - y0
        val tx = fx - x0
        val top = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * tx
        val bottom = g[y1 * gw + x0] + (g[y1 * gw + x1] - g[y1 * gw + x0]) * tx
        return top + (bottom - top) * ty
    }

    // ---------- Document ----------

    private const val MAX_GAIN = 2.2f // keeps a dark area the paper estimate missed from being blown out
    private const val MAX_DIM_GAIN = 3.5f // a page photographed in dim light may be lifted this far
    private const val BLACK_POINT = 25 // after evening out: at or below this becomes black
    private const val WHITE_POINT = 235 // ... and at or above this becomes pure white
    private const val SHARPEN = 0.35f

    /**
     * Turns [bmp] (mutable, ARGB_8888) into a "scanned" page in place.
     * 1. Finds the bare paper's colour around every spot ([paperOf]).
     * 2. Divides each colour by it, so the paper becomes white everywhere: shadows and
     *    yellowing disappear, text keeps its contrast, and photos and coloured boxes keep their
     *    tones, only freed of the shadow over them.
     * 3. Sets black and white points, then sharpens text edges a little.
     * [vivid] also raises colour saturation and darkens mid-tones, for a punchier page.
     */
    fun document(bmp: Bitmap, vivid: Boolean = false) {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val paper = paperOf(px, w, h)
        val gw = paper.gw
        val gh = paper.gh
        // A page photographed in dim light may be lifted further, so its paper still turns white;
        // a dark area on a normally lit page is not. Lifted further, the noise is too, so such a
        // page is sharpened less.
        val maxGain = max(MAX_GAIN, min(MAX_DIM_GAIN, 255f / max(paper.level, 1f)))
        val sharpness = SHARPEN * MAX_GAIN / maxGain

        val range = (WHITE_POINT - BLACK_POINT).toFloat()
        val rgb = FloatArray(3)
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
                for (ch in 0..2) {
                    val g = paper.colour[ch]
                    val top = g[y0 * gw + x0] + (g[y0 * gw + x1] - g[y0 * gw + x0]) * tx
                    val bottom = g[y1 * gw + x0] + (g[y1 * gw + x1] - g[y1 * gw + x0]) * tx
                    val bg = max(top + (bottom - top) * ty, 1f)
                    val gain = min(255f / bg, maxGain)
                    val v = ((c shr (16 - 8 * ch)) and 255) * gain
                    rgb[ch] = (v - BLACK_POINT) * 255f / range
                }
                if (vivid) boost(rgb)
                px[row + x] = (c and 0xFF000000.toInt()) or
                    (rgb[0].toInt().coerceIn(0, 255) shl 16) or
                    (rgb[1].toInt().coerceIn(0, 255) shl 8) or
                    rgb[2].toInt().coerceIn(0, 255)
            }
        }

        sharpen(px, w, h, sharpness)
        bmp.setPixels(px, 0, w, 0, 0, w, h)
    }

    private const val VIVID_SATURATION = 1.45f

    // Gamma 1.25 as a lookup table: darkens mid-tones, while paper (255) stays white.
    private val vividCurve = FloatArray(256) { 255f * Math.pow(it / 255.0, 1.25).toFloat() }

    /** Pushes colours away from grey and darkens mid-tones; [rgb] is 0–255 per channel. */
    private fun boost(rgb: FloatArray) {
        val grey = 0.299f * rgb[0] + 0.587f * rgb[1] + 0.114f * rgb[2]
        for (ch in 0..2) {
            val saturated = (grey + (rgb[ch] - grey) * VIVID_SATURATION).coerceIn(0f, 255f)
            rgb[ch] = vividCurve[saturated.toInt()]
        }
    }

    /** Light unsharp mask with the 4 nearest neighbours: c + a·(4c − up − down − left − right). */
    private fun sharpen(px: IntArray, w: Int, h: Int, amount: Float) {
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
                    val s = (v + amount * (4 * v - around)).toInt().coerceIn(0, 255)
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

    // An 8 × 8 ordered-dither pattern (Bayer): a photo's tones become more or fewer black dots.
    private val DOT_LEVELS = intArrayOf(
        0, 32, 8, 40, 2, 34, 10, 42, 48, 16, 56, 24, 50, 18, 58, 26,
        12, 44, 4, 36, 14, 46, 6, 38, 60, 28, 52, 20, 62, 30, 54, 22,
        3, 35, 11, 43, 1, 33, 9, 41, 51, 19, 59, 27, 49, 17, 57, 25,
        15, 47, 7, 39, 13, 45, 5, 37, 63, 31, 55, 23, 61, 29, 53, 21,
    ).map { (it + 0.5f) / 64f }.toFloatArray()

    /**
     * Text and drawings: each pixel becomes black when it is clearly darker than the average of
     * the area around it (Bradley's adaptive threshold, about 1/12 of the page wide). Judging
     * locally keeps faint print and text in a shadow readable, where one threshold for the whole
     * page would lose it. Photos ([paperOf]) become fine dots instead, their darkness measured
     * against the paper around them, so they look like newspaper photos rather than blotches.
     */
    fun bitonal(bmp: Bitmap): Bitonal {
        val bits = bitonalBits(bmp)
        val packed = ByteArrayOutputStream(bits.size / 8)
        val deflater = Deflater(Deflater.BEST_COMPRESSION)
        try {
            DeflaterOutputStream(packed, deflater).use { it.write(bits) }
        } finally {
            deflater.end() // a Deflater we created isn't freed by closing the stream
        }
        return Bitonal(bmp.width, bmp.height, packed.toByteArray())
    }

    /** [bitonal]'s black and white pixels as a picture, to show a page before its PDF is made. */
    fun bitonalPreview(bmp: Bitmap): Bitmap {
        val w = bmp.width
        val h = bmp.height
        val bits = bitonalBits(bmp)
        val rowBytes = (w + 7) / 8
        val px = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * rowBytes
            for (x in 0 until w) {
                val white = (bits[row + (x shr 3)].toInt() and (0x80 ushr (x and 7))) != 0
                px[y * w + x] = if (white) Color.WHITE else Color.BLACK
            }
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.RGB_565)
    }

    /** One bit per pixel, rows packed from the left, 1 = white. */
    private fun bitonalBits(bmp: Bitmap): ByteArray {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)

        val lum = IntArray(w * h)
        for (i in px.indices) lum[i] = lum(px[i])

        // Photos: dots there.
        val paper = paperOf(px, w, h)
        val gw = paper.gw
        val gh = paper.gh
        val dots = photoAreas(paper)
        val paperLum = FloatArray(gw * gh) { i ->
            (paper.colour[0][i] * 77 + paper.colour[1][i] * 150 + paper.colour[2][i] * 29) / 256f
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
        val black = BooleanArray(w * h)
        for (y in 0 until h) {
            val ya = max(0, y - half)
            val yb = min(h - 1, y + half)
            val cellRow = (y / CELL) * gw
            for (x in 0 until w) {
                val l = lum[y * w + x]
                black[y * w + x] = if (dots[cellRow + x / CELL]) {
                    // As dark as the photo is there, against the paper: that share of dots is black.
                    l < max(at(paperLum, gw, gh, x, y), 1f) * DOT_LEVELS[(y and 7) * 8 + (x and 7)]
                } else {
                    val xa = max(0, x - half)
                    val xb = min(w - 1, x + half)
                    val count = ((xb - xa + 1) * (yb - ya + 1)).toLong()
                    val sum = (sums[(yb + 1) * stride + xb + 1] - sums[ya * stride + xb + 1] -
                        sums[(yb + 1) * stride + xa] + sums[ya * stride + xa]).toLong()
                    l < ALWAYS_BLACK || l * count * 100 <= sum * (100 - THRESHOLD)
                }
            }
        }

        // Specks: a black pixel with at most one black neighbour is paper grain, not print.
        // Even a full stop is several pixels across at this size, so real marks are kept.
        // A photo's dots are left alone: there, a lone dot is a light tone.
        val rowBytes = (w + 7) / 8
        val bits = ByteArray(rowBytes * h) // 0 = black until a pixel is found to be white
        for (y in 0 until h) {
            val cellRow = (y / CELL) * gw
            for (x in 0 until w) {
                var ink = black[y * w + x]
                if (ink && !dots[cellRow + x / CELL]) {
                    var neighbours = 0
                    for (dy in -1..1) for (dx in -1..1) {
                        val yy = y + dy
                        val xx = x + dx
                        if ((dx != 0 || dy != 0) && yy in 0 until h && xx in 0 until w && black[yy * w + xx]) neighbours++
                    }
                    ink = neighbours > 1
                }
                if (!ink) {
                    val i = y * rowBytes + (x shr 3)
                    bits[i] = (bits[i].toInt() or (0x80 ushr (x and 7))).toByte()
                }
            }
        }
        return bits
    }
}
