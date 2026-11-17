package com.watchlite.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A watch face designed on the phone. Saved as JSON (so it can be edited later)
 * and turned into a standard `.wtheme` manifest (docs/THEME_FORMAT.md) for the watch.
 */
class ThemeDesign {
    var id = "ph_" + System.currentTimeMillis().toString(36)
    var name = ""
    var bgType = "gradient"            // photo | wallpaper | gradient | solid
    var wallpaper = "sakura_night"     // one of the watch's built-in wallpapers (assets/wallpapers)
    var gradient = 0                   // index into GRADIENTS
    var solid = 0xFF000000.toInt()
    var photoFx = 0.5f; var photoFy = 0.5f; var photoZoom = 1f
    var scrim = 25                     // %
    var overlay = "none"               // one of OVERLAYS
    var clock = "digital"              // digital | analog
    var font = "tajawal_extrabold"
    var outline = false
    var position = "center"            // top | center | bottom
    var size = 30                      // % of short side
    var timeColor = 0xFFFFFFFF.toInt()
    var accent = 0xFF7B6CFF.toInt()
    var showDate = true
    var showBattery = true
    var showSteps = true
    var showPrayer = false
    var showHijri = false
    var seconds = true                 // analog second hand

    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("name", name).put("bgType", bgType).put("gradient", gradient).put("solid", solid)
        .put("fx", photoFx.toDouble()).put("fy", photoFy.toDouble()).put("zoom", photoZoom.toDouble())
        .put("scrim", scrim).put("wallpaper", wallpaper).put("overlay", overlay).put("clock", clock).put("font", font).put("outline", outline)
        .put("position", position).put("size", size).put("timeColor", timeColor).put("accent", accent)
        .put("date", showDate).put("battery", showBattery).put("steps", showSteps).put("prayer", showPrayer)
        .put("hijri", showHijri).put("seconds", seconds)

