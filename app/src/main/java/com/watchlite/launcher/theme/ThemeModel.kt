package com.watchlite.launcher.theme

import android.content.Context
import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.Locale

/** Where a theme's files live: inside the APK assets or in app storage. */
class ThemeSource(val assetDir: String?, val dir: File?) {
    fun open(ctx: Context, path: String): InputStream {
        if (path.startsWith("asset:")) return ctx.assets.open(path.removePrefix("asset:"))
        if (dir != null) {
            val f = File(dir, path)
            require(f.canonicalPath.startsWith(dir.canonicalPath)) { "path escapes theme" }
            return f.inputStream()
        }
        return ctx.assets.open("$assetDir/$path")
    }
}

/** A color that is either fixed or refers to a user-customizable slot. */
class Col(val value: Int, val ref: Int) {
    companion object {
        const val FIXED = 0
        const val ACCENT = 1
        const val TIME = 2
        val WHITE = Col(Color.WHITE, FIXED)

        fun parse(s: String?, def: Col): Col {
            if (s.isNullOrBlank()) return def
            return when (s) {
                "@accent" -> Col(0, ACCENT)
                "@time" -> Col(Color.WHITE, TIME)
                else -> try { Col(Color.parseColor(s), FIXED) } catch (_: Exception) { def }
            }
        }
    }
}

class Shadow(val r: Float, val dx: Float, val dy: Float, val color: Int)

/**
 * One drawable element of a watch face. All positions are normalized:
 * x/cx are fractions of width, y/cy fractions of height, sizes fractions
 * of the short side. A single flat class keeps parsing simple and drawing fast.
 */
class El(o: JSONObject) {
    val type: String = o.optString("type")
    val x = o.optDouble("x", 0.5).toFloat()
    val y = o.optDouble("y", 0.5).toFloat()
    val anchor: String = o.optString("anchor", "center")
    val size = o.optDouble("size", 0.1).toFloat()
    val font: String? = o.optString("font").ifEmpty { null }
    val alpha = o.optDouble("alpha", 1.0).toFloat()
    val role: String = o.optString("role", if (type == "digitalTime") "time" else "")
    val color: Col = Col.parse(o.optString("color"), if (role == "time") Col(Color.WHITE, Col.TIME) else Col.WHITE)
    val shadow: Shadow? = o.optJSONObject("shadow")?.let {
        Shadow(it.optDouble("r", 0.01).toFloat(), it.optDouble("dx", 0.0).toFloat(), it.optDouble("dy", 0.004).toFloat(),
            safeColor(it.optString("color"), 0x80000000.toInt()))
    }
    val glow = o.optBoolean("glow", false)

    // text / digital
    val format: String = o.optString("format", "auto")
    val text: String = o.optString("text", "")
    val upper = o.optBoolean("upper", false)
    val seconds = o.optBoolean("seconds", false)
    val style: String = o.optString("style", "fill")
    val ghost: String? = o.optString("ghost").ifEmpty { null }
    val tracking = o.optDouble("tracking", 0.0).toFloat()
    val digits: String? = o.optString("digits").ifEmpty { null }

    // geometry
    val cx = o.optDouble("cx", 0.5).toFloat()
    val cy = o.optDouble("cy", 0.5).toFloat()
    val radius = o.optDouble("radius", 0.45).toFloat()
    val w = o.optDouble("w", 0.3).toFloat()
    val h = o.optDouble("h", 0.1).toFloat()
    val width = o.optDouble("width", 0.03).toFloat()
    val corner = o.optDouble("corner", 0.5).toFloat()

    // analog
    val hourColor = Col.parse(o.optString("hourColor"), Col.WHITE)
    val minuteColor = Col.parse(o.optString("minuteColor"), Col.WHITE)
    val secondColor = Col.parse(o.optString("secondColor"), Col(0, Col.ACCENT))
    val centerColor = Col.parse(o.optString("centerColor"), Col.WHITE)
    val hourW = o.optDouble("hourW", 0.07).toFloat()
    val minuteW = o.optDouble("minuteW", 0.05).toFloat()
    val secondW = o.optDouble("secondW", 0.015).toFloat()
    val smooth = o.optBoolean("smooth", false)

    // ticks
    val count = o.optInt("count", 60)
    val major = o.optInt("major", 5)
    val minorLen = o.optDouble("minorLen", 0.04).toFloat()
    val majorLen = o.optDouble("majorLen", 0.09).toFloat()
    val minorW = o.optDouble("minorW", 0.006).toFloat()
    val majorW = o.optDouble("majorW", 0.016).toFloat()
    val majorColor = Col.parse(o.optString("majorColor"), color)
    val numerals: String = o.optString("numerals", "none")
    val numeralSize = o.optDouble("numeralSize", 0.11).toFloat()
    val numeralColor = Col.parse(o.optString("numeralColor"), color)
    val numeralInset = o.optDouble("numeralInset", 0.2).toFloat()

