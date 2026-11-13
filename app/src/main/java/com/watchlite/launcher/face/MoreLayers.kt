package com.watchlite.launcher.face

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Rain streaks falling at a slight angle; fills with a night gradient unless used as overlay. */
class RainLayer(private val color: Int, private val speed: Float, private val fill: Boolean) : Layer {
    override val animated = true
    private val n = 70
    private val rnd = Random(21)
    private val xs = FloatArray(n) { rnd.nextFloat() }
    private val ys = FloatArray(n) { rnd.nextFloat() }
    private val ls = FloatArray(n) { 0.03f + rnd.nextFloat() * 0.05f }
    private val vs = FloatArray(n) { 0.8f + rnd.nextFloat() * 0.6f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val bg = Paint(Paint.DITHER_FLAG)
    private var lw = 0
    private var last = 0L

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (fill) {
            if (lw != w) { bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), 0xFF0A1220.toInt(), 0xFF1B2A3F.toInt(), Shader.TileMode.CLAMP); lw = w }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        }
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        p.color = color; p.strokeWidth = max(1f, w * 0.004f)
        for (i in 0 until n) {
            ys[i] += vs[i] * dt * speed
            if (ys[i] > 1.1f) { ys[i] = -0.1f; xs[i] = rnd.nextFloat() }
            val x = xs[i] * w; val y = ys[i] * h; val l = ls[i] * h
            p.alpha = (60 + vs[i] * 60).toInt()
            c.drawLine(x, y, x - l * 0.15f, y - l, p)
        }
    }
}

/** Falling columns of digits/glyphs ("digital rain"). */
class MatrixLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    private val rnd = Random(9)
    private var cols = 0
    private var heads = FloatArray(0)
    private var vel = FloatArray(0)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = android.graphics.Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val glyphs = "0123456789ABCDEF٠١٢٣٤٥٦٧٨٩".toCharArray()
    private var last = 0L
    private val buf = CharArray(1)

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val cell = min(w, h) * 0.055f
        val nc = (w / cell).toInt().coerceAtLeast(4)
        if (nc != cols) {
            cols = nc
            heads = FloatArray(nc) { rnd.nextFloat() * h }
            vel = FloatArray(nc) { 0.25f + rnd.nextFloat() * 0.5f }
        }
        c.drawColor(0xFF000300.toInt())
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        tp.textSize = cell * 0.9f
        val rows = (h / cell).toInt() + 1
        for (i in 0 until cols) {
            heads[i] += vel[i] * dt * speed * h
            if (heads[i] - rows * cell * 0.6f > h) { heads[i] = 0f; vel[i] = 0.25f + rnd.nextFloat() * 0.5f }
            val x = cell * (i + 0.5f)
            val headRow = (heads[i] / cell).toInt()
            for (k in 0 until 14) {
                val r = headRow - k
                if (r < 0 || r > rows) continue
                val a = if (k == 0) 255 else (220 * (1f - k / 14f)).toInt()
                tp.color = if (k == 0) 0xFFE8FFE8.toInt() else color
                tp.alpha = a
                buf[0] = glyphs[((r * 31 + i * 17 + (t / 400).toInt() * (if (k % 3 == 0) 1 else 0)) and 0x7fffffff) % glyphs.size]
                c.drawText(buf, 0, 1, x, r * cell, tp)
            }
        }
    }
}

/** Soft glowing particles drifting around (overlay). */
class FirefliesLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    private val n = 22
    private val rnd = Random(4)
    private val px = FloatArray(n) { rnd.nextFloat() }
    private val py = FloatArray(n) { 0.35f + rnd.nextFloat() * 0.65f }
    private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val core = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val s = t / 1000f * speed
        val m = min(w, h)
        for (i in 0 until n) {
            val x = (px[i] + 0.06f * sin(s * 0.35f + ph[i]) + 0.03f * sin(s * 0.9f + i)) * w
            val y = (py[i] + 0.05f * cos(s * 0.3f + ph[i] * 1.3f)) * h
            val a = (0.5f + 0.5f * sin(s * 1.6f + ph[i])).let { it * it }
            glow.color = color; glow.alpha = (a * 70).toInt()
            c.drawCircle(x, y, m * 0.03f, glow)
            core.color = color; core.alpha = (a * 255).toInt()
            c.drawCircle(x, y, m * 0.007f, core)
        }
    }
}

