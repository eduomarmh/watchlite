package com.watchlite.launcher.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import com.watchlite.launcher.App
import com.watchlite.launcher.util.Log
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

/**
 * Third-party icon packs (the standard ADW / Nova / Go format used by most packs on
 * Google Play). The pack's appfilter.xml maps app components to its drawables.
 */
class IconPack(private val ctx: Context, val pkg: String) {
    private var res: Resources? = null
    private val map = HashMap<String, String>()

    companion object {
        private val ACTIONS = listOf("org.adw.launcher.THEMES", "com.novalauncher.THEME", "com.teslacoilsw.launcher.THEME",
            "com.gau.go.launcherex.theme", "com.fede.launcher.THEME_ICONPACK", "com.anddoes.launcher.THEME")

        /** Installed icon packs: package -> label. */
        fun installed(ctx: Context): Map<String, String> {
            val pm = ctx.packageManager
            val out = LinkedHashMap<String, String>()
            for (a in ACTIONS) {
                for (ri in pm.queryIntentActivities(Intent(a), PackageManager.GET_META_DATA)) {
                    val p = ri.activityInfo.packageName
                    if (p !in out) out[p] = ri.loadLabel(pm).toString()
                }
            }
            return out
        }
    }

    fun load(): Boolean = try {
        val r = ctx.packageManager.getResourcesForApplication(pkg)
        res = r
        val id = r.getIdentifier("appfilter", "xml", pkg)
        val parser: XmlPullParser = if (id != 0) r.getXml(id) else
            XmlPullParserFactory.newInstance().newPullParser().apply { setInput(r.assets.open("appfilter.xml"), "UTF-8") }
        var ev = parser.eventType
        while (ev != XmlPullParser.END_DOCUMENT) {
            if (ev == XmlPullParser.START_TAG && parser.name == "item") {
                val comp = parser.getAttributeValue(null, "component")
                val draw = parser.getAttributeValue(null, "drawable")
                if (comp != null && draw != null) {
                    // ComponentInfo{pkg/cls}
                    val inner = comp.substringAfter('{', "").substringBefore('}', "")
                    if (inner.isNotEmpty()) map[inner] = draw
                }
            }
            ev = parser.next()
        }
        Log.i("IconPack", "$pkg: ${map.size} icons")
        map.isNotEmpty()
    } catch (t: Throwable) { Log.w("IconPack", "load $pkg failed", t); false }

    fun drawableFor(cn: ComponentName): Drawable? {
        val r = res ?: return null
        val name = map[cn.packageName + "/" + cn.className] ?: map[cn.flattenToString()] ?: return null
        val id = r.getIdentifier(name, "drawable", pkg).takeIf { it != 0 } ?: r.getIdentifier(name, "mipmap", pkg)
        if (id == 0) return null
        return try { r.getDrawable(id, null) } catch (_: Throwable) { null }
    }
}

/**
 * Renders an app icon in one of the launcher's icon styles. The finished bitmap is
 * cached by AppRepository, so styling costs nothing while scrolling.
 */
object IconStyler {
    val STYLES = listOf("circle", "squircle", "original", "mono", "glass", "outline")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(d: Drawable, size: Int, iconStyle: String, accent: Int, fromPack: Boolean): Bitmap {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat()
        // icon packs already ship a finished shape: keep them as they are unless a frame style is chosen
        val raw = raster(d, size)
        when (if (fromPack && iconStyle in setOf("circle", "squircle", "original")) "original" else iconStyle) {
            "original" -> c.drawBitmap(raw, 0f, 0f, paint)
            "circle" -> clip(c, raw, circlePath(s), 1.08f)
            "squircle" -> clip(c, raw, squirclePath(s), 1.08f)
            "mono" -> {
                paint.color = 0xFF2C2C2E.toInt(); c.drawCircle(s / 2, s / 2, s / 2, paint)
                val p = Paint(paint).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix().apply { setSaturation(0f) }) }
                drawInset(c, raw, s, 0.68f, p)
            }
            "glass" -> {
                paint.shader = LinearGradient(0f, 0f, 0f, s, 0x66FFFFFF, 0x1AFFFFFF, Shader.TileMode.CLAMP)
                c.drawCircle(s / 2, s / 2, s / 2, paint); paint.shader = null
                val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.03f; color = 0x88FFFFFF.toInt() }
                c.drawCircle(s / 2, s / 2, s / 2 - rim.strokeWidth, rim)
                drawInset(c, raw, s, 0.66f, paint)
            }
            "outline" -> {
                paint.color = 0xFF111114.toInt(); c.drawCircle(s / 2, s / 2, s / 2, paint)
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.05f; color = accent }
                c.drawCircle(s / 2, s / 2, s / 2 - ring.strokeWidth / 2, ring)
                drawInset(c, raw, s, 0.62f, paint)
            }
        }
        raw.recycle()
        paint.colorFilter = null
        return out
    }

    private fun raster(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(b))
        return b
    }

    private fun clip(c: Canvas, src: Bitmap, path: Path, zoom: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val m = android.graphics.Matrix()
        val s = src.width.toFloat()
        m.setScale(zoom, zoom, s / 2, s / 2)
        p.shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(m) }
        // dark base so transparent legacy icons still read as a shape
        c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF1C1C1E.toInt() })
        c.drawPath(path, p)
    }

    private fun drawInset(c: Canvas, src: Bitmap, s: Float, frac: Float, p: Paint) {
        val pad = s * (1 - frac) / 2
        c.drawBitmap(src, null, RectF(pad, pad, s - pad, s - pad), p)
    }

    private fun circlePath(s: Float) = Path().apply { addCircle(s / 2, s / 2, s / 2, Path.Direction.CW) }

    /** Superellipse (n = 4): the modern "squircle" icon shape. */
    private fun squirclePath(s: Float): Path {
        val p = Path(); val r = s / 2
        for (i in 0..72) {
            val t = Math.toRadians(i * 5.0)
            val ct = Math.cos(t); val st = Math.sin(t)
            val x = r + r * sign(ct) * abs(ct).pow(0.5)
            val y = r + r * sign(st) * abs(st).pow(0.5)
            if (i == 0) p.moveTo(x.toFloat(), y.toFloat()) else p.lineTo(x.toFloat(), y.toFloat())
        }
        p.close(); return p
    }

}
