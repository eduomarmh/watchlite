package com.watchlite.launcher.face

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.watchlite.launcher.App
import com.watchlite.launcher.theme.Col
import com.watchlite.launcher.theme.El
import com.watchlite.launcher.theme.Theme
import com.watchlite.launcher.theme.ThemeCustom
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Fonts
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Draws theme elements. Holds reusable Paint/Path objects so drawing a frame
 * allocates (almost) nothing.
 */
class FaceRenderer(private val ctx: Context) {

    val data = FaceData(ctx)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val sp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val rect = RectF()
    private val bounds = Rect()
    private val digitBounds = HashMap<String, Rect>()

    /** Colors resolved per frame. */
    private var accent = 0xFFFF9F0A.toInt()
    private var timeColor: Int? = null
    private var secondsOverride: Boolean? = null
    private var alphaMul = 1f

    fun draw(c: Canvas, theme: Theme, w: Int, h: Int, cal: Calendar, ms: Long,
             custom: ThemeCustom, aod: Boolean = false, aodAlpha: Float = 1f) {
        data.refreshIfNeeded(cal)
        accent = custom.accent ?: theme.accent ?: App.prefs.accent
        timeColor = custom.timeColor
        secondsOverride = if (aod) false else custom.seconds
        alphaMul = if (aod) aodAlpha else 1f
        derivedAod = aod && theme.aod.isEmpty()
        aodColorOverride = if (aod) App.prefs.aodColor else 0
        val list = if (!aod) theme.elements else theme.aod.ifEmpty { derivedAodFor(theme) }
        val short = min(w, h).toFloat()
        for (e in list) {
            try {
                when (e.type) {
                    "digitalTime" -> digital(c, e, w, h, short, cal, theme)
                    "text" -> text(c, e, w, h, short, cal, theme)
                    "analog" -> analog(c, e, w, h, short, cal, ms)
                    "ticks" -> ticks(c, e, w, h, short, theme)
                    "ring" -> ring(c, e, w, h, short, cal)
                    "batteryChip" -> batteryChip(c, e, w, h, short, theme)
                    "panel" -> panel(c, e, w, h, short)
                    "dot" -> dot(c, e, w, h, short)
                }
            } catch (t: Throwable) {
                com.watchlite.launcher.util.Log.w("Render", "element ${e.type} failed", t)
            }
        }
    }

    /** AOD built from the theme's own clock (same font/position), dim and on black. */
    private var derivedAod = false
    private var aodColorOverride = 0

    /** Small always-on widgets row: battery, steps, next prayer, notifications, date. */
    fun drawAodWidgets(c: Canvas, w: Int, h: Int, cal: Calendar, alpha: Float) {
        val set = App.prefs.aodWidgets
        if (set.isEmpty()) return
        data.refreshIfNeeded(cal)
        val ar = Digits.useArabic()
        val parts = ArrayList<String>()
        if ("battery" in set) parts += Digits.apply("${App.I.state.battery}%", ar)
        if ("steps" in set && App.I.steps.available) parts += Digits.apply("${App.I.steps.today}", ar) + " ↑"
        if ("prayer" in set && data.nextPrayerName.isNotEmpty()) parts += "${data.nextPrayerName} ${data.nextPrayerTime}"
        if ("date" in set) parts += Digits.apply(data.formatDate("EEE d", cal), ar)
        if ("phone" in set && App.I.state.phoneBattery >= 0) parts += Digits.apply("📱${App.I.state.phoneBattery}%", ar)
        val short = min(w, h).toFloat()
        val col = App.prefs.aodColor.takeIf { it != 0 } ?: 0xFF9A9AA0.toInt()
        val a = (Color.alpha(col) * alpha).toInt()
        tp.reset(); tp.isAntiAlias = true
        tp.typeface = Fonts.get(ctx, "tajawal_medium", null)
        tp.textSize = short * 0.055f
        tp.color = Color.argb(a, Color.red(col), Color.green(col), Color.blue(col))
        tp.textAlign = Paint.Align.CENTER
        if (parts.isNotEmpty()) {
            var s = parts.joinToString("  ·  ")
            val mw = w * 0.86f
            if (tp.measureText(s) > mw) tp.textSize *= mw / tp.measureText(s)
            c.drawText(s, w / 2f, h * 0.88f, tp)
        }
        if ("notif" in set && App.I.notifications.count() > 0) {
            fp.reset(); fp.isAntiAlias = true
            val ac = App.prefs.aodColor.takeIf { it != 0 } ?: App.prefs.accent
            fp.color = Color.argb(a, Color.red(ac), Color.green(ac), Color.blue(ac))
            c.drawCircle(w / 2f, h * 0.12f, short * 0.018f, fp)
        }
    }
    private val derivedCache = HashMap<String, List<El>>()

