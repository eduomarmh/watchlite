package com.watchlite.launcher.control

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.watchlite.launcher.util.Fonts
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Ui
import kotlin.math.roundToInt

/**
 * Pill-shaped slider (brightness / volume), drawn in one pass: no child views.
 * Drag anywhere on it; it claims the touch so the page doesn't treat it as a swipe.
 */
class SliderBar(ctx: Context, private val iconRes: Int, private val steps: Int,
                private val onChange: (Int) -> Unit, private val onRelease: (Int) -> Unit = {}) : View(ctx) {

    var value = 0
        set(v) { field = v.coerceIn(0, steps); invalidate() }
    var label: (Int) -> String = { "${it * 100 / steps.coerceAtLeast(1)}%" }
    var locked = false              // e.g. no permission: tap calls onLockedTap
    var onLockedTap: () -> Unit = {}

    private val track = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Fonts.uiBold(ctx); textAlign = Paint.Align.RIGHT }
    private val r = RectF()
    private val icon = ctx.getDrawable(iconRes)!!.mutate()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val rad = h / 2
        r.set(0f, 0f, w, h)
        track.color = Ui.CARD2
        c.drawRoundRect(r, rad, rad, track)
        val frac = value / steps.toFloat()
        if (frac > 0f) {
            c.save()
            c.clipRect(0f, 0f, maxOf(h, w * frac), h)
            fill.color = if (locked) Ui.TEXT2 else com.watchlite.launcher.App.prefs.accent
            c.drawRoundRect(r, rad, rad, fill)
            c.restore()
        }
        val isz = (h * 0.5f).toInt(); val ix = (h * 0.3f).toInt(); val iy = ((h - isz) / 2).toInt()
        val onFill = w * frac > ix + isz
        icon.setTint(if (onFill) Ui.BG else Ui.TEXT)
        icon.setBounds(ix, iy, ix + isz, iy + isz); icon.draw(c)
        text.textSize = h * 0.36f
        text.color = if (w * frac > w - h * 1.4f) Ui.BG else Ui.TEXT2
        c.drawText(label(value), w - h * 0.35f, h / 2 - (text.descent() + text.ascent()) / 2, text)
    }

    private var lastSent = -1

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (locked) { onLockedTap(); return false }
                update(e.x)
            }
            MotionEvent.ACTION_MOVE -> update(e.x)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onRelease(value)
            }
        }
        return true
    }

    private fun update(x: Float) {
        val v = ((x / width) * steps).roundToInt().coerceIn(0, steps)
        if (v != value) {
            value = v
            if (v != lastSent) { lastSent = v; onChange(v); Haptics.tick(context) }
        }
    }

    /** Wheel support: nudge by one step. */
    fun nudge(d: Int) { if (!locked) { value += d; onChange(value); onRelease(value) } }
}
