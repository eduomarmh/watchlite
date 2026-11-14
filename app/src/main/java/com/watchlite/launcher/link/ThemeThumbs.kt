package com.watchlite.launcher.link

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import com.watchlite.launcher.App
import com.watchlite.launcher.face.Backgrounds
import com.watchlite.launcher.face.FaceRenderer
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar

/**
 * Watch-face previews for the phone's picture picker.
 * The phone sends `theme.thumbs {have:{id:rev}}`; the watch renders (or reads from its
 * own cache) only the faces whose revision changed and streams them one by one as
 * `theme.thumb {id, rev, name, builtIn, current, data(jpeg base64)}`, then `theme.thumbs.done`.
 * Rendering happens on the background thread, one face at a time, so memory stays flat.
 */
object ThemeThumbs {
    private const val TW = 160
    private const val TH = 192
    @Volatile private var busy = false

    private fun rev(ctx: Context, id: String, builtIn: Boolean, dir: File?): String {
        val p = App.prefs
        val base = if (builtIn) com.watchlite.launcher.BuildConfig.VERSION_CODE.toString() else (dir?.lastModified() ?: 0L).toString()
        return base + "_" + Integer.toHexString(p.accent) + (if (p.accentOnFaces) "a" else "") + "_" + p.themeCustom(id).hashCode()
    }

    fun send(ctx: Context, have: JSONObject) {
        if (busy) return
        busy = true
        Bg.run {
            try {
                val cache = File(ctx.cacheDir, "thumbs").apply { mkdirs() }
                val themes = App.I.themes.all()
                val current = App.prefs.themeId
                val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 9); set(Calendar.SECOND, 36) }
                val renderer = FaceRenderer(ctx)
                var sent = 0
                val ids = ArrayList<String>()
                for (t in themes) {
                    ids.add(t.id)
                    val rev = rev(ctx, t.id, t.builtIn, t.fileDir)
                    if (have.optString(t.id) == rev) continue
                    val f = File(cache, "${t.id}_${rev.hashCode()}.jpg")
                    val bytes = if (f.isFile) f.readBytes() else render(ctx, renderer, t, cal)?.also { b -> try { f.writeBytes(b) } catch (_: Throwable) { } } ?: continue
                    LinkManager.send("theme.thumb", JSONObject().put("id", t.id).put("rev", rev).put("name", t.name())
                        .put("builtIn", t.builtIn).put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)))
                    sent++
                }
                LinkManager.send("theme.thumbs.done", JSONObject().put("ids", org.json.JSONArray(ids)).put("current", current))
                Log.i("Thumbs", "sent $sent of ${themes.size}")
                // drop stale cache files
                val keep = themes.map { "${it.id}_${rev(ctx, it.id, it.builtIn, it.fileDir).hashCode()}.jpg" }.toSet()
                cache.listFiles()?.forEach { if (it.name !in keep) it.delete() }
            } catch (t: Throwable) { Log.w("Thumbs", "failed", t) } finally { busy = false }
        }
    }

    private fun render(ctx: Context, r: FaceRenderer, t: com.watchlite.launcher.theme.Theme, cal: Calendar): ByteArray? {
        val sp = com.watchlite.launcher.util.ScreenProfile.of(ctx)
        val w = sp.width.coerceIn(200, 480); val h = sp.height.coerceIn(200, 560)
        var full: Bitmap? = null; var small: Bitmap? = null
        val layers = try { Backgrounds.build(ctx, t, w, h, true) } catch (e: Throwable) { emptyList() }
        return try {
            full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(full); c.drawColor(0xFF000000.toInt())
            for (f in 0..2) layers.forEach { it.draw(c, w, h, 4_000L + f * 40L) }
            r.draw(c, t, w, h, cal, cal.timeInMillis, App.I.themes.custom(t.id))
            small = Bitmap.createScaledBitmap(full, TW, TW * h / w, true)
            ByteArrayOutputStream().use { o -> small.compress(Bitmap.CompressFormat.JPEG, 80, o); o.toByteArray() }
        } catch (e: Throwable) { Log.w("Thumbs", "render ${t.id}", e); null } finally {
            layers.forEach { try { it.release() } catch (_: Throwable) { } }
            full?.recycle(); small?.recycle()
        }
    }
}
