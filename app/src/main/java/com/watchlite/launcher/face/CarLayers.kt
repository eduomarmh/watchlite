package com.watchlite.launcher.face

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Night highway seen from behind a car: moving lane markings, passing street
 * lights, a city skyline and glowing tail lights. Pure vector drawing.
 */
class NightDriveLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val accent = if (colors.isNotEmpty()) colors[0] else 0xFFFF2D55.toInt()
    private val skyP = Paint(Paint.DITHER_FLAG)
    private val roadP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lineP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cityP = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0B0B1A.toInt() }
    private val winP = Paint().apply { color = 0xFFFFC966.toInt() }
    private val bodyP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val glowP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lampP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val r = RectF()
    private var lw = 0; private var lh = 0
    private val rnd = Random(11)
    private val bx = FloatArray(26) { rnd.nextFloat() }
    private val bh = FloatArray(26) { 0.05f + rnd.nextFloat() * 0.14f }
    private val bwin = Array(26) { BooleanArray(12) { rnd.nextFloat() < 0.35f } }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val horizon = h * 0.46f
        if (lw != w || lh != h) {
            skyP.shader = LinearGradient(0f, 0f, 0f, horizon, 0xFF05030F.toInt(), 0xFF2A1240.toInt(), Shader.TileMode.CLAMP)
            roadP.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), 0xFF101018.toInt(), 0xFF22222C.toInt(), Shader.TileMode.CLAMP)
            lw = w; lh = h
        }
        val s = t / 1000f * speed
        c.drawRect(0f, 0f, w.toFloat(), horizon, skyP)
        // skyline
        for (i in bx.indices) {
            val bw = w * 0.07f
            val x = bx[i] * w * 1.1f - w * 0.05f
            val top = horizon - bh[i] * h
            c.drawRect(x, top, x + bw, horizon, cityP)
            for (k in 0 until 12) if (bwin[i][k]) {
                val wx = x + bw * (0.2f + (k % 3) * 0.25f); val wy = top + h * 0.012f + (k / 3) * h * 0.022f
                if (wy < horizon - h * 0.01f) c.drawRect(wx, wy, wx + bw * 0.12f, wy + h * 0.008f, winP)
            }
        }
        // road
        val cx = w / 2f
        path.reset()
        path.moveTo(cx - w * 0.03f, horizon); path.lineTo(cx + w * 0.03f, horizon)
        path.lineTo(w * 1.25f, h.toFloat()); path.lineTo(-w * 0.25f, h.toFloat()); path.close()
        c.drawPath(path, roadP)
        // edge lines
        lineP.color = 0x88FFFFFF.toInt(); lineP.strokeWidth = max(1f, w * 0.006f)
        c.drawLine(cx - w * 0.03f, horizon, -w * 0.2f, h.toFloat(), lineP)
        c.drawLine(cx + w * 0.03f, horizon, w * 1.2f, h.toFloat(), lineP)
        // moving center dashes (perspective)
        lineP.color = 0xFFFFE08A.toInt()
        val phase = (s * 1.4f) % 1f
        for (i in 0 until 8) {
            val z0 = ((i + phase) / 8f); val z1 = ((i + phase + 0.45f) / 8f)
            val y0 = horizon + (h - horizon) * z0 * z0; val y1 = horizon + (h - horizon) * z1 * z1
            lineP.strokeWidth = max(1f, w * 0.03f * z1)
            c.drawLine(cx, y0, cx, min(y1, h.toFloat()), lineP)
        }
        // street lights passing on both sides
        for (side in intArrayOf(-1, 1)) for (i in 0 until 3) {
            val z = ((i + (s * 0.9f) % 1f) / 3f)
            val zz = z * z
            val x = cx + side * (w * 0.06f + w * 0.9f * zz)
            val y = horizon + (h - horizon) * zz
            val pole = h * 0.5f * zz + 2f
            lineP.color = 0xFF2E2E3A.toInt(); lineP.strokeWidth = max(1f, w * 0.012f * zz)
            c.drawLine(x, y, x, y - pole, lineP)
            val lr = max(1.5f, w * 0.03f * zz)
            lampP.color = 0x30FFD27A; c.drawCircle(x, y - pole, lr * 4, lampP)
            lampP.color = 0x66FFD27A; c.drawCircle(x, y - pole, lr * 2, lampP)
            lampP.color = 0xFFFFE6B0.toInt(); c.drawCircle(x, y - pole, lr * 0.8f, lampP)
        }
        // car (rear view) with a tiny suspension bounce
        val bounce = sin(s * 9f) * h * 0.003f
        val cw = w * 0.56f; val ch = h * 0.17f
        val left = cx - cw / 2; val top = h * 0.74f + bounce
        bodyP.shader = LinearGradient(0f, top, 0f, top + ch, 0xFF2A2A33.toInt(), 0xFF0C0C10.toInt(), Shader.TileMode.CLAMP)
        // cabin
        path.reset()
        path.moveTo(left + cw * 0.2f, top); path.lineTo(left + cw * 0.8f, top)
        path.lineTo(left + cw * 0.9f, top + ch * 0.38f); path.lineTo(left + cw * 0.1f, top + ch * 0.38f); path.close()
        c.drawPath(path, bodyP)
        // rear window glint
        bodyP.shader = null; bodyP.color = 0xFF3B3F55.toInt()
        path.reset()
        path.moveTo(left + cw * 0.25f, top + ch * 0.06f); path.lineTo(left + cw * 0.75f, top + ch * 0.06f)
        path.lineTo(left + cw * 0.82f, top + ch * 0.32f); path.lineTo(left + cw * 0.18f, top + ch * 0.32f); path.close()
        c.drawPath(path, bodyP)
        // body
        bodyP.color = 0xFF15151B.toInt()
        r.set(left, top + ch * 0.34f, left + cw, top + ch); c.drawRoundRect(r, ch * 0.16f, ch * 0.16f, bodyP)
        // tail lights + glow (light bar style)
        val ly = top + ch * 0.52f
        glowP.shader = RadialGradient(cx, ly, cw * 0.75f, (accent and 0x00FFFFFF) or 0x66000000, accent and 0x00FFFFFF, Shader.TileMode.CLAMP)
        c.drawCircle(cx, ly, cw * 0.75f, glowP)
        bodyP.color = accent
        r.set(left + cw * 0.04f, ly - ch * 0.06f, left + cw * 0.96f, ly + ch * 0.05f); c.drawRoundRect(r, ch * 0.05f, ch * 0.05f, bodyP)
        bodyP.color = Color.WHITE; bodyP.alpha = 170
        r.set(left + cw * 0.06f, ly - ch * 0.02f, left + cw * 0.22f, ly + ch * 0.01f); c.drawRoundRect(r, 2f, 2f, bodyP)
        r.set(left + cw * 0.78f, ly - ch * 0.02f, left + cw * 0.94f, ly + ch * 0.01f); c.drawRoundRect(r, 2f, 2f, bodyP)
        bodyP.alpha = 255
        // tyres
        bodyP.color = 0xFF050507.toInt()
        r.set(left + cw * 0.06f, top + ch * 0.9f, left + cw * 0.22f, top + ch * 1.08f); c.drawRoundRect(r, 4f, 4f, bodyP)
        r.set(left + cw * 0.78f, top + ch * 0.9f, left + cw * 0.94f, top + ch * 1.08f); c.drawRoundRect(r, 4f, 4f, bodyP)
    }
}