    private fun derivedAodFor(theme: Theme): List<El> = derivedCache.getOrPut(theme.id) {
        val keep = theme.elements.filter {
            it.type == "digitalTime" || it.type == "analog" || it.type == "ticks" ||
                (it.type == "text" && (it.text.contains("{date") || it.text.contains("{hijri")))
        }
        if (keep.none { it.type == "digitalTime" || it.type == "analog" }) DEFAULT_AOD else keep.take(4)
    }

    private fun lighten(c: Int): Int {
        val lum = (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0
        return if (lum < 0.45) Color.argb(Color.alpha(c), 200, 200, 200) else c
    }

    private fun resolve(col: Col, e: El? = null): Int {
        val base0 = when (col.ref) {
            Col.ACCENT -> accent
            Col.TIME -> timeColor ?: col.value
            else -> if (e?.role == "time" && timeColor != null) timeColor!! else if (e?.role == "accent") accent else col.value
        }
        val ov = aodColorOverride
        val base = if (ov != 0) Color.argb(Color.alpha(base0), Color.red(ov), Color.green(ov), Color.blue(ov))
            else if (derivedAod) lighten(base0) else base0
        val a = (Color.alpha(base) * alphaMul * (e?.alpha ?: 1f)).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun setupText(e: El, short: Float, theme: Theme, sizeMul: Float = 1f) {
        tp.reset()
        tp.flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        tp.typeface = Fonts.get(ctx, e.font, theme.fileDir)
        tp.textSize = e.size * short * sizeMul
        tp.color = resolve(e.color, e)
        tp.letterSpacing = e.tracking
        if (e.style == "outline") { tp.style = Paint.Style.STROKE; tp.strokeWidth = maxOf(1f, tp.textSize * 0.025f) }
        val sh = e.shadow
        if (e.glow) tp.setShadowLayer(tp.textSize * 0.18f, 0f, 0f, tp.color)
        else if (sh != null && alphaMul >= 0.99f) tp.setShadowLayer(maxOf(0.5f, sh.r * short), sh.dx * short, sh.dy * short, sh.color)
    }

    /** Places text by anchor. Uses digit bounds for vertical centering of numbers. */
    private fun placeText(c: Canvas, s: String, x: Float, y: Float, anchor: String, digitsMode: Boolean, theme: Theme) {
        val tw = tp.measureText(s)
        val left = when {
            anchor.endsWith("Left", true) || anchor == "left" -> x
            anchor.endsWith("Right", true) || anchor == "right" -> x - tw
            else -> x - tw / 2f
        }
        val top: Float; val bottom: Float
        if (digitsMode) {
            val key = "${tp.typeface.hashCode()}_${tp.textSize}"
            val b = digitBounds.getOrPut(key) { Rect().also { tp.getTextBounds("0123456789", 0, 10, it) } }
            top = b.top.toFloat(); bottom = b.bottom.toFloat()
        } else {
            val fm = tp.fontMetrics
            top = fm.ascent * 0.82f; bottom = fm.descent * 0.6f
        }
        val baseline = when {
            anchor.startsWith("top") -> y - top
            anchor.startsWith("bottom") -> y - bottom
            else -> y - (top + bottom) / 2f
        }
        c.drawText(s, left, baseline, tp)
        lastRight = left + tw; lastTop = baseline + top
    }

    private var lastRight = 0f
    private var lastTop = 0f

    private fun digital(c: Canvas, e: El, w: Int, h: Int, short: Float, cal: Calendar, theme: Theme) {
        setupText(e, short, theme)
        val arabic = Digits.forElement(e.digits)
        val s = Digits.apply(data.formatTime(e.format, cal), arabic)
        val x = e.x * w; val y = e.y * h
        e.ghost?.let { g ->
            val col = tp.color
            tp.color = Color.argb((Color.alpha(col) * 0.10f).toInt(), Color.red(col), Color.green(col), Color.blue(col))
            tp.clearShadowLayer()
            placeText(c, Digits.apply(g, arabic), x, y, e.anchor, true, theme)
            tp.color = col
        }
        // never let a wide font run off the screen
        val maxW = w * 0.94f
        val tw = tp.measureText(s)
        if (tw > maxW) tp.textSize *= maxW / tw
        placeText(c, s, x, y, e.anchor, true, theme)
        val showSec = secondsOverride ?: e.seconds
        if (showSec && alphaMul >= 0.99f) {
            val right = lastRight; val top = lastTop
            tp.textSize = e.size * short * 0.34f
            tp.color = resolve(Col(0, Col.ACCENT))
            tp.clearShadowLayer()
            val ss = Digits.apply(data.formatTime("ss", cal), arabic)
            val b = Rect(); tp.getTextBounds("0", 0, 1, b)
            c.drawText(ss, right + short * 0.012f, top - b.top, tp)
        }
        // AM/PM marker for 12h faces using "auto"
        if (e.format == "auto" && !com.watchlite.launcher.util.TimeFmt.is24(ctx) && e.size >= 0.15f) {
            val right = lastRight; val top = lastTop
            tp.textSize = e.size * short * 0.2f
            tp.color = resolve(e.color, e).let { Color.argb((Color.alpha(it) * 0.7f).toInt(), Color.red(it), Color.green(it), Color.blue(it)) }
            tp.clearShadowLayer()
            if (!(secondsOverride ?: e.seconds)) {
                val b = Rect(); tp.getTextBounds("A", 0, 1, b)
                c.drawText(data.formatTime("a", cal), right + short * 0.012f, top - b.top, tp)
            }
        }
    }

    private fun text(c: Canvas, e: El, w: Int, h: Int, short: Float, cal: Calendar, theme: Theme) {
        var s = data.expand(e.text, cal, Digits.forElement(e.digits))
        if (s.isBlank()) return
        if (e.upper) s = s.uppercase()
        setupText(e, short, theme)
        // auto-shrink long strings (Arabic month names can be wide)
        val maxW = w * 0.9f
        val tw = tp.measureText(s)
        if (tw > maxW) tp.textSize *= maxW / tw
        placeText(c, s, e.x * w, e.y * h, e.anchor, false, theme)
    }

    private fun analog(c: Canvas, e: El, w: Int, h: Int, short: Float, cal: Calendar, ms: Long) {
        val cx = e.cx * w; val cy = e.cy * h; val r = e.radius * short
        val sec = cal.get(Calendar.SECOND) + if (e.smooth) (ms % 1000) / 1000f else 0f
        val min = cal.get(Calendar.MINUTE) + sec / 60f
        val hr = (cal.get(Calendar.HOUR) % 12) + min / 60f
        val shadowOk = alphaMul >= 0.99f
        hand(c, cx, cy, r, hr * 30f, 0.52f, e.hourW, resolve(e.hourColor), e.style, shadowOk, short)
        hand(c, cx, cy, r, min * 6f, 0.80f, e.minuteW, resolve(e.minuteColor), e.style, shadowOk, short)
        val showSec = secondsOverride ?: e.seconds
        if (showSec && alphaMul >= 0.99f) {
            val col = resolve(e.secondColor)
            sp.reset(); sp.isAntiAlias = true; sp.color = col; sp.strokeCap = Paint.Cap.ROUND
            sp.strokeWidth = maxOf(1.5f, e.secondW * r)
            val a = Math.toRadians(sec * 6.0 - 90).toFloat()
            c.drawLine(cx - cos(a) * r * 0.16f, cy - sin(a) * r * 0.16f, cx + cos(a) * r * 0.9f, cy + sin(a) * r * 0.9f, sp)
            fp.reset(); fp.isAntiAlias = true; fp.color = col
            c.drawCircle(cx, cy, r * 0.045f, fp)
        }
        fp.reset(); fp.isAntiAlias = true; fp.color = resolve(e.centerColor)
        c.drawCircle(cx, cy, r * (if (showSec) 0.022f else 0.04f), fp)
    }

    private fun hand(c: Canvas, cx: Float, cy: Float, r: Float, deg: Float, len: Float, width: Float, color: Int,
                     style: String, shadow: Boolean, short: Float) {
        c.save()
        c.rotate(deg, cx, cy)
        val l = r * len; val wd = maxOf(2f, width * r)
        fp.reset(); fp.isAntiAlias = true; fp.color = color
        if (shadow) fp.setShadowLayer(short * 0.012f, 0f, short * 0.006f, 0x66000000)
        when (style) {
            "sword" -> {
                path.reset()
                path.moveTo(cx, cy - l); path.lineTo(cx + wd * 0.6f, cy - l * 0.25f)
                path.lineTo(cx, cy + r * 0.08f); path.lineTo(cx - wd * 0.6f, cy - l * 0.25f); path.close()
                c.drawPath(path, fp)
            }
            "line" -> {
                fp.style = Paint.Style.STROKE; fp.strokeWidth = maxOf(2f, wd * 0.5f); fp.strokeCap = Paint.Cap.ROUND
                c.drawLine(cx, cy + r * 0.06f, cx, cy - l, fp)
            }
            "baton" -> {
                rect.set(cx - wd / 2, cy - l, cx + wd / 2, cy + r * 0.04f)
                c.drawRoundRect(rect, wd / 2, wd / 2, fp)
                fp.clearShadowLayer(); fp.color = 0xCC000000.toInt()
                rect.set(cx - wd * 0.18f, cy - l + wd * 0.5f, cx + wd * 0.18f, cy - l * 0.3f)
                c.drawRoundRect(rect, wd * 0.18f, wd * 0.18f, fp)
            }
            else -> { // classic: neck + rounded blade
                fp.style = Paint.Style.STROKE; fp.strokeCap = Paint.Cap.ROUND
                fp.strokeWidth = maxOf(1.5f, wd * 0.35f)
                c.drawLine(cx, cy, cx, cy - l * 0.22f, fp)
                fp.strokeWidth = wd
                c.drawLine(cx, cy - l * 0.22f, cx, cy - l, fp)
            }
        }
        c.restore()
    }

    private fun ticks(c: Canvas, e: El, w: Int, h: Int, short: Float, theme: Theme) {
        val cx = e.cx * w; val cy = e.cy * h; val r = e.radius * short
        sp.reset(); sp.isAntiAlias = true; sp.strokeCap = Paint.Cap.ROUND
        val minorCol = resolve(e.color, e); val majorCol = resolve(e.majorColor)
        for (i in 0 until e.count) {
            val major = e.major > 0 && i % e.major == 0
            val a = Math.toRadians(i * 360.0 / e.count - 90).toFloat()
            val len = (if (major) e.majorLen else e.minorLen) * short
            sp.color = if (major) majorCol else minorCol
            if (e.style == "dot") {
                fp.reset(); fp.isAntiAlias = true; fp.color = sp.color
                val rr = r - len / 2
                c.drawCircle(cx + cos(a) * rr, cy + sin(a) * rr, (if (major) e.majorW else e.minorW) * short, fp)
            } else {
                sp.strokeWidth = maxOf(1f, (if (major) e.majorW else e.minorW) * short)
                c.drawLine(cx + cos(a) * (r - len), cy + sin(a) * (r - len), cx + cos(a) * r, cy + sin(a) * r, sp)
            }
        }
        if (e.numerals != "none") {
            tp.reset(); tp.isAntiAlias = true
            tp.typeface = Fonts.get(ctx, e.font, theme.fileDir)
            tp.textSize = e.numeralSize * short
            tp.color = resolve(e.numeralColor)
            tp.textAlign = Paint.Align.CENTER
            val arabic = Digits.forElement(e.digits)
            val rr = r - e.numeralInset * short
            for (n in 1..12) {
                if ((e.numerals == "quarters" || e.numerals == "romanQuarters") && n % 3 != 0) continue
                val a = Math.toRadians(n * 30.0 - 90).toFloat()
                val s = if (e.numerals.startsWith("roman")) ROMAN[n - 1] else Digits.apply(n.toString(), arabic)
                tp.getTextBounds(s, 0, s.length, bounds)
                c.drawText(s, cx + cos(a) * rr, cy + sin(a) * rr - bounds.exactCenterY(), tp)
            }
        }
    }

    private fun progressOf(source: String, cal: Calendar): Float = when (source) {
        "steps" -> (App.I.steps.today / maxOf(1f, App.prefs.stepsGoal.toFloat())).coerceIn(0f, 1f)
        "battery" -> App.I.state.battery / 100f
        "seconds" -> cal.get(Calendar.SECOND) / 60f
        "minute" -> cal.get(Calendar.MINUTE) / 60f
        "hour" -> (cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f) / 12f
        "day" -> (cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)) / 1440f
        else -> 0f
    }

    private fun ring(c: Canvas, e: El, w: Int, h: Int, short: Float, cal: Calendar) {
        if (e.source == "seconds" && alphaMul < 0.99f) return
        val cx = e.cx * w; val cy = e.cy * h; val r = e.radius * short
        sp.reset(); sp.isAntiAlias = true; sp.style = Paint.Style.STROKE; sp.strokeCap = Paint.Cap.ROUND
        sp.strokeWidth = e.width * short
        rect.set(cx - r, cy - r, cx + r, cy + r)
        sp.color = (e.trackColor and 0x00FFFFFF) or ((Color.alpha(e.trackColor) * alphaMul).toInt() shl 24)
        c.drawArc(rect, 0f, 360f, false, sp)
        val p = progressOf(e.source, cal)
        if (p > 0f) { sp.color = resolve(e.color, e); c.drawArc(rect, e.start, 360f * p, false, sp) }
    }

    private fun anchorRect(e: El, w: Int, h: Int, short: Float) {
        val bw = e.w * short; val bh = e.h * short
        val x = e.x * w; val y = e.y * h
        val left = when {
            e.anchor.endsWith("Left", true) -> x
            e.anchor.endsWith("Right", true) -> x - bw
            else -> x - bw / 2
        }
        val top = when {
            e.anchor.startsWith("top") -> y
            e.anchor.startsWith("bottom") -> y - bh
            else -> y - bh / 2
        }
        rect.set(left, top, left + bw, top + bh)
    }

    private fun batteryChip(c: Canvas, e: El, w: Int, h: Int, short: Float, theme: Theme) {
        anchorRect(e, w, h, short)
        val level = App.I.state.battery
        val charging = App.I.state.charging
        val rad = rect.height() * 0.32f
        fp.reset(); fp.isAntiAlias = true
        fp.color = Color.argb((70 * alphaMul).toInt(), 40, 40, 60)
        c.drawRoundRect(rect, rad, rad, fp)
        // filled part
        c.save()
        c.clipRect(rect.left, rect.top, rect.left + rect.width() * level / 100f, rect.bottom)
        fp.color = when {
            charging -> Color.argb((150 * alphaMul).toInt(), 48, 160, 90)
            level <= 20 -> Color.argb((150 * alphaMul).toInt(), 200, 60, 60)
            else -> Color.argb((95 * alphaMul).toInt(), 120, 120, 160)
        }
        c.drawRoundRect(rect, rad, rad, fp)
        c.restore()
        tp.reset(); tp.isAntiAlias = true
        tp.typeface = Fonts.get(ctx, e.font ?: "tajawal_bold", theme.fileDir)
        tp.textSize = rect.height() * 0.52f
        tp.color = Color.argb((255 * alphaMul).toInt(), 255, 255, 255)
        val s = Digits.apply("$level%", Digits.forElement(e.digits)) + if (charging) " ⚡" else ""
        tp.getTextBounds("0", 0, 1, bounds)
        c.drawText(s, rect.left + rect.height() * 0.38f, rect.centerY() - bounds.exactCenterY(), tp)
    }

    private fun panel(c: Canvas, e: El, w: Int, h: Int, short: Float) {
        anchorRect(e, w, h, short)
        fp.reset(); fp.isAntiAlias = true; fp.color = resolve(e.color, e)
        val rad = rect.height() * e.corner
        c.drawRoundRect(rect, rad, rad, fp)
    }

    private fun dot(c: Canvas, e: El, w: Int, h: Int, short: Float) {
        if (e.text == "notif" && App.I.notifications.count() == 0) return
        fp.reset(); fp.isAntiAlias = true; fp.color = resolve(e.color, e)
        c.drawCircle(e.x * w, e.y * h, e.size * short, fp)
    }

    companion object {
        private val ROMAN = arrayOf("I", "II", "III", "IV", "V", "VI", "VII", "VIII", "IX", "X", "XI", "XII")

        /** Generic dim clock used by themes that don't define an AOD layout. */
        val DEFAULT_AOD: List<El> = run {
            val arr = JSONArray()
                .put(JSONObject().put("type", "digitalTime").put("x", 0.5).put("y", 0.46).put("size", 0.27)
                    .put("font", "tajawal_light").put("color", "#FFFFFFFF"))
                .put(JSONObject().put("type", "text").put("text", "{date:EEE d MMM}").put("x", 0.5).put("y", 0.62)
                    .put("size", 0.075).put("font", "tajawal_medium").put("color", "#FF8E8E93"))
            List(arr.length()) { El(arr.getJSONObject(it)) }
        }
    }
}

