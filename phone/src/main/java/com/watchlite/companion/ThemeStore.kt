package com.watchlite.companion

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Phone-side cache of watch-face previews + sending designed themes to the watch. */
object ThemeStore {
    /** Last theme transfer event for the UI: progress:i/n | installed:id | error:msg */
    @Volatile var event: String = ""

    private val prefs get() = CompanionApp.prefs
    fun dir(ctx: Context) = File(ctx.filesDir, "thumbs").apply { mkdirs() }

    private fun index(): JSONObject = try { JSONObject(prefs.getString("thumb_index", "{}")) } catch (_: Exception) { JSONObject() }

    class Face(val id: String, val name: String, val builtIn: Boolean, val file: File)

    /** Faces in the watch's order (only those with a preview). */
    fun faces(ctx: Context): List<Face> {
        val idx = index()
        val order = try { JSONArray(prefs.getString("thumb_order", "[]")) } catch (_: Exception) { JSONArray() }
        val ids = (0 until order.length()).map { order.getString(it) }.ifEmpty { idx.keys().asSequence().toList() }
        return ids.mapNotNull { id ->
            val o = idx.optJSONObject(id) ?: return@mapNotNull null
            val f = File(dir(ctx), "$id.jpg")
            if (!f.isFile) null else Face(id, o.optString("name", id), o.optBoolean("builtIn", true), f)
        }
    }

    /** Asks the watch for previews it hasn't sent yet (or that changed). */
    fun request(): Boolean {
        val have = JSONObject()
        val idx = index()
        idx.keys().forEach { have.put(it, idx.getJSONObject(it).optString("rev")) }
        return PhoneLinkService.send("theme.thumbs", JSONObject().put("have", have))
    }

    @Synchronized fun saveThumb(ctx: Context, p: JSONObject) {
        val id = p.optString("id").takeIf { it.isNotEmpty() && !it.contains('/') } ?: return
        try { File(dir(ctx), "$id.jpg").writeBytes(Base64.decode(p.optString("data"), Base64.DEFAULT)) } catch (_: Exception) { return }
        val idx = index()
        idx.put(id, JSONObject().put("rev", p.optString("rev")).put("name", p.optString("name")).put("builtIn", p.optBoolean("builtIn", true)))
        prefs.edit().putString("thumb_index", idx.toString()).apply()
    }

    @Synchronized fun done(ctx: Context, p: JSONObject) {
        val ids = p.optJSONArray("ids") ?: return
        val keep = (0 until ids.length()).map { ids.getString(it) }.toSet()
        val idx = index()
        idx.keys().asSequence().toList().forEach { if (it !in keep) { idx.remove(it); File(dir(ctx), "$it.jpg").delete() } }
        prefs.edit().putString("thumb_index", idx.toString()).putString("thumb_order", ids.toString()).apply()
    }

    fun forget(ctx: Context, id: String) {
        val idx = index(); idx.remove(id); File(dir(ctx), "$id.jpg").delete()
        prefs.edit().putString("thumb_index", idx.toString()).apply()
    }

    /** Zips manifest + background and streams it to the watch in chunks (call off the main thread). */
    fun sendTheme(manifest: JSONObject, bg: Bitmap?, apply: Boolean): Boolean {
        val zip = ByteArrayOutputStream()
        ZipOutputStream(zip).use { z ->
            z.putNextEntry(ZipEntry("manifest.json")); z.write(manifest.toString(2).toByteArray()); z.closeEntry()
            if (bg != null) {
                z.putNextEntry(ZipEntry("bg.jpg"))
                bg.compress(Bitmap.CompressFormat.JPEG, 88, z); z.closeEntry()
            }
        }
        val bytes = zip.toByteArray()
        val id = manifest.getString("id")
        val chunk = 48 * 1024
        val total = (bytes.size + chunk - 1) / chunk
        for (i in 0 until total) {
            val part = bytes.copyOfRange(i * chunk, minOf(bytes.size, (i + 1) * chunk))
            val ok = PhoneLinkService.send("theme.install", JSONObject().put("id", id).put("index", i).put("total", total)
                .put("apply", apply).put("data", Base64.encodeToString(part, Base64.NO_WRAP)))
            if (!ok) return false
        }
        return true
    }

    fun decodeThumb(f: File): Bitmap? = try { BitmapFactory.decodeFile(f.path) } catch (_: Throwable) { null }
}
