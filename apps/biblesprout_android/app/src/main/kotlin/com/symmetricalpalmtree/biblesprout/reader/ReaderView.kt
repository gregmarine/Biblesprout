package com.symmetricalpalmtree.biblesprout.reader

import android.content.Context
import android.graphics.Canvas
import android.text.StaticLayout
import android.util.AttributeSet
import android.view.View

/** One rendered reader page: body text plus, on a chapter's first page, its heading. */
class ReaderPage(
    val body: StaticLayout,
    val title: StaticLayout? = null,
    val number: StaticLayout? = null,
)

/**
 * Draws a [ReaderPage] with the reader's padding. Drawing the same [StaticLayout]
 * the paginator measured guarantees the page fits exactly. The activity supplies
 * padding/gaps from [ReaderTypography] and reads [readingWidth]/[readingHeight]
 * for pagination.
 */
class ReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var page: ReaderPage? = null
        set(value) {
            field = value
            invalidate()
        }

    var horizontalPad = 0
    var verticalPad = 0
    var gap1 = 0
    var gap2 = 0

    fun readingWidth(): Int = width - horizontalPad * 2
    fun readingHeight(): Int = height - verticalPad * 2

    override fun onDraw(canvas: Canvas) {
        val p = page ?: return
        canvas.save()
        canvas.translate(horizontalPad.toFloat(), verticalPad.toFloat())
        var y = 0
        if (p.title != null && p.number != null) {
            drawAt(canvas, p.title, y)
            y += p.title.height + gap1
            drawAt(canvas, p.number, y)
            y += p.number.height + gap2
        }
        drawAt(canvas, p.body, y)
        canvas.restore()
    }

    private fun drawAt(canvas: Canvas, layout: StaticLayout, y: Int) {
        canvas.save()
        canvas.translate(0f, y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }
}
