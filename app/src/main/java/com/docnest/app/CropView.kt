package com.docnest.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.hypot
import kotlin.math.min

/**
 * A photo with a four-cornered outline over it. Drag a corner to move it; while it moves, a
 * magnifier in a top corner shows it close up. [quad] holds the corners as fractions of the
 * photo's width and height, in the order top-left, top-right, bottom-right, bottom-left.
 */
class CropView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    var bitmap: Bitmap? = null
        set(v) {
            field = v
            placeImage()
            invalidate()
        }

    /** The outline's corners. Read them freely; change them with [setQuad]. */
    val quad = Crop.WHOLE

    /** Called while a corner moves, and when [setQuad] changes the outline. */
    var onChange: (() -> Unit)? = null

    fun setQuad(q: FloatArray) {
        q.copyInto(quad)
        invalidate()
        onChange?.invoke()
    }

    private val dp = resources.displayMetrics.density
    private val margin = 28 * dp // room around the photo, so corner handles at its edge can be reached
    private val reach = 44 * dp // how far from a corner a touch still picks it up
    private val image = RectF()
    private var active = -1
    private var grabX = 0f
    private var grabY = 0f

    private val accent = 0xFF0A7453.toInt()
    private val photoPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val shade = Paint().apply { color = 0x99000000.toInt() }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        color = Color.WHITE
    }
    private val thinEdge = Paint(edge)
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = accent
    }
    private val loupeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        color = Color.WHITE
    }
    private val cross = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        color = accent
    }
    private val outline = Path()
    private val shadeShape = Path()
    private val loupeClip = Path()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        placeImage()
    }

    /** Fits the photo in the view, with [margin] all round. */
    private fun placeImage() {
        val b = bitmap ?: return
        val aw = width - 2 * margin
        val ah = height - 2 * margin
        if (aw <= 0 || ah <= 0) return
        val s = min(aw / b.width, ah / b.height)
        val iw = b.width * s
        val ih = b.height * s
        image.set((width - iw) / 2, (height - ih) / 2, (width + iw) / 2, (height + ih) / 2)
    }

    private fun cx(i: Int) = image.left + quad[2 * i] * image.width()
    private fun cy(i: Int) = image.top + quad[2 * i + 1] * image.height()

    override fun onDraw(canvas: Canvas) {
        val b = bitmap ?: return
        if (image.isEmpty) placeImage()
        canvas.drawBitmap(b, null, image, photoPaint)
        drawOutline(canvas, edge)
        for (i in 0 until 4) {
            val r = (if (i == active) 13 else 10) * dp
            canvas.drawCircle(cx(i), cy(i), r, handleFill)
            canvas.drawCircle(cx(i), cy(i), r, handleRing)
        }
        if (active >= 0) drawLoupe(canvas, b, active)
    }

    /** Darkens what is cut away, and draws the outline. */
    private fun drawOutline(canvas: Canvas, line: Paint) {
        outline.reset()
        outline.moveTo(cx(0), cy(0))
        for (i in 1 until 4) outline.lineTo(cx(i), cy(i))
        outline.close()
        shadeShape.reset()
        shadeShape.fillType = Path.FillType.EVEN_ODD
        shadeShape.addRect(image, Path.Direction.CW)
        shadeShape.addPath(outline)
        canvas.drawPath(shadeShape, shade)
        canvas.drawPath(outline, line)
    }

    /** The corner being dragged, 2.5 times larger, in the top corner away from it. */
    private fun drawLoupe(canvas: Canvas, b: Bitmap, i: Int) {
        val r = 60 * dp
        val zoom = 2.5f
        val px = cx(i)
        val py = cy(i)
        val x = if (px < width / 2f) width - r - 12 * dp else r + 12 * dp
        val y = r + 12 * dp
        canvas.save()
        loupeClip.reset()
        loupeClip.addCircle(x, y, r, Path.Direction.CW)
        canvas.clipPath(loupeClip)
        canvas.drawColor(Color.BLACK)
        canvas.translate(x, y)
        canvas.scale(zoom, zoom)
        canvas.translate(-px, -py)
        canvas.drawBitmap(b, null, image, photoPaint)
        thinEdge.strokeWidth = 2 * dp / zoom
        drawOutline(canvas, thinEdge)
        canvas.restore()
        canvas.drawCircle(x, y, r, loupeRing)
        canvas.drawLine(x - 10 * dp, y, x + 10 * dp, y, cross)
        canvas.drawLine(x, y - 10 * dp, x, y + 10 * dp, cross)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (bitmap == null || image.isEmpty) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                active = nearest(e.x, e.y)
                if (active < 0) return false
                // The corner keeps its distance from the finger, so it doesn't jump under it.
                grabX = cx(active) - e.x
                grabY = cy(active) - e.y
                parent?.requestDisallowInterceptTouchEvent(true)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (active >= 0) {
                quad[2 * active] = ((e.x + grabX - image.left) / image.width()).coerceIn(0f, 1f)
                quad[2 * active + 1] = ((e.y + grabY - image.top) / image.height()).coerceIn(0f, 1f)
                invalidate()
                onChange?.invoke()
                return true
            }
            MotionEvent.ACTION_UP -> if (active >= 0) {
                active = -1
                invalidate()
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                active = -1
                invalidate()
                return true
            }
        }
        return active >= 0
    }

    override fun performClick(): Boolean = super.performClick()

    private fun nearest(x: Float, y: Float): Int {
        var best = -1
        var bestDistance = reach
        for (i in 0 until 4) {
            val d = hypot(cx(i) - x, cy(i) - y)
            if (d <= bestDistance) {
                best = i
                bestDistance = d
            }
        }
        return best
    }
}
