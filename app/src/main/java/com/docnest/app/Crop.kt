package com.docnest.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A page's outline for cropping: four corners as fractions of the photo's width and height
 * (0 to 1), in the order top-left, top-right, bottom-right, bottom-left, as 8 numbers
 * (x0, y0, x1, y1, …).
 *
 * A page keeps its crop in the photo as stored, before the page is turned, so turning a page
 * never disturbs its crop. [toTurned] and [fromTurned] convert to and from what the user sees.
 */
object Crop {
    /** The whole photo. */
    val WHOLE: FloatArray get() = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)

    // Anything smaller isn't a page: a slip of a finger, most likely.
    private const val MIN_AREA = 0.02f

    /** [quad], as stored, as it appears on the page turned clockwise by [degrees]. */
    fun toTurned(quad: FloatArray, degrees: Int): FloatArray {
        val k = turns(degrees)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val (x, y) = turnPoint(quad[2 * i], quad[2 * i + 1], k)
            // Turning clockwise moves each corner one place on: the top-left becomes the top-right.
            val j = (i + k) % 4
            out[2 * j] = x
            out[2 * j + 1] = y
        }
        return out
    }

    /** The reverse of [toTurned]: corners as seen on the turned page, back to the photo as stored. */
    fun fromTurned(quad: FloatArray, degrees: Int): FloatArray {
        val k = turns(degrees)
        val out = FloatArray(8)
        for (i in 0 until 4) {
            val j = (i + k) % 4
            val (u, v) = unturnPoint(quad[2 * j], quad[2 * j + 1], k)
            out[2 * i] = u
            out[2 * i + 1] = v
        }
        return out
    }

    private fun turns(degrees: Int) = ((degrees % 360 + 360) % 360) / 90

    private fun turnPoint(u: Float, v: Float, k: Int): Pair<Float, Float> = when (k) {
        1 -> 1 - v to u
        2 -> 1 - u to 1 - v
        3 -> v to 1 - u
        else -> u to v
    }

    private fun unturnPoint(x: Float, y: Float, k: Int): Pair<Float, Float> = when (k) {
        1 -> y to 1 - x
        2 -> 1 - x to 1 - y
        3 -> 1 - y to x
        else -> x to y
    }

    /**
     * Puts corners the user dragged back in order around the outline, starting at the top-left,
     * so two corners dragged past each other still make a sensible outline. Null when the
     * outline can't be a page: bent inwards, or too small.
     */
    fun tidy(quad: FloatArray): FloatArray? {
        if (quad.size != 8 || quad.any { it.isNaN() }) return null
        val cx = (quad[0] + quad[2] + quad[4] + quad[6]) / 4
        val cy = (quad[1] + quad[3] + quad[5] + quad[7]) / 4
        // By angle around the middle. With y pointing down this goes clockwise on screen.
        val order = (0 until 4).sortedBy { atan2(quad[2 * it + 1] - cy, quad[2 * it] - cx) }
        val start = order.indices.minBy { quad[2 * order[it]] + quad[2 * order[it] + 1] }
        val out = FloatArray(8)
        for (n in 0 until 4) {
            val i = order[(start + n) % 4]
            out[2 * n] = quad[2 * i].coerceIn(0f, 1f)
            out[2 * n + 1] = quad[2 * i + 1].coerceIn(0f, 1f)
        }
        return out.takeIf { convex(it) && area(it) >= MIN_AREA }
    }

    /** Every corner turns the same way. Scaling x and y doesn't change that, so fractions do. */
    private fun convex(q: FloatArray): Boolean {
        for (i in 0 until 4) {
            val a = i
            val b = (i + 1) % 4
            val c = (i + 2) % 4
            val cross = (q[2 * b] - q[2 * a]) * (q[2 * c + 1] - q[2 * b + 1]) -
                (q[2 * b + 1] - q[2 * a + 1]) * (q[2 * c] - q[2 * b])
            if (cross <= 1e-6f) return false
        }
        return true
    }

    /** How much of the photo the outline covers, from 0 to 1. */
    fun area(q: FloatArray): Float {
        var sum = 0f
        for (i in 0 until 4) {
            val j = (i + 1) % 4
            sum += q[2 * i] * q[2 * j + 1] - q[2 * j] * q[2 * i + 1]
        }
        return abs(sum) / 2
    }

    /** True when [quad] is the whole photo, give or take a hair. */
    fun isWhole(quad: FloatArray): Boolean {
        val whole = WHOLE
        return quad.indices.all { abs(quad[it] - whole[it]) < 0.002f }
    }

    /** The larger of the outline's width and height, as a fraction of the photo's. */
    fun span(quad: FloatArray): Float {
        val xs = floatArrayOf(quad[0], quad[2], quad[4], quad[6])
        val ys = floatArrayOf(quad[1], quad[3], quad[5], quad[7])
        return max(xs.max() - xs.min(), ys.max() - ys.min()).coerceIn(0.1f, 1f)
    }

    /** A short, exact-enough label for [quad], to tell one crop from another. */
    fun tag(quad: FloatArray?): String =
        quad?.joinToString(",") { (it * 10000).roundToInt().toString() } ?: ""

    /** Reads a stored crop; null for anything that isn't eight numbers from 0 to 1. */
    fun parse(values: List<Double>): FloatArray? {
        if (values.size != 8 || values.any { it.isNaN() }) return null
        return FloatArray(8) { min(1f, max(0f, values[it].toFloat())) }
    }
}
