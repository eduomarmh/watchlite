package com.watchlite.launcher.face

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.watchlite.launcher.theme.BgSpec
import com.watchlite.launcher.theme.Theme
import com.watchlite.launcher.util.Log
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** A background layer. Animated layers are drawn every frame, static ones once. */
interface Layer {
    val animated: Boolean
    fun draw(c: Canvas, w: Int, h: Int, t: Long)
    fun release() {}
}

object Backgrounds {

    /** Builds the background stack for a theme at the given size. */
    fun build(ctx: Context, theme: Theme, w: Int, h: Int, allowAnimation: Boolean): List<Layer> {
        val bg = theme.bg
        val out = ArrayList<Layer>(3)
        try {
            when (bg.type) {
                "image" -> bg.src?.let { loadImage(ctx, theme, it, w, h) }?.let { out.add(ImageLayer(it)) }
                "gradient" -> out.add(GradientLayer(bg.colors))
                "procedural" -> out.add(procedural(bg.proc ?: "liquid", bg, allowAnimation))
                else -> out.add(SolidLayer(bg.colors.firstOrNull() ?: Color.BLACK))
            }
        } catch (t: Throwable) {
            Log.w("Bg", "background failed for ${theme.id}", t)
        }
        if (out.isEmpty()) out.add(SolidLayer(Color.BLACK))
        bg.overlay?.let { out.add(procedural(it, bg, allowAnimation, overlay = true)) }
        if (bg.scrim > 0f) out.add(SolidLayer(Color.argb((bg.scrim * 255).toInt(), 0, 0, 0)))
        return out
    }

    private fun procedural(name: String, bg: BgSpec, anim: Boolean, overlay: Boolean = false): Layer {
        val cols = if (overlay) intArrayOf(bg.overlayColor) else bg.colors
        val l: Layer = when (name) {
            "liquid" -> LiquidLayer(cols, bg.speed)
            "starfield" -> StarfieldLayer(cols.first(), bg.speed, !overlay)
            "neongrid" -> NeonGridLayer(cols, bg.speed)
            "waves" -> WavesLayer(cols, bg.speed)
            "bubbles" -> BubblesLayer(cols.first(), bg.speed, !overlay)
            "twinkle" -> TwinkleLayer(cols.first(), bg.speed)
            "nightdrive" -> NightDriveLayer(cols, bg.speed)
            "racer" -> RacerLayer(cols, bg.speed)
            "speedo" -> SpeedoLayer(cols, bg.speed)
            "rain" -> RainLayer(cols.first(), bg.speed, !overlay)
            "matrix" -> MatrixLayer(cols.first(), bg.speed)
            "fireflies" -> FirefliesLayer(cols.first(), bg.speed)
            "snow" -> SnowLayer(cols.first(), bg.speed)
            "radar" -> RadarLayer(cols, bg.speed)
            "orbit" -> OrbitLayer(bg.speed)
            "circuit" -> CircuitLayer(cols.first(), bg.speed, !overlay)
            "hud" -> HudLayer(cols, bg.speed)
            "hexgrid" -> HexGridLayer(cols, bg.speed)
            "gradient" -> GradientLayer(cols)
            else -> SolidLayer(cols.firstOrNull() ?: Color.BLACK)
        }
        return if (anim) l else FrozenLayer(l)
    }

    /** Decodes an image down-sampled and center-cropped to exactly w x h. */
    fun loadImage(ctx: Context, theme: Theme, src: String, w: Int, h: Int): Bitmap? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        theme.source.open(ctx, src).use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth <= 0) return null
        var sample = 1
        while (opts.outWidth / (sample * 2) >= w && opts.outHeight / (sample * 2) >= h) sample *= 2
        val raw = theme.source.open(ctx, src).use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null
        val scale = max(w / raw.width.toFloat(), h / raw.height.toFloat())
        val sw = (w / scale).toInt().coerceAtMost(raw.width)
        val sh = (h / scale).toInt().coerceAtMost(raw.height)
        val sx = (raw.width - sw) / 2
        val sy = (raw.height - sh) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(raw, Rect(sx, sy, sx + sw, sy + sh), Rect(0, 0, w, h), Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG))
        raw.recycle()
        return out
    }
}

class SolidLayer(private val color: Int) : Layer {
    override val animated = false
    override fun draw(c: Canvas, w: Int, h: Int, t: Long) { c.drawColor(color) }
}

