package com.watchlite.launcher.face

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Falling cherry-blossom petals that flutter (scale-flip) and spin (overlay). */
class SakuraLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    private val n = 22
    private val rnd = Random(21)
    private val xs = FloatArray(n) { rnd.nextFloat() }
    private val ys = FloatArray(n) { rnd.nextFloat() * 1.1f - 0.1f }
    private val sz = FloatArray(n) { 0.012f + rnd.nextFloat() * 0.016f }
    private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }
    private val vy = FloatArray(n) { 0.045f + rnd.nextFloat() * 0.05f }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val petal = Path()
    private var last = 0L
    private val light = blend(color, Color.WHITE, 0.55f)

    init {
        // unit petal: rounded teardrop with a small notch at the tip
        petal.moveTo(0f, -1f)
        petal.cubicTo(0.9f, -0.9f, 0.9f, 0.6f, 0.12f, 1f)
        petal.lineTo(0f, 0.82f); petal.lineTo(-0.12f, 1f)
        petal.cubicTo(-0.9f, 0.6f, -0.9f, -0.9f, 0f, -1f)
        petal.close()
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        val m = min(w, h); val s = t / 1000f
        for (i in 0 until n) {
            ys[i] += vy[i] * dt * speed
            xs[i] += 0.02f * dt * speed
            if (ys[i] > 1.08f) { ys[i] = -0.06f; xs[i] = rnd.nextFloat() * 1.1f - 0.1f }
            if (xs[i] > 1.08f) xs[i] -= 1.16f
            val x = (xs[i] + 0.03f * sin(s * 0.9f + ph[i])) * w
            val y = ys[i] * h
            val r = sz[i] * m
            c.save()
            c.translate(x, y)
            c.rotate(((s * 60f * (0.6f + sz[i] * 30f)) + ph[i] * 57f) % 360f)
            c.scale(r * (0.35f + 0.65f * kotlin.math.abs(sin(s * 1.7f + ph[i]))), r)
            p.color = if (i % 3 == 0) light else color
            p.alpha = 225
            c.drawPath(petal, p)
            c.restore()
        }
    }

    private fun blend(a: Int, b: Int, k: Float) = Color.rgb(
        (Color.red(a) * (1 - k) + Color.red(b) * k).toInt(), (Color.green(a) * (1 - k) + Color.green(b) * k).toInt(),
        (Color.blue(a) * (1 - k) + Color.blue(b) * k).toInt())
}

