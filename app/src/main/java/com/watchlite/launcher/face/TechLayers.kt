package com.watchlite.launcher.face

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Printed-circuit traces with light pulses travelling along them (overlay on an image or on dark green). */
class CircuitLayer(private val color: Int, private val speed: Float, private val fill: Boolean) : Layer {
    override val animated = true
    private val traces = ArrayList<Path>()
    private val lengths = ArrayList<Float>()
    private val measures = ArrayList<PathMeasure>()
    private var lw = 0; private var lh = 0
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val pad = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pulse = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pos = FloatArray(2)
    private val ends = ArrayList<FloatArray>()

    private fun build(w: Int, h: Int) {
        traces.clear(); lengths.clear(); measures.clear(); ends.clear()
        val rnd = Random(77)
        val g = min(w, h) / 14f
        repeat(16) {
            val p = Path()
            var x = (rnd.nextInt(14) + 0.5f) * g * w / min(w, h)
            var y = if (rnd.nextBoolean()) -g else h + g
            val dirY = if (y < 0) 1 else -1
            p.moveTo(x, y)
            ends.add(floatArrayOf(x, y))
            repeat(4 + rnd.nextInt(3)) {
                when (rnd.nextInt(3)) {
                    0 -> y += dirY * g * (2 + rnd.nextInt(4))
                    1 -> { val d = g * (1 + rnd.nextInt(3)) * (if (rnd.nextBoolean()) 1 else -1); x += d; y += dirY * kotlin.math.abs(d) }   // 45°
                    else -> x += g * (2 + rnd.nextInt(3)) * (if (rnd.nextBoolean()) 1 else -1)
                }
                p.lineTo(x, y)
            }
            ends.add(floatArrayOf(x, y))
            traces.add(p)
            val m = PathMeasure(p, false); measures.add(m); lengths.add(m.length)
        }
        lw = w; lh = h
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (lw != w || lh != h) build(w, h)
        if (fill) c.drawColor(0xFF03140E.toInt())
        val m = min(w, h)
        line.strokeWidth = max(1f, m * 0.006f)
        line.color = (color and 0x00FFFFFF) or 0x55000000
        for (p in traces) c.drawPath(p, line)
        pad.color = (color and 0x00FFFFFF) or 0x99000000.toInt()
        for (e in ends) c.drawCircle(e[0], e[1], m * 0.011f, pad)
        val s = t / 1000f * speed
        for (i in traces.indices) {
            val len = lengths[i]; if (len <= 0f) continue
            val d = ((s * 0.35f + i * 0.137f) % 1f) * len
            measures[i].getPosTan(d, pos, null)
            glow.color = (color and 0x00FFFFFF) or 0x44000000
            c.drawCircle(pos[0], pos[1], m * 0.022f, glow)
            pulse.color = color
            c.drawCircle(pos[0], pos[1], m * 0.008f, pulse)
        }
    }
}

/** Sci-fi HUD: rotating segmented rings, ticks and a scanning arc. */
class HudLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val col = if (colors.isNotEmpty()) colors[0] else 0xFF00E5FF.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bg = Paint(Paint.DITHER_FLAG)
    private val r = RectF()
    private var lw = 0

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val cx = w / 2f; val cy = h / 2f; val m = min(w, h)
        if (lw != w) { bg.shader = RadialGradient(cx, cy, max(w, h) * 0.7f, 0xFF061826.toInt(), 0xFF010409.toInt(), Shader.TileMode.CLAMP); lw = w }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)
        val s = t / 1000f * speed
        fun ring(rad: Float, width: Float, segs: Int, gap: Float, rot: Float, alpha: Int) {
            r.set(cx - rad, cy - rad, cx + rad, cy + rad)
            p.strokeWidth = width; p.color = (col and 0x00FFFFFF) or (alpha shl 24); p.strokeCap = Paint.Cap.BUTT
            val seg = 360f / segs
            for (i in 0 until segs) c.drawArc(r, rot + i * seg, seg - gap, false, p)
        }
        ring(m * 0.46f, m * 0.008f, 60, 3f, s * 6f, 0x66)
        ring(m * 0.42f, m * 0.02f, 6, 14f, -s * 18f, 0xCC)
        ring(m * 0.36f, m * 0.006f, 1, 0f, 0f, 0x44)
        ring(m * 0.33f, m * 0.012f, 3, 50f, s * 40f, 0x99)
        // scanning arc
        r.set(cx - m * 0.39f, cy - m * 0.39f, cx + m * 0.39f, cy + m * 0.39f)
        p.strokeWidth = m * 0.03f; p.strokeCap = Paint.Cap.ROUND
        p.color = (col and 0x00FFFFFF) or 0x55000000
        c.drawArc(r, (s * 90f) % 360f, 40f, false, p)
        // corner brackets
        p.strokeWidth = m * 0.008f; p.color = (col and 0x00FFFFFF) or 0x88000000.toInt(); p.strokeCap = Paint.Cap.SQUARE
        val b = m * 0.08f; val in_ = m * 0.05f
        c.drawLine(in_, in_, in_ + b, in_, p); c.drawLine(in_, in_, in_, in_ + b, p)
        c.drawLine(w - in_, in_, w - in_ - b, in_, p); c.drawLine(w - in_, in_, w - in_, in_ + b, p)
        c.drawLine(in_, h - in_, in_ + b, h - in_, p); c.drawLine(in_, h - in_, in_, h - in_ - b, p)
        c.drawLine(w - in_, h - in_, w - in_ - b, h - in_, p); c.drawLine(w - in_, h - in_, w - in_, h - in_ - b, p)
    }
}

/** Hexagon grid with a slow wave of light passing through it. */
class HexGridLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val col = if (colors.isNotEmpty()) colors[0] else 0xFF7C4DFF.toInt()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val f = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hex = Path()

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        c.drawColor(0xFF07060F.toInt())
        val m = min(w, h); val rad = m * 0.07f
        val dx = rad * 1.732f; val dy = rad * 1.5f
        val s = t / 1000f * speed
        p.strokeWidth = max(1f, m * 0.004f)
        var row = 0; var y = -rad
        while (y < h + rad) {
            var x = if (row % 2 == 0) 0f else dx / 2
            while (x < w + dx) {
                val wave = 0.5f + 0.5f * sin((x * 0.012f + y * 0.018f) - s * 1.6f)
                val a = (30 + 150 * wave * wave).toInt()
                hex.reset()
                for (k in 0 until 6) {
                    val ang = Math.toRadians((60 * k - 30).toDouble())
                    val px = x + rad * 0.92f * cos(ang).toFloat(); val py = y + rad * 0.92f * sin(ang).toFloat()
                    if (k == 0) hex.moveTo(px, py) else hex.lineTo(px, py)
                }
                hex.close()
                if (wave > 0.85f) { f.color = (col and 0x00FFFFFF) or (((wave - 0.85f) * 300).toInt().coerceIn(0, 60) shl 24); c.drawPath(hex, f) }
                p.color = (col and 0x00FFFFFF) or (a shl 24)
                c.drawPath(hex, p)
                x += dx
            }
            y += dy; row++
        }
    }
}