/** Side view of a sports car racing along a coastline: parallax hills, spinning wheels, speed lines. */
class RacerLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val body = if (colors.isNotEmpty()) colors[0] else 0xFFE10600.toInt()
    private val skyP = Paint(Paint.DITHER_FLAG)
    private val hillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val roadP = Paint()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var lw = 0; private var lh = 0
    private val rnd = Random(5)
    private val lines = Array(10) { floatArrayOf(rnd.nextFloat(), 0.25f + rnd.nextFloat() * 0.5f, 0.2f + rnd.nextFloat() * 0.6f) }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (lw != w || lh != h) {
            skyP.shader = LinearGradient(0f, 0f, 0f, h * 0.7f, intArrayOf(0xFF0E1A3D.toInt(), 0xFF6A2C70.toInt(), 0xFFFF8C42.toInt()), null, Shader.TileMode.CLAMP)
            roadP.color = 0xFF17171C.toInt()
            lw = w; lh = h
        }
        val s = t / 1000f * speed
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), skyP)
        // sun
        p.shader = null; p.color = 0xFFFFD27A.toInt(); p.alpha = 230
        c.drawCircle(w * 0.72f, h * 0.48f, w * 0.13f, p); p.alpha = 255
        // parallax hills (2 layers)
        hill(c, w, h, h * 0.58f, h * 0.06f, 1.3f, s * 0.04f, 0xFF3A1F4F.toInt())
        hill(c, w, h, h * 0.64f, h * 0.045f, 2.1f, s * 0.12f, 0xFF231433.toInt())
        // road
        val roadTop = h * 0.68f
        c.drawRect(0f, roadTop, w.toFloat(), h.toFloat(), roadP)
        p.color = 0xFFFFFFFF.toInt(); p.alpha = 200
        val dash = w * 0.18f; val off = (s * w * 1.6f) % (dash * 2)
        var x = -off
        while (x < w) { c.drawRect(x, h * 0.84f, x + dash, h * 0.85f + 2f, p); x += dash * 2 }
        p.alpha = 255
        // speed lines
        p.strokeWidth = max(1f, h * 0.004f)
        for (l in lines) {
            val lx = w - ((l[0] + s * (0.9f + l[2])) % 1.3f) * w * 1.3f
            p.color = 0x55FFFFFF
            c.drawLine(lx, h * l[1], lx + w * 0.2f * l[2], h * l[1], p)
        }
        // car
        val cw = w * 0.62f; val ch = cw * 0.27f
        val left = w * 0.18f; val base = h * 0.8f + sin(s * 14f) * h * 0.002f
        path.reset()
        path.moveTo(left, base - ch * 0.25f)
        path.cubicTo(left, base - ch * 0.55f, left + cw * 0.12f, base - ch * 0.6f, left + cw * 0.3f, base - ch * 0.62f)
        path.cubicTo(left + cw * 0.4f, base - ch * 1.02f, left + cw * 0.62f, base - ch * 1.05f, left + cw * 0.74f, base - ch * 0.66f)
        path.cubicTo(left + cw * 0.9f, base - ch * 0.62f, left + cw, base - ch * 0.5f, left + cw, base - ch * 0.2f)
        path.lineTo(left + cw, base); path.lineTo(left, base); path.close()
        p.shader = LinearGradient(0f, base - ch, 0f, base, body, darken(body), Shader.TileMode.CLAMP)
        c.drawPath(path, p)
        p.shader = null
        // window
        path.reset()
        path.moveTo(left + cw * 0.36f, base - ch * 0.64f)
        path.cubicTo(left + cw * 0.44f, base - ch * 0.93f, left + cw * 0.6f, base - ch * 0.95f, left + cw * 0.68f, base - ch * 0.66f)
        path.close()
        p.color = 0xCC1A2238.toInt(); c.drawPath(path, p)
        // highlight stripe
        p.color = 0x55FFFFFF; c.drawRect(left + cw * 0.05f, base - ch * 0.4f, left + cw * 0.95f, base - ch * 0.37f, p)
        // headlight
        p.color = 0xFFFFF4C2.toInt(); c.drawCircle(left + cw * 0.97f, base - ch * 0.3f, ch * 0.06f, p)
        // wheels
        val wr = ch * 0.3f
        wheel(c, left + cw * 0.2f, base, wr, s)
        wheel(c, left + cw * 0.8f, base, wr, s)
    }

    private fun wheel(c: Canvas, x: Float, y: Float, r: Float, s: Float) {
        p.color = 0xFF050505.toInt(); c.drawCircle(x, y, r, p)
        p.color = 0xFF9A9AA5.toInt(); c.drawCircle(x, y, r * 0.62f, p)
        p.color = 0xFF2A2A30.toInt(); p.strokeWidth = r * 0.14f
        val a0 = -s * 25f
        for (i in 0 until 5) {
            val a = a0 + i * 1.2566f
            c.drawLine(x, y, x + cos(a) * r * 0.58f, y + sin(a) * r * 0.58f, p)
        }
        p.color = 0xFF111111.toInt(); c.drawCircle(x, y, r * 0.14f, p)
    }

    private fun hill(c: Canvas, w: Int, h: Int, base: Float, amp: Float, freq: Float, shift: Float, color: Int) {
        path.reset(); path.moveTo(0f, h.toFloat())
        var x = 0f; val step = w / 20f
        while (x <= w + step) {
            val u = x / w + shift
            path.lineTo(x, base - amp * (sin(u * 6.283f * freq) * 0.6f + sin(u * 6.283f * freq * 2.7f + 1f) * 0.4f))
            x += step
        }
        path.lineTo(w.toFloat(), h.toFloat()); path.close()
        hillP.color = color; c.drawPath(path, hillP)
    }

    private fun darken(c: Int) = Color.rgb(Color.red(c) / 3, Color.green(c) / 3, Color.blue(c) / 3)
}

