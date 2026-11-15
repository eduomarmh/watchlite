package com.watchlite.launcher.link

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Environment
import android.util.Base64
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.notifications.NotificationItem
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Log
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * File sharing over the link, both directions (same protocol on the phone):
 *   file.start {fid, name, size}  →  file.chunk {fid, i, data} ×n  →  file.end {fid, n}
 *   receiver answers file.done {fid, ok}; either side may send file.cancel {fid}.
 * Chunks are 32 KB; the socket's own back-pressure paces the sender, so no extra
 * acks or timers are needed and nothing runs while no transfer is in progress.
 */
object FileTransfer {
    private const val CHUNK = 32 * 1024
    const val MAX_SIZE = 200L * 1024 * 1024

    /** Current transfer shown in the UI (one at a time per direction). */
    class Progress(val fid: String, val name: String, val size: Long, val outgoing: Boolean) {
        @Volatile var done = 0L
        @Volatile var state = "run"   // run | ok | fail | cancel
    }

    @Volatile var outgoing: Progress? = null
    @Volatile var incoming: Progress? = null
    private val listeners = ArrayList<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners += l } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners -= l } }
    private fun changed() { val ls = synchronized(listeners) { listeners.toList() }; Bg.ui { ls.forEach { it() } } }

    // ------------------------------------------------------------------ storage
    /** Received files go to Download/WatchLite (or the app's own folder without storage permission). */
    fun inbox(ctx: Context): File {
        val pub = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "WatchLite")
        val canWrite = ctx.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE) == android.content.pm.PackageManager.PERMISSION_GRANTED
        if (canWrite && (pub.isDirectory || pub.mkdirs())) return pub
        return File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, "received").apply { mkdirs() }
    }

    private fun uniqueName(dir: File, name: String): File {
        val clean = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "file" }
        var f = File(dir, clean); var n = 1
        val dot = clean.lastIndexOf('.').takeIf { it > 0 } ?: clean.length
        while (f.exists()) { f = File(dir, clean.substring(0, dot) + " ($n)" + clean.substring(dot)); n++ }
        return f
    }

    // ------------------------------------------------------------------ send
    fun send(ctx: Context, file: File): Boolean {
        if (!file.isFile) return false
        if (outgoing?.state == "run") return false
        if (!App.I.state.linkConnected) return false
        val pr = Progress(UUID.randomUUID().toString().take(12), file.name, file.length(), true)
        outgoing = pr; changed()
        Bg.run {
            try {
                if (!LinkManager.send("file.start", JSONObject().put("fid", pr.fid).put("name", pr.name).put("size", pr.size))) error("link")
                val buf = ByteArray(CHUNK); var i = 0
                file.inputStream().use { input ->
                    while (true) {
                        if (pr.state != "run") return@use
                        val n = input.read(buf); if (n <= 0) break
                        val data = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                        if (!LinkManager.send("file.chunk", JSONObject().put("fid", pr.fid).put("i", i++).put("data", data))) error("link")
                        pr.done += n
                        if (i % 4 == 0) changed()
                    }
                }
                if (pr.state == "run") LinkManager.send("file.end", JSONObject().put("fid", pr.fid).put("n", i))
                else LinkManager.send("file.cancel", JSONObject().put("fid", pr.fid))
            } catch (t: Throwable) {
                Log.w("Files", "send failed", t); pr.state = "fail"
            }
            changed()
        }
        return true
    }

    fun cancel() {
        outgoing?.let { if (it.state == "run") it.state = "cancel" }
        incoming?.let { if (it.state == "run") { LinkManager.send("file.cancel", JSONObject().put("fid", it.fid)); it.state = "cancel"; drop() } }
        changed()
    }

    // ------------------------------------------------------------------ receive (link thread)
    private var tmp: File? = null
    private var out: FileOutputStream? = null

    private fun drop() { try { out?.close() } catch (_: Throwable) { }; out = null; tmp?.delete(); tmp = null }

    fun handle(ctx: Context, type: String, p: JSONObject) {
        val fid = p.optString("fid")
        when (type) {
            "file.start" -> {
                drop()
                val size = p.optLong("size")
                if (size > MAX_SIZE) { LinkManager.send("file.done", JSONObject().put("fid", fid).put("ok", false).put("error", "too big")); return }
                incoming = Progress(fid, p.optString("name", "file"), size, false)
                tmp = File(ctx.cacheDir, "incoming_$fid.part")
                out = FileOutputStream(tmp)
                changed()
            }
            "file.chunk" -> {
                val pr = incoming ?: return
                if (pr.fid != fid || pr.state != "run") return
                val b = Base64.decode(p.optString("data"), Base64.DEFAULT)
                try { out?.write(b); pr.done += b.size } catch (t: Throwable) { Log.w("Files", "write", t); pr.state = "fail"; drop() }
                if (p.optInt("i") % 4 == 0) changed()
            }
            "file.end" -> {
                val pr = incoming ?: return
                if (pr.fid != fid) return
                try { out?.close() } catch (_: Throwable) { }
                out = null
                val t = tmp; tmp = null
                val ok = pr.state == "run" && t != null && t.length() == pr.size
                if (ok) {
                    val dest = uniqueName(inbox(ctx), pr.name)
                    val moved = t!!.renameTo(dest) || try { t.copyTo(dest, true); t.delete(); true } catch (_: Throwable) { false }
                    pr.state = if (moved) "ok" else "fail"
                    if (moved) announce(ctx, dest)
                } else { pr.state = "fail"; t?.delete() }
                LinkManager.send("file.done", JSONObject().put("fid", fid).put("ok", pr.state == "ok"))
                changed()
            }
            "file.cancel" -> {
                outgoing?.let { if (it.fid == fid) it.state = "cancel" }
                incoming?.let { if (it.fid == fid) { it.state = "cancel"; drop() } }
                changed()
            }
            "file.done" -> {
                outgoing?.let { if (it.fid == fid) it.state = if (p.optBoolean("ok")) "ok" else "fail" }
                changed()
            }
        }
    }

    /** "File received" in the notification center + banner; tapping opens the Files screen on it. */
    private fun announce(ctx: Context, f: File) {
        val i = Intent(ctx, com.watchlite.launcher.files.FilesActivity::class.java).putExtra("path", f.path).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, f.path.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT)
        Bg.ui {
            App.I.notifications.put(NotificationItem("file:" + f.path, NotificationItem.PHONE, ctx.packageName,
                ctx.getString(R.string.files_title), ctx.getString(R.string.file_received), f.name, System.currentTimeMillis(),
                null, pi, emptyList(), true))
        }
    }
}