class GradientLayer(private val colors: IntArray) : Layer {
    override val animated = false
    private val p = Paint(Paint.DITHER_FLAG)
    private var lw = 0; private var lh = 0
    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (colors.size < 2) { c.drawColor(colors.firstOrNull() ?: Color.BLACK); return }
        if (lw != w || lh != h) {
            p.shader = LinearGradient(0f, 0f, w * 0.3f, h.toFloat(), colors, null, Shader.TileMode.CLAMP)
            lw = w; lh = h
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
    }
}

class ImageLayer(private var bmp: Bitmap?) : Layer {
    override val animated = false
    private val p = Paint(Paint.FILTER_BITMAP_FLAG)
    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val b = bmp ?: return
        if (b.width == w && b.height == h) c.drawBitmap(b, 0f, 0f, p)
        else c.drawBitmap(b, null, Rect(0, 0, w, h), p)
    }
    override fun release() { bmp?.recycle(); bmp = null }
}

/** Shows a single frame of an animated layer (battery saver / previews). */
class FrozenLayer(private val inner: Layer) : Layer {
    override val animated = false
    override fun draw(c: Canvas, w: Int, h: Int, t: Long) = inner.draw(c, w, h, 12_000L)
    override fun release() = inner.release()
}

/**
 * Glossy "liquid" metaballs. Computed on a tiny bitmap (~70 px wide) and
 * up-scaled with bilinear filtering: smooth look, very low CPU cost.
 */
class LiquidLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val cols = if (colors.size >= 3) colors else intArrayOf(0xFF2B4CFF.toInt(), 0xFFA23BD9.toInt(), 0xFFFF7A3D.toInt())
    private val base = 0xFF05040A.toInt()
    private var gw = 0; private var gh = 0
    private var bmp: Bitmap? = null
    private var px = IntArray(0)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val dst = Rect()
    private val n = 6
    private val bx = FloatArray(n); private val by = FloatArray(n); private val br = FloatArray(n)
    private val bcR = FloatArray(n); private val bcG = FloatArray(n); private val bcB = FloatArray(n)

    init {
        for (i in 0 until n) {
            val c = cols[i % cols.size]
            bcR[i] = Color.red(c) / 255f; bcG[i] = Color.green(c) / 255f; bcB[i] = Color.blue(c) / 255f
        }
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (bmp == null || dst.right != w || dst.bottom != h) {
            gw = 68; gh = max(8, (68f * h / w).toInt())
            bmp?.recycle()
            bmp = Bitmap.createBitmap(gw, gh, Bitmap.Config.ARGB_8888)
            px = IntArray(gw * gh)
            dst.set(0, 0, w, h)
        }
        val s = t / 1000f * 0.35f * speed
        for (i in 0 until n) {
            val k = i * 1.7f
            bx[i] = gw * (0.5f + 0.38f * sin(s * (0.6f + i * 0.13f) + k))
            by[i] = gh * (0.5f + 0.40f * cos(s * (0.5f + i * 0.11f) + k * 1.3f))
            val r = gw * (0.20f + 0.05f * sin(s * 0.9f + i))
            br[i] = r * r
        }
        val baseR = Color.red(base) / 255f; val baseG = Color.green(base) / 255f; val baseB = Color.blue(base) / 255f
        var idx = 0
        for (y in 0 until gh) {
            val fy = y.toFloat()
            for (x in 0 until gw) {
                val fx = x.toFloat()
                var f = 0f; var r = 0f; var g = 0f; var b = 0f
                for (i in 0 until n) {
                    val dx = fx - bx[i]; val dy = fy - by[i]
                    val v = br[i] / (dx * dx + dy * dy + 1f)
                    f += v; r += bcR[i] * v; g += bcG[i] * v; b += bcB[i] * v
                }
                r /= f; g /= f; b /= f
                val edge = smooth(0.75f, 1.25f, f)
                val shine = smooth(2.2f, 4.5f, f) * 0.45f
                val rr = baseR + (r - baseR) * edge + shine
                val gg = baseG + (g - baseG) * edge + shine
                val bb = baseB + (b - baseB) * edge + shine
                px[idx++] = Color.rgb(clamp(rr), clamp(gg), clamp(bb))
            }
        }
        bmp!!.setPixels(px, 0, gw, 0, 0, gw, gh)
        c.drawBitmap(bmp!!, null, dst, paint)
    }

    private fun smooth(a: Float, b: Float, x: Float): Float { val t = ((x - a) / (b - a)).coerceIn(0f, 1f); return t * t * (3 - 2 * t) }
    private fun clamp(v: Float) = (v * 255f).toInt().coerceIn(0, 255)
    override fun release() { bmp?.recycle(); bmp = null }
}

