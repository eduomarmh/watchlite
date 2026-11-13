package com.watchlite.launcher.backup

import android.content.Context
import android.net.Uri
import com.watchlite.launcher.App
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Exports/imports all settings + custom themes + custom sounds as one .wlbackup zip. */
object BackupManager {

    fun export(ctx: Context, uri: Uri) {
        ctx.contentResolver.openOutputStream(uri)!!.use { raw ->
            ZipOutputStream(raw.buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("prefs.json"))
                zip.write(prefsJson().toString().toByteArray())
                zip.closeEntry()
                for (dirName in listOf("themes", "sounds")) {
                    val base = File(ctx.filesDir, dirName)
                    base.walkTopDown().filter { it.isFile }.forEach { f ->
                        zip.putNextEntry(ZipEntry(dirName + "/" + f.relativeTo(base).invariantSeparatorsPath))
                        f.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
        }
    }

    fun restore(ctx: Context, uri: Uri) {
        val root = ctx.filesDir.canonicalFile
        var total = 0L
        ctx.contentResolver.openInputStream(uri)!!.use { raw ->
            ZipInputStream(raw.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    val name = e.name.replace('\\', '/')
                    if (name == "prefs.json") { applyPrefs(JSONObject(zip.readBytes().toString(Charsets.UTF_8))); continue }
                    if (!(name.startsWith("themes/") || name.startsWith("sounds/")) || e.isDirectory) continue
                    val out = File(root, name).canonicalFile
                    require(out.path.startsWith(root.path + File.separator)) { "unsafe entry" }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { o ->
                        val buf = ByteArray(16 * 1024)
                        while (true) { val n = zip.read(buf); if (n < 0) break; total += n; require(total < 80L * 1024 * 1024) { "backup too large" }; o.write(buf, 0, n) }
                    }
                }
            }
        }
        App.I.themes.invalidate()
        App.I.themes.notifyChanged()
    }

    private fun prefsJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in App.prefs.sp.all) {
            if (k == "crash_times") continue
            val t = JSONObject()
            when (v) {
                is Boolean -> t.put("t", "b").put("v", v)
                is Int -> t.put("t", "i").put("v", v)
                is Long -> t.put("t", "l").put("v", v)
                is Float -> t.put("t", "f").put("v", v.toDouble())
                is String -> t.put("t", "s").put("v", v)
                is Set<*> -> t.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, t)
        }
        return o
    }

    private fun applyPrefs(o: JSONObject) {
        val ed = App.prefs.sp.edit()
        for (k in o.keys()) {
            val t = o.getJSONObject(k)
            when (t.getString("t")) {
                "b" -> ed.putBoolean(k, t.getBoolean("v"))
                "i" -> ed.putInt(k, t.getInt("v"))
                "l" -> ed.putLong(k, t.getLong("v"))
                "f" -> ed.putFloat(k, t.getDouble("v").toFloat())
                "s" -> ed.putString(k, t.getString("v"))
                "set" -> { val a = t.getJSONArray("v"); ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toSet()) }
            }
        }
        ed.apply()
    }
}
