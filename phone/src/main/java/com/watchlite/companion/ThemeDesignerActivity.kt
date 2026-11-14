package com.watchlite.companion

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.FileOutputStream

/** Design a watch face on the phone (photo/gradient, clock style, colors, complications) and install it on the watch. */
class ThemeDesignerActivity : Activity() {

    private lateinit var ui: UiKit
    private lateinit var d: ThemeDesign
    private var photo: Bitmap? = null
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private val prefs get() = CompanionApp.prefs
    private val dynamic = HashMap<String, View>()
    private var sending = false
    private val listener = { onEvent() }

    private fun ctl(): JSONObject? = prefs.getString("stats", null)?.let { JSONObject(it).optJSONObject("ctl") }
    private val screenW get() = ctl()?.optInt("screenW", 320)?.takeIf { it > 0 } ?: 320
    private val screenH get() = ctl()?.optInt("screenH", 385)?.takeIf { it > 0 } ?: 385

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = UiKit(this)
        d = intent.getStringExtra("id")?.let { ThemeDesign.load(this, it) } ?: ThemeDesign()
        photo = ThemeDesign.loadPhoto(this, d.id)
        build()
        prefs.getString("stats", null)?.let { JSONObject(it) }?.let { s ->
            preview.painter.battery = s.optInt("battery", 76); preview.painter.steps = s.optInt("steps", 4321); preview.painter.goal = s.optInt("goal", 6000)
        }
        refresh()
    }

    override fun onResume() { super.onResume(); PhoneLinkService.listen(listener) }
    override fun onPause() { PhoneLinkService.unlisten(listener); super.onPause() }

    private val root by lazy { ui.vbox().apply { setPadding(ui.dp(16), ui.dp(18), ui.dp(16), ui.dp(28)) } }

    // --------------------------------------------------------------- preview
    inner class PreviewView(ctx: Context) : View(ctx) {
        val painter = ManifestPainter(ctx)
        private var lx = 0f; private var ly = 0f
        private val scale = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(g: ScaleGestureDetector): Boolean {
                d.photoZoom = (d.photoZoom * g.scaleFactor).coerceIn(1f, 4f); invalidate(); return true
            }
        })
        init {
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, ui.dpf(26f)) }
            }
            clipToOutline = true
        }
        override fun onMeasure(wSpec: Int, hSpec: Int) {
            val w = (resources.displayMetrics.widthPixels * 0.62f).toInt()
            setMeasuredDimension(w, w * screenH / screenW)
        }
        override fun onDraw(c: Canvas) {
            val sw = screenW.toFloat(); val s = width / sw
            c.save(); c.scale(s, s)
            painter.draw(c, d.manifest(screenW, screenH), screenW, screenH, photo, d)
            c.restore()
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (d.bgType != "photo" || photo == null) return false
            parent.requestDisallowInterceptTouchEvent(true)
            scale.onTouchEvent(e)
            val src = photo!!
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
                MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && e.pointerCount == 1) {
                    // drag moves the photo under the frame
                    val k = maxOf(width.toFloat() / src.width, height.toFloat() / src.height) * d.photoZoom
                    d.photoFx = (d.photoFx - (e.x - lx) / k / src.width).coerceIn(0f, 1f)
                    d.photoFy = (d.photoFy - (e.y - ly) / k / src.height).coerceIn(0f, 1f)
                    lx = e.x; ly = e.y; invalidate()
                }
                MotionEvent.ACTION_POINTER_UP -> { val i = if (e.actionIndex == 0) 1 else 0; lx = e.getX(i); ly = e.getY(i) }
            }
            return true
        }
    }

    // --------------------------------------------------------------- UI
    private fun section(title: String, icon: Int): LinearLayout {
        root.addView(ui.sectionTitle(title, icon))
        return ui.cardBox().also { root.addView(it) }
    }

    private fun label(s: String) = ui.tv(s, 12f, ui.text2).apply { setPadding(0, ui.dp(10), 0, ui.dp(6)) }

    private fun build() {
        preview = PreviewView(this)
        root.addView(ui.toolbar(getString(R.string.design_title)))
        val holder = FrameLayout(this).apply { setPadding(0, ui.dp(16), 0, ui.dp(4)) }
        holder.addView(preview, FrameLayout.LayoutParams(-2, -2, android.view.Gravity.CENTER_HORIZONTAL))
        root.addView(holder)
        root.addView(ui.tv(getString(R.string.design_pan_hint), 12f, ui.text2).apply { gravity = android.view.Gravity.CENTER }.also { dynamic["panHint"] = it })

        // name
        val n = section(getString(R.string.design_name), R.drawable.ic_edit)
        n.addView(EditText(this).apply {
            setText(d.name); hint = getString(R.string.design_name_hint); setTextColor(ui.white); setHintTextColor(ui.text2)
            background = ui.bg(ui.card2, 12); setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12)); maxLines = 1
            addTextChangedListener(object : android.text.TextWatcher {
                override fun afterTextChanged(s: android.text.Editable?) { d.name = s.toString() }
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            })
        })

        // background
        val b = section(getString(R.string.design_background), R.drawable.ic_image)
        b.addView(ui.segmented(listOf(
            Triple("photo", getString(R.string.design_photo), R.drawable.ic_add_photo_alternate),
            Triple("gradient", getString(R.string.design_gradient), R.drawable.ic_gradient),
            Triple("solid", getString(R.string.design_solid), R.drawable.ic_format_color_fill)), d.bgType) {
            d.bgType = it; if (it == "photo" && photo == null) pickPhoto(); refresh()
        })
        dynamic["photoBtn"] = ui.button(getString(R.string.design_choose_photo), R.drawable.ic_add_photo_alternate) { pickPhoto() }
        b.addView(dynamic["photoBtn"], ui.lp(h = ui.dp(46), top = 10))
        dynamic["gradients"] = gradientSwatches().also { b.addView(it, ui.lp(top = 10)) }
        dynamic["solids"] = ui.swatches(ThemeDesign.SOLIDS, d.solid) { d.solid = it; refresh() }.also { b.addView(it, ui.lp(top = 10)) }
        b.addView(ui.slider(getString(R.string.design_dim), R.drawable.ic_bedtime, 0, 70, d.scrim, { "$it%" }) { d.scrim = it; refresh() }, ui.lp(top = 8))
        b.addView(label(getString(R.string.design_effect)))
        b.addView(ui.segmented(listOf(
            Triple("none", getString(R.string.design_none), null), Triple("twinkle", getString(R.string.design_twinkle), null),
            Triple("bubbles", getString(R.string.design_bubbles), null), Triple("starfield", getString(R.string.design_stars), null)), d.overlay) { d.overlay = it; refresh() })

        // clock
        val c = section(getString(R.string.design_clock), R.drawable.ic_schedule)
        c.addView(ui.segmented(listOf(Triple("digital", getString(R.string.design_digital), R.drawable.ic_text_fields),
            Triple("analog", getString(R.string.design_analog), R.drawable.ic_schedule)), d.clock) { d.clock = it; refresh() })
        val digital = ui.vbox().also { dynamic["digital"] = it; c.addView(it) }
        digital.addView(label(getString(R.string.design_font)))
        digital.addView(fontPicker())
        digital.addView(label(getString(R.string.design_position)))
        digital.addView(ui.segmented(listOf(
            Triple("top", getString(R.string.design_top), R.drawable.ic_vertical_align_top),
            Triple("center", getString(R.string.design_center), R.drawable.ic_vertical_align_center),
            Triple("bottom", getString(R.string.design_bottom), R.drawable.ic_vertical_align_bottom)), d.position) { d.position = it; refresh() })
        digital.addView(ui.slider(getString(R.string.design_size), R.drawable.ic_format_size, 20, 42, d.size, { "$it" }) { d.size = it; refresh() }, ui.lp(top = 8))
        digital.addView(ui.switchRow(getString(R.string.design_outline), R.drawable.ic_brush, d.outline) { d.outline = it; refresh() })
        dynamic["analog"] = ui.switchRow(getString(R.string.design_seconds), R.drawable.ic_timer, d.seconds) { d.seconds = it; refresh() }.also { c.addView(it, ui.lp(top = 8)) }

        // colors
        val col = section(getString(R.string.design_colors), R.drawable.ic_palette)
        col.addView(label(getString(R.string.design_time_color)))
        col.addView(ui.swatches(ThemeDesign.COLORS, d.timeColor) { d.timeColor = it; refresh() })
        col.addView(label(getString(R.string.design_accent)))
        col.addView(ui.swatches(ThemeDesign.COLORS.drop(2) + ThemeDesign.COLORS.take(2), d.accent) { d.accent = it; refresh() })

        // complications
        val x = section(getString(R.string.design_extras), R.drawable.ic_auto_awesome)
        x.addView(ui.switchRow(getString(R.string.design_date), R.drawable.ic_calendar_month, d.showDate) { d.showDate = it; refresh() })
        x.addView(ui.switchRow(getString(R.string.battery), R.drawable.ic_battery_full, d.showBattery) { d.showBattery = it; refresh() })
        x.addView(ui.switchRow(getString(R.string.steps), R.drawable.ic_directions_walk, d.showSteps) { d.showSteps = it; refresh() })
        x.addView(ui.switchRow(getString(R.string.design_prayer), R.drawable.ic_schedule, d.showPrayer) { d.showPrayer = it; refresh() })
        x.addView(ui.switchRow(getString(R.string.design_hijri), R.drawable.ic_calendar_month, d.showHijri) { d.showHijri = it; refresh() })

        // send
        status = ui.tv("", 13f, ui.text2).apply { gravity = android.view.Gravity.CENTER; setPadding(0, ui.dp(14), 0, ui.dp(6)) }
        root.addView(status)
        root.addView(ui.button(getString(R.string.design_send), R.drawable.ic_send, true) { send() }, ui.lp(h = ui.dp(52)))
        root.addView(ui.tv(getString(R.string.design_send_hint), 12f, ui.text2).apply { gravity = android.view.Gravity.CENTER; setPadding(0, ui.dp(8), 0, 0) })

        setContentView(ScrollView(this).apply { addView(root); setBackgroundColor(0xFF0B0B10.toInt()) })
    }

    private fun gradientSwatches(): View {
        val r = ui.hbox()
        val views = ArrayList<View>()
        fun paint() = views.forEachIndexed { i, v -> (v.background as GradientDrawable).setStroke(if (i == d.gradient) ui.dp(3) else ui.dp(1), if (i == d.gradient) ui.white else 0x33FFFFFF) }
        ThemeDesign.GRADIENTS.forEachIndexed { i, g ->
            val v = View(this).apply {
                background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, g).apply { shape = GradientDrawable.OVAL }
                setOnClickListener { d.gradient = i; paint(); refresh() }
            }
            views += v; r.addView(v, LinearLayout.LayoutParams(ui.dp(34), ui.dp(34)).apply { marginEnd = ui.dp(10) })
        }
        paint()
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun fontPicker(): View {
        val r = ui.hbox()
        val views = ArrayList<TextView>()
        fun paint() = views.forEachIndexed { i, v ->
            val on = ThemeDesign.FONTS[i] == d.font
            v.background = ui.bg(if (on) ui.accent else ui.card2, 12); v.setTextColor(if (on) ui.white else ui.text2)
        }
        ThemeDesign.FONTS.forEach { f ->
            val v = ui.tv("12:30", 20f).apply {
                typeface = try { Typeface.createFromAsset(assets, "fonts/$f.ttf") } catch (_: Exception) { Typeface.DEFAULT_BOLD }
                setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)); gravity = android.view.Gravity.CENTER
                setOnClickListener { d.font = f; paint(); refresh() }
            }
            views += v; r.addView(v, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) })
        }
        paint()
        return HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(r) }
    }

    private fun refresh() {
        val photoMode = d.bgType == "photo"
        dynamic["photoBtn"]?.visibility = if (photoMode) View.VISIBLE else View.GONE
        dynamic["panHint"]?.visibility = if (photoMode && photo != null) View.VISIBLE else View.GONE
        dynamic["gradients"]?.visibility = if (d.bgType == "gradient") View.VISIBLE else View.GONE
        dynamic["solids"]?.visibility = if (d.bgType == "solid") View.VISIBLE else View.GONE
        dynamic["digital"]?.visibility = if (d.clock == "digital") View.VISIBLE else View.GONE
        dynamic["analog"]?.visibility = if (d.clock == "analog") View.VISIBLE else View.GONE
        preview.invalidate()
    }

    // --------------------------------------------------------------- photo
    private fun pickPhoto() {
        val i = Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        try { startActivityForResult(Intent.createChooser(i, getString(R.string.design_choose_photo)), 7) } catch (_: Exception) { ui.toast(getString(R.string.design_no_gallery)) }
    }

    @Deprecated("simple result API is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: run { if (photo == null) { d.bgType = "gradient"; rebuild() }; return }
        if (requestCode != 7 || resultCode != RESULT_OK) return
        Thread {
            val bmp = decode(uri)
            runOnUiThread {
                if (bmp == null) { ui.toast(getString(R.string.design_bad_photo)); return@runOnUiThread }
                photo = bmp; d.photoFx = .5f; d.photoFy = .5f; d.photoZoom = 1f; d.bgType = "photo"
                try { FileOutputStream(ThemeDesign.photoFile(this, d.id)).use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) } } catch (_: Exception) { }
                if (d.scrim < 15) d.scrim = 25
                refresh()
            }
        }.start()
    }

    private fun rebuild() { (root.parent as? android.view.ViewGroup)?.removeView(root); root.removeAllViews(); build(); refresh() }

    private fun decode(uri: Uri): Bitmap? = try {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= 1400) sample *= 2
        val raw = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample }) }
        val rot = try {
            contentResolver.openInputStream(uri)!!.use {
                when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
            }
        } catch (_: Exception) { 0f }
        if (raw != null && rot != 0f) Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, Matrix().apply { postRotate(rot) }, true) else raw
    } catch (_: Throwable) { null }

    // --------------------------------------------------------------- send
    private fun send() {
        if (sending) return
        if (!PhoneLinkService.connected) { ui.toast(getString(R.string.not_connected)); return }
        if (d.name.isBlank()) d.name = getString(R.string.design_default_name)
        d.save(this)
        sending = true
        status.setTextColor(ui.text2); status.text = getString(R.string.design_sending)
        val w = screenW; val h = screenH
        val m = d.manifest(w, h)
        val bg = if (d.bgType == "photo") photo?.let { d.croppedPhoto(it, w, h) } else null
        Thread {
            val ok = try { ThemeStore.sendTheme(m, bg, apply = true) } catch (_: Throwable) { false }
            bg?.recycle()
            runOnUiThread {
                if (!ok) { sending = false; status.text = getString(R.string.design_failed) }
            }
        }.start()
    }

    private fun onEvent() {
        if (!sending) return
        val e = ThemeStore.event
        when {
            e.startsWith("progress:") -> status.text = getString(R.string.design_sending) + " " + e.removePrefix("progress:")
            e == "installed:${d.id}" -> {
                sending = false; status.setTextColor(0xFF30D158.toInt()); status.text = getString(R.string.design_installed)
                ThemeStore.forget(this, d.id)
            }
            e.startsWith("error:") -> { sending = false; status.setTextColor(0xFFFF453A.toInt()); status.text = getString(R.string.design_failed) + " (" + e.removePrefix("error:") + ")" }
        }
    }
}