class StarfieldLayer(private val color: Int, private val speed: Float, private val fill: Boolean) : Layer {
    override val animated = true
    private val count = 110
    private val sx = FloatArray(count); private val sy = FloatArray(count); private val sz = FloatArray(count)
    private val rnd = Random(42)
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private var last = 0L

    init { for (i in 0 until count) reset(i, true) }
    private fun reset(i: Int, any: Boolean) {
        sx[i] = rnd.nextFloat() * 2 - 1; sy[i] = rnd.nextFloat() * 2 - 1; sz[i] = if (any) rnd.nextFloat() else 1f
    }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (fill) c.drawColor(0xFF020208.toInt())
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        val cx = w / 2f; val cy = h / 2f; val sc = max(w, h) * 0.6f
        for (i in 0 until count) {
            val pz = sz[i]
            sz[i] -= dt * 0.18f * speed
            if (sz[i] <= 0.02f) { reset(i, false); continue }
            val x = cx + sx[i] / sz[i] * sc * 0.5f; val y = cy + sy[i] / sz[i] * sc * 0.5f
            val x0 = cx + sx[i] / pz * sc * 0.5f; val y0 = cy + sy[i] / pz * sc * 0.5f
            if (x < -10 || x > w + 10 || y < -10 || y > h + 10) { reset(i, false); continue }
            val a = ((1f - sz[i]) * 255).toInt().coerceIn(30, 255)
            p.color = color; p.alpha = a
            p.strokeWidth = (1f - sz[i]) * w * 0.012f + 1f
            c.drawLine(x0, y0, x, y, p)
        }
    }
}

class TwinkleLayer(private val color: Int, private val speed: Float) : Layer {
    override val animated = true
    private val n = 45
    private val rnd = Random(7)
    private val xs = FloatArray(n) { rnd.nextFloat() }
    private val ys = FloatArray(n) { rnd.nextFloat() * 0.75f }
    private val ph = FloatArray(n) { rnd.nextFloat() * 6.28f }
    private val sz = FloatArray(n) { 0.4f + rnd.nextFloat() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val s = t / 1000f * speed
        val base = min(w, h) * 0.0045f
        p.color = color
        for (i in 0 until n) {
            val a = 0.5f + 0.5f * sin(s * (1.2f + i % 5 * 0.3f) + ph[i])
            p.alpha = (a * a * 230).toInt()
            c.drawCircle(xs[i] * w, ys[i] * h, base * sz[i], p)
        }
    }
}

class NeonGridLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val sky = if (colors.size >= 2) intArrayOf(colors[0], colors[1]) else intArrayOf(0xFF0B0221.toInt(), 0xFF3A0A4A.toInt())
    private val line = if (colors.size >= 3) colors[2] else 0xFFFF3EA5.toInt()
    private val sunCol = if (colors.size >= 4) colors[3] else 0xFFFFB84D.toInt()
    private val skyP = Paint(Paint.DITHER_FLAG)
    private val sunP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val lp = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val groundP = Paint()
    private var lw = 0; private var lh = 0
    private val cut = Paint().apply { color = Color.BLACK }

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        val horizon = h * 0.58f
        if (lw != w || lh != h) {
            skyP.shader = LinearGradient(0f, 0f, 0f, horizon, sky[0], sky[1], Shader.TileMode.CLAMP)
            sunP.shader = LinearGradient(0f, horizon - w * 0.42f, 0f, horizon, sunCol, line, Shader.TileMode.CLAMP)
            groundP.shader = LinearGradient(0f, horizon, 0f, h.toFloat(), 0xFF120326.toInt(), 0xFF000000.toInt(), Shader.TileMode.CLAMP)
            lw = w; lh = h
        }
        c.drawRect(0f, 0f, w.toFloat(), horizon, skyP)
        // striped retro sun
        val r = w * 0.26f; val cx = w / 2f; val cy = horizon - r * 0.15f
        c.save(); c.clipRect(0f, 0f, w.toFloat(), horizon)
        c.drawCircle(cx, cy, r, sunP)
        cut.color = sky[1]
        var k = 0
        var y = cy + r * 0.05f
        while (y < cy + r) { val th = r * (0.03f + k * 0.018f); c.drawRect(cx - r, y, cx + r, y + th, cut); y += r * 0.17f; k++ }
        c.restore()
        c.drawRect(0f, horizon, w.toFloat(), h.toFloat(), groundP)
        lp.color = line; lp.strokeWidth = max(1f, w * 0.005f)
        // vertical lines converging
        val vanishX = w / 2f
        for (i in -8..8) {
            val bx = vanishX + i * w * 0.16f
            lp.alpha = 200
            c.drawLine(vanishX + i * w * 0.012f, horizon, bx + (bx - vanishX) * 1.4f, h.toFloat(), lp)
        }
        // horizontal lines moving toward viewer
        val phase = (t / 1000f * 0.5f * speed) % 1f
        for (i in 0 until 10) {
            val z = (i + phase) / 10f
            val yy = horizon + (h - horizon) * z * z
            lp.alpha = (60 + 195 * z).toInt()
            c.drawLine(0f, yy, w.toFloat(), yy, lp)
        }
    }
}

