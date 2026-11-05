package com.docnest.app

import android.content.Context
import android.util.AttributeSet
import android.view.View
import android.widget.FrameLayout

/** A FrameLayout shaped like a portrait page (3 wide, 4 tall), for the page thumbnails. */
class PageFrameLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : FrameLayout(context, attrs) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = View.MeasureSpec.makeMeasureSpec(width * 4 / 3, View.MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, height)
    }
}
