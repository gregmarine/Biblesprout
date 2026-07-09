package com.symmetricalpalmtree.biblesprout.notes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.Rect
import android.util.AttributeSet
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import com.onyx.android.sdk.api.device.epd.EpdController
import com.onyx.android.sdk.api.device.epd.UpdateMode
import com.onyx.android.sdk.data.note.TouchPoint
import com.onyx.android.sdk.pen.RawInputCallback
import com.onyx.android.sdk.pen.TouchHelper
import com.onyx.android.sdk.pen.data.TouchPointList

/**
 * A full-screen handwriting surface backed by the Onyx BOOX pen pipeline
 * (`TouchHelper` raw drawing → `EpdController`). The SDK renders live ink straight
 * to the panel; on pen-lift the completed stroke arrives in
 * [RawInputCallback.onRawDrawingTouchPointListReceived], where we keep it and redraw
 * the committed layer so the Android canvas matches. A toolbar/pagination band is
 * carved out of the capture area via the limit rect ([topInsetPx]/[bottomInsetPx]),
 * so the view can sit full-screen behind those bars without the pen writing under
 * them. The single raw-drawing pipeline is a process-global resource (see [penOwner]).
 *
 * The Onyx pattern here is distilled from notesprout_android's OnyxNotebookView;
 * the stroke storage is Biblesprout's own.
 */
class NoteCanvasView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {

    enum class Tool { PEN, ERASER }

    /** Bands (in px) at the top/bottom reserved for the toolbar and pagination. */
    var topInsetPx = 0
    var bottomInsetPx = 0

    /** Fired on the main thread when a stroke is committed (for persistence). */
    var onStrokeAdded: ((InkStroke) -> Unit)? = null

    /** Fired when strokes are erased (for deletion from storage). */
    var onStrokesErased: ((List<InkStroke>) -> Unit)? = null

    private val strokes = ArrayList<InkStroke>()
    private var tool = Tool.PEN
    private var setup = false
    private var hasFocusNow = false