class WavesLayer(colors: IntArray, private val speed: Float) : Layer {
    override val animated = true
    private val cols = if (colors.size >= 4) colors else intArrayOf(0xFF001018.toInt(), 0xFF00506E.toInt(), 0xFF00A6C0.toInt(), 0xFF7FF0FF.toInt())
    private val bgP = Paint(Paint.DITHER_FLAG)
    private val wp = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var lw = 0; private var lh = 0

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (lw != w || lh != h) {
            bgP.shader = LinearGradient(0f, 0f, 0f, h.toFloat(), cols[0], cols[1], Shader.TileMode.CLAMP); lw = w; lh = h
        }
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgP)
        val s = t / 1000f * speed
        for (layer in 0 until 4) {
            val base = h * (0.55f + layer * 0.11f)
            val amp = h * (0.035f - layer * 0.004f)
            val freq = 1.6f + layer * 0.55f
            path.reset(); path.moveTo(0f, h.toFloat())
            var x = 0f
            val step = w / 24f
            while (x <= w + step) {
                val y = base + amp * sin(x / w * 6.283f * freq + s * (0.6f + layer * 0.25f) + layer) +
                    amp * 0.4f * sin(x / w * 6.283f * freq * 2.3f - s * 0.9f)
                path.lineTo(x, y); x += step
            }
            path.lineTo(w.toFloat(), h.toFloat()); path.close()
            wp.color = cols[2 + (layer % 2)]
            wp.alpha = 46 + layer * 22
            c.drawPath(path, wp)
        }
    }
}

class BubblesLayer(private val color: Int, private val speed: Float, private val fill: Boolean) : Layer {
    override val animated = true
    private val n = 16
    private val rnd = Random(3)
    private val xs = FloatArray(n) { rnd.nextFloat() }
    private val ys = FloatArray(n) { rnd.nextFloat() }
    private val rs = FloatArray(n) { 0.03f + rnd.nextFloat() * 0.09f }
    private val vs = FloatArray(n) { 0.03f + rnd.nextFloat() * 0.05f }
    private val fillP = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val hi = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bgP = Paint(Paint.DITHER_FLAG)
    private var last = 0L
    private var lw = 0

    override fun draw(c: Canvas, w: Int, h: Int, t: Long) {
        if (fill) {
            if (lw != w) {
                bgP.shader = RadialGradient(w * 0.5f, h * 0.35f, max(w, h) * 0.9f, 0xFF0A3A44.toInt(), 0xFF02090C.toInt(), Shader.TileMode.CLAMP); lw = w
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bgP)
        }
        val dt = if (last == 0L) 0f else ((t - last).coerceIn(0, 100)) / 1000f
        last = t
        val m = min(w, h)
        for (i in 0 until n) {
            ys[i] -= vs[i] * dt * speed
            if (ys[i] < -0.15f) { ys[i] = 1.15f; xs[i] = rnd.nextFloat() }
            val x = (xs[i] + 0.02f * sin(t / 900f + i)) * w
            val y = ys[i] * h
            val r = rs[i] * m
            fillP.color = color; fillP.alpha = 26
            c.drawCircle(x, y, r, fillP)
            ring.color = color; ring.alpha = 110; ring.strokeWidth = max(1f, m * 0.004f)
            c.drawCircle(x, y, r, ring)
            hi.color = Color.WHITE; hi.alpha = 120
            c.drawCircle(x - r * 0.35f, y - r * 0.35f, r * 0.16f, hi)
        }
    }
}

