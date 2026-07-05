package com.symmetricalpalmtree.biblesprout.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.ViewConfiguration
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * A non-scrolling, page-flipping container for e-ink. Turns pages on a horizontal
 * swipe — recognised by drag distance or fling velocity, so slow swipes register
 * too — while letting taps fall through to child views (e.g. a book row). Page
 * turns are instant, no refresh flash. Mirrors the Flutter `PagedView` gesture.
 *
 * The host swaps the visible page's view in/out; this view only reports intent
 * via [onSwipeLeft] (advance) and [onSwipeRight] (go back).
 */
class SwipePager @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0,
) : FrameLayout(context, attrs, defStyle) {

    /** Next page (swipe leftwards). */
    var onSwipeLeft: (() -> Unit)? = null

    /** Previous page (swipe rightwards). */
    var onSwipeRight: (() -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val density = resources.displayMetrics.density
    private val distanceThreshold = 40f * density // logical px of travel to turn
    private val velocityThreshold = 250f * density // or a faster fling, px/s

    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var tracker: VelocityTracker? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.x - downX
                val dy = ev.y - downY
                // Steal the gesture only once it's clearly a horizontal drag, so
                // taps on book rows still reach them.
                if (!dragging && abs(dx) > touchSlop && abs(dx) > abs(dy)) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val vt = tracker ?: VelocityTracker.obtain().also { tracker = it }
        vt.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x
                downY = ev.y
                return true
            }
            MotionEvent.ACTION_UP -> {
                vt.computeCurrentVelocity(1000)
                val dx = ev.x - downX
                val vx = vt.xVelocity
                if (dx <= -distanceThreshold || vx <= -velocityThreshold) {
                    onSwipeLeft?.invoke()
                } else if (dx >= distanceThreshold || vx >= velocityThreshold) {
                    onSwipeRight?.invoke()
                }
                endGesture()
            }
            MotionEvent.ACTION_CANCEL -> endGesture()
        }
        return true
    }

    private fun endGesture() {
        dragging = false
        tracker?.recycle()
        tracker = null
    }
}