    val penWidth = dp(3f)
    private val eraserRadius = dp(16f)

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = penWidth
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        style = Paint.Style.FILL
    }
    private val path = Path()

    private val touchHelper: TouchHelper by lazy { TouchHelper.create(this, callback) }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Replaces the committed strokes (e.g. on a page switch) and repaints. */
    fun setStrokes(list: List<InkStroke>) {
        strokes.clear()
        strokes.addAll(list)
        commitToPanel()
    }

    fun setTool(newTool: Tool) {
        tool = newTool
        if (!setup) return
        // Re-enabling the pipeline restores live-ink render to its default-on state
        // (toggling the render flag off→on would drop the next stroke); only the
        // eraser turns render off so the tip lifts ink instead of leaving it.
        touchHelper.setRawDrawingEnabled(true)
        if (newTool == Tool.ERASER) touchHelper.setRawDrawingRenderEnabled(false)
    }

    /** Re-claim the pen pipeline (call from the activity's onResume). */
    fun resumeDrawing() {
        hasFocusNow = true
        maybeOpen()
    }

    /** Release the pen pipeline (call from the activity's onPause). */
    fun pauseDrawing() {
        hasFocusNow = false
        closeIfOwner()
    }

    // ── Rendering ─────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(Color.WHITE)
        for (stroke in strokes) {
            val pts = stroke.points
            if (pts.size < 2) continue
            if (pts.size == 2) {
                dotPaint.color = stroke.color
                canvas.drawCircle(pts[0], pts[1], stroke.width / 2f, dotPaint)
                continue
            }
            paint.color = stroke.color
            paint.strokeWidth = stroke.width
            path.rewind()
            path.moveTo(pts[0], pts[1])
            var i = 2
            while (i < pts.size) {
                path.lineTo(pts[i], pts[i + 1])
                i += 2
            }
            canvas.drawPath(path, paint)
        }
    }

    // The Onyx integration requires MotionEvents to be fed to the TouchHelper (both
    // BOOXDemo and notesprout do this). Without it the hardware still captures strokes
    // but the render surface isn't primed, so the first stroke after each (re)arm never
    // renders live — it only appears on pen-lift via the committed layer. Finger events
    // are consumed by the activity's swipe listener before they reach here.
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean =
        if (setup) touchHelper.onTouchEvent(event) else super.onTouchEvent(event)

    // ── Raw input ─────────────────────────────────────────────────────────────

    private val callback = object : RawInputCallback() {
        // Intentionally empty: render is left on by default for the pen (see
        // [openRawDrawing]); toggling the render flag here would drop the current stroke.
        override fun onBeginRawDrawing(shortcut: Boolean, point: TouchPoint) {}

        override fun onEndRawDrawing(shortcut: Boolean, point: TouchPoint) {
            if (tool == Tool.ERASER) commitToPanel()
        }

        override fun onRawDrawingTouchPointMoveReceived(point: TouchPoint) {
            if (tool == Tool.ERASER) eraseAt(listOf(PointF(point.x, point.y)))
        }

        override fun onRawDrawingTouchPointListReceived(list: TouchPointList) {
            if (tool == Tool.ERASER) eraseAt(list.toPointFs()) else addStroke(list)
        }

        // Dedicated stylus eraser end / button routes through the erasing callbacks.
        override fun onBeginRawErasing(shortcut: Boolean, point: TouchPoint) {
            if (setup) touchHelper.setRawDrawingRenderEnabled(false)
        }

        override fun onEndRawErasing(shortcut: Boolean, point: TouchPoint) {
            commitToPanel()
        }

        override fun onRawErasingTouchPointMoveReceived(point: TouchPoint) {
            eraseAt(listOf(PointF(point.x, point.y)))
        }

        override fun onRawErasingTouchPointListReceived(list: TouchPointList) {
            eraseAt(list.toPointFs())
        }
    }

    private fun TouchPointList.toPointFs(): List<PointF> =
        points?.map { PointF(it.x, it.y) } ?: emptyList()

    private fun addStroke(list: TouchPointList) {
        val pts = list.points ?: return
        if (pts.isEmpty()) return
        val arr = FloatArray(pts.size * 2)
        pts.forEachIndexed { i, tp ->
            arr[i * 2] = tp.x
            arr[i * 2 + 1] = tp.y
        }
        val stroke = InkStroke(0, arr, penWidth, Color.BLACK)
        strokes.add(stroke)
        onStrokeAdded?.invoke(stroke)
        invalidate()
    }

    private fun eraseAt(erasePoints: List<PointF>) {
        if (strokes.isEmpty() || erasePoints.isEmpty()) return
        val r2 = eraserRadius * eraserRadius
        val removed = strokes.filter { stroke -> stroke.isHitBy(erasePoints, r2) }
        if (removed.isNotEmpty()) {
            strokes.removeAll(removed)
            onStrokesErased?.invoke(removed)
            invalidate()
        }
    }

    private fun InkStroke.isHitBy(erasePoints: List<PointF>, r2: Float): Boolean {
        var i = 0
        while (i < points.size) {
            val x = points[i]
            val y = points[i + 1]
            for (p in erasePoints) {
                val dx = p.x - x
                val dy = p.y - y
                if (dx * dx + dy * dy <= r2) return true
            }
            i += 2
        }
        return false
    }

    /**
     * Push the committed-stroke layer ([onDraw]) to the e-ink panel — for page
     * turns, page loads, and after an erase. While the pen pipeline is live it owns
     * the drawing region: the SDK's overlay shows live ink and a plain [invalidate]
     * never reaches the panel, so a page turn would stay invisible.
     *
     * This mirrors notesprout's loadStrokesWithBitmap exactly, and the sequence is
     * load-bearing: only the *render* flag is toggled off (which pauses painting but
     * keeps the pen's render surface warm), then [EpdController.handwritingRepaint]
     * pushes the committed layer, then [TouchHelper.setRawDrawingEnabled]`(true)`
     * restores render implicitly. We must NOT disable the whole pipeline
     * (`setRawDrawingEnabled(false)`) nor explicitly re-enable render here — either
     * one cold-restarts the render surface, and the first stroke after that lands
     * before the surface wakes, so it isn't painted live (it only appears on lift).
     */
    private fun commitToPanel() {
        if (setup) touchHelper.setRawDrawingRenderEnabled(false)
        invalidate()
        post {
            if (width > 0 && height > 0) {
                EpdController.handwritingRepaint(this, Rect(0, 0, width, height))
            }
            post {
                if (setup && hasFocusNow) {
                    touchHelper.setRawDrawingEnabled(true)
                    if (tool == Tool.ERASER) touchHelper.setRawDrawingRenderEnabled(false)
                }
            }
        }
    }

    // ── Pen-pipeline lifecycle ────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        maybeOpen()
    }

    override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
        super.onWindowFocusChanged(hasWindowFocus)
        hasFocusNow = hasWindowFocus
        if (hasWindowFocus) maybeOpen() else closeIfOwner()
    }

    override fun onDetachedFromWindow() {
        closeIfOwner()
        super.onDetachedFromWindow()
    }

    private fun maybeOpen() {
        if (!hasFocusNow || width <= 0 || height <= 0) return
        openRawDrawing()
    }

    private fun openRawDrawing() {
        applyLimitRect()
        if (!setup) {
            touchHelper.setStrokeWidth(penWidth).setStrokeColor(Color.BLACK).openRawDrawing()
            setup = true
        } else {
            touchHelper.restartRawDrawing()
        }
        penOwner = this
        touchHelper.setRawDrawingEnabled(true)
        // Do NOT explicitly enable render for the pen — setRawDrawingEnabled(true)
        // leaves it on by default, and a render off→on toggle drops the first stroke's
        // live ink (the SDK primes the render surface lazily). Only the eraser disables it.
        if (tool == Tool.ERASER) touchHelper.setRawDrawingRenderEnabled(false)
        EpdController.setUpdListSize(EPD_UPDATE_LIST_SIZE)
        applyHandwritingFastMode()
    }

    private fun applyLimitRect() {
        // Give the pen pipeline the *full* view as its limit rect — the Onyx
        // real-time render fast-path wants the whole drawing surface; a shrunk
        // sub-region limit makes it fall back to painting only on pen-lift. The
        // toolbar/pagination bands are kept unwritable via exclude rects instead.
        val frame = Rect()
        getWindowVisibleDisplayFrame(frame)
        val loc = IntArray(2)
        getLocationOnScreen(loc)
        val limit = Rect(
            maxOf(0, frame.left - loc[0]),
            maxOf(0, frame.top - loc[1]),
            minOf(width, frame.right - loc[0]),
            minOf(height, frame.bottom - loc[1]),
        )
        // A non-empty exclude list is required: the SDK treats an empty list as a
        // no-op and keeps any previously persisted exclusion.
        val excludes = ArrayList<Rect>(2)
        if (topInsetPx > 0) excludes.add(Rect(0, 0, width, topInsetPx))
        if (bottomInsetPx > 0) excludes.add(Rect(0, height - bottomInsetPx, width, height))
        if (excludes.isEmpty()) excludes.add(Rect(-1, -1, 0, 0))
        touchHelper.setLimitRect(limit, excludes)
    }

    private fun closeIfOwner() {
        if (penOwner === this && setup) {
            touchHelper.setRawDrawingEnabled(false)
            touchHelper.closeRawDrawing()
            penOwner = null
            // Drop the handwriting fast-mode so the rest of the app (menus, dialogs,
            // other screens) renders in normal quality; a new owner re-applies it.
            EpdController.clearAppScopeUpdate()
        }
    }

    /**
     * Pin the app's default panel update mode to the fast handwriting waveform while the
     * pen pipeline is live, via [EpdController.applyAppScopeUpdate]. Without this the first
     * stroke after an open / page-flip pays a GC→handwriting mode switch (a 1–2s warm-up
     * lag on the BOOX). This is the Notesprout "AppScope-HWR" fix: a device sweep there
     * proved scribble / view-mode / system-fast all still lagged — only app-scope was
     * instant with no ghosting. Idempotent: re-applying the same tag is a panel no-op.
     * Cleared in [closeIfOwner] when we relinquish the pipeline.
     */
    private fun applyHandwritingFastMode() {
        EpdController.applyAppScopeUpdate(
            HWR_APP_SCOPE, true, false, UpdateMode.HAND_WRITING_REPAINT_MODE, 0,
        )
    }

    private fun dp(value: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, resources.displayMetrics)

    companion object {
        private const val EPD_UPDATE_LIST_SIZE = 512

        // App-scope tag pinning the panel into the fast handwriting waveform (see
        // [applyHandwritingFastMode]) — the Notesprout first-stroke fix, ported here.
        private const val HWR_APP_SCOPE = "biblesprout_hwr"

        // The Onyx raw-drawing pipeline is a single process-global hardware
        // resource; only the current owner may close it, so a screen we've left
        // can't tear down the pipeline a new screen just claimed.
        @Volatile
        private var penOwner: NoteCanvasView? = null
    }
}
