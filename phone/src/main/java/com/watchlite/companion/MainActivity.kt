package com.watchlite.companion

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import org.json.JSONObject
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Marker
import java.text.DateFormat
import java.util.Date

/**
 * Companion dashboard: watch status, watch location on an OpenStreetMap map,
 * pairing, feature switches and the permissions Huawei phones need.
 */
class MainActivity : Activity() {

    private val accent = 0xFF7B6CFF.toInt()
    private val card = 0xFF17171F.toInt()
    private val text2 = 0xFF9A9AA6.toInt()
    private lateinit var root: LinearLayout
    private var map: MapView? = null
    private var marker: Marker? = null
    private val prefs get() = CompanionApp.prefs
    private val listener = { try { render() } catch (t: Throwable) { Log.w("WLC", "render", t); CompanionApp.report(t) } }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var battery: TextView
    private lateinit var steps: TextView
    private lateinit var stepsBar: ProgressBar
    private lateinit var details: TextView
    private lateinit var locText: TextView
    private lateinit var findBtn: Button
    private lateinit var batteryIcon: FrameLayout
    private lateinit var batterySub: TextView
    private lateinit var filesBox: LinearLayout
    private var filesKey = ""

    @Deprecated("simple result API is enough here")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 21 || resultCode != RESULT_OK || data == null) return
        val uris = ArrayList<Uri>()
        data.clipData?.let { c -> for (i in 0 until c.itemCount) uris += c.getItemAt(i).uri }
        if (uris.isEmpty()) data.data?.let { uris += it }
        if (!PhoneLinkService.connected) { toastNotConnected(); return }
        FileTransfer.enqueue(this, uris)
    }

    private fun size(b: Long) = when {
        b >= 1048576 -> "%.1f MB".format(b / 1048576.0); b >= 1024 -> "${b / 1024} KB"; else -> "$b B"
    }

    private fun renderFiles() {
        val o = FileTransfer.outgoing; val i = FileTransfer.incoming
        val key = listOf(o, i).joinToString { if (it == null) "-" else it.fid + it.state + (it.done * 100 / it.size.coerceAtLeast(1)) } + FileTransfer.received().size
        if (key == filesKey) return
        filesKey = key
        filesBox.removeAllViews()
        for (pr in listOfNotNull(o, i)) {
            val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(0xFF20202A.toInt(), 14); setPadding(dp(12), dp(10), dp(12), dp(10)) }
            val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            head.addView(iconView(if (pr.outgoing) R.drawable.ic_upload_file else R.drawable.ic_download, accent, 20))
            head.addView(tv(pr.name, 14f, bold = true).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            c.addView(head)
            val status = when (pr.state) {
                "ok" -> getString(if (pr.outgoing) R.string.file_sent_ok else R.string.file_saved)
                "fail" -> getString(R.string.file_failed); "cancel" -> getString(R.string.file_cancelled)
                else -> getString(if (pr.outgoing) R.string.file_sending else R.string.file_receiving) + " · " + size(pr.done) + " / " + size(pr.size)
            }
            if (pr.state == "run") c.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000; progress = (pr.done * 1000 / pr.size.coerceAtLeast(1)).toInt(); progressTintList = ColorStateList.valueOf(accent)
            }, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(8) })
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(status, 12f, if (pr.state == "fail") 0xFFFF453A.toInt() else text2), LinearLayout.LayoutParams(0, -2, 1f))
            if (pr.state == "run") row.addView(tv(getString(R.string.cancel_transfer), 13f, accent, true).apply {
                setPadding(dp(8), dp(6), dp(8), dp(6)); setOnClickListener { FileTransfer.cancel() }
            })
            c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            filesBox.addView(c, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        }
        val got = FileTransfer.received()
        if (got.isNotEmpty()) filesBox.addView(tv(getString(R.string.files_from_watch), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        got.take(6).forEach { (name, uri, time) ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(8), 0, dp(8)) }
            r.addView(iconView(R.drawable.ic_description, accent, 20))
            val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            col.addView(tv(name, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.MIDDLE })
            col.addView(tv(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(time)), 11.5f, text2))
            r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
            r.setOnClickListener { try { startActivity(FileTransfer.viewIntent(name, uri)) } catch (_: Exception) { toast(getString(R.string.no_viewer)) } }
            filesBox.addView(r)
        }
    }

    /** Shows the last crash (if any) with copy/share, so it can be sent to the developer. */
    private fun showCrashReport() {
        val f = java.io.File(filesDir, "crash.txt")
        if (!f.isFile) return
        val text = f.readText(); f.delete()
        val tv = TextView(this).apply { this.text = text; textSize = 11f; setTextIsSelectable(true); setPadding(dp(20), dp(8), dp(20), 0) }
        AlertDialog.Builder(this).setTitle(R.string.crash_title).setView(ScrollView(this).apply { addView(tv) })
            .setPositiveButton(R.string.crash_copy) { _, _ ->
                (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText("crash", text))
                toast(getString(R.string.crash_copied))
            }
            .setNeutralButton(R.string.crash_share) { _, _ ->
                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.post { showCrashReport() }
        Configuration.getInstance().apply {
            userAgentValue = packageName
            osmdroidBasePath = filesDir
            osmdroidTileCache = java.io.File(cacheDir, "tiles")
        }
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(20), dp(16), dp(24)) }
        setContentView(ScrollView(this).apply { addView(root) })
        build()
        if (!prefs.getString("watch_addr", "").isNullOrEmpty()) PhoneLinkService.start(this)
        askBluetoothPermission()
    }

    override fun onResume() {
        super.onResume()
        PhoneLinkService.listen(listener)
        PhoneLinkService.instance?.stopRing()
        map?.onResume()
        render()
        if (PhoneLinkService.connected) PhoneLinkService.send("stats.request")
        askBluetoothOn()
    }

    /** The link needs Bluetooth on; ask once per app open when a watch is paired and Bluetooth is off. */
    @SuppressLint("MissingPermission")
    private fun askBluetoothOn() {
        if (prefs.getString("watch_addr", "").isNullOrEmpty()) return
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) return
        val a = BluetoothAdapter.getDefaultAdapter() ?: return
        if (!a.isEnabled) try { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } catch (_: Exception) { }
    }

    override fun onPause() {
        PhoneLinkService.unlisten(listener)
        map?.onPause()
        super.onPause()
    }

    // ------------------------------------------------------------------ UI kit (icons, no emoji)
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun tv(s: CharSequence, size: Float = 15f, color: Int = 0xFFFFFFFF.toInt(), bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bg(color: Int, r: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun icon(res: Int, tint: Int, sizeDp: Int = 22): Drawable =
        getDrawable(res)!!.mutate().apply { setTint(tint); setBounds(0, 0, dp(sizeDp), dp(sizeDp)) }

    private fun iconView(res: Int, tint: Int, sizeDp: Int = 22) = ImageView(this).apply {
        setImageDrawable(icon(res, tint, sizeDp)); layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    /** Icon inside a soft round badge (used for tiles and stat blocks). */
    private fun badge(res: Int, tint: Int, size: Int = 40) = FrameLayout(this).apply {
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((tint and 0x00FFFFFF) or 0x2A000000) }
        addView(iconView(res, tint, size / 2), FrameLayout.LayoutParams(dp(size / 2), dp(size / 2), Gravity.CENTER))
        layoutParams = LinearLayout.LayoutParams(dp(size), dp(size))
    }

    private fun section(title: String, iconRes: Int): LinearLayout {
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        h.addView(iconView(iconRes, accent, 18))
        h.addView(tv(title, 13f, accent, true), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
        root.addView(h, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20); bottomMargin = dp(8); marginStart = dp(4) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(card); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        root.addView(c)
        return c
    }

    private fun button(label: String, iconRes: Int? = null, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; textSize = 14f
        val fg = if (primary) 0xFFFFFFFF.toInt() else accent
        setTextColor(fg)
        if (iconRes != null) { setCompoundDrawablesRelative(icon(iconRes, fg, 20), null, null, null); compoundDrawablePadding = dp(8) }
        gravity = Gravity.CENTER
        setPadding(dp(12), 0, dp(12), 0)
        background = bg(if (primary) accent else 0xFF262633.toInt(), 14); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, dp(48), 1f).apply { if (i > 0) marginStart = dp(8) }) }
    }

    private fun toggle(label: String, key: String, iconRes: Int, def: Boolean = true, sum: String? = null) {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(badge(iconRes, accent, 36))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 15f))
        if (sum != null) col.addView(tv(sum, 12f, text2))
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        r.addView(Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        })
        lastSection?.addView(r)
    }

    private var lastSection: LinearLayout? = null

    // ------------------------------------------------------------------ remote controls
    private class Tile(val key: String, val view: LinearLayout, val badgeBg: GradientDrawable, val iconV: ImageView,
                       val onRes: Int, val offRes: Int, val state: TextView, val permKey: String?)
    private val tiles = ArrayList<Tile>()
    private class Slide(val key: String, val bar: SeekBar, val value: TextView, val permKey: String?, val min: Int, val unit: String) { var dragging = false }
    private val slides = ArrayList<Slide>()
    private val ringerBtns = LinkedHashMap<String, TextView>()
    private lateinit var themeBtn: Button
    private lateinit var goalBtn: Button
    private lateinit var controlsBox: LinearLayout

    private fun ctl(): JSONObject? = prefs.getString("stats", null)?.let { JSONObject(it).optJSONObject("ctl") }

    private fun sendCtl(k: String, v: Any?): Boolean {
        val o = JSONObject().put("k", k); if (v != null) o.put("v", v)
        if (!PhoneLinkService.send("ctl.set", o)) { toastNotConnected(); return false }
        // optimistic: reflect the change right away, the watch confirms with fresh stats
        prefs.getString("stats", null)?.let { st ->
            val j = JSONObject(st); val c = j.optJSONObject("ctl") ?: JSONObject()
            if (v != null) c.put(k, v); j.put("ctl", c); prefs.edit().putString("stats", j.toString()).apply()
        }
        render()
        return true
    }

    private fun controlTile(key: String, label: String, onRes: Int, offRes: Int = onRes, permKey: String? = null): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            background = bg(0xFF20202A.toInt(), 16); setPadding(dp(12), dp(12), dp(12), dp(12))
        }
        val bgd = GradientDrawable().apply { shape = GradientDrawable.OVAL }
        val ic = ImageView(this)
        val badge = FrameLayout(this).apply { background = bgd; addView(ic, FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER)) }
        v.addView(badge, LinearLayout.LayoutParams(dp(38), dp(38)))
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 13.5f, bold = true).apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END })
        val st = tv("", 12f, text2).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
        col.addView(st)
        v.addView(col, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        v.setOnClickListener {
            val c = ctl() ?: run { toastNotConnected(); return@setOnClickListener }
            if (permKey != null && !c.optBoolean(permKey, true)) { toast(getString(R.string.c_needs_perm)); return@setOnClickListener }
            sendCtl(key, !c.optBoolean(key))
        }
        tiles += Tile(key, v, bgd, ic, onRes, offRes, st, permKey)
        return v
    }

    private fun grid(vararg views: View): LinearLayout {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        views.toList().chunked(2).forEachIndexed { i, pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            pair.forEachIndexed { j, v -> r.addView(v, LinearLayout.LayoutParams(0, -2, 1f).apply { if (j > 0) marginStart = dp(8) }) }
            if (pair.size == 1) r.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f).apply { marginStart = dp(8) })
            box.addView(r, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(8) })
        }
        return box
    }

    private fun slider(key: String, label: String, iconRes: Int, min: Int = 0, max: Int = 100, permKey: String? = null, unit: String = "%"): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(8), 0, dp(4)) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(iconView(iconRes, accent, 20))
        head.addView(tv(label, 14f), LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(10) })
        val value = tv("--", 13f, text2)
        head.addView(value)
        box.addView(head)
        val bar = SeekBar(this).apply {
            this.max = max - min
            progressTintList = ColorStateList.valueOf(accent); thumbTintList = ColorStateList.valueOf(accent)
            progressBackgroundTintList = ColorStateList.valueOf(0xFF3A3A48.toInt())
        }
        val sl = Slide(key, bar, value, permKey, min, unit)
        bar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) value.text = "${p + min}$unit" }
            override fun onStartTrackingTouch(sb: SeekBar) { sl.dragging = true }
            override fun onStopTrackingTouch(sb: SeekBar) {
                sl.dragging = false
                val c = ctl()
                if (permKey != null && c != null && !c.optBoolean(permKey, true)) {
                    toast(getString(if (permKey == "canBrightness") R.string.c_brightness_perm else R.string.c_needs_perm)); render(); return
                }
                sendCtl(key, sb.progress + min)
            }
        })
        box.addView(bar, LinearLayout.LayoutParams(-1, dp(36)))
        slides += sl
        return box
    }

    private fun ringerRow(): View {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = bg(0xFF20202A.toInt(), 14); setPadding(dp(4), dp(4), dp(4), dp(4)) }
        listOf(Triple("normal", R.string.c_normal, R.drawable.ic_notifications_active),
               Triple("vibrate", R.string.c_vibrate_mode, R.drawable.ic_vibration),
               Triple("silent", R.string.c_silent, R.drawable.ic_volume_off)).forEach { (k, l, ic) ->
            val b = tv(getString(l), 13f, text2).apply {
                gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10))
                compoundDrawablePadding = dp(6); setCompoundDrawablesRelative(icon(ic, text2, 18), null, null, null)
                setOnClickListener { sendCtl("ringer", k) }
            }
            ringerBtns[k] = b
            r.addView(b, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return r
    }

    private fun build() {
        // header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(badge(R.drawable.ic_watch, accent, 44))
        head.addView(tv("WatchLite", 24f, bold = true).apply { maxLines = 1 }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        val chip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; background = bg(card, 20); setPadding(dp(12), dp(6), dp(12), dp(6)) }
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF555555.toInt()) } }
        chip.addView(statusDot, LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginEnd = dp(8) })
        statusText = tv("", 13f, text2)
        chip.addView(statusText)
        head.addView(chip)
        root.addView(head)

        // watch status
        val w = section(getString(R.string.sec_watch), R.drawable.ic_watch)
        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        battery = tv("--", 28f, bold = true); steps = tv("--", 28f, bold = true)
        batteryIcon = badge(R.drawable.ic_battery_full, 0xFF30D158.toInt(), 44)
        batterySub = tv("", 12f, text2)
        fun stat(b: View, title: String, value: TextView, sub: TextView?) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(b)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL; addView(tv(title, 12f, text2)); addView(value); if (sub != null) addView(sub)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        }
        big.addView(stat(batteryIcon, getString(R.string.battery), battery, batterySub), LinearLayout.LayoutParams(0, -2, 1f))
        big.addView(stat(badge(R.drawable.ic_directions_walk, 0xFF30D158.toInt(), 44), getString(R.string.steps), steps, null), LinearLayout.LayoutParams(0, -2, 1f))
        w.addView(big)
        stepsBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(0xFF30D158.toInt()); progressBackgroundTintList = ColorStateList.valueOf(0xFF2A2A36.toInt())
        }
        w.addView(stepsBar, LinearLayout.LayoutParams(-1, dp(8)).apply { topMargin = dp(12) })
        details = tv("", 12f, text2)
        w.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        findBtn = button(getString(R.string.find_watch), R.drawable.ic_phonelink_ring, true) {
            if (PhoneLinkService.watchRinging()) {
                PhoneLinkService.watchRingingUntil = 0; PhoneLinkService.send("find.watch.stop")
            } else if (PhoneLinkService.send("find.watch")) PhoneLinkService.watchRingingUntil = System.currentTimeMillis() + 30_000
            else toastNotConnected()
            render()
            root.postDelayed({ render() }, 30_500)
        }
        w.addView(row(button(getString(R.string.refresh), R.drawable.ic_sync) { if (!PhoneLinkService.send("stats.request")) toastNotConnected() }, findBtn),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // remote controls
        controlsBox = section(getString(R.string.sec_controls), R.drawable.ic_settings)
        controlsBox.addView(grid(
            controlTile("wifi", getString(R.string.c_wifi), R.drawable.ic_wifi, R.drawable.ic_wifi_off),
            controlTile("dnd", getString(R.string.c_dnd), R.drawable.ic_do_not_disturb_on, R.drawable.ic_do_not_disturb_off, "canDnd"),
            controlTile("aod", getString(R.string.c_aod), R.drawable.ic_nightlight),
            controlTile("chime", getString(R.string.c_chime), R.drawable.ic_alarm),
            controlTile("notifBanner", getString(R.string.c_banner), R.drawable.ic_notifications),
            controlTile("notifVibrate", getString(R.string.c_vibrate), R.drawable.ic_vibration),
            controlTile("chargeAnim", getString(R.string.c_charge_anim), R.drawable.ic_bolt),
            controlTile("chargeSound", getString(R.string.c_charge_sound), R.drawable.ic_music_note)))
        controlsBox.addView(tv(getString(R.string.c_ringer), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16); bottomMargin = dp(6) })
        controlsBox.addView(ringerRow())
        controlsBox.addView(slider("brightness", getString(R.string.c_brightness), R.drawable.ic_light_mode, 1, 100, "canBrightness"), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        controlsBox.addView(slider("aodBrightness", getString(R.string.c_aod_brightness), R.drawable.ic_bedtime, 1, 60))
        controlsBox.addView(slider("volMedia", getString(R.string.c_vol_media), R.drawable.ic_music_note))
        controlsBox.addView(slider("volRing", getString(R.string.c_vol_ring), R.drawable.ic_notifications_active))
        controlsBox.addView(slider("volAlarm", getString(R.string.c_vol_alarm), R.drawable.ic_alarm))
        chargeSoundBtn = button(getString(R.string.charge_sound_pick), R.drawable.ic_music_note) { pickChargeSound() }
        controlsBox.addView(chargeSoundBtn, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        controlsBox.addView(slider("chargeAnimSec", getString(R.string.c_charge_anim_time), R.drawable.ic_bolt, 3, 30, unit = " " + getString(R.string.sec_short)))
        themeBtn = button(getString(R.string.c_theme), R.drawable.ic_palette) { chooseTheme() }
        goalBtn = button(getString(R.string.c_goal), R.drawable.ic_flag) { editGoal() }
        controlsBox.addView(row(themeBtn, goalBtn), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        controlsBox.addView(button(getString(R.string.design_new), R.drawable.ic_brush, true) {
            startActivity(Intent(this, ThemeDesignerActivity::class.java))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        controlsBox.addView(row(
            button(getString(R.string.c_wake), R.drawable.ic_light_mode) { sendCtl("wake", null) },
            button(getString(R.string.c_lock), R.drawable.ic_lock) { if (ctl()?.optBoolean("canLock") == false) toast(getString(R.string.c_no_lock)) else sendCtl("lock", null) }),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // files
        val fs = section(getString(R.string.sec_files), R.drawable.ic_folder)
        fs.addView(button(getString(R.string.send_file), R.drawable.ic_upload_file, true) {
            val i = Intent(Intent.ACTION_GET_CONTENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
            try { startActivityForResult(Intent.createChooser(i, getString(R.string.send_file)), 21) } catch (_: Exception) { }
        }, LinearLayout.LayoutParams(-1, dp(48)))
        filesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fs.addView(filesBox, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        fs.addView(tv(getString(R.string.files_hint), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })

        // location
        val l = section(getString(R.string.sec_location), R.drawable.ic_location_on)
        val mv = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            controller.setZoom(15.0); controller.setCenter(GeoPoint(30.0444, 31.2357))
            clipToOutline = true; background = bg(0xFF000000.toInt(), 14)
        }
        map = mv
        l.addView(mv, LinearLayout.LayoutParams(-1, dp(240)))
        locText = tv("", 12f, text2)
        l.addView(locText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        l.addView(row(button(getString(R.string.locate_watch), R.drawable.ic_my_location, true) {
            if (!PhoneLinkService.send("watch.locate")) toastNotConnected() else toast(getString(R.string.locating))
        }, button(getString(R.string.open_maps), R.drawable.ic_map) { openInMaps() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // pairing
        val pr = section(getString(R.string.sec_pairing), R.drawable.ic_link)
        val name = tv("", 15f, bold = true)
        val nameRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        nameRow.addView(badge(R.drawable.ic_bluetooth, accent, 36))
        nameRow.addView(name, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(12) })
        pr.addView(nameRow)
        name.text = prefs.getString("watch_name", null) ?: getString(R.string.no_watch)
        pr.addView(tv(getString(R.string.pair_help), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val code = EditText(this).apply {
            setText(prefs.getString("code", "")); hint = getString(R.string.code_hint); inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(text2); textSize = 20f; letterSpacing = 0.3f; gravity = Gravity.CENTER
            background = bg(0xFF20202A.toInt(), 14); setPadding(dp(12), dp(12), dp(12), dp(12))
            setCompoundDrawablesRelative(icon(R.drawable.ic_vpn_key, text2, 20), null, null, null)
        }
        pr.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        pr.addView(row(button(getString(R.string.choose_watch), R.drawable.ic_watch) { chooseWatch { name.text = it } },
            button(getString(R.string.connect), R.drawable.ic_link, true) {
                prefs.edit().putString("code", code.text.toString().trim()).apply()
                if (prefs.getString("watch_addr", "").isNullOrEmpty()) chooseWatch { name.text = it; reconnect() } else reconnect()
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // features
        lastSection = section(getString(R.string.sec_features), R.drawable.ic_notifications)
        toggle(getString(R.string.f_notif), "mod_notif", R.drawable.ic_notifications, sum = getString(R.string.f_notif_sum))
        toggle(getString(R.string.f_battery), "mod_battery", R.drawable.ic_battery_full)
        toggle(getString(R.string.f_find), "mod_find", R.drawable.ic_phonelink_ring, sum = getString(R.string.f_find_sum))
        toggle(getString(R.string.f_calls), "mod_calls", R.drawable.ic_call, sum = getString(R.string.f_calls_sum))
        lastSection?.addView(button(getString(R.string.perm_calls), R.drawable.ic_call) {
            requestPermissions(CallBridge.PERMS, 5)
        }, LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4); bottomMargin = dp(6) })
        toggle(getString(R.string.f_weather), "mod_weather", R.drawable.ic_light_mode, sum = getString(R.string.f_weather_sum))
        toggle(getString(R.string.f_location), "mod_location", R.drawable.ic_my_location, def = false, sum = getString(R.string.f_location_sum))
        toggle(getString(R.string.f_media), "mod_media", R.drawable.ic_music_note, sum = getString(R.string.f_media_sum))
        toggle(getString(R.string.f_net), "mod_net", R.drawable.ic_wifi)
        toggle(getString(R.string.f_dnd), "mod_dnd_sync", R.drawable.ic_do_not_disturb_on, def = false, sum = getString(R.string.f_dnd_sum))
        toggle(getString(R.string.f_alerts), "mod_watch_alerts", R.drawable.ic_battery_full, sum = getString(R.string.f_alerts_sum))
        toggle(getString(R.string.f_lost), "mod_lost_alert", R.drawable.ic_link, def = false, sum = getString(R.string.f_lost_sum))
        lastSection?.addView(button(getString(R.string.muted_apps), R.drawable.ic_phone_android) { mutedAppsDialog() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(6) })

        // permissions
        val pm = section(getString(R.string.sec_permissions), R.drawable.ic_shield)
        pm.addView(button(getString(R.string.perm_notif), R.drawable.ic_notifications) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, LinearLayout.LayoutParams(-1, dp(48)))
        pm.addView(button(getString(R.string.perm_location), R.drawable.ic_my_location) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 3)
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        pm.addView(button(getString(R.string.perm_battery), R.drawable.ic_battery_full) { askIgnoreBattery() }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        pm.addView(button(getString(R.string.perm_app_settings), R.drawable.ic_settings) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(-1, dp(48)).apply { topMargin = dp(8) })
        pm.addView(tv(getString(R.string.huawei_tip), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(tv(getString(R.string.developed_by), 12f, text2).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
    }

    private fun renderControls(connected: Boolean) {
        val c = ctl()
        controlsBox.alpha = if (connected && c != null) 1f else 0.5f
        for (t in tiles) {
            val on = c?.optBoolean(t.key) == true
            val allowed = t.permKey == null || c?.optBoolean(t.permKey, true) != false
            val col = if (on) accent else text2
            t.badgeBg.setColor(if (on) accent else 0xFF2E2E3A.toInt())
            t.iconV.setImageDrawable(icon(if (on) t.onRes else t.offRes, if (on) 0xFFFFFFFF.toInt() else text2, 20))
            t.state.text = getString(if (!allowed) R.string.c_needs_perm else if (on) R.string.c_on else R.string.c_off)
            t.state.setTextColor(if (!allowed) 0xFFFF9F0A.toInt() else col)
        }
        for (s in slides) {
            if (s.dragging || c == null || !c.has(s.key)) continue
            val v = c.optInt(s.key)
            s.bar.progress = (v - s.min).coerceIn(0, s.bar.max)
            s.value.text = "$v${s.unit}"
            s.bar.isEnabled = s.permKey == null || c.optBoolean(s.permKey, true)
        }
        val ringer = c?.optString("ringer")
        ringerBtns.forEach { (k, b) ->
            val sel = k == ringer
            b.background = if (sel) bg(accent, 11) else null
            b.setTextColor(if (sel) 0xFFFFFFFF.toInt() else text2)
            b.compoundDrawablesRelative[0]?.setTint(if (sel) 0xFFFFFFFF.toInt() else text2)
        }
        val themes = themesList()
        val cur = themes.firstOrNull { it.first == c?.optString("theme") }?.second
        themeBtn.text = cur ?: getString(R.string.c_theme)
        chargeSoundBtn.text = getString(R.string.charge_sound_pick) + ": " + chargeSoundName(c?.optString("chargeSoundId")?.ifEmpty { "crystal" } ?: "crystal")
        goalBtn.text = c?.optInt("goal", 0)?.takeIf { it > 0 }?.let { "%,d".format(it) } ?: getString(R.string.c_goal)
    }

    private lateinit var chargeSoundBtn: Button
    private val chargeSoundIds = listOf("crystal", "glass", "pulse", "drop", "energy", "magnet", "harp")
    private fun chargeSoundName(id: String) = getString(when (id) {
        "glass" -> R.string.cs_glass; "pulse" -> R.string.cs_pulse; "drop" -> R.string.cs_drop; "energy" -> R.string.cs_energy
        "magnet" -> R.string.cs_magnet; "harp" -> R.string.cs_harp; else -> R.string.cs_crystal
    })

    /** Picking a tone plays it on the watch right away. */
    private fun pickChargeSound() {
        val cur = chargeSoundIds.indexOf(ctl()?.optString("chargeSoundId") ?: "crystal")
        AlertDialog.Builder(this).setTitle(R.string.charge_sound_pick)
            .setSingleChoiceItems(chargeSoundIds.map { chargeSoundName(it) }.toTypedArray(), cur) { _, i -> sendCtl("chargeSoundId", chargeSoundIds[i]) }
            .setPositiveButton(android.R.string.ok, null).show()
    }

    private fun themesList(): List<Pair<String, String>> {
        val a = try { org.json.JSONArray(prefs.getString("themes", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        return (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("id") to o.optString("name") } }
    }

    private fun chooseTheme() = startActivity(Intent(this, ThemePickerActivity::class.java))

    private fun editGoal() {
        val e = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER; hint = getString(R.string.c_goal_hint)
            setText(ctl()?.optInt("goal", 6000)?.toString() ?: "6000"); gravity = Gravity.CENTER; textSize = 20f
        }
        val box = FrameLayout(this).apply { setPadding(dp(24), dp(8), dp(24), 0); addView(e) }
        AlertDialog.Builder(this).setTitle(R.string.c_goal).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> e.text.toString().toIntOrNull()?.let { sendCtl("goal", it.coerceIn(500, 100_000)) } }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    private fun render() {
        val st = PhoneLinkService.status
        val (col, label) = when {
            PhoneLinkService.connected && st == "auth" -> 0xFFFF9F0A.toInt() to getString(R.string.wrong_code)
            PhoneLinkService.connected -> 0xFF30D158.toInt() to getString(R.string.connected)
            st == "connecting" -> 0xFFFFD60A.toInt() to getString(R.string.connecting)
            else -> 0xFF8E8E93.toInt() to getString(R.string.not_connected)
        }
        (statusDot.background as GradientDrawable).setColor(col)
        findBtn.text = getString(if (PhoneLinkService.watchRinging()) R.string.stop_ringing else R.string.find_watch)
        statusText.text = label

        prefs.getString("stats", null)?.let {
            val s = JSONObject(it)
            val lvl = s.optInt("battery"); val chg = s.optBoolean("charging")
            battery.text = "$lvl%"
            batterySub.text = getString(if (chg) R.string.charging else R.string.not_charging)
            val bcol = when { chg -> 0xFF30D158.toInt(); lvl <= 15 -> 0xFFFF453A.toInt(); lvl <= 30 -> 0xFFFF9F0A.toInt(); else -> 0xFF30D158.toInt() }
            (batteryIcon.background as GradientDrawable).setColor((bcol and 0x00FFFFFF) or 0x2A000000)
            (batteryIcon.getChildAt(0) as ImageView).setImageDrawable(icon(if (chg) R.drawable.ic_bolt else R.drawable.ic_battery_full, bcol, 22))
            val stp = s.optInt("steps"); val goal = s.optInt("goal", 6000).coerceAtLeast(1)
            steps.text = "%,d".format(stp)
            stepsBar.progress = (stp * 1000 / goal).coerceAtMost(1000)
            val t = prefs.getLong("stats_time", 0)
            details.text = getString(R.string.stats_details, "%,d".format(goal), s.optString("theme"), s.optString("model"),
                s.optString("android"), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)))
        }
        renderControls(PhoneLinkService.connected)
        renderFiles()
        prefs.getString("loc", null)?.let {
            val o = JSONObject(it)
            val p = GeoPoint(o.getDouble("lat"), o.getDouble("lng"))
            map?.let { m ->
                if (marker == null) marker = Marker(m).also { mk -> mk.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM); m.overlays.add(mk) }
                marker?.position = p; marker?.title = getString(R.string.app_name)
                m.controller.animateTo(p); m.invalidate()
            }
            locText.text = getString(R.string.loc_details, "%.5f, %.5f".format(p.latitude, p.longitude),
                o.optDouble("acc").toInt(), DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(o.optLong("time"))))
        } ?: run {
            locText.text = when (prefs.getString("loc_error", "")) {
                "permission" -> getString(R.string.loc_no_permission)
                "gps_off" -> getString(R.string.loc_gps_off)
                else -> getString(R.string.loc_none)
            }
        }
    }

    // ------------------------------------------------------------------ actions
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private fun toastNotConnected() = toast(getString(R.string.not_connected))

    private fun reconnect() {
        PhoneLinkService.start(this)
        startService(Intent(this, PhoneLinkService::class.java).setAction("reconnect"))
    }

    @SuppressLint("MissingPermission")
    private fun chooseWatch(done: (String) -> Unit) {
        if (!askBluetoothPermission()) return
        val adapter = BluetoothAdapter.getDefaultAdapter() ?: run { toast(getString(R.string.no_bt)); return }
        if (!adapter.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        val devices = adapter.bondedDevices.toList()
        if (devices.isEmpty()) {
            AlertDialog.Builder(this).setMessage(R.string.pair_first)
                .setPositiveButton(R.string.open_bt) { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.show()
            return
        }
        val names = devices.map { (it.name ?: it.address) + "\n" + it.address }.toTypedArray()
        AlertDialog.Builder(this).setTitle(R.string.choose_watch).setItems(names) { _, i ->
            val d = devices[i]
            prefs.edit().putString("watch_addr", d.address).putString("watch_name", d.name ?: d.address).apply()
            done(d.name ?: d.address)
            reconnect()
        }.setNeutralButton(R.string.open_bt) { _, _ -> startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }.show()
    }

    private fun askBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 31 && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1); return false
        }
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 2)
        return true
    }

    @SuppressLint("BatteryLife")
    private fun askIgnoreBattery() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= 23 && !pm.isIgnoringBatteryOptimizations(packageName)) {
            try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))); return } catch (_: Exception) { }
        }
        try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast(getString(R.string.done)) }
    }

    private fun openInMaps() {
        val o = prefs.getString("loc", null)?.let { JSONObject(it) } ?: run { toast(getString(R.string.loc_none)); return }
        val uri = Uri.parse("geo:${o.getDouble("lat")},${o.getDouble("lng")}?q=${o.getDouble("lat")},${o.getDouble("lng")}(WatchLite)")
        try { startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.openstreetmap.org/?mlat=${o.getDouble("lat")}&mlon=${o.getDouble("lng")}#map=17/${o.getDouble("lat")}/${o.getDouble("lng")}")))
        }
    }

    private fun mutedAppsDialog() {
        val apps = PhoneNotifListener.seenApps().sorted()
        if (apps.isEmpty()) { toast(getString(R.string.no_apps_yet)); return }
        val muted = PhoneNotifListener.muted().toMutableSet()
        val labels = apps.map { try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(it, 0)).toString() } catch (_: Exception) { it } }.toTypedArray()
        val checked = BooleanArray(apps.size) { apps[it] !in muted }
        AlertDialog.Builder(this).setTitle(R.string.muted_apps).setMultiChoiceItems(labels, checked) { _, i, on ->
            if (on) muted.remove(apps[i]) else muted.add(apps[i])
        }.setPositiveButton(android.R.string.ok) { _, _ -> prefs.edit().putStringSet("muted_apps", muted).apply() }.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) PhoneLinkService.start(this)
    }
}
