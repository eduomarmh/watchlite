package com.watchlite.launcher.theme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.watchlite.launcher.App
import com.watchlite.launcher.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Calendar
import java.util.zip.ZipInputStream

class ThemeRepository(private val ctx: Context) {

    private var cache: List<Theme>? = null
    private val listeners = ArrayList<() -> Unit>()

    val userDir: File get() = File(ctx.filesDir, "themes").apply { mkdirs() }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    fun notifyChanged() { listeners.toList().forEach { it() } }

    @Synchronized
    fun all(): List<Theme> {
        cache?.let { return it }
        val out = ArrayList<Theme>()
        val order = try {
            ctx.assets.open("themes/order.txt").bufferedReader().readLines().map { it.trim() }.filter { it.isNotEmpty() }
        } catch (_: Exception) { ctx.assets.list("themes")?.toList() ?: emptyList() }
        for (dir in order) {
            try {
                val json = ctx.assets.open("themes/$dir/manifest.json").bufferedReader().readText()
                out.add(ThemeParser.parse(json, ThemeSource("themes/$dir", null), true, null))
            } catch (t: Throwable) { Log.w("Themes", "built-in $dir failed", t) }
        }
        userDir.listFiles()?.sortedBy { it.lastModified() }?.forEach { d ->
            val m = File(d, "manifest.json")
            if (m.isFile) try {
                out.add(ThemeParser.parse(m.readText(), ThemeSource(null, d), false, d))
            } catch (t: Throwable) { Log.w("Themes", "user theme ${d.name} failed", t) }
        }
        cache = out
        return out
    }

    fun get(id: String): Theme? = all().firstOrNull { it.id == id }
    fun default(): Theme = get("flow") ?: all().first()

    @Synchronized
    fun invalidate() { cache = null }

    /** The theme that should be shown right now (honours auto-theme rules). */
    fun effectiveId(charging: Boolean, now: Calendar = Calendar.getInstance()): String {
        val p = App.prefs
        if (!p.autoTheme) return p.themeId
        if (charging && p.chargingTheme.isNotEmpty()) return p.chargingTheme
        val h = now.get(Calendar.HOUR_OF_DAY)
        val night = inRange(h, p.nightFrom, p.nightTo)
        return if (night) p.nightTheme else p.dayTheme
    }

    fun custom(id: String) = ThemeCustom.parse(App.prefs.themeCustom(id))
    fun setCustom(id: String, c: ThemeCustom) { App.prefs.setThemeCustom(id, c.toJson()); notifyChanged() }

    fun select(id: String) {
        val p = App.prefs
        if (p.autoTheme) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (inRange(h, p.nightFrom, p.nightTo)) p.nightTheme = id else p.dayTheme = id
        }
        p.themeId = id
        notifyChanged()
    }

    fun delete(id: String): Boolean {
        val t = get(id) ?: return false
        if (t.builtIn || t.fileDir == null) return false
        t.fileDir.deleteRecursively()
        invalidate()
        if (App.prefs.themeId == id) App.prefs.themeId = "flow"
        notifyChanged()
        return true
    }

    fun trimMemory() { /* bitmaps belong to renderers; nothing cached here */ }

    // ---------------------------------------------------------------- import

    /** Imports a .wtheme (zip) package. Data only: never executes anything. */
    fun importPackage(uri: Uri): Theme {
        val tmp = File(ctx.cacheDir, "import_${System.nanoTime()}").apply { mkdirs() }
        try {
            var total = 0L
            var count = 0
            ctx.contentResolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    while (true) {
                        val e = zip.nextEntry ?: break
                        if (++count > MAX_ENTRIES) error("too many files")
                        val name = e.name.replace('\\', '/')
                        if (name.startsWith("/") || name.split('/').any { it == ".." }) error("unsafe path $name")
                        val out = File(tmp, name)
                        if (!out.canonicalPath.startsWith(tmp.canonicalPath + File.separator)) error("unsafe path $name")
                        if (e.isDirectory) { out.mkdirs(); continue }
                        out.parentFile?.mkdirs()
                        FileOutputStream(out).use { fo ->
                            val buf = ByteArray(16 * 1024)
                            while (true) {
                                val n = zip.read(buf); if (n < 0) break
                                total += n
                                if (total > MAX_BYTES) error("theme too large")
                                fo.write(buf, 0, n)
                            }
                        }
                    }
                }
            }
            // manifest may be at root or inside a single top folder
            val root = if (File(tmp, "manifest.json").isFile) tmp
            else tmp.listFiles()?.firstOrNull { File(it, "manifest.json").isFile } ?: error("manifest.json missing")
            val parsed = ThemeParser.parse(File(root, "manifest.json").readText(), ThemeSource(null, root), false, root)
            if (get(parsed.id)?.builtIn == true) error("id clashes with a built-in theme")
            val dest = File(userDir, parsed.id)
            dest.deleteRecursively()
            if (!root.renameTo(dest)) { root.copyRecursively(dest, true) }
            invalidate()
            notifyChanged()
            return get(parsed.id)!!
        } finally {
            tmp.deleteRecursively()
        }
    }

    /**
     * Creates a theme from a picture chosen by the user.
     * layout: top | center | bottom ; style: bold | light | bebas
     */
    fun createFromImage(uri: Uri, layout: String, style: String): Theme {
        val id = "photo_" + System.currentTimeMillis().toString(36)
        val dir = File(userDir, id).apply { mkdirs() }
        // decode down-sampled and store as webp to save space
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, opts) }
        var sample = 1
        while (opts.outWidth / (sample * 2) >= 600 && opts.outHeight / (sample * 2) >= 600) sample *= 2
        val bmp = ctx.contentResolver.openInputStream(uri)!!.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: error("not an image")
        FileOutputStream(File(dir, "bg.webp")).use { bmp.compress(Bitmap.CompressFormat.WEBP, 85, it) }
        bmp.recycle()

        val y = when (layout) { "top" -> 0.26; "bottom" -> 0.74; else -> 0.47 }
        val font = when (style) { "light" -> "tajawal_light"; "bebas" -> "bebas"; else -> "tajawal_extrabold" }
        val size = if (style == "bebas") 0.36 else 0.29
        val m = JSONObject()
            .put("schema", 1).put("id", id)
            .put("name", JSONObject().put("en", "My photo").put("ar", "صورتي"))
            .put("author", "me")
            .put("background", JSONObject().put("type", "image").put("src", "bg.webp").put("scrim", 0.18))
            .put("elements", JSONArray()
                .put(JSONObject().put("type", "digitalTime").put("x", 0.5).put("y", y).put("size", size).put("font", font)
                    .put("shadow", JSONObject().put("r", 0.02).put("dy", 0.004).put("color", "#99000000")))
                .put(JSONObject().put("type", "text").put("text", "{date:EEE d MMM}").put("x", 0.5).put("y", y + 0.13)
                    .put("size", 0.07).put("font", "tajawal_medium").put("color", "#E6FFFFFF")
                    .put("shadow", JSONObject().put("r", 0.015).put("color", "#99000000")))
                .put(JSONObject().put("type", "batteryChip").put("x", 0.05).put("y", 0.05).put("anchor", "topLeft").put("w", 0.28).put("h", 0.1)))
        File(dir, "manifest.json").writeText(m.toString(2))
        invalidate()
        notifyChanged()
        return get(id)!!
    }

    companion object {
        const val MAX_BYTES = 30L * 1024 * 1024
        const val MAX_ENTRIES = 600

        fun inRange(h: Int, from: Int, to: Int): Boolean =
            if (from == to) false else if (from < to) h in from until to else h >= from || h < to
    }
}
