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
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
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
    private val listener = { render() }

    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var battery: TextView
    private lateinit var steps: TextView
    private lateinit var stepsBar: ProgressBar
    private lateinit var details: TextView
    private lateinit var locText: TextView
    private lateinit var findBtn: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

    // ------------------------------------------------------------------ UI
    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun tv(s: CharSequence, size: Float = 15f, color: Int = 0xFFFFFFFF.toInt(), bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun bg(color: Int, r: Int = 18) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }

    private fun section(title: String): LinearLayout {
        root.addView(tv(title, 13f, accent, true), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); bottomMargin = dp(8); marginStart = dp(4) })
        val c = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = bg(card); setPadding(dp(16), dp(14), dp(16), dp(14)) }
        root.addView(c)
        return c
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = label; isAllCaps = false; setTextColor(if (primary) 0xFFFFFFFF.toInt() else accent)
        background = bg(if (primary) accent else 0xFF262633.toInt(), 14); stateListAnimator = null
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEachIndexed { i, v -> addView(v, LinearLayout.LayoutParams(0, dp(46), 1f).apply { if (i > 0) marginStart = dp(8) }) }
    }

    private fun toggle(label: String, key: String, def: Boolean = true, sum: String? = null) {
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(tv(label, 15f))
        if (sum != null) col.addView(tv(sum, 12f, text2))
        val sw = Switch(this).apply {
            isChecked = prefs.getBoolean(key, def)
            thumbTintList = ColorStateList.valueOf(accent)
            setOnCheckedChangeListener { _, v -> prefs.edit().putBoolean(key, v).apply() }
        }
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(6), 0, dp(6)) }
        r.addView(col, LinearLayout.LayoutParams(0, -2, 1f)); r.addView(sw)
        lastSection?.addView(r)
    }

    private var lastSection: LinearLayout? = null

    private fun build() {
        // header
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(tv("WatchLite", 26f, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        statusDot = View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(0xFF555555.toInt()) } }
        head.addView(statusDot, LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(8) })
        statusText = tv("", 13f, text2)
        head.addView(statusText)
        root.addView(head)

        // watch status
        val w = section(getString(R.string.sec_watch))
        val big = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        battery = tv("--", 34f, bold = true); steps = tv("--", 34f, bold = true)
        val bcol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv(getString(R.string.battery), 12f, text2)); addView(battery) }
        val scol = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; addView(tv(getString(R.string.steps), 12f, text2)); addView(steps) }
        big.addView(bcol, LinearLayout.LayoutParams(0, -2, 1f)); big.addView(scol, LinearLayout.LayoutParams(0, -2, 1f))
        w.addView(big)
        stepsBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(0xFF30D158.toInt())
        }
        w.addView(stepsBar, LinearLayout.LayoutParams(-1, dp(10)).apply { topMargin = dp(8) })
        details = tv("", 12f, text2)
        w.addView(details, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        findBtn = button(getString(R.string.find_watch), true) {
            if (PhoneLinkService.watchRinging()) {
                PhoneLinkService.watchRingingUntil = 0; PhoneLinkService.send("find.watch.stop")
            } else if (PhoneLinkService.send("find.watch")) PhoneLinkService.watchRingingUntil = System.currentTimeMillis() + 30_000
            else toastNotConnected()
            render()
            root.postDelayed({ render() }, 30_500)
        }
        w.addView(row(button(getString(R.string.refresh)) { if (!PhoneLinkService.send("stats.request")) toastNotConnected() }, findBtn),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })

        // location
        val l = section(getString(R.string.sec_location))
        val mv = MapView(this).apply {
            setTileSource(TileSourceFactory.MAPNIK); setMultiTouchControls(true)
            controller.setZoom(15.0); controller.setCenter(GeoPoint(30.0444, 31.2357))
            clipToOutline = true; background = bg(0xFF000000.toInt(), 14)
        }
        map = mv
        l.addView(mv, LinearLayout.LayoutParams(-1, dp(240)))
        locText = tv("", 12f, text2)
        l.addView(locText, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        l.addView(row(button(getString(R.string.locate_watch), true) {
            if (!PhoneLinkService.send("watch.locate")) toastNotConnected() else toast(getString(R.string.locating))
        }, button(getString(R.string.open_maps)) { openInMaps() }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // pairing
        val pr = section(getString(R.string.sec_pairing))
        val name = tv("", 15f)
        pr.addView(name)
        name.text = prefs.getString("watch_name", null) ?: getString(R.string.no_watch)
        pr.addView(tv(getString(R.string.pair_help), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        val code = EditText(this).apply {
            setText(prefs.getString("code", "")); hint = getString(R.string.code_hint); inputType = InputType.TYPE_CLASS_NUMBER
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(text2); textSize = 20f; letterSpacing = 0.3f; gravity = Gravity.CENTER
        }
        pr.addView(code, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        pr.addView(row(button(getString(R.string.choose_watch)) { chooseWatch { name.text = it } },
            button(getString(R.string.connect), true) {
                prefs.edit().putString("code", code.text.toString().trim()).apply()
                if (prefs.getString("watch_addr", "").isNullOrEmpty()) chooseWatch { name.text = it; reconnect() } else reconnect()
            }), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        // features
        lastSection = section(getString(R.string.sec_features))
        toggle(getString(R.string.f_notif), "mod_notif", sum = getString(R.string.f_notif_sum))
        toggle(getString(R.string.f_battery), "mod_battery")
        toggle(getString(R.string.f_find), "mod_find", sum = getString(R.string.f_find_sum))
        lastSection?.addView(button(getString(R.string.muted_apps)) { mutedAppsDialog() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(6) })

        // permissions
        val pm = section(getString(R.string.sec_permissions))
        pm.addView(button(getString(R.string.perm_notif)) {
            startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }, LinearLayout.LayoutParams(-1, dp(46)))
        pm.addView(button(getString(R.string.perm_battery)) { askIgnoreBattery() }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        pm.addView(button(getString(R.string.perm_app_settings)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }, LinearLayout.LayoutParams(-1, dp(46)).apply { topMargin = dp(8) })
        pm.addView(tv(getString(R.string.huawei_tip), 12f, text2), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })

        root.addView(tv(getString(R.string.developed_by), 12f, text2).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
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
            battery.text = "${s.optInt("battery")}%" + if (s.optBoolean("charging")) " ⚡" else ""
            val stp = s.optInt("steps"); val goal = s.optInt("goal", 6000).coerceAtLeast(1)
            steps.text = "%,d".format(stp)
            stepsBar.progress = (stp * 1000 / goal).coerceAtMost(1000)
            val t = prefs.getLong("stats_time", 0)
            details.text = getString(R.string.stats_details, "%,d".format(goal), s.optString("theme"), s.optString("model"),
                s.optString("android"), DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(t)))
        }
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