    companion object {
        val GRADIENTS = listOf(
            intArrayOf(0xFF2B1B5A.toInt(), 0xFF0B0820.toInt()), intArrayOf(0xFF0F3D5C.toInt(), 0xFF03101C.toInt()),
            intArrayOf(0xFF7A2E3A.toInt(), 0xFF1A0A12.toInt()), intArrayOf(0xFF1E4D3A.toInt(), 0xFF06140F.toInt()),
            intArrayOf(0xFF6B2A0A.toInt(), 0xFF140602.toInt()), intArrayOf(0xFF2F3640.toInt(), 0xFF0A0C10.toInt()),
            intArrayOf(0xFF5C1E4A.toInt(), 0xFF12060F.toInt()), intArrayOf(0xFF3A3A3A.toInt(), 0xFF000000.toInt()))
        val COLORS = listOf(0xFFFFFFFF, 0xFFE8E3FF, 0xFF7B6CFF, 0xFF64D2FF, 0xFF30D158, 0xFFFFD60A, 0xFFFF9F6B, 0xFFFF6B6B, 0xFFFF6BD5, 0xFFBFBFBF).map { it.toInt() }
        val SOLIDS = listOf(0xFF000000, 0xFF101018, 0xFF14213D, 0xFF2D0B3A, 0xFF0B2E20, 0xFF3A0B0B).map { it.toInt() }
        /** Animated effects the watch can draw on top of any background. */
        val OVERLAYS = listOf("none", "sakura", "lanterns", "twinkle", "clouds", "snow", "rain", "fireflies", "bubbles", "starfield")
        /** Wallpapers bundled in both apps (same files), newest first. */
        fun wallpapers(ctx: Context): List<String> = (ctx.assets.list("wallpapers") ?: emptyArray())
            .filter { it.endsWith(".webp") }.map { it.removeSuffix(".webp") }.sorted()
        val FONTS = listOf("tajawal_extrabold", "tajawal_light", "bebas", "michroma", "techmono", "ruqaa")

        fun fromJson(o: JSONObject) = ThemeDesign().apply {
            id = o.optString("id", id); name = o.optString("name"); bgType = o.optString("bgType", bgType)
            gradient = o.optInt("gradient"); solid = o.optInt("solid", solid)
            photoFx = o.optDouble("fx", .5).toFloat(); photoFy = o.optDouble("fy", .5).toFloat(); photoZoom = o.optDouble("zoom", 1.0).toFloat()
            scrim = o.optInt("scrim", scrim); wallpaper = o.optString("wallpaper", wallpaper); overlay = o.optString("overlay", overlay); clock = o.optString("clock", clock)
            font = o.optString("font", font); outline = o.optBoolean("outline"); position = o.optString("position", position)
            size = o.optInt("size", size); timeColor = o.optInt("timeColor", timeColor); accent = o.optInt("accent", accent)
            showDate = o.optBoolean("date", true); showBattery = o.optBoolean("battery", true); showSteps = o.optBoolean("steps", true)
            showPrayer = o.optBoolean("prayer"); showHijri = o.optBoolean("hijri"); seconds = o.optBoolean("seconds", true)
        }

        fun dir(ctx: Context) = File(ctx.filesDir, "designs").apply { mkdirs() }
        fun load(ctx: Context, id: String): ThemeDesign? = try { fromJson(JSONObject(File(dir(ctx), "$id.json").readText())) } catch (_: Exception) { null }
        fun photoFile(ctx: Context, id: String) = File(dir(ctx), "${id}_src.jpg")
        fun hex(c: Int) = String.format("#%08X", c)

        /** Decodes the stored source photo (≤ 1400 px) . */
        fun loadPhoto(ctx: Context, id: String): Bitmap? = photoFile(ctx, id).takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.path) }
    }

    fun save(ctx: Context) = File(dir(ctx), "$id.json").writeText(toJson().toString())

    // ------------------------------------------------------------------ manifest
    private fun timeY() = when (position) { "top" -> 0.27; "bottom" -> 0.66; else -> 0.44 }

    fun manifest(screenW: Int, screenH: Int): JSONObject {
        val shortOverH = min(screenW, screenH).toDouble() / screenH
        val els = JSONArray()
        val bg = when (bgType) {
            "photo" -> JSONObject().put("type", "image").put("src", "bg.jpg")
            "wallpaper" -> JSONObject().put("type", "image").put("src", "asset:wallpapers/$wallpaper.webp")
            "solid" -> JSONObject().put("type", "solid").put("colors", JSONArray().put(hex(solid)))
            else -> JSONObject().put("type", "gradient").put("colors", JSONArray(GRADIENTS[gradient.coerceIn(0, GRADIENTS.size - 1)].map { hex(it) }))
        }
        if (scrim > 0) bg.put("scrim", scrim / 100.0)
        if (overlay != "none") bg.put("overlay", overlay).put("overlayColor", hex(accent))
        val dim = hex((timeColor and 0x00FFFFFF) or 0xCC000000.toInt())
        val acc = hex(accent)
        val shadow = if (bgType == "photo" || bgType == "wallpaper") JSONObject().put("r", 0.02).put("dx", 0).put("dy", 0.006).put("color", "#99000000") else null

        fun text(t: String, x: Double, y: Double, s: Double, color: String, font: String = "tajawal_medium") =
            JSONObject().put("type", "text").put("text", t).put("x", x).put("y", y).put("size", s).put("color", color).put("font", font)
                .also { o -> shadow?.let { o.put("shadow", it) } }

        if (clock == "digital") {
            val y = timeY(); val sz = size / 100.0
            els.put(JSONObject().put("type", "digitalTime").put("role", "time").put("x", 0.5).put("y", y).put("size", sz).put("font", font)
                .put("format", "auto").put("color", hex(timeColor)).put("style", if (outline) "outline" else "fill")
                .also { o -> shadow?.let { o.put("shadow", it) } })
            val half = 0.36 * sz * shortOverH
            val lines = ArrayList<Pair<String, String>>()
            if (showDate) lines += "{date:EEEE d MMMM}" to dim
            if (showPrayer) lines += "{prayer.next} {prayer.nextTime}" to acc
            if (showHijri) lines += "{hijri}" to dim
            val below = position != "bottom"
            lines.forEachIndexed { i, (t, col) ->
                val ly = if (below) y + half + 0.065 + i * 0.07 else y - half - 0.065 - (lines.size - 1 - i) * 0.07
                els.put(text(t, 0.5, ly, if (i == 0 && showDate) 0.072 else 0.062, col))
            }
            val cy = if (position == "top") 0.86 else 0.14
            if (showBattery) els.put(JSONObject().put("type", "gauge").put("cx", 0.17).put("cy", cy).put("radius", 0.105).put("source", "battery").put("color", "#FF30D158"))
            if (showSteps) els.put(JSONObject().put("type", "gauge").put("cx", 0.83).put("cy", cy).put("radius", 0.105).put("source", "steps").put("color", acc))
        } else {
            els.put(JSONObject().put("type", "ticks").put("cx", 0.5).put("cy", 0.5).put("radius", 0.46).put("count", 60).put("major", 5)
                .put("color", hex((timeColor and 0x00FFFFFF) or 0x77000000)).put("majorColor", hex(timeColor)).put("numerals", "quarters").put("numeralSize", 0.09))
            if (showBattery) els.put(JSONObject().put("type", "gauge").put("cx", 0.29).put("cy", 0.5).put("radius", 0.085).put("source", "battery").put("color", "#FF30D158"))
            if (showSteps) els.put(JSONObject().put("type", "gauge").put("cx", 0.71).put("cy", 0.5).put("radius", 0.085).put("source", "steps").put("color", acc))
            if (showHijri) els.put(text("{hijri}", 0.5, 0.33, 0.055, dim))
            if (showDate) els.put(text("{date:EEE d}", 0.5, 0.67, 0.065, dim))
            if (showPrayer) els.put(text("{prayer.next} {prayer.nextTime}", 0.5, 0.74, 0.055, acc))
            els.put(JSONObject().put("type", "analog").put("cx", 0.5).put("cy", 0.5).put("radius", 0.44).put("style", "classic")
                .put("hourColor", hex(timeColor)).put("minuteColor", hex(timeColor)).put("secondColor", acc).put("centerColor", acc).put("seconds", seconds))
        }
        return JSONObject().put("schema", 1).put("id", id)
            .put("name", JSONObject().put("en", name.ifBlank { "My face" }).put("ar", name.ifBlank { "ثيمي" }))
            .put("author", "WatchLite Companion").put("accent", acc).put("background", bg).put("elements", els)
    }

    /** Background photo cropped to the watch screen with the chosen focus/zoom. */
    fun croppedPhoto(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, photoMatrix(src, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
        return out
    }

    fun photoMatrix(src: Bitmap, w: Float, h: Float): Matrix {
        val s = max(w / src.width, h / src.height) * photoZoom
        val vw = w / s; val vh = h / s   // visible part in source pixels
        val cx = (photoFx * src.width).coerceIn(vw / 2, src.width - vw / 2)
        val cy = (photoFy * src.height).coerceIn(vh / 2, src.height - vh / 2)
        return Matrix().apply { postTranslate(-(cx - vw / 2), -(cy - vh / 2)); postScale(s, s) }
    }
}

