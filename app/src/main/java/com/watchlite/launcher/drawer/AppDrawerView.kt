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
    private var panY = 0f
    private var panX = 0f
    private var minX = 0f; private var maxX = 0f; private var minY = 0f
    private val bubbles get() = mode == "bubbles"
    private val scaleDetector = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            if (!bubbles) return false
            App.prefs.bubbleScale = (App.prefs.bubbleScale * d.scaleFactor).coerceIn(0.6f, 1.9f)
            relayout(); invalidate(); return true
        }
    })
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
        if (bubbles) {
            val old = App.prefs.bubbleScale
            val nz = (old * Math.pow(1.12, d.toDouble()).toFloat()).coerceIn(0.6f, 1.9f)
            if (nz == old) return false
            App.prefs.bubbleScale = nz
            panX *= nz / old; panY *= nz / old
            relayout(); Haptics.tick(context); invalidate(); return true
        }
        val z = (zoom + d).coerceIn(0, 4)
        if (z == zoom) return false
        // keep the item at the center stable
        val frac = if (contentHeight > 0) (panY + height / 2f) / contentHeight else 0f
        App.prefs.drawerZoom = z
        relayout()
        panY = (frac * contentHeight - height / 2f).coerceIn(0f, maxScroll())
        Haptics.tick(context)
        invalidate()
        return true
    }

    fun scrollByDetents(d: Int) {
        scroller.forceFinished(true)
        panY = (panY + d * rowH * 0.6f).coerceIn(0f, maxScroll())
        invalidate()
    }

    fun resetScroll() { panY = 0f; panX = 0f; scroller.forceFinished(true) }

    override fun canScrollHorizontally(direction: Int): Boolean =
        bubbles && (if (direction < 0) panX > minX + 0.5f else panX < maxX - 0.5f)

    override fun canScrollVertically(direction: Int): Boolean =
        if (direction < 0) panY > minY + 0.5f else panY < maxScroll() - 0.5f

    private var bubbleMaxY = 0f
    private fun maxScroll() = if (bubbles) bubbleMaxY else max(0f, contentHeight - height)

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { relayout() }

    private fun relayout() {
        val w = width.takeIf { it > 0 } ?: return
        val h = height
        val n = apps.size
        cx = FloatArray(n); cy = FloatArray(n)
        val pad = sp.safe.toFloat()
        minX = 0f; maxX = 0f; minY = 0f
        when (mode) {
            "bubbles" -> {
                // Apple-Watch style: rings of icons around the centre, pannable in every direction
                val spacing = sp.shortSide * 0.30f * App.prefs.bubbleScale
                cellR = spacing * 0.45f; rowH = spacing
                val dirs = arrayOf(intArrayOf(1, 0), intArrayOf(1, -1), intArrayOf(0, -1), intArrayOf(-1, 0), intArrayOf(-1, 1), intArrayOf(0, 1))
                var i = 0; var k = 0
                fun put(q: Int, r: Int) {
                    if (i >= n) return
                    cx[i] = w / 2f + spacing * (q + r / 2f); cy[i] = h / 2f + spacing * 0.866f * r; i++
                }
                put(0, 0)
                while (i < n) {
                    k++
                    var q = -k; var r = k        // start corner of ring k
                    for (d in 0 until 6) for (step in 0 until k) { put(q, r); q += dirs[d][0]; r += dirs[d][1] }
                }
                var x0 = 0f; var x1 = 0f; var y0 = 0f; var y1 = 0f
                for (j in 0 until n) { x0 = minOf(x0, cx[j] - w / 2f); x1 = maxOf(x1, cx[j] - w / 2f); y0 = minOf(y0, cy[j] - h / 2f); y1 = maxOf(y1, cy[j] - h / 2f) }
                minX = x0; maxX = x1; minY = y0; bubbleMaxY = y1
                contentHeight = h.toFloat()
            }
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
        panY = panY.coerceIn(minY, maxScroll()); panX = panX.coerceIn(minX, maxX)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { panY = scroller.currY.toFloat(); if (bubbles) panX = scroller.currX.toFloat(); postInvalidateOnAnimation() }
    }

    override fun onDraw(c: Canvas) {
        val h = height.toFloat(); val w = width.toFloat()
        val fav = App.prefs.favoriteApps
        val honey = mode == "honeycomb"
        val list = mode == "list"
        val showLabels = list || (mode == "grid" && intArrayOf(5, 4, 3, 3, 2)[zoom] <= 3)
        labelPaint.textSize = if (list) rowH * 0.3f else sp.u * 4.6f
        labelPaint.color = Ui.TEXT
        bgPaint.color = Ui.CARD
        for (i in apps.indices) {
            val x = cx[i] - panX; val y = cy[i] - panY
            if (y < -cellR * 2 - rowH || y > h + cellR * 2 + rowH) continue
            var scale = 1f
            if (bubbles) {
                // fisheye: full size near the centre, shrinking smoothly toward the edges
                val d = hypot(x - w / 2, y - h / 2) / (minOf(w, h) / 2)
                scale = (1.12f - max(0f, d - 0.3f) * 1.05f).coerceIn(0.12f, 1f)
                if (d > 1.45f) continue
            } else if (honey) {
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
        // icons are pre-rendered in the chosen style by IconStyler
        rect.set(x - r, y - r, x + r, y + r)
        c.drawBitmap(b, null, rect, iconPaint)
    }

    private fun hit(x: Float, y: Float): Int {
        var best = -1; var bestD = Float.MAX_VALUE
        for (i in apps.indices) {
            val d = hypot(cx[i] - panX - x, cy[i] - panY - y)
            if (d < bestD) { bestD = d; best = i }
        }
        val limit = if (mode == "list") rowH else cellR * 1.15f
        if (mode == "list" && best >= 0) return if (abs(cy[best] - panY - y) < rowH / 2) best else -1
        return if (bestD <= limit) best else -1
    }

    private val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); return true }
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            if (bubbles) panX = (panX + dx).coerceIn(minX, maxX)
            panY = (panY + dy).coerceIn(minY, maxScroll()); invalidate(); return true
        }
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (bubbles) scroller.fling(panX.toInt(), panY.toInt(), -vx.toInt(), -vy.toInt(), minX.toInt(), maxX.toInt(), minY.toInt(), maxScroll().toInt())
            else scroller.fling(0, panY.toInt(), 0, -vy.toInt(), 0, 0, 0, maxScroll().toInt(), 0, (height * 0.08f).toInt())
            postInvalidateOnAnimation(); return true
        }
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = hit(e.x, e.y); if (i < 0) return false
            val r = cellR
            val loc = IntArray(2); getLocationOnScreen(loc)
            val rr = RectF(cx[i] - panX - r + loc[0], cy[i] - panY - r + loc[1], cx[i] - panX + r + loc[0], cy[i] - panY + r + loc[1])
            Haptics.tick(context)
            onLaunch?.invoke(apps[i], this@AppDrawerView, rr)
            return true
        }
        override fun onLongPress(e: MotionEvent) {
            val i = hit(e.x, e.y); if (i >= 0) { Haptics.tick(context, true); onLongPress?.invoke(apps[i]) }
        }
    })

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        if (scaleDetector.isInProgress) return true
        return gd.onTouchEvent(ev) || super.onTouchEvent(ev)
    }

}