/** Ramadan lanterns (fanoos) hanging from the top edge, swinging gently with a warm flicker (overlay). */
class LanternLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    // x position, string length (fraction of height), size, phase
    private val spec = arrayOf(floatArrayOf(0.62f, 0.10f, 1f, 0f), floatArrayOf(0.80f, 0.20f, 0.8f, 1.7f), floatArrayOf(0.93f, 0.06f, 0.65f, 3.1f))
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val frame = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glass = Paint(Paint.ANTI_ALIAS_FLAG)
    private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
    private val body = Path(); private val cap = Path(); private val bottom = Path()
    private val gold = 0xFFC9A04A.toInt()
    private var lastM = 0

    private fun build(u: Float) {
        // lantern in local units: top at 0, height ~ 3.2u, width 1.2u
        body.reset()
        body.moveTo(-0.45f * u, 0.55f * u); body.lineTo(0.45f * u, 0.55f * u)
        body.lineTo(0.62f * u, 1.25f * u); body.lineTo(0.45f * u, 2.3f * u)
        body.lineTo(-0.45f * u, 2.3f * u); body.lineTo(-0.62f * u, 1.25f * u); body.close()
        cap.reset()
        cap.moveTo(-0.12f * u, 0f); cap.lineTo(0.12f * u, 0f); cap.lineTo(0.55f * u, 0.55f * u); cap.lineTo(-0.55f * u, 0.55f * u); cap.close()
        bottom.reset()
        bottom.moveTo(-0.5f * u, 2.3f * u); bottom.lineTo(0.5f * u, 2.3f * u); bottom.lineTo(0.18f * u, 2.75f * u); bottom.lineTo(-0.18f * u, 2.75f * u); bottom.close()
        bottom.moveTo(-0.05f * u, 2.75f * u); bottom.lineTo(0.05f * u, 2.75f * u); bottom.lineTo(0f, 3.2f * u); bottom.close()
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val m = min(w, h)
        val s = t / 1000f * speed
        for ((k, sp) in spec.withIndex()) {
            val u = m * 0.07f * sp[2]
            if (lastM != m) { lastM = m }
            build(u)
            val ax = sp[0] * w; val len = sp[1] * h
            val ang = 7f * sin(s * 0.9f + sp[3])
            val flick = 0.85f + 0.15f * sin(s * 7f + k * 2f) * sin(s * 3.3f + k)
            c.save()
            c.translate(ax, 0f); c.rotate(ang)
            line.color = 0xAA8C7A55.toInt(); line.strokeWidth = m * 0.004f
            c.drawLine(0f, 0f, 0f, len, line)
            c.translate(0f, len)
            val cy = 1.4f * u
            halo.shader = RadialGradient(0f, cy, u * 2.6f, intArrayOf(Color.argb((110 * flick).toInt(), Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(0f, cy, u * 2.6f, halo)
            glass.shader = LinearGradient(0f, 0.55f * u, 0f, 2.3f * u,
                intArrayOf(Color.argb(255, 255, (230 * flick).toInt(), 160), color, 0xFF8A3B10.toInt()), floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP)
            c.drawPath(body, glass)
            frame.color = gold
            c.drawPath(cap, frame); c.drawPath(bottom, frame)
            line.color = gold; line.strokeWidth = u * 0.07f
            c.drawPath(body, line)
            c.drawLine(0f, 0.55f * u, 0f, 2.3f * u, line)                 // window bars
            c.drawLine(-0.62f * u, 1.25f * u, 0.62f * u, 1.25f * u, line)
            c.drawCircle(0f, -0.08f * u, 0.12f * u, frame)                // hanging ring
            c.restore()
        }
    }
}

/** Soft clouds drifting slowly across the sky (overlay). Cloud sprites are rendered once. */
class CloudsLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    private val rnd = Random(33)
    private val n = 4
    private val xs = FloatArray(n) { rnd.nextFloat() * 1.4f - 0.2f }
    private val ys = FloatArray(n) { 0.06f + rnd.nextFloat() * 0.38f }
    private val sc = FloatArray(n) { 0.6f + rnd.nextFloat() * 0.6f }
    private var sprite: Bitmap? = null
    private var sw = 0
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    private var last = 0L

    private fun sprite(m: Int): Bitmap {
        sprite?.let { if (sw == m) return it }
        sprite?.recycle()
        val bw = (m * 0.55f).toInt(); val bh = (m * 0.22f).toInt()
        val b = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = Random(5)
        val puff = Paint(Paint.ANTI_ALIAS_FLAG)
        repeat(18) {
            val x = bw * (0.15f + r.nextFloat() * 0.7f); val y = bh * (0.45f + r.nextFloat() * 0.3f)
            val rad = bh * (0.22f + r.nextFloat() * 0.25f)
            puff.shader = RadialGradient(x, y, rad, intArrayOf(Color.argb(150, Color.red(color), Color.green(color), Color.blue(color)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
            c.drawCircle(x, y, rad, puff)
        }
        sprite = b; sw = m
        return b
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        val m = min(w, h)
        val b = sprite(m)
        for (i in 0 until n) {
            xs[i] += 0.012f * dt * speed * (0.6f + sc[i] * 0.5f)
            if (xs[i] > 1.25f) { xs[i] = -0.5f; ys[i] = 0.06f + rnd.nextFloat() * 0.38f }
            c.save()
            c.translate(xs[i] * w, ys[i] * h)
            c.scale(sc[i], sc[i])
            p.alpha = (150 + 90 * sc[i]).toInt().coerceAtMost(255)
            c.drawBitmap(b, 0f, 0f, p)
            c.restore()
        }
    }

    override fun release() { sprite?.recycle(); sprite = null }
}

/**
 * Islamic geometric rosette: concentric 8- and 16-point star rings turning slowly in
 * opposite directions over a deep gradient (full background).
 * colors: [0] lines (gold), [1] top, [2] bottom.
 */
class RosetteLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val lineCol = colors.getOrElse(0) { 0xFFD4AF5A.toInt() }
    private val top = colors.getOrElse(1) { 0xFF06261F.toInt() }
    private val bot = colors.getOrElse(2) { 0xFF0B3F33.toInt() }
    private val bg = Paint(Paint.DITHER_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND }
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rings = ArrayList<Path>()
    private var lw = 0; private var lh = 0

    private fun star(points: Int, r1: Float, r2: Float, rot: Float): Path {
        val p = Path()
        for (k in 0 until points * 2) {
            val r = if (k % 2 == 0) r1 else r2
            val a = PI * k / points + rot
            val x = (r * cos(a)).toFloat(); val y = (r * sin(a)).toFloat()
            if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        p.close(); return p
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val m = min(w, h).toFloat()
        if (lw != w || lh != h) {
            lw = w; lh = h
            bg.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), top, bot, Shader.TileMode.CLAMP)
            rings.clear()
            val R = m * 0.44f
            rings += star(16, R, R * 0.82f, 0f)
            rings += star(8, R * 0.78f, R * 0.52f, 0f)
            rings += star(8, R * 0.78f, R * 0.52f, (PI / 8).toFloat())
            rings += star(16, R * 0.5f, R * 0.38f, 0f)
            rings += star(8, R * 0.34f, R * 0.18f, 0f)
            glowP.shader = RadialGradient(0f, 0f, R * 1.1f, intArrayOf(Color.argb(70, Color.red(lineCol), Color.green(lineCol), Color.blue(lineCol)), Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val s = t / 1000f * speed
        c.save()
        c.translate(w / 2f, h * 0.6f)
        c.drawCircle(0f, 0f, m * 0.5f, glowP)
        line.color = lineCol
        rings.forEachIndexed { i, p ->
            c.save()
            c.rotate((if (i % 2 == 0) 1 else -1) * s * (3f + i * 1.5f))
            line.strokeWidth = m * (if (i == 0) 0.006f else 0.0045f)
            line.alpha = if (i == 0) 255 else 200
            c.drawPath(p, line)
            c.restore()
        }
        c.restore()
    }
}
