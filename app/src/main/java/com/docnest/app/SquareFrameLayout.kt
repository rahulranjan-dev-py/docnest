package com.docnest.app

import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout

/** A FrameLayout as tall as it is wide, for the photo thumbnails. */
class SquareFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, widthMeasureSpec)
    }
}
