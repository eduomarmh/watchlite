package com.watchlite.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import android.webkit.MimeTypeMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/**
 * File sharing with the watch (same protocol as the watch's FileTransfer):
 * file.start {fid,name,size} → file.chunk {fid,i,data} → file.end {fid,n}; answer file.done {fid,ok}; file.cancel {fid}.
 * Received files land in Download/WatchLite. Nothing runs between transfers.
 */
object FileTransfer {
    private const val CHUNK = 32 * 1024
    const val MAX_SIZE = 200L * 1024 * 1024

    class Progress(val fid: String, val name: String, val size: Long, val outgoing: Boolean) {
        @Volatile var done = 0L
        @Volatile var state = "run"
    }

    @Volatile var outgoing: Progress? = null
    @Volatile var incoming: Progress? = null
    private val main = Handler(Looper.getMainLooper())
    private val queue = ArrayList<Uri>()
    private fun changed() = main.post { PhoneLinkService.changed() }

    // ------------------------------------------------------------------ send (a queue, one at a time)
    fun enqueue(ctx: Context, uris: List<Uri>) {
        synchronized(queue) { queue.addAll(uris) }
        pump(ctx.applicationContext)
    }

    private fun pump(ctx: Context) {
        if (outgoing?.state == "run") return
        val uri = synchronized(queue) { if (queue.isEmpty()) null else queue.removeAt(0) } ?: return
        Thread { sendOne(ctx, uri); main.postDelayed({ pump(ctx) }, 300) }.start()
    }

    /** Copies a shared URI into cache/share/<name> (deleted once sent). */
    fun stage(ctx: Context, uri: Uri): Uri? = try {
        val name = meta(ctx, uri).first.replace('/', '_')
        val dir = File(ctx.cacheDir, "share/" + System.nanoTime()).apply { mkdirs() }
        val f = File(dir, name)
        ctx.contentResolver.openInputStream(uri)!!.use { i -> f.outputStream().use { i.copyTo(it) } }
        Uri.fromFile(f)
    } catch (t: Throwable) { Log.w("WLC", "stage: ${t.message}"); null }