/**
 * Draws a manifest the same way the watch does (same coordinates, fonts, text sizing and
 * vertical centering), so the phone preview matches the result on the watch.
 */
class ManifestPainter(private val ctx: Context) {
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fonts = HashMap<String, Typeface>()
    private var assetName = ""; private var assetBmp: Bitmap? = null
    private fun asset(path: String): Bitmap? {
        if (path != assetName) {
            assetBmp = try { ctx.assets.open(path).use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
            assetName = path
        }
        return assetBmp
    }
    var battery = 76; var steps = 4321; var goal = 6000

    private fun font(id: String): Typeface = fonts.getOrPut(id) {
        try { Typeface.createFromAsset(ctx.assets, "fonts/$id.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
    }

    private fun col(s: String?, def: Int = Color.WHITE): Int = try { if (s.isNullOrEmpty()) def else Color.parseColor(s) } catch (_: Exception) { def }

    private fun fill(t: String, cal: Calendar): String {
        var s = t
        Regex("\\{date:([^}]+)\\}").findAll(t).forEach { m -> s = s.replace(m.value, SimpleDateFormat(m.groupValues[1], Locale.getDefault()).format(cal.time)) }
        val ar = Locale.getDefault().language == "ar"
        s = s.replace("{prayer.next}", if (ar) "العصر" else "Asr").replace("{prayer.nextTime}", "3:12")
            .replace("{battery}", "$battery").replace("{steps}", "%,d".format(steps))
        if (s.contains("{hijri}")) s = s.replace("{hijri}", try {
            java.time.chrono.HijrahDate.now().format(java.time.format.DateTimeFormatter.ofPattern("d MMMM y", Locale.getDefault()))
        } catch (_: Exception) { "" })
        return s
    }

    private fun place(c: Canvas, s: String, x: Float, y: Float, digits: Boolean) {
        val tw = tp.measureText(s)
        val top: Float; val bottom: Float
        if (digits) { val b = Rect(); tp.getTextBounds("0123456789", 0, 10, b); top = b.top.toFloat(); bottom = b.bottom.toFloat() }
        else { val fm = tp.fontMetrics; top = fm.ascent * 0.82f; bottom = fm.descent * 0.6f }
        c.drawText(s, x - tw / 2f, y - (top + bottom) / 2f, tp)
    }

    private fun setupText(e: JSONObject, short: Float) {
        tp.reset(); tp.flags = Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG
        tp.typeface = font(e.optString("font", "tajawal_medium"))
        tp.textSize = e.optDouble("size", 0.07).toFloat() * short
        tp.color = col(e.optString("color"))
        if (e.optString("style") == "outline") { tp.style = Paint.Style.STROKE; tp.strokeWidth = max(1f, tp.textSize * 0.025f) }
        e.optJSONObject("shadow")?.let { sh ->
            tp.setShadowLayer(max(0.5f, sh.optDouble("r").toFloat() * short), sh.optDouble("dx").toFloat() * short, sh.optDouble("dy").toFloat() * short, col(sh.optString("color"), 0x99000000.toInt()))
        }
    }

    fun draw(c: Canvas, m: JSONObject, w: Int, h: Int, photo: Bitmap?, design: ThemeDesign?, cal: Calendar = Calendar.getInstance()) {
        val short = min(w, h).toFloat()
        val bg = m.optJSONObject("background") ?: JSONObject()
        val colors = bg.optJSONArray("colors")
        when (bg.optString("type")) {
            "image" -> {
                val src = bg.optString("src")
                val bmp = if (src.startsWith("asset:")) asset(src.removePrefix("asset:")) else photo
                when {
                    bmp == null -> c.drawColor(Color.DKGRAY)
                    src.startsWith("asset:") -> {   // center-crop like the watch does
                        val s = max(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
                        c.save(); c.translate((w - bmp.width * s) / 2f, (h - bmp.height * s) / 2f); c.scale(s, s)
                        c.drawBitmap(bmp, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG)); c.restore()
                    }
                    design != null -> c.drawBitmap(bmp, design.photoMatrix(bmp, w.toFloat(), h.toFloat()), Paint(Paint.FILTER_BITMAP_FLAG))
                }
            }
            "solid" -> c.drawColor(col(colors?.optString(0), Color.BLACK))
            else -> { p.reset(); p.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), col(colors?.optString(0)), col(colors?.optString(1), Color.BLACK), Shader.TileMode.CLAMP); c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p); p.shader = null }
        }
        if (bg.has("overlay")) {
            p.reset(); p.isAntiAlias = true; val oc = col(bg.optString("overlayColor"))
            val rnd = java.util.Random(7)
            repeat(28) {
                p.color = Color.argb(60 + rnd.nextInt(120), Color.red(oc), Color.green(oc), Color.blue(oc))
                val kind = bg.optString("overlay")
                val r = when (kind) {
                    "bubbles" -> short * (0.01f + rnd.nextFloat() * 0.04f)
                    "sakura" -> short * (0.012f + rnd.nextFloat() * 0.012f)
                    "clouds" -> short * (0.06f + rnd.nextFloat() * 0.06f)
                    "lanterns" -> short * (0.03f + rnd.nextFloat() * 0.02f)
                    else -> short * (0.004f + rnd.nextFloat() * 0.007f)
                }
                if (kind == "clouds") p.color = Color.argb(40, 255, 255, 255)
                if (kind == "lanterns" && it > 3) return@repeat
                if (bg.optString("overlay") == "bubbles") { p.style = Paint.Style.STROKE; p.strokeWidth = short * 0.004f } else p.style = Paint.Style.FILL
                c.drawCircle(rnd.nextFloat() * w, rnd.nextFloat() * h, r, p)
            }
        }
        val scrim = bg.optDouble("scrim", 0.0)
        if (scrim > 0) c.drawColor(Color.argb((scrim * 255).toInt().coerceIn(0, 255), 0, 0, 0))

        val els = m.optJSONArray("elements") ?: JSONArray()
        for (i in 0 until els.length()) {
            val e = els.getJSONObject(i)
            when (e.optString("type")) {
                "digitalTime" -> {
                    setupText(e, short)
                    val f = if (android.text.format.DateFormat.is24HourFormat(ctx)) "HH:mm" else "h:mm"
                    place(c, SimpleDateFormat(f, Locale.US).format(cal.time), e.optDouble("x").toFloat() * w, e.optDouble("y").toFloat() * h, true)
                }
                "text" -> { setupText(e, short); place(c, fill(e.optString("text"), cal), e.optDouble("x").toFloat() * w, e.optDouble("y").toFloat() * h, false) }
                "gauge" -> gauge(c, e, w, h, short)
                "ticks" -> ticks(c, e, w, h, short)
                "analog" -> hands(c, e, w, h, short, cal)
            }
        }
    }

