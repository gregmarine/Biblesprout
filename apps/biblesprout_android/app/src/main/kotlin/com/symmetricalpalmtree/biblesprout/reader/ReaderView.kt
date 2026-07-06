package com.symmetricalpalmtree.biblesprout.reader

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.StaticLayout
import android.util.AttributeSet
import android.util.TypedValue
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
 * for pagination. Highlighted word spans ([bodyHighlights], char ranges into the
 * body) are drawn as heavy underlines — a marker without color, for e-ink.
 */
class ReaderView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    var page: ReaderPage? = null
        set(value) {
            field = value
            invalidate()
        }

    /** Char ranges (`start until end`) into the body layout to underline. */
    var bodyHighlights: List<IntRange> = emptyList()
        set(value) {
            field = value
            invalidate()
        }

    var horizontalPad = 0
    var verticalPad = 0
    var gap1 = 0
    var gap2 = 0

    private val underlineThickness = dp(2.5f)
    private val underlineGap = dp(1.5f)
    private val underlinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ReaderTypography.BLACK
        style = Paint.Style.FILL
    }

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
        drawHighlights(canvas, p.body, y)
        canvas.restore()
    }

    private fun drawAt(canvas: Canvas, layout: StaticLayout, y: Int) {
        canvas.save()
        canvas.translate(0f, y.toFloat())
        layout.draw(canvas)
        canvas.restore()
    }

    /** Underlines each highlighted char range, one segment per wrapped line. */
    private fun drawHighlights(canvas: Canvas, body: StaticLayout, bodyY: Int) {
        if (bodyHighlights.isEmpty()) return
        for (range in bodyHighlights) {
            val start = range.first
            val endExclusive = range.last + 1
            if (endExclusive <= start) continue
            var line = body.getLineForOffset(start)
            val lastLine = body.getLineForOffset(endExclusive - 1)
            while (line <= lastLine) {
                val segStart = maxOf(start, body.getLineStart(line))
                val segEnd = minOf(endExclusive, body.getLineEnd(line))
                if (segEnd > segStart) {
                    val x1 = body.getPrimaryHorizontal(segStart)
                    val x2 = body.getPrimaryHorizontal(segEnd)
                    val top = bodyY + body.getLineBaseline(line) + underlineGap
                    canvas.drawRect(
                        minOf(x1, x2), top, maxOf(x1, x2), top + underlineThickness,
                        underlinePaint,
                    )
                }
                line++
            }
        }
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)
}