    private fun meta(ctx: Context, uri: Uri): Pair<String, Long> {
        if (uri.scheme == "file") uri.path?.let { File(it) }?.let { return it.name to it.length() }
        var name = uri.lastPathSegment ?: "file"; var size = -1L
        try {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst()) { c.getString(0)?.let { name = it }; if (!c.isNull(1)) size = c.getLong(1) }
            }
        } catch (_: Throwable) { }
        return name to size
    }

    private fun sendOne(ctx: Context, uri: Uri) {
        val (name, known) = meta(ctx, uri)
        // size must be known up front: copy to cache if the provider doesn't say
        var src: File? = null
        val size = if (known >= 0) known else try {
            src = File(ctx.cacheDir, "out_" + System.currentTimeMillis()); ctx.contentResolver.openInputStream(uri)!!.use { i -> src!!.outputStream().use { i.copyTo(it) } }; src!!.length()
        } catch (_: Throwable) { -1L }
        val pr = Progress(UUID.randomUUID().toString().take(12), name, size.coerceAtLeast(0), true)
        outgoing = pr; changed()
        try {
            if (size < 0) error("unreadable")
            if (size > MAX_SIZE) error("too big")
            if (!PhoneLinkService.send("file.start", JSONObject().put("fid", pr.fid).put("name", name).put("size", size))) error("link")
            val input = src?.inputStream() ?: ctx.contentResolver.openInputStream(uri) ?: error("open")
            var i = 0
            input.use {
                val buf = ByteArray(CHUNK)
                while (pr.state == "run") {
                    val n = it.read(buf); if (n <= 0) break
                    if (!PhoneLinkService.send("file.chunk", JSONObject().put("fid", pr.fid).put("i", i++).put("data", Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)))) error("link")
                    pr.done += n
                    if (i % 4 == 0) changed()
                }
            }
            if (pr.state == "run") PhoneLinkService.send("file.end", JSONObject().put("fid", pr.fid).put("n", i))
            else PhoneLinkService.send("file.cancel", JSONObject().put("fid", pr.fid))
        } catch (t: Throwable) { Log.w("WLC", "send file: ${t.message}"); pr.state = "fail" }
        src?.delete()
        if (uri.scheme == "file" && uri.path?.contains("/cache/share/") == true) uri.path?.let { File(it).parentFile?.deleteRecursively() }
        changed()
    }

    fun cancel() {
        synchronized(queue) { queue.clear() }
        outgoing?.let { if (it.state == "run") it.state = "cancel" }
        incoming?.let { if (it.state == "run") { PhoneLinkService.send("file.cancel", JSONObject().put("fid", it.fid)); it.state = "cancel"; drop() } }
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
                incoming = Progress(fid, p.optString("name", "file"), p.optLong("size"), false)
                tmp = File(ctx.cacheDir, "in_$fid.part"); out = FileOutputStream(tmp)
                changed()
            }
            "file.chunk" -> {
                val pr = incoming ?: return
                if (pr.fid != fid || pr.state != "run") return
                val b = Base64.decode(p.optString("data"), Base64.DEFAULT)
                try { out?.write(b); pr.done += b.size } catch (_: Throwable) { pr.state = "fail"; drop() }
                if (p.optInt("i") % 4 == 0) changed()
            }
            "file.end" -> {
                val pr = incoming ?: return
                if (pr.fid != fid) return
                try { out?.close() } catch (_: Throwable) { }
                out = null
                val t = tmp; tmp = null
                var saved: Uri? = null
                if (pr.state == "run" && t != null && t.length() == pr.size) saved = try { store(ctx, t, pr.name) } catch (e: Throwable) { Log.w("WLC", "store", e); null }
                t?.delete()
                pr.state = if (saved != null) "ok" else "fail"
                if (saved != null) { remember(pr.name, saved); notifyReceived(ctx, pr.name, saved) }
                PhoneLinkService.send("file.done", JSONObject().put("fid", fid).put("ok", saved != null))
                changed()
            }
            "file.cancel" -> {
                outgoing?.let { if (it.fid == fid) it.state = "cancel" }
                incoming?.let { if (it.fid == fid) { it.state = "cancel"; drop() } }
                changed()
            }
            "file.done" -> { outgoing?.let { if (it.fid == fid) it.state = if (p.optBoolean("ok")) "ok" else "fail" }; changed() }
        }
    }

    fun mime(name: String) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase()) ?: "application/octet-stream"

    /** Download/WatchLite via MediaStore (Android 10+), or the app's own Download folder before that. */
    private fun store(ctx: Context, src: File, name: String): Uri {
        if (Build.VERSION.SDK_INT >= 29) {
            val v = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime(name))
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/WatchLite")
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: error("insert")
            ctx.contentResolver.openOutputStream(uri)!!.use { o -> src.inputStream().use { it.copyTo(o) } }
            return uri
        }
        val dir = File(ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "WatchLite").apply { mkdirs() }
        var f = File(dir, name); var n = 1
        while (f.exists()) { f = File(dir, name.substringBeforeLast('.') + " ($n)" + (if ('.' in name) "." + name.substringAfterLast('.') else "")); n++ }
        src.copyTo(f)
        return Uri.fromFile(f)
    }

    private fun remember(name: String, uri: Uri) {
        val prefs = CompanionApp.prefs
        val a = try { JSONArray(prefs.getString("received_files", "[]")) } catch (_: Exception) { JSONArray() }
        val b = JSONArray().put(JSONObject().put("name", name).put("uri", uri.toString()).put("time", System.currentTimeMillis()))
        for (i in 0 until minOf(a.length(), 19)) b.put(a.get(i))
        prefs.edit().putString("received_files", b.toString()).apply()
    }

    fun received(): List<Triple<String, Uri, Long>> {
        val a = try { JSONArray(CompanionApp.prefs.getString("received_files", "[]")) } catch (_: Exception) { JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.optString("name"), Uri.parse(o.optString("uri")), o.optLong("time")) } }
    }

    fun viewIntent(name: String, uri: Uri): Intent =
        Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime(name)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun notifyReceived(ctx: Context, name: String, uri: Uri) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("files", ctx.getString(R.string.files_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val target = if (uri.scheme == "file") Intent(ctx, MainActivity::class.java) else viewIntent(name, uri)
        val pi = PendingIntent.getActivity(ctx, name.hashCode(), target, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        nm.notify(("file" + name).hashCode(), Notification.Builder(ctx, "files").setSmallIcon(R.drawable.ic_download)
            .setContentTitle(ctx.getString(R.string.file_from_watch)).setContentText(name).setContentIntent(pi).setAutoCancel(true).build())
    }
}