/** Gently falling snow (overlay). */
class SnowLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    private val n = 55
    private val rnd = Random(12)
    private val xs = FloatArray(n) { rnd.nextFloat() }
    private val ys = FloatArray(n) { rnd.nextFloat() }
    private val rs = FloatArray(n) { 0.004f + rnd.nextFloat() * 0.01f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var last = 0L

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        val m = min(w, h)
        p.color = color
        for (i in 0 until n) {
            ys[i] += (0.05f + rs[i] * 6f) * dt * speed
            if (ys[i] > 1.05f) { ys[i] = -0.05f; xs[i] = rnd.nextFloat() }
            val x = (xs[i] + 0.02f * sin(t / 1300f + i)) * w
            p.alpha = (120 + rs[i] * 9000).toInt().coerceAtMost(240)
            c.drawCircle(x, ys[i] * h, rs[i] * m, p)
        }
    }
}

/** Tactical radar: rings, sweeping beam and fading blips. */
class RadarLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val col = if (colors.isNotEmpty()) colors[0] else 0xFF30D158.toInt()
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val beam = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blip = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bg = Paint(Paint.DITHER_FLAG)
    private var lw = 0
    private val rnd = Random(30)
    private val ba = FloatArray(7) { rnd.nextFloat() * 360f }
    private val br = FloatArray(7) { 0.2f + rnd.nextFloat() * 0.75f }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val cx = w / 2f; val cy = h / 2f; val r = min(w, h) * 0.47f
        if (lw != w) {
            bg.shader = RadialGradient(cx, cy, max(w, h) * 0.7f, 0xFF04140A.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            beam.shader = SweepGradient(cx, cy, intArrayOf(0x00000000, 0x00000000, (col and 0x00FFFFFF) or 0x99000000.toInt()), floatArrayOf(0f, 0.8f, 1f))
            lw = w
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        ring.color = col; ring.strokeWidth = max(1f, r * 0.008f)
        for (k in 1..4) { ring.alpha = 70; c.drawCircle(cx, cy, r * k / 4f, ring) }
        ring.alpha = 50
        c.drawLine(cx - r, cy, cx + r, cy, ring); c.drawLine(cx, cy - r, cx, cy + r, ring)
        val ang = (t / 1000f * 90f * speed) % 360f
        c.save(); c.rotate(ang, cx, cy); c.drawCircle(cx, cy, r, beam); c.restore()
        for (i in ba.indices) {
            var d = (ang - ba[i]) % 360f; if (d < 0) d += 360f
            val a = (1f - d / 300f).coerceIn(0f, 1f)
            if (a <= 0f) continue
            val rad = Math.toRadians(ba[i].toDouble())
            blip.color = col; blip.alpha = (a * 255).toInt()
            c.drawCircle(cx + cos(rad).toFloat() * r * br[i], cy + sin(rad).toFloat() * r * br[i], r * 0.025f, blip)
        }
    }
}

/** Little solar system: planets orbiting a glowing sun. */
class OrbitLayer(private val speed: Float) : Layer {
    override val animated = true
    private val orbit = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x33FFFFFF }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sun = Paint(Paint.ANTI_ALIAS_FLAG)
    private var lw = 0
    private val planets = arrayOf(
        floatArrayOf(0.16f, 0.018f, 4.0f),
        floatArrayOf(0.24f, 0.026f, 2.6f),
        floatArrayOf(0.33f, 0.028f, 1.8f),
        floatArrayOf(0.41f, 0.022f, 1.3f),
        floatArrayOf(0.48f, 0.04f, 0.7f)
    )
    private val colors = intArrayOf(0xFFB0A090.toInt(), 0xFFE8B86A.toInt(), 0xFF4A90E2.toInt(), 0xFFD0533A.toInt(), 0xFFD8B48A.toInt())

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        c.drawColor(0xFF03030A.toInt())
        val cx = w / 2f; val cy = h / 2f; val m = min(w, h)
        if (lw != w) { sun.shader = RadialGradient(cx, cy, m * 0.12f, intArrayOf(0xFFFFF2C0.toInt(), 0xFFFFA43A.toInt(), 0x00FF6A00), floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP); lw = w }
        c.drawCircle(cx, cy, m * 0.12f, sun)
        val s = t / 1000f * speed * 0.25f
        orbit.strokeWidth = max(1f, m * 0.003f)
        for ((i, pl) in planets.withIndex()) {
            val r = pl[0] * m
            c.drawOval(cx - r, cy - r * 0.9f, cx + r, cy + r * 0.9f, orbit)
            val a = s * pl[2] + i * 1.7f
            p.color = colors[i]
            c.drawCircle(cx + cos(a) * r, cy + sin(a) * r * 0.9f, pl[1] * m, p)
        }
    }
}