    // ring
    val source: String = o.optString("source", "battery")
    val trackColor = safeColor(o.optString("trackColor"), 0x33FFFFFF)
    val start = o.optDouble("start", -90.0).toFloat()

    val known get() = type in TYPES

    companion object {
        val TYPES = setOf("digitalTime", "text", "analog", "ticks", "ring", "batteryChip", "panel", "dot")
        fun safeColor(s: String?, def: Int) = try { if (s.isNullOrEmpty()) def else Color.parseColor(s) } catch (_: Exception) { def }
    }
}

class BgSpec(
    val type: String,          // image | solid | gradient | procedural
    val src: String?,
    val proc: String?,         // liquid | starfield | neongrid | waves | bubbles
    val colors: IntArray,
    val overlay: String?,      // twinkle | bubbles | ...
    val overlayColor: Int,
    val speed: Float,
    val scrim: Float           // 0..1 darkening for legibility
) {
    val animated get() = type == "procedural" || overlay != null
}

class Theme(
    val id: String,
    private val names: Map<String, String>,
    val author: String,
    val builtIn: Boolean,
    val source: ThemeSource,
    val bg: BgSpec,
    val elements: List<El>,
    val aod: List<El>,
    val accent: Int?,
    val fileDir: File?
) {
    fun name(locale: Locale = Locale.getDefault()): String =
        names[locale.language] ?: names["en"] ?: names.values.firstOrNull() ?: id

    val hasSeconds get() = elements.any { (it.type == "analog" || it.type == "digitalTime") && it.seconds } ||
        elements.any { it.type == "text" && it.text.contains("{second}") } ||
        elements.any { it.type == "ring" && it.source == "seconds" }
}

/** Per-theme user customization (stored in prefs as JSON). */
class ThemeCustom(val timeColor: Int?, val accent: Int?, val seconds: Boolean?) {
    fun toJson(): String = JSONObject().apply {
        timeColor?.let { put("time", it) }
        accent?.let { put("accent", it) }
        seconds?.let { put("seconds", it) }
    }.toString()

    companion object {
        fun parse(s: String): ThemeCustom = try {
            val o = JSONObject(s)
            ThemeCustom(
                if (o.has("time")) o.getInt("time") else null,
                if (o.has("accent")) o.getInt("accent") else null,
                if (o.has("seconds")) o.getBoolean("seconds") else null
            )
        } catch (_: Exception) { ThemeCustom(null, null, null) }
    }
}

object ThemeParser {
    const val MAX_ELEMENTS = 40

    fun parse(json: String, source: ThemeSource, builtIn: Boolean, dir: File?): Theme {
        val o = JSONObject(json)
        val schema = o.optInt("schema", 1)
        require(schema <= 1) { "unsupported schema $schema" }
        val id = sanitizeId(o.getString("id"))
        val names = HashMap<String, String>()
        when (val n = o.opt("name")) {
            is JSONObject -> n.keys().forEach { names[it] = n.getString(it) }
            is String -> names["en"] = n
        }
        val b = o.optJSONObject("background") ?: JSONObject()
        val colors = b.optJSONArray("colors")?.let { arr -> IntArray(arr.length()) { El.safeColor(arr.optString(it), 0xFF000000.toInt()) } }
            ?: intArrayOf(0xFF000000.toInt())
        val bg = BgSpec(
            type = b.optString("type", "solid"),
            src = b.optString("src").ifEmpty { null },
            proc = b.optString("procedural").ifEmpty { b.optString("name").ifEmpty { null } },
            colors = colors,
            overlay = b.optString("overlay").ifEmpty { null },
            overlayColor = El.safeColor(b.optString("overlayColor"), 0xFFFFFFFF.toInt()),
            speed = b.optDouble("speed", 1.0).toFloat().coerceIn(0.1f, 4f),
            scrim = b.optDouble("scrim", 0.0).toFloat().coerceIn(0f, 0.9f)
        )
        val els = elements(o.optJSONArray("elements"))
        val aod = elements(o.optJSONObject("aod")?.optJSONArray("elements"))
        val accent = o.optString("accent").ifEmpty { null }?.let { El.safeColor(it, 0) }
        return Theme(id, names, o.optString("author", ""), builtIn, source, bg, els, aod, accent, dir)
    }

    private fun elements(arr: JSONArray?): List<El> {
        if (arr == null) return emptyList()
        val out = ArrayList<El>()
        for (i in 0 until minOf(arr.length(), MAX_ELEMENTS)) {
            val e = El(arr.getJSONObject(i))
            if (e.known) out.add(e) else com.watchlite.launcher.util.Log.w("Theme", "unknown element ${e.type}")
        }
        return out
    }

    fun sanitizeId(id: String): String {
        val s = id.lowercase(Locale.US).replace(Regex("[^a-z0-9._-]"), "_").take(64)
        require(s.isNotEmpty() && s != "." && s != "..") { "bad id" }
        return s
    }
}
