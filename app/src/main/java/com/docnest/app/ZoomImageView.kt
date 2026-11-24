package com.docnest.app

import android.content.Context
import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.ViewConfiguration
import androidx.appcompat.widget.AppCompatImageView
import kotlin.math.abs

/**
 * A page to look at closely: pinch or double-tap to zoom, drag to look around, double-tap again
 * to see the whole page. At normal size a sideways swipe calls [onSwipe] (true for the next page).
 */
class ZoomImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : AppCompatImageView(context, attrs) {

    var onSwipe: ((next: Boolean) -> Unit)? = null

    val isZoomed get() = scale > 1.01f

    private val fit = Matrix()
    private val zoom = Matrix()
    private val shown = Matrix()
    private val bounds = RectF()
    private val box = RectF()
    private var scale = 1f
    private val minSwipe = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 8f

    // The image view's own constructor can set a picture before this class's fields are ready.
    private var ready = false

    private val scaler = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoomBy(d.scaleFactor, d.focusX, d.focusY)
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (isZoomed) showWhole() else zoomBy(MAX_ZOOM / 2 / scale, e.x, e.y)
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (!isZoomed) return false
            zoom.postTranslate(-dx, -dy)
            settle()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (isZoomed || abs(vx) < minSwipe || abs(vx) < abs(vy) * 1.5f) return false
            onSwipe?.invoke(vx < 0)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent) = performClick()
    })

    init {
        scaleType = ScaleType.MATRIX
        ready = true
    }

    override fun setImageDrawable(drawable: Drawable?) {
        super.setImageDrawable(drawable)
        fitImage()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitImage()
    }

    /** The whole picture, as large as fits, in the middle. */
    private fun fitImage() {
        if (!ready) return
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        bounds.set(0f, 0f, d.intrinsicWidth.toFloat(), d.intrinsicHeight.toFloat())
        fit.setRectToRect(bounds, RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.CENTER)
        showWhole()
    }

    private fun showWhole() {
        zoom.reset()
        scale = 1f
        show()
    }

    private fun zoomBy(factor: Float, x: Float, y: Float) {
        val next = (scale * factor).coerceIn(1f, MAX_ZOOM)
        val f = next / scale
        zoom.postScale(f, f, x, y)
        scale = next
        if (!isZoomed) zoom.reset()
        settle()
    }

    /** Keeps a zoomed picture covering the view, and centres it where it is smaller than the view. */
    private fun settle() {
        shown.set(fit)
        shown.postConcat(zoom)
        box.set(bounds)
        shown.mapRect(box)
        val dx = when {
            box.width() <= width -> width / 2f - box.centerX()
            box.left > 0 -> -box.left
            box.right < width -> width - box.right
            else -> 0f
        }
        val dy = when {
            box.height() <= height -> height / 2f - box.centerY()
            box.top > 0 -> -box.top
            box.bottom < height -> height - box.bottom
            else -> 0f
        }
        zoom.postTranslate(dx, dy)
        show()
    }

    private fun show() {
        shown.set(fit)
        shown.postConcat(zoom)
        imageMatrix = shown
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaler.onTouchEvent(e)
        if (!scaler.isInProgress) gestures.onTouchEvent(e)
        if (isZoomed) parent?.requestDisallowInterceptTouchEvent(true)
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private companion object {
        const val MAX_ZOOM = 5f
    }
}