/**
 * Sports dashboard gauge: 0-240 km/h dial, red zone, and a needle that
 * "revs" smoothly. The time itself is drawn by theme elements on top.
 */
class SpeedoLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val accent = if (colors.isNotEmpty()) colors[0] else 0xFFFF3B30.toInt()
    private val bgP = Paint(Paint.DITHER_FLAG)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; isFakeBoldText = true }
    private val r = RectF()
    private var lw = 0
    private val start = 150f
    private val sweep = 240f

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val cx = w / 2f; val cy = h / 2f; val rad = min(w, h) * 0.46f
        if (lw != w) {
            bgP.shader = RadialGradient(cx, cy, max(w, h) * 0.7f, 0xFF1C1F26.toInt(), 0xFF030304.toInt(), Shader.TileMode.CLAMP); lw = w
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgP)
        r.set(cx - rad, cy - rad, cx + rad, cy + rad)
        // track + red zone
        p.style = Paint.Style.STROKE; p.strokeCap = Paint.Cap.BUTT
        p.strokeWidth = rad * 0.035f; p.color = 0x33FFFFFF
        c.drawArc(r, start, sweep, false, p)
        p.color = accent
        c.drawArc(r, start + sweep * 0.8f, sweep * 0.2f, false, p)
        // ticks + labels
        tp.textSize = rad * 0.11f; tp.color = 0xFFCFCFD6.toInt()
        for (i in 0..48) {
            val a = Math.toRadians((start + sweep * i / 48f).toDouble()).toFloat()
            val major = i % 4 == 0
            val l = if (major) rad * 0.12f else rad * 0.06f
            p.strokeWidth = if (major) rad * 0.022f else rad * 0.01f
            p.color = if (i >= 39) accent else 0xCCFFFFFF.toInt()
            c.drawLine(cx + cos(a) * (rad - l), cy + sin(a) * (rad - l), cx + cos(a) * rad * 0.985f, cy + sin(a) * rad * 0.985f, p)
            if (major) {
                val lr = rad * 0.72f
                c.drawText((i * 5).toString(), cx + cos(a) * lr, cy + sin(a) * lr + tp.textSize * 0.35f, tp)
            }
        }
        // needle: gentle rev cycle
        val s = t / 1000f * speed
        val v = (0.35f + 0.25f * sin(s * 0.9f) + 0.12f * sin(s * 2.3f + 1f)).coerceIn(0f, 1f)
        val a = Math.toRadians((start + sweep * v).toDouble()).toFloat()
        // glow arc up to the needle
        p.strokeWidth = rad * 0.035f; p.color = (accent and 0x00FFFFFF) or 0x88000000.toInt()
        c.drawArc(r, start, sweep * v, false, p)
        p.style = Paint.Style.FILL
        p.color = accent; p.strokeWidth = rad * 0.025f; p.strokeCap = Paint.Cap.ROUND
        p.style = Paint.Style.STROKE
        c.drawLine(cx - cos(a) * rad * 0.12f, cy - sin(a) * rad * 0.12f, cx + cos(a) * rad * 0.88f, cy + sin(a) * rad * 0.88f, p)
        p.style = Paint.Style.FILL; p.color = 0xFF0E0E12.toInt(); c.drawCircle(cx, cy, rad * 0.07f, p)
        p.color = accent; c.drawCircle(cx, cy, rad * 0.035f, p)
        // km/h label
        tp.textSize = rad * 0.08f; tp.color = 0x88FFFFFF.toInt()
        c.drawText("km/h", cx, cy + rad * 0.3f, tp)
    }
}
