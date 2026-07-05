package com.symmetricalpalmtree.biblesprout.reader

import android.content.Context
import android.graphics.Canvas
import android.text.Layout
import android.util.AttributeSet
import android.view.View

/** One stacked element on a flowing page: a text layout with padding above/below. */
class FlowElement(val layout: Layout, val topPad: Int = 0, val bottomPad: Int = 0)

/**
 * Draws a flowing page — a vertical stack of [FlowElement]s (passage headings and
 * body blocks) — with the reader's padding. Like [ReaderView], drawing the same
 * StaticLayouts the paginator measured guarantees the page fits.
 */
class FlowingView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var elements: List<FlowElement> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var horizontalPad = 0
    var verticalPad = 0

    fun readingWidth(): Int = width - horizontalPad * 2
    fun readingHeight(): Int = height - verticalPad * 2

    override fun onDraw(canvas: Canvas) {
        canvas.save()
        canvas.translate(horizontalPad.toFloat(), verticalPad.toFloat())
        var y = 0
        for (element in elements) {
            y += element.topPad
            canvas.save()
            canvas.translate(0f, y.toFloat())
            element.layout.draw(canvas)
            canvas.restore()
            y += element.layout.height + element.bottomPad
        }
        canvas.restore()
    }
}
