package com.docnest.app

import java.io.OutputStream
import java.util.Locale

enum class PageSize { A4, PHOTO }

private const val A4_SHORT = 595.28
private const val A4_LONG = 841.89

/** Where a photo and its footer sit on a page, in PDF points (1/72 inch). */
private class Layout(
    val pw: Double, val ph: Double,
    val iw: Double, val ih: Double, val ix: Double, val iy: Double,
    val fx: Double, val fy: Double,
)

// A landscape photo gets a landscape page. The whole photo is always shown: it is scaled, never cropped.
private fun layout(w: Int, h: Int, size: PageSize, footer: Boolean): Layout {
    val land = w > h
    val pw = if (land) A4_LONG else A4_SHORT
    if (size == PageSize.PHOTO) {
        val fb = if (footer) 20.0 else 0.0
        val ih = pw * h / w
        return Layout(pw, ih + fb, pw, ih, 0.0, fb, 10.0, 6.5)
    }
    val ph = if (land) A4_SHORT else A4_LONG
    val m = 18.0
    val fb = if (footer) 16.0 else 0.0
    val aw = pw - 2 * m
    val ah = ph - 2 * m - fb
    val s = minOf(aw / w, ah / h)
    val iw = w * s
    val ih = h * s
    return Layout(pw, ph, iw, ih, (pw - iw) / 2, m + fb + (ah - ih) / 2, m, m - 2)
}

/** Keeps only characters the built-in Helvetica font can print. */
fun asciiOnly(s: String): String = s
    .replace(Regex("[–—]"), "-")
    .replace(Regex("[‘’]"), "'")
    .replace(Regex("[“”]"), "\"")
    .replace(Regex("[^\\x20-\\x7e]"), "")
    .replace(Regex("\\s+"), " ")
    .trim()

private fun num(n: Double): String {
    val r = Math.round(n * 100) / 100.0
    if (r == Math.floor(r)) return r.toLong().toString()
    return String.format(Locale.US, "%.2f", r).trimEnd('0')
}

private fun escape(s: String): String = s.replace(Regex("[\\\\()]")) { "\\" + it.value }

/**
 * Streams a PDF with one JPEG photo per page, so only one page is in memory at a time.
 * Call [addPage] exactly [pageCount] times, then [finish].
 * Objects: 1 catalog, 2 page tree, 3 font, then page, contents and image for each page.
 */
class PdfWriter(
    private val out: OutputStream,
    private val pageCount: Int,
    private val size: PageSize,
    private val footer: String?,
) {
    private val offsets = LongArray(4 + 3 * pageCount)
    private var pos = 0L
    private var index = 0

    private fun put(b: ByteArray) {
        out.write(b)
        pos += b.size
    }

    private fun put(s: String) = put(s.toByteArray(Charsets.ISO_8859_1))

    private fun begin(k: Int) {
        offsets[k] = pos
        put("$k 0 obj\n")
    }

    init {
        put("%PDF-1.4\n")
        put(byteArrayOf(37, 226.toByte(), 227.toByte(), 207.toByte(), 211.toByte(), 10))
        val kids = (0 until pageCount).joinToString(" ") { "${4 + 3 * it} 0 R" }
        begin(1); put("<< /Type /Catalog /Pages 2 0 R >>\nendobj\n")
        begin(2); put("<< /Type /Pages /Kids [$kids] /Count $pageCount >>\nendobj\n")
        begin(3); put("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>\nendobj\n")
    }

    /** A photo page, stored as the JPEG itself. */
    fun addPage(jpeg: ByteArray, w: Int, h: Int) =
        addImagePage("/ColorSpace /DeviceRGB /BitsPerComponent 8 /Filter /DCTDecode", jpeg, w, h)

    /** A black & white page: 1 bit per pixel (1 = white), zlib-compressed, as [Enhance.bitonal] makes it. */
    fun addBitonalPage(page: Enhance.Bitonal) =
        addImagePage("/ColorSpace /DeviceGray /BitsPerComponent 1 /Filter /FlateDecode", page.data, page.width, page.height)

    private fun addImagePage(format: String, data: ByteArray, w: Int, h: Int) {
        val i = index++
        val lay = layout(w, h, size, footer != null)
        val page = 4 + 3 * i
        val contents = page + 1
        val image = page + 2

        begin(page)
        put(
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${num(lay.pw)} ${num(lay.ph)}]" +
                " /Resources << /XObject << /Im0 $image 0 R >> /Font << /F1 3 0 R >> >> /Contents $contents 0 R >>\nendobj\n"
        )

        var s = "q ${num(lay.iw)} 0 0 ${num(lay.ih)} ${num(lay.ix)} ${num(lay.iy)} cm /Im0 Do Q\n"
        if (footer != null) {
            val prefix = if (footer.isNotEmpty()) "$footer   |   " else ""
            val t = escape(asciiOnly(prefix + "Page ${i + 1} of $pageCount"))
            s += "BT 0.3 g /F1 9 Tf ${num(lay.fx)} ${num(lay.fy)} Td ($t) Tj ET\n"
        }
        val sb = s.toByteArray(Charsets.ISO_8859_1)
        begin(contents); put("<< /Length ${sb.size} >>\nstream\n"); put(sb); put("endstream\nendobj\n")

        begin(image)
        put("<< /Type /XObject /Subtype /Image /Width $w /Height $h $format /Length ${data.size} >>\nstream\n")
        put(data); put("\nendstream\nendobj\n")
    }

    fun finish() {
        check(index == pageCount) { "Expected $pageCount pages, got $index" }
        val total = 3 + 3 * pageCount
        val xref = pos
        val x = StringBuilder("xref\n0 ${total + 1}\n0000000000 65535 f \n")
        for (j in 1..total) x.append(String.format(Locale.US, "%010d 00000 n \n", offsets[j]))
        put(x.toString())
        put(
            "trailer\n<< /Size ${total + 1} /Root 1 0 R /Info << /Producer (DocNest) >> >>\n" +
                "startxref\n$xref\n%%EOF\n"
        )
        out.flush()
    }
}
