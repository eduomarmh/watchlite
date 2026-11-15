package com.watchlite.launcher.files

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.webkit.MimeTypeMap
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.input.InputMapper
import com.watchlite.launcher.link.FileTransfer
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Ui
import java.io.File
import java.text.DateFormat

/** Files on the watch: received files, a simple browser, and "send to phone". */
class FilesActivity : Activity() {

    private lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private val stack = ArrayList<File?>()        // null = home screen
    private val listener = { render() }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        list = ui.vbox()
        list.setPadding(ui.sp.safe, ui.px(if (ui.sp.isRound) 12f else 5f), ui.sp.safe, ui.px(if (ui.sp.isRound) 18f else 10f))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list); setBackgroundColor(Ui.BG) }
        setContentView(scroll)
        stack.add(null)
        val need = arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE, android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
            .filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 1)
        render()
        intent.getStringExtra("path")?.let { File(it) }?.takeIf { it.isFile }?.let { fileDialog(it) }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) = render()
    override fun onResume() { super.onResume(); FileTransfer.listen(listener); render() }
    override fun onPause() { FileTransfer.unlisten(listener); super.onPause() }

    @Deprecated("framework") override fun onBackPressed() {
        if (stack.size > 1) { stack.removeAt(stack.size - 1); render(); scroll.scrollTo(0, 0) } else super.onBackPressed()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val d = InputMapper.fromKey(event)
        if (d != 0) { if (event.action == KeyEvent.ACTION_DOWN) scroll.smoothScrollBy(0, (d * ui.u * 14).toInt()); return true }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val d = InputMapper.fromMotion(event)
        if (d != 0) { scroll.smoothScrollBy(0, (d * ui.u * 14).toInt()); return true }
        return super.onGenericMotionEvent(event)
    }

    // ------------------------------------------------------------------ UI
    private fun s(id: Int) = getString(id)

    private fun header(t: String) = list.addView(ui.text(t, 4.6f, ui.accent, 2), ui.lp(top = 3f, bottom = 1.2f, start = 2f))

    private fun row(icon: Int, title: String, sub: String?, onClick: () -> Unit) {
        val r = ui.hbox()
        r.background = ui.ripple(ui.rounded(Ui.CARD, 5f * ui.u))
        r.setPadding(ui.px(3.5f), ui.px(3f), ui.px(4f), ui.px(3f))
        r.addView(ImageView(this).apply { setImageResource(icon); imageTintList = ColorStateList.valueOf(ui.accent) }, ui.lp(ui.px(9f), ui.px(9f), end = 3f))
        val col = ui.vbox()
        col.addView(ui.text(title, 5.1f, Ui.TEXT, 1, 2))
        if (!sub.isNullOrEmpty()) col.addView(ui.text(sub, 4.1f, Ui.TEXT2, 1, 1), ui.lp(top = 0.4f))
        r.addView(col, ui.lp(0, weight = 1f))
        r.isClickable = true
        r.setOnClickListener { Haptics.tick(this); onClick() }
        list.addView(r, ui.lp(bottom = 1.4f))
    }

    private fun size(b: Long): String {
        val ar = Digits.useArabic()
        val t = when {
            b >= 1024L * 1024 * 1024 -> "%.1f GB".format(b / 1073741824.0)
            b >= 1024L * 1024 -> "%.1f MB".format(b / 1048576.0)
            b >= 1024 -> "${b / 1024} KB"
            else -> "$b B"
        }
        return Digits.apply(t, ar)
    }

    private fun transferCard(pr: FileTransfer.Progress) {
        val c = ui.card()
        val title = s(if (pr.outgoing) R.string.file_sending else R.string.file_receiving)
        val state = when (pr.state) {
            "ok" -> s(if (pr.outgoing) R.string.file_sent_ok else R.string.file_received)
            "fail" -> s(R.string.file_failed); "cancel" -> s(R.string.file_cancelled)
            else -> size(pr.done) + " / " + size(pr.size)
        }
        c.addView(ui.text(title, 4.4f, Ui.TEXT2))
        c.addView(ui.text(pr.name, 5.1f, Ui.TEXT, 2, 1))
        if (pr.state == "run") c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progress = if (pr.size > 0) (pr.done * 1000 / pr.size).toInt() else 0
            progressTintList = ColorStateList.valueOf(ui.accent)
        }, ui.lp(top = 1.5f))
        c.addView(ui.text(state, 4.3f, if (pr.state == "fail") Ui.RED else Ui.TEXT2), ui.lp(top = 1f))
        if (pr.state == "run") c.addView(ui.pill(s(R.string.cancel)) { FileTransfer.cancel() }, ui.lp(top = 1.5f))
        list.addView(c, ui.lp(bottom = 2f))
    }

    private fun render() {
        val y = scroll.scrollY
        list.removeAllViews()
        val dir = stack.last()
        list.addView(ui.text(dir?.name ?: s(R.string.files_title), 7f, Ui.TEXT, 2, 1).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 3f))
        FileTransfer.outgoing?.let { transferCard(it) }
        FileTransfer.incoming?.let { transferCard(it) }
        if (dir == null) home() else folder(dir)
        scroll.post { scroll.scrollTo(0, y) }
    }

    private fun home() {
        if (!App.I.state.linkConnected) list.addView(ui.text(s(R.string.files_not_connected), 4.4f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 2f))
        val inbox = FileTransfer.inbox(this)
        header(s(R.string.files_received))
        val got = inbox.listFiles()?.filter { it.isFile }?.sortedByDescending { it.lastModified() } ?: emptyList()
        if (got.isEmpty()) list.addView(ui.text(s(R.string.files_none), 4.4f, Ui.TEXT2), ui.lp(start = 2f, bottom = 1f))
        got.take(30).forEach { f -> row(iconFor(f), f.name, size(f.length()) + " · " + DateFormat.getDateInstance(DateFormat.SHORT).format(f.lastModified())) { fileDialog(f) } }
        header(s(R.string.files_browse))
        val root = Environment.getExternalStorageDirectory()
        listOf(Environment.DIRECTORY_DOWNLOADS to R.string.dir_downloads, Environment.DIRECTORY_DCIM to R.string.dir_camera,
            Environment.DIRECTORY_PICTURES to R.string.dir_pictures, Environment.DIRECTORY_MUSIC to R.string.dir_music,
            "Recordings" to R.string.dir_recordings, Environment.DIRECTORY_DOCUMENTS to R.string.dir_documents).forEach { (d, label) ->
            val f = File(root, d)
            if (f.isDirectory) row(R.drawable.ic_folder, s(label), null) { push(f) }
        }
        row(R.drawable.ic_folder, s(R.string.dir_all), null) { push(root) }
    }

    private fun push(f: File) { stack.add(f); render(); scroll.scrollTo(0, 0) }

    private fun folder(dir: File) {
        val items = dir.listFiles()?.filter { !it.name.startsWith(".") }
            ?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        if (items.isEmpty()) list.addView(ui.text(s(if (dir.canRead()) R.string.files_empty else R.string.files_no_permission), 4.6f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, ui.lp(top = 4f))
        items.take(300).forEach { f ->
            if (f.isDirectory) row(R.drawable.ic_folder, f.name, null) { push(f) }
            else row(iconFor(f), f.name, size(f.length())) { fileDialog(f) }
        }
    }

    private fun mime(f: File) = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"

    private fun iconFor(f: File): Int = when (mime(f).substringBefore('/')) {
        "image" -> R.drawable.ic_image; "audio" -> R.drawable.ic_music_note; "video" -> R.drawable.ic_movie
        else -> R.drawable.ic_description
    }

    private fun fileDialog(f: File) {
        val items = arrayOf(s(R.string.file_open), s(R.string.file_send_phone), s(R.string.file_delete))
        AlertDialog.Builder(this, Ui.DIALOG).setTitle(f.name).setItems(items) { _, i ->
            when (i) {
                0 -> open(f)
                1 -> sendToPhone(f)
                2 -> AlertDialog.Builder(this, Ui.DIALOG).setMessage(getString(R.string.file_delete_q, f.name))
                    .setPositiveButton(R.string.file_delete) { _, _ -> f.delete(); render() }.setNegativeButton(R.string.cancel, null).show()
            }
        }.show()
    }

    private fun sendToPhone(f: File) {
        when {
            !App.I.state.linkConnected -> toast(s(R.string.files_not_connected))
            f.length() > FileTransfer.MAX_SIZE -> toast(s(R.string.file_too_big))
            !FileTransfer.send(this, f) -> toast(s(R.string.file_busy))
            else -> render()
        }
    }

    private fun open(f: File) {
        // personal launcher, no AndroidX FileProvider: allow file:// URIs for the viewer app
        StrictMode.setVmPolicy(StrictMode.VmPolicy.Builder().build())
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(f), mime(f)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Exception) { toast(s(R.string.no_app)) }
    }

    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()
}
