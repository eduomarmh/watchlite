package com.watchlite.launcher.drawer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.text.TextPaint
import android.text.TextUtils
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import com.watchlite.launcher.App
import com.watchlite.launcher.util.Fonts
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.ScreenProfile
import com.watchlite.launcher.util.Ui
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * One custom view drawing three layouts. No RecyclerView/inflation: cheap on RAM.
 *  honeycomb: hex grid with fisheye shrink at the edges (Apple-Watch style)
 *  grid:      square grid, labels under icons when large
 *  list:      icon + label rows
 */
class AppDrawerView(ctx: Context, private val sp: ScreenProfile) : View(ctx) {

    var onLaunch: ((AppEntry, View, RectF) -> Unit)? = null
    var onLongPress: ((AppEntry) -> Unit)? = null

    private var apps: List<AppEntry> = emptyList()
    private val scroller = OverScroller(ctx)
    private var scrollPos = 0f
    private var contentHeight = 0f
    private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CARD }
    private val labelPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Fonts.ui(ctx) }
    private val favPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val matrix = Matrix()
    private val shaders = HashMap<Bitmap, BitmapShader>()

    // computed layout: center x/y in content coordinates and base radius
    private var cx = FloatArray(0)
    private var cy = FloatArray(0)
    private var cellR = 0f
    private var rowH = 0f

    private val mode get() = App.prefs.drawerMode
    private val zoom get() = App.prefs.drawerZoom.coerceIn(0, 4)

    fun setApps(list: List<AppEntry>) { apps = list; relayout(); invalidate() }

    fun zoomBy(d: Int): Boolean {
        val z = (zoom + d).coerceIn(0, 4)
        if (z == zoom) return false
        // keep the item at the center stable
        val frac = if (contentHeight > 0) (scrollPos + height / 2f) / contentHeight else 0f
        App.prefs.drawerZoom = z
        relayout()
        scrollPos = (frac * contentHeight - height / 2f).coerceIn(0f, maxScroll())
        Haptics.tick(context)
        invalidate()
        return true
    }

    fun scrollByDetents(d: Int) {
        scroller.forceFinished(true)
        scrollPos = (scrollPos + d * rowH * 0.6f).coerceIn(0f, maxScroll())
        invalidate()
    }

    fun resetScroll() { scrollPos = 0f; scroller.forceFinished(true) }

    override fun canScrollVertically(direction: Int): Boolean =
        if (direction < 0) scrollPos > 0.5f else scrollPos < maxScroll() - 0.5f

    private fun maxScroll() = max(0f, contentHeight - height)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { relayout() }

    private fun relayout() {
        val w = width.takeIf { it > 0 } ?: return
        val h = height
        val n = apps.size
        cx = FloatArray(n); cy = FloatArray(n)
        val pad = sp.safe.toFloat()
        when (mode) {
            "list" -> {
                rowH = sp.shortSide * floatArrayOf(0.16f, 0.19f, 0.22f, 0.26f, 0.3f)[zoom]
                cellR = rowH * 0.36f
                for (i in 0 until n) { cx[i] = pad + cellR + sp.u * 3; cy[i] = h * 0.12f + rowH * i + rowH / 2 }
                contentHeight = h * 0.12f + rowH * n + h * 0.15f
            }
            "grid" -> {
                val cols = intArrayOf(5, 4, 3, 3, 2)[zoom]
                val cellW = (w - pad * 2) / cols
                val labels = cols <= 3
                cellR = cellW * (if (labels) 0.34f else 0.42f)
                rowH = cellW + if (labels) sp.u * 6 else 0f
                for (i in 0 until n) {
                    cx[i] = pad + cellW * (i % cols) + cellW / 2
                    cy[i] = h * 0.1f + rowH * (i / cols) + cellW / 2
                }
                contentHeight = h * 0.1f + rowH * ((n + cols - 1) / cols) + h * 0.12f
            }
            else -> { // honeycomb
                val cols = intArrayOf(5, 4, 4, 3, 3)[zoom]
                val spacing = w / (cols - 0.1f)
                cellR = spacing * 0.43f
                rowH = spacing * 0.866f
                // rows alternate cols and cols-1 items
                var i = 0; var row = 0
                while (i < n) {
                    val inRow = if (row % 2 == 0) cols else cols - 1
                    val rowW = (inRow - 1) * spacing
                    for (k in 0 until inRow) {
                        if (i >= n) break
                        cx[i] = w / 2f - rowW / 2f + k * spacing
                        cy[i] = h * 0.5f - rowH * 0.5f + row * rowH
                        i++
                    }
                    row++
                }
                contentHeight = h * 0.5f - rowH * 0.5f + row * rowH + h * 0.5f - rowH * 0.5f
            }
        }
        App.I.apps.requestIconSize((cellR * 2).toInt())
        scrollPos = scrollPos.coerceIn(0f, maxScroll())
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scrollPos = scroller.currY.toFloat(); postInvalidateOnAnimation() }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val fav = App.prefs.favoriteApps
        val honey = mode == "honeycomb"
        val list = mode == "list"
        val showLabels = list || (mode == "grid" && intArrayOf(5, 4, 3, 3, 2)[zoom] <= 3)
        labelPaint.textSize = if (list) rowH * 0.3f else sp.u * 4.6f
        for (i in apps.indices) {
            val x = cx[i]; val y = cy[i] - scrollPos
            if (y < -cellR * 2 - rowH || y > h + cellR * 2 + rowH) continue
            var scale = 1f
            if (honey) {
                // fisheye: shrink toward the edges (and the screen corners on round watches)
                val dx = (x - w / 2) / (w / 2); val dy = (y - h / 2) / (h / 2)
                val d = if (sp.isRound) hypot(dx, dy) else max(abs(dx) * 0.9f, abs(dy))
                scale = (1.15f - max(0f, d - 0.45f) * 1.2f).coerceIn(0.25f, 1f)
            }
            val r = cellR * scale
            drawIcon(c, apps[i], x, y, r)
            if (apps[i].key in fav && !honey) {
                favPaint.color = App.prefs.accent
                c.drawCircle(x + r * 0.78f, y - r * 0.78f, r * 0.13f, favPaint)
            }
            if (showLabels) {
                val avail = if (list) w - x - cellR - sp.safe - sp.u * 4 else rowH * 1.05f
                val label = TextUtils.ellipsize(apps[i].label, labelPaint, avail, TextUtils.TruncateAt.END).toString()
                if (list) {
                    val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
                    labelPaint.textAlign = if (rtl) Paint.Align.RIGHT else Paint.Align.LEFT
                    val lx = if (rtl) w - sp.safe.toFloat() else x + cellR + sp.u * 4
                    c.drawText(label, lx, y + labelPaint.textSize * 0.35f, labelPaint)
                } else {
                    labelPaint.textAlign = Paint.Align.CENTER
                    c.drawText(label, x, y + cellR + sp.u * 5f, labelPaint)
                }
            }
        }
    }

    private fun drawIcon(c: Canvas, e: AppEntry, x: Float, y: Float, r: Float) {
        val b = e.icon
        if (b == null || b.isRecycled) { c.drawCircle(x, y, r, bgPaint); return }
        // circular crop through a shader: uniform round icons like a watch
        val sh = shaders.getOrPut(b) { BitmapShader(b, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        val s = (r * 2.3f) / b.width
        matrix.setScale(s, s)
        matrix.postTranslate(x - b.width * s / 2, y - b.height * s / 2)
        sh.setLocalMatrix(matrix)
        c.drawCircle(x, y, r, bgPaint)
        iconPaint.shader = sh
        c.drawCircle(x, y, r, iconPaint)
        iconPaint.shader = null
        if (shaders.size > 300) shaders.clear()
    }

    private fun hit(x: Float, y: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in apps.indices) {
            val d = hypot(cx[i] - x, cy[i] - scrollPos - y)
            if (d < bestD) { bestD = d; best = i }
        }
        val limit = if (mode == "list") rowH else cellR * 1.15f
        if (mode == "list" && best >= 0) return if (abs(cy[best] - scrollPos - y) < rowH / 2) best else -1
        return if (bestD <= limit) best else -1
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            scrollPos = (scrollPos + dy).coerceIn(0f, maxScroll()); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            scroller.fling(0, scrollPos.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt(), 0, (height * 0.08f).toInt())
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = hit(e.x, e.y); if (i < 0) return false
            val r = cellR
            val loc = IntArray(2); getLocationOnScreen(loc)
            val rr = RectF(cx[i] - r + loc[0], cy[i] - scrollPos - r + loc[1], cx[i] + r + loc[0], cy[i] - scrollPos + r + loc[1])
            Haptics.tick(context)
            onLaunch?.invoke(apps[i], this@AppDrawerView, rr)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = hit(e.x, e.y); if (i >= 0) { Haptics.tick(context, true); onLongPress?.invoke(apps[i]) }
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean = gd.onTouchEvent(ev) || super.onTouchEvent(ev)

}
