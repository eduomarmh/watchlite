package com.watchlite.companion

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject

/** Watch faces as pictures: rendered by the watch itself, cached on the phone, tap to apply. */
class ThemePickerActivity : Activity() {

    private lateinit var ui: UiKit
    private lateinit var grid: LinearLayout
    private lateinit var status: TextView
    private val prefs get() = CompanionApp.prefs
    private val bitmaps = HashMap<String, Bitmap>()
    private var shown = ""
    private val listener = { render() }

    private fun current(): String? = prefs.getString("stats", null)?.let { JSONObject(it).optJSONObject("ctl")?.optString("theme") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        val root = ui.vbox().apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(28)) }
        val add = ui.button(getString(R.string.design_new), R.drawable.ic_add, true) { startActivity(Intent(this, ThemeDesignerActivity::class.java)) }
        root.addView(ui.toolbar(getString(R.string.c_theme), add.apply { layoutParams = LinearLayout.LayoutParams(-2, ui.dp(42)) }))
        status = ui.tv("", 12.5f, ui.text2).apply { setPadding(ui.dp(4), ui.dp(14), 0, ui.dp(6)) }
        root.addView(status)
        grid = ui.vbox()
        root.addView(grid)
        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B0B10.toInt()) })
    }

    override fun onResume() {
        super.onResume()
        PhoneLinkService.listen(listener)
        shown = ""
        if (!ThemeStore.request()) status.text = getString(R.string.not_connected)
        render()
    }

    override fun onPause() { PhoneLinkService.unlisten(listener); super.onPause() }

    override fun onDestroy() { bitmaps.values.forEach { it.recycle() }; bitmaps.clear(); super.onDestroy() }

    private fun render() {
        val faces = ThemeStore.faces(this)
        val cur = current()
        status.text = when {
            !PhoneLinkService.connected -> getString(R.string.not_connected) + if (faces.isNotEmpty()) " · " + getString(R.string.picker_cached) else ""
            faces.isEmpty() -> getString(R.string.picker_loading)
            else -> getString(R.string.picker_hint)
        }
        val key = faces.joinToString { it.id + it.file.lastModified() } + cur
        if (key == shown) return
        shown = key
        grid.removeAllViews()
        val cols = 3
        faces.chunked(cols).forEach { rowFaces ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            rowFaces.forEach { f -> r.addView(cell(f, f.id == cur), LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(10) }) }
            repeat(cols - rowFaces.size) { r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginEnd = ui.dp(10) }) }
            grid.addView(r, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = ui.dp(14); marginEnd = -ui.dp(10) })
        }
    }

    private fun cell(f: ThemeStore.Face, selected: Boolean): View {
        val box = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val frame = FrameLayout(this).apply {
            background = GradientDrawable().apply {
                cornerRadius = ui.dpf(18f); setColor(0xFF000000.toInt())
                setStroke(if (selected) ui.dp(3) else ui.dp(1), if (selected) ui.accent else 0x22FFFFFF)
            }
            setPadding(ui.dp(3), ui.dp(3), ui.dp(3), ui.dp(3))
        }
        val img = object : ImageView(this) {
            override fun onMeasure(w: Int, h: Int) {
                val width = MeasureSpec.getSize(w)
                setMeasuredDimension(width, width * 192 / 160)
            }
        }.apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            val b = bitmaps.getOrPut(f.id + f.file.lastModified()) { ThemeStore.decodeThumb(f.file) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) }
            setImageBitmap(b)
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dpf(15f)) }
            }
            clipToOutline = true
        }
        frame.addView(img, FrameLayout.LayoutParams(-1, -2))
        if (selected) frame.addView(ui.iconView(R.drawable.ic_check_circle, ui.accent, 22).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFFFFFFFF.toInt()) }
        }, FrameLayout.LayoutParams(ui.dp(22), ui.dp(22), Gravity.TOP or Gravity.END).apply { setMargins(0, ui.dp(6), ui.dp(6), 0) })
        if (!f.builtIn) frame.addView(ui.iconView(R.drawable.ic_brush, ui.white, 16).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xAA000000.toInt()) }; setPadding(ui.dp(4), ui.dp(4), ui.dp(4), ui.dp(4))
        }, FrameLayout.LayoutParams(ui.dp(24), ui.dp(24), Gravity.TOP or Gravity.START).apply { setMargins(ui.dp(6), ui.dp(6), 0, 0) })
        box.addView(frame, LinearLayout.LayoutParams(-1, -2))
        box.addView(ui.tv(f.name, 12f, if (selected) ui.white else ui.text2, selected).apply {
            maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER; setPadding(0, ui.dp(6), 0, 0)
        }, LinearLayout.LayoutParams(-1, -2))
        box.setOnClickListener { apply(f) }
        box.setOnLongClickListener { options(f); true }
        return box
    }

    private fun apply(f: ThemeStore.Face) {
        if (!PhoneLinkService.send("ctl.set", JSONObject().put("k", "theme").put("v", f.id))) { ui.toast(getString(R.string.not_connected)); return }
        prefs.getString("stats", null)?.let { st ->
            val j = JSONObject(st); val c = j.optJSONObject("ctl") ?: JSONObject(); c.put("theme", f.id); j.put("ctl", c)
            prefs.edit().putString("stats", j.toString()).apply()
        }
        render()
    }

    private fun options(f: ThemeStore.Face) {
        if (f.builtIn) return
        val canEdit = ThemeDesign.load(this, f.id) != null
        val items = ArrayList<String>(); val acts = ArrayList<() -> Unit>()
        if (canEdit) { items += getString(R.string.picker_edit); acts += { startActivity(Intent(this, ThemeDesignerActivity::class.java).putExtra("id", f.id)) } }
        items += getString(R.string.picker_delete); acts += {
            AlertDialog.Builder(this).setMessage(getString(R.string.picker_delete_q, f.name))
                .setPositiveButton(R.string.picker_delete) { _, _ ->
                    if (PhoneLinkService.send("theme.delete", JSONObject().put("id", f.id))) { ThemeStore.forget(this, f.id); shown = ""; render() }
                    else ui.toast(getString(R.string.not_connected))
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        AlertDialog.Builder(this).setTitle(f.name).setItems(items.toTypedArray()) { _, i -> acts[i]() }.show()
    }
}
