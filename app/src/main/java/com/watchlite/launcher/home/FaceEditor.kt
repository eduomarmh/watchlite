package com.watchlite.launcher.home

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.OverScroller
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.face.Backgrounds
import com.watchlite.launcher.face.FaceRenderer
import com.watchlite.launcher.settings.SettingsActivity
import com.watchlite.launcher.theme.Theme
import com.watchlite.launcher.theme.ThemeCustom
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Ui
import java.util.Calendar
import kotlin.math.abs
import kotlin.math.roundToInt

/** Full-screen theme picker: swipe/wheel through live previews, tap to apply. */
class FaceEditor(private val host: HomeActivity) : FrameLayout(host) {

    private val ui = host.ui
    private var themes: List<Theme> = emptyList()
    private val carousel = Carousel()
    private val title: TextView = ui.text("", 6.4f, Ui.TEXT, 2).apply { gravity = Gravity.CENTER }
    private val previews = HashMap<String, Bitmap>()
    private val previewRenderer = FaceRenderer(host)
    private var deleteBtn: View? = null

    init {
        setBackgroundColor(0xF2000000.toInt())
        visibility = GONE
        isClickable = true
        addView(carousel, ui.frameLp())
        addView(title, ui.frameLp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            topMargin = ui.px(if (ui.sp.isRound) 9f else 4f)
        })
        val row = ui.hbox().apply { gravity = Gravity.CENTER }
        val b = 12f
        row.addView(ui.circleButton(R.drawable.ic_palette, b) { customize() }, ui.lp(ui.px(b), ui.px(b), end = 2.5f))
        row.addView(ui.circleButton(R.drawable.ic_add, b) { addTheme() }, ui.lp(ui.px(b), ui.px(b), end = 2.5f))
        deleteBtn = ui.circleButton(R.drawable.ic_close, b) { deleteCurrent() }
        row.addView(deleteBtn, ui.lp(ui.px(b), ui.px(b), end = 2.5f))
        row.addView(ui.circleButton(R.drawable.ic_settings, b) {
            host.startActivity(Intent(host, SettingsActivity::class.java))
        }, ui.lp(ui.px(b), ui.px(b)))
        addView(row, ui.frameLp(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
            bottomMargin = ui.px(if (ui.sp.isRound) 8f else 3f)
        })
    }

    val isOpen get() = visibility == VISIBLE

    fun open() {
        setBackgroundColor((Ui.BG and 0x00FFFFFF) or 0xF2000000.toInt())
        title.setTextColor(Ui.TEXT)
        themes = App.I.themes.all()
        val cur = themes.indexOfFirst { it.id == host.face.theme?.id }.coerceAtLeast(0)
        carousel.jumpTo(cur)
        updateTitle()
        visibility = VISIBLE
        alpha = 0f; scaleX = 1.08f; scaleY = 1.08f
        animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180).start()
        host.face.pause()
        renderPreviews()
    }

    fun close() {
        if (!isOpen) return
        animate().alpha(0f).setDuration(140).withEndAction {
            visibility = GONE
            previews.values.forEach { it.recycle() }; previews.clear()
            host.face.resume()
        }.start()
    }

    fun onWheel(d: Int): Boolean { carousel.step(d); return true }

    private fun updateTitle() {
        val t = themes.getOrNull(carousel.index) ?: return
        title.text = t.name()
        deleteBtn?.visibility = if (t.builtIn) GONE else VISIBLE
    }

    private fun previewSize(): Pair<Int, Int> {
        val w = (ui.sp.width * 0.62f).toInt(); val h = (ui.sp.height * 0.62f).toInt()
        return w to h
    }

    private fun renderPreviews() {
        val list = themes
        val (w, h) = previewSize()
        val start = carousel.index
        val order = list.indices.sortedBy { abs(it - start) }
        Bg.run {
            val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 9); set(Calendar.SECOND, 30) }
            for (i in order) {
                if (visibility != VISIBLE) break
                val t = list[i]
                if (previews.containsKey(t.id)) continue
                try {
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    val c = Canvas(bmp)
                    val layers = Backgrounds.build(context, t, w, h, allowAnimation = false)
                    layers.forEach { it.draw(c, w, h, 0) }
                    layers.forEach { it.release() }
                    previewRenderer.draw(c, t, w, h, cal, cal.timeInMillis, App.I.themes.custom(t.id))
                    Bg.ui { if (visibility == VISIBLE) { previews[t.id] = bmp; carousel.invalidate() } else bmp.recycle() }
                } catch (e: Throwable) { com.watchlite.launcher.util.Log.w("Editor", "preview ${t.id}", e) }
            }
        }
    }

    private fun applyTheme(i: Int) {
        val t = themes.getOrNull(i) ?: return
        Haptics.tick(context, true)
        App.I.themes.select(t.id)
        host.applyTheme()
        close()
    }

    private fun current() = themes.getOrNull(carousel.index)

    private fun customize() {
        val t = current() ?: return
        val c = App.I.themes.custom(t.id)
        val items = arrayOf(
            context.getString(R.string.cust_time_color), context.getString(R.string.cust_accent),
            context.getString(if (c.seconds == true) R.string.cust_hide_seconds else R.string.cust_show_seconds),
            context.getString(R.string.cust_digits), context.getString(R.string.cust_complications),
            context.getString(R.string.cust_gauge_style), context.getString(R.string.reset)
        )
        AlertDialog.Builder(context, Ui.DIALOG).setTitle(t.name()).setItems(items) { _, which ->
            when (which) {
                0 -> pickColor { col -> save(t, c.copy(timeColor = col)) }
                1 -> pickColor { col -> save(t, c.copy(accent = col)) }
                2 -> save(t, c.copy(seconds = !(c.seconds ?: t.hasSeconds)))
                3 -> {
                    val modes = arrayOf("latin", "arabic", "auto")
                    val labels = arrayOf(context.getString(R.string.digits_latin), context.getString(R.string.digits_arabic), context.getString(R.string.auto))
                    AlertDialog.Builder(context, Ui.DIALOG).setItems(labels) { _, k ->
                        App.prefs.digits = modes[k]; previews.clear(); renderPreviews(); host.applyTheme()
                    }.show()
                }
                4 -> complications(t, c)
                5 -> {
                    val keys = arrayOf("", "arc", "ring", "segments", "bar", "pill")
                    val labels = arrayOf(R.string.gs_theme, R.string.gs_arc, R.string.gs_ring, R.string.gs_segments, R.string.gs_bar, R.string.gs_pill)
                        .map { context.getString(it) }.toTypedArray()
                    AlertDialog.Builder(context, Ui.DIALOG).setTitle(R.string.cust_gauge_style)
                        .setSingleChoiceItems(labels, keys.indexOf(c.gaugeStyle ?: "")) { dd, k ->
                            dd.dismiss(); save(t, c.copy(gaugeStyle = keys[k].ifEmpty { null }))
                        }.show()
                }
                6 -> save(t, ThemeCustom(null, null, null))
            }
        }.show()
    }

    /** Turn the theme's interactive complications (steps, battery, …) on or off. */
    private fun complications(t: Theme, c: ThemeCustom) {
        val slots = t.slots
        if (slots.isEmpty()) { host.toast(context.getString(R.string.no_complications)); return }
        val hidden = t.hiddenSlots(c).toMutableSet()
        val labels = slots.map { slotLabel(it) }.toTypedArray()
        val checked = BooleanArray(slots.size) { slots[it] !in hidden }
        AlertDialog.Builder(context, Ui.DIALOG).setTitle(R.string.cust_complications)
            .setMultiChoiceItems(labels, checked) { _, i, on -> if (on) hidden.remove(slots[i]) else hidden.add(slots[i]) }
            .setPositiveButton(android.R.string.ok) { _, _ -> save(t, c.copy(hidden = hidden.toSet())) }.show()
    }

    private fun slotLabel(s: String) = context.getString(when (s) {
        "steps" -> R.string.slot_steps; "battery" -> R.string.slot_battery; "prayer" -> R.string.slot_prayer
        "hijri" -> R.string.slot_hijri; "notif" -> R.string.slot_notif; "weather" -> R.string.slot_weather
        else -> R.string.slot_phone
    })

    private fun save(t: Theme, c: ThemeCustom) {
        App.I.themes.setCustom(t.id, c)
        previews.remove(t.id)?.recycle()
        renderPreviews()
        if (host.face.theme?.id == t.id) host.face.refreshCustom()
    }

    private fun pickColor(done: (Int) -> Unit) {
        val grid = ui.hbox().apply { gravity = Gravity.CENTER; setPadding(ui.px(2f), ui.px(4f), ui.px(2f), ui.px(4f)) }
        val dlg = AlertDialog.Builder(context, Ui.DIALOG).setView(grid).create()
        val wrap = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        grid.addView(wrap)
        var row: LinearLayout? = null
        Ui.ACCENTS.forEachIndexed { i, col ->
            if (i % 5 == 0) { row = ui.hbox(); wrap.addView(row) }
            row!!.addView(View(context).apply {
                background = ui.oval(col)
                setOnClickListener { done(col); dlg.dismiss() }
            }, ui.lp(ui.px(10f), ui.px(10f), top = 1f, bottom = 1f, start = 1f, end = 1f))
        }
        dlg.show()
    }

    private fun addTheme() {
        val items = arrayOf(context.getString(R.string.theme_from_photo), context.getString(R.string.theme_import))
        AlertDialog.Builder(context, Ui.DIALOG).setTitle(R.string.add_theme).setItems(items) { _, w ->
            if (w == 0) host.pickDocument(HomeActivity.REQ_PHOTO, "image/*") else host.pickDocument(HomeActivity.REQ_THEME, "*/*")
        }.show()
    }

    private fun deleteCurrent() {
        val t = current() ?: return
        if (t.builtIn) return
        AlertDialog.Builder(context, Ui.DIALOG).setTitle(t.name()).setMessage(R.string.delete_theme_q)
            .setPositiveButton(R.string.delete) { _, _ ->
                App.I.themes.delete(t.id); host.applyTheme(); themes = App.I.themes.all()
                carousel.jumpTo(carousel.index.coerceAtMost(themes.size - 1)); updateTitle()
            }.setNegativeButton(R.string.cancel, null).show()
    }

    fun reloadThemes(selectId: String?) {
        themes = App.I.themes.all()
        val i = themes.indexOfFirst { it.id == selectId }
        if (i >= 0) carousel.jumpTo(i)
        updateTitle(); renderPreviews()
    }

    /** Horizontal snapping carousel; neighbours are drawn smaller. */
    private inner class Carousel : View(host) {
        var index = 0
        private var pos = 0f              // fractional index
        private val scroller = OverScroller(context)
        private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val frame = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val ph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Ui.CARD }
        private val r = RectF()
        private val clip = Path()

        fun jumpTo(i: Int) { index = i.coerceIn(0, (themes.size - 1).coerceAtLeast(0)); pos = index.toFloat(); invalidate() }
        fun step(d: Int) { snapTo(index + d) }

        private fun snapTo(i: Int) {
            val target = i.coerceIn(0, themes.size - 1)
            if (target != index) Haptics.tick(context)
            index = target
            val spacing = width * 0.7f
            scroller.startScroll((pos * spacing).toInt(), 0, ((target - pos) * spacing).toInt(), 0, 220)
            updateTitle()
            postInvalidateOnAnimation()
        }

        override fun computeScroll() {
            if (scroller.computeScrollOffset()) { pos = scroller.currX / (width * 0.7f); postInvalidateOnAnimation() }
        }

        override fun onDraw(c: Canvas) {
            val (pw, phh) = previewSize()
            val cx = width / 2f; val cy = height / 2f
            val spacing = width * 0.7f
            val lo = (pos - 2).toInt().coerceAtLeast(0); val hi = (pos + 2).toInt().coerceAtMost(themes.size - 1)
            for (i in lo..hi) {
                val d = i - pos
                val s = (1f - abs(d) * 0.22f).coerceIn(0.5f, 1f)
                val x = cx + d * spacing
                r.set(x - pw * s / 2, cy - phh * s / 2, x + pw * s / 2, cy + phh * s / 2)
                val rad = if (ui.sp.isRound) r.width() / 2 else r.width() * 0.14f
                clip.reset(); clip.addRoundRect(r, rad, rad, Path.Direction.CW)
                c.save(); c.clipPath(clip)
                val bmp = previews[themes[i].id]
                if (bmp != null && !bmp.isRecycled) c.drawBitmap(bmp, null, r, p) else c.drawRect(r, ph)
                c.restore()
                frame.color = if (themes[i].id == host.face.theme?.id) ui.accent else 0x55FFFFFF
                frame.strokeWidth = ui.u * 0.8f
                c.drawRoundRect(r, rad, rad, frame)
            }
        }

        private var dragStartPos = 0f
        private val gd = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean { scroller.forceFinished(true); dragStartPos = pos; return true }
            override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
                pos = (pos + dx / (width * 0.7f) * (if (layoutDirection == LAYOUT_DIRECTION_RTL) 1f else 1f)).coerceIn(-0.3f, themes.size - 0.7f)
                invalidate(); return true
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                snapTo(if (vx < 0) dragStartPos.roundToInt() + 1 else dragStartPos.roundToInt() - 1); return true
            }
            override fun onSingleTapUp(e: MotionEvent): Boolean {
                val (pw, _) = previewSize()
                if (abs(e.x - width / 2f) < pw / 2f) applyTheme(index)
                else snapTo(index + if (e.x > width / 2f) 1 else -1)
                return true
            }
        })

        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val handled = gd.onTouchEvent(ev)
            if ((ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) && scroller.isFinished) {
                if (abs(pos - index) > 0.01f) snapTo(pos.roundToInt())
            }
            return handled || true
        }
    }
}
