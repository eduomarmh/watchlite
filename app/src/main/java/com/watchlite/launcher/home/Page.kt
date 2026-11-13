package com.watchlite.launcher.home

import android.animation.ValueAnimator
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import com.watchlite.launcher.util.Ui
import kotlin.math.abs

/**
 * A full-screen page that slides in from one side. It follows the finger both
 * when opening (driven by HomeActivity) and when closing (handled here).
 */
abstract class Page(val host: HomeActivity, val side: Int) : FrameLayout(host) {

    companion object {
        const val FROM_RIGHT = 0   // opened by finger moving left
        const val FROM_LEFT = 1    // opened by finger moving right
        const val FROM_TOP = 2     // opened by finger moving down
        const val FROM_BOTTOM = 3  // opened by finger moving up
    }

    val ui = host.ui
    var progress = 0f
        private set
    val isOpen get() = progress >= 0.999f
    val isPresent get() = progress > 0f
    private var anim: ValueAnimator? = null
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private val minFling = ViewConfiguration.get(context).scaledMinimumFlingVelocity * 3

    init {
        setBackgroundColor(Ui.BG)
        visibility = View.GONE
        isClickable = true
    }

    private val horizontal get() = side == FROM_RIGHT || side == FROM_LEFT
    private val extent get() = (if (horizontal) width else height).takeIf { it > 0 } ?: (if (horizontal) host.ui.sp.width else host.ui.sp.height)

    fun setProgress(p: Float) {
        progress = p.coerceIn(0f, 1f)
        val off = (1f - progress) * extent
        when (side) {
            FROM_RIGHT -> translationX = off
            FROM_LEFT -> translationX = -off
            FROM_TOP -> translationY = -off
            FROM_BOTTOM -> translationY = off
        }
        visibility = if (progress > 0f) View.VISIBLE else View.GONE
        host.onPageProgress(this, progress)
    }

    fun animateTo(target: Float, after: (() -> Unit)? = null) {
        anim?.cancel()
        val wasOpen = isOpen
        anim = ValueAnimator.ofFloat(progress, target).apply {
            duration = (220 * abs(target - progress)).toLong().coerceAtLeast(90)
            interpolator = DecelerateInterpolator(1.6f)
            addUpdateListener { setProgress(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (target >= 1f && !wasOpen) onOpened()
                    if (target <= 0f) onClosed()
                    after?.invoke()
                }
            })
            start()
        }
    }

    fun open() { if (!isOpen) { onOpening(); animateTo(1f) } }
    fun close(after: (() -> Unit)? = null) { if (isPresent) animateTo(0f, after) else after?.invoke() }

    open fun onOpening() {}
    open fun onOpened() {}
    open fun onClosed() {}
    /** Rotary wheel input. Return true if consumed. */
    open fun onWheel(detents: Int): Boolean = false
    /** Whether the content can still scroll in the closing direction (then we don't intercept). */
    open fun contentCanScroll(direction: Int): Boolean = false
    /** Horizontal panning content (bubble drawer): closing only once it reached its edge. */
    open fun contentCanScrollH(direction: Int): Boolean = false
    /** For FROM_TOP pages: closing only starts from the bottom handle area unless content is at its end. */
    open fun closeStartAllowed(ev: MotionEvent): Boolean = true

    // ------------------------------------------------------------ swipe to close
    private var downX = 0f; private var downY = 0f
    private var dragging = false
    private var vt: VelocityTracker? = null
    private var startAllowed = true

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY; dragging = false
                startAllowed = closeStartAllowed(ev)
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(ev)
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                if (startAllowed && closingMove(dx, dy) > slop) { dragging = true; host.cancelIdle(); return true }
            }
        }
        return false
    }

    /** Distance moved in the closing direction (positive) if that axis dominates. */
    private fun closingMove(dx: Float, dy: Float): Float = when (side) {
        FROM_RIGHT -> if (dx > 0 && abs(dx) > abs(dy) * 1.3f && !contentCanScrollH(-1)) dx else 0f
        FROM_LEFT -> if (dx < 0 && abs(dx) > abs(dy) * 1.3f && !contentCanScrollH(1)) -dx else 0f
        FROM_TOP -> if (dy < 0 && abs(dy) > abs(dx) * 1.3f && !contentCanScroll(1)) -dy else 0f
        else -> if (dy > 0 && abs(dy) > abs(dx) * 1.3f && !contentCanScroll(-1)) dy else 0f
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        vt?.addMovement(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; return true }
            MotionEvent.ACTION_MOVE -> {
                val dx = ev.rawX - downX; val dy = ev.rawY - downY
                val d = when (side) { FROM_RIGHT -> dx; FROM_LEFT -> -dx; FROM_TOP -> -dy; else -> dy }
                if (!dragging && d > slop && startAllowed) dragging = true
                if (dragging) setProgress(1f - d.coerceAtLeast(0f) / extent)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    vt?.computeCurrentVelocity(1000)
                    val v = when (side) {
                        FROM_RIGHT -> vt?.xVelocity ?: 0f; FROM_LEFT -> -(vt?.xVelocity ?: 0f)
                        FROM_TOP -> -(vt?.yVelocity ?: 0f); else -> vt?.yVelocity ?: 0f
                    }
                    if (progress < 0.65f || v > minFling) close() else animateTo(1f)
                }
                dragging = false
                vt?.recycle(); vt = null
                return true
            }
        }
        return true
    }
}