    private fun gauge(c: Canvas, e: JSONObject, w: Int, h: Int, short: Float) {
        val cx = e.optDouble("cx").toFloat() * w; val cy = e.optDouble("cy").toFloat() * h; val r = e.optDouble("radius").toFloat() * short
        val color = col(e.optString("color"))
        val frac = if (e.optString("source") == "battery") battery / 100f else (steps.toFloat() / goal).coerceAtMost(1f)
        p.reset(); p.isAntiAlias = true
        p.color = 0x66000000; c.drawCircle(cx, cy, r, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = r * 0.16f; p.strokeCap = Paint.Cap.ROUND
        val o = RectF(cx - r * 0.86f, cy - r * 0.86f, cx + r * 0.86f, cy + r * 0.86f)
        p.color = Color.argb(60, Color.red(color), Color.green(color), Color.blue(color)); c.drawArc(o, 135f, 270f, false, p)
        p.color = color; c.drawArc(o, 135f, 270f * frac, false, p)
        tp.reset(); tp.isAntiAlias = true; tp.typeface = font("tajawal_bold"); tp.color = Color.WHITE; tp.textSize = r * 0.52f
        place(c, if (e.optString("source") == "battery") "$battery" else if (steps >= 1000) "%.1fk".format(steps / 1000f) else "$steps", cx, cy, true)
    }

    private fun ticks(c: Canvas, e: JSONObject, w: Int, h: Int, short: Float) {
        val cx = e.optDouble("cx").toFloat() * w; val cy = e.optDouble("cy").toFloat() * h; val r = e.optDouble("radius").toFloat() * short
        val n = e.optInt("count", 60); val major = e.optInt("major", 5)
        p.reset(); p.isAntiAlias = true; p.strokeCap = Paint.Cap.ROUND
        for (i in 0 until n) {
            val big = i % major == 0
            val a = Math.toRadians(i * 360.0 / n - 90)
            val len = if (big) r * 0.09f else r * 0.04f
            p.color = col(if (big) e.optString("majorColor") else e.optString("color")); p.strokeWidth = if (big) r * 0.025f else r * 0.01f
            c.drawLine(cx + (r * cos(a)).toFloat(), cy + (r * sin(a)).toFloat(), cx + ((r - len) * cos(a)).toFloat(), cy + ((r - len) * sin(a)).toFloat(), p)
        }
        if (e.optString("numerals") == "quarters") {
            tp.reset(); tp.isAntiAlias = true; tp.typeface = font("tajawal_bold"); tp.color = col(e.optString("majorColor")); tp.textSize = e.optDouble("numeralSize", 0.09).toFloat() * short
            listOf(12, 3, 6, 9).forEachIndexed { i, v ->
                val a = Math.toRadians(i * 90.0 - 90); val rr = r * 0.76f
                place(c, "$v", cx + (rr * cos(a)).toFloat(), cy + (rr * sin(a)).toFloat(), true)
            }
        }
    }

    private fun hands(c: Canvas, e: JSONObject, w: Int, h: Int, short: Float, cal: Calendar) {
        val cx = e.optDouble("cx").toFloat() * w; val cy = e.optDouble("cy").toFloat() * h; val r = e.optDouble("radius").toFloat() * short
        val hr = cal.get(Calendar.HOUR) + cal.get(Calendar.MINUTE) / 60f; val mn = cal.get(Calendar.MINUTE) + cal.get(Calendar.SECOND) / 60f
        p.reset(); p.isAntiAlias = true; p.strokeCap = Paint.Cap.ROUND
        fun hand(angle: Float, len: Float, width: Float, color: Int) {
            val a = Math.toRadians(angle - 90.0); p.color = color; p.strokeWidth = width
            c.drawLine(cx, cy, cx + (len * cos(a)).toFloat(), cy + (len * sin(a)).toFloat(), p)
        }
        p.setShadowLayer(r * 0.03f, 0f, r * 0.01f, 0x88000000.toInt())
        hand(hr * 30f, r * 0.55f, r * 0.06f, col(e.optString("hourColor")))
        hand(mn * 6f, r * 0.82f, r * 0.04f, col(e.optString("minuteColor")))
        if (e.optBoolean("seconds", true)) hand(cal.get(Calendar.SECOND) * 6f, r * 0.9f, r * 0.015f, col(e.optString("secondColor")))
        p.clearShadowLayer(); p.color = col(e.optString("centerColor")); c.drawCircle(cx, cy, r * 0.045f, p)
    }
}
