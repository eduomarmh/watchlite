package com.watchlite.launcher.control

import android.content.Intent
import android.content.res.ColorStateList
import android.provider.Settings
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.home.Page
import com.watchlite.launcher.link.LinkManager
import com.watchlite.launcher.settings.SettingsActivity
import com.watchlite.launcher.system.SystemActions
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Ui

class ControlPage(host: HomeActivity) : Page(host, FROM_LEFT) {

    private class Tile(val icon: Int, val label: Int, val state: () -> Boolean?, val tap: () -> Unit, val long: (() -> Unit)? = null) {
        lateinit var circle: ImageView
        lateinit var fill: android.graphics.drawable.GradientDrawable
        /** Last state painted; the tile is only repainted when it really changes (no flicker). */
        var shown: Boolean? = null
        var painted = false
        /** After a tap, the expected state is shown until the system catches up (radios take seconds). */
        var pending: Boolean? = null
        var pendingUntil = 0L
    }

    private val scroll = ScrollView(host).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val header: TextView
    private val tiles = ArrayList<Tile>()
    private var toggled = false
    private val stateListener = { refresh() }
    private lateinit var brightness: SliderBar
    private lateinit var volume: SliderBar

    init {
        val box = ui.vbox()
        box.setPadding(ui.sp.safe, ui.px(if (ui.sp.isRound) 12f else 5f), ui.sp.safe, ui.px(14f))
        header = ui.text("", 5.6f, Ui.TEXT2, 2).apply { gravity = Gravity.CENTER }
        box.addView(header, ui.lp(bottom = 3f))
        val ctx = host
        // brightness & volume sliders (professional, one-view each)
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        brightness = SliderBar(ctx, R.drawable.ic_brightness_6, 20, { v ->
            SystemActions.setBrightness(ctx, (v * 255 / 20).coerceAtLeast(3))
        }).apply {
            onLockedTap = { com.watchlite.launcher.system.Permissions.help(host, com.watchlite.launcher.system.Permissions.Kind.WRITE_SETTINGS) }
        }
        volume = SliderBar(ctx, R.drawable.ic_volume_up, maxVol, { v ->
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, v, 0)
        })
        val bh = ui.px(12f)
        box.addView(brightness, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bh).apply { bottomMargin = ui.px(2f) })
        box.addView(volume, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, bh).apply { bottomMargin = ui.px(3.5f) })
        tiles += Tile(R.drawable.ic_wifi, R.string.tile_wifi, { SystemActions.wifiOn(ctx) },
            { toggled = SystemActions.setWifi(ctx, !SystemActions.wifiOn(ctx)); if (!toggled) open(Settings.ACTION_WIFI_SETTINGS) }, { open(Settings.ACTION_WIFI_SETTINGS) })
        tiles += Tile(R.drawable.ic_bluetooth, R.string.tile_bluetooth, { SystemActions.btOn() },
            { toggled = SystemActions.setBt(!SystemActions.btOn()); if (!toggled) open(Settings.ACTION_BLUETOOTH_SETTINGS) }, { open(Settings.ACTION_BLUETOOTH_SETTINGS) })
        tiles += Tile(R.drawable.ic_signal_cellular_alt, R.string.tile_data, { SystemActions.mobileDataOn(ctx) },
            { open(Settings.ACTION_DATA_ROAMING_SETTINGS) })
        tiles += Tile(R.drawable.ic_airplanemode_active, R.string.tile_airplane, { SystemActions.airplaneOn(ctx) },
            { open(Settings.ACTION_AIRPLANE_MODE_SETTINGS) })
        tiles += Tile(R.drawable.ic_do_not_disturb_on, R.string.tile_dnd, { SystemActions.dndOn(ctx) }, {
            if (!SystemActions.setDnd(ctx, !SystemActions.dndOn(ctx))) {
                com.watchlite.launcher.system.Permissions.help(host, com.watchlite.launcher.system.Permissions.Kind.DND)
            }
        })
        tiles += Tile(R.drawable.ic_theaters, R.string.tile_theater, { App.prefs.theater }, {
            App.prefs.theater = !App.prefs.theater
            if (App.prefs.theater) { host.toast(ctx.getString(R.string.theater_on)); host.goHome(); host.screenOff() }
        })
        tiles += Tile(R.drawable.ic_flashlight_on, R.string.tile_flashlight, { SystemActions.torchState() }, { host.toggleFlashlight() })
        tiles += Tile(R.drawable.ic_volume_up, R.string.tile_sound_settings, { null }, { open(Settings.ACTION_SOUND_SETTINGS) })
        tiles += Tile(R.drawable.ic_watch, R.string.tile_aod, { App.prefs.aodEnabled }, {
            App.prefs.aodEnabled = !App.prefs.aodEnabled; host.applyAodSetting()
        })
        tiles += Tile(R.drawable.ic_lock, R.string.tile_touch_lock, { false }, { close { host.touchLock(false) } })
        tiles += Tile(R.drawable.ic_water_drop, R.string.tile_water_lock, { false }, { close { host.touchLock(true) } })
        tiles += Tile(R.drawable.ic_location_on, R.string.tile_location, { SystemActions.locationOn(ctx) }, {
            toggled = com.watchlite.launcher.system.Permissions.setLocation(ctx, !SystemActions.locationOn(ctx))
            if (!toggled) open(Settings.ACTION_LOCATION_SOURCE_SETTINGS)
        }, { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) })
        tiles += Tile(R.drawable.ic_battery_saver, R.string.tile_saver, { App.prefs.saverManual }, {
            App.prefs.saverManual = !App.prefs.saverManual; host.applyPowerState()
        })
        tiles += Tile(R.drawable.ic_phonelink_ring, R.string.tile_find_phone, { LinkManager.phoneRinging() }, {
            val wasRinging = LinkManager.phoneRinging()
            if (!LinkManager.toggleFindPhone()) host.toast(ctx.getString(R.string.phone_not_connected))
            else host.toast(ctx.getString(if (wasRinging) R.string.find_stopped else R.string.find_ringing))
        })
        tiles += Tile(R.drawable.ic_photo_camera, R.string.tile_camera_remote, { null }, {
            if (!LinkManager.send("camera.remote")) host.toast(ctx.getString(R.string.phone_not_connected))
        })
        tiles += Tile(R.drawable.ic_history, R.string.tile_recents, { null }, {
            if (!SystemActions.recents(ctx)) com.watchlite.launcher.system.RecentApps.show(host, ui)
        })
        tiles += Tile(R.drawable.ic_power_settings_new, R.string.tile_power, { null }, {
            if (!SystemActions.powerDialog(ctx)) host.powerSheet()
        })
        tiles += Tile(R.drawable.ic_settings, R.string.tile_settings, { null }, {
            ctx.startActivity(Intent(ctx, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        })

        val cols = 3
        val grid = GridLayout(host).apply { columnCount = cols }
        val cell = (ui.sp.width - ui.sp.safe * 2) / cols
        for (t in tiles) grid.addView(tileView(t, cell))
        box.addView(grid, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.addView(box)
        addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun tileView(t: Tile, cell: Int): LinearLayout {
        val v = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val d = (cell * 0.66f).toInt()
        t.circle = ImageView(context).apply {
            setImageResource(t.icon)
            val pad = (d * 0.26f).toInt(); setPadding(pad, pad, pad, pad)
            isClickable = true
            contentDescription = context.getString(t.label)
            t.fill = ui.oval(Ui.CARD2)
            background = ui.ripple(t.fill, ui.oval(0xFFFFFFFF.toInt()))
            imageTintList = ColorStateList.valueOf(Ui.TEXT)
            setOnClickListener {
                Haptics.tick(context)
                val before = t.state()
                toggled = false
                t.tap()
                if (before != null) {
                    val after = t.state()
                    // the change happened right away (or not at all, e.g. a settings page opened): trust the system
                    if (toggled && after == before && t.label in SLOW) { t.pending = !before; t.pendingUntil = System.currentTimeMillis() + 6000 }
                }
                refresh()
                postDelayed({ refresh() }, 600); postDelayed({ refresh() }, 2000); postDelayed({ refresh() }, 6500)
            }
            t.long?.let { l -> setOnLongClickListener { Haptics.tick(context, true); l(); true } }
        }
        v.addView(t.circle, LinearLayout.LayoutParams(d, d))
        v.addView(ui.text(context.getString(t.label), 3.9f, Ui.TEXT2, 1).apply { gravity = Gravity.CENTER },
            LinearLayout.LayoutParams(cell, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = ui.px(1f); bottomMargin = ui.px(2.5f) })
        return v
    }

    private fun refresh() {
        val st = App.I.state
        header.text = Digits.apply("${st.battery}%", Digits.useArabic()) +
            (if (st.charging) " · " + context.getString(R.string.charging) else "") +
            (if (st.phoneBattery >= 0) " · " + context.getString(R.string.phone) + " " + Digits.apply("${st.phoneBattery}%", Digits.useArabic()) else "")
        val ctx = context
        brightness.locked = !SystemActions.canWriteSettings(ctx)
        brightness.value = SystemActions.systemBrightness(ctx) * 20 / 255
        val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
        volume.value = am.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        brightness.invalidate(); volume.invalidate()
        val now = System.currentTimeMillis()
        for (t in tiles) {
            var on = t.state()
            t.pending?.let { p -> if (on == p || now > t.pendingUntil) t.pending = null else on = p }
            if (t.painted && on == t.shown) continue
            t.painted = true; t.shown = on
            t.fill.setColor(if (on == true) ui.accent else Ui.CARD2)
            t.circle.imageTintList = ColorStateList.valueOf(if (on == true) Ui.BG else Ui.TEXT)
        }
    }

    companion object {
        /** Toggles whose system state changes asynchronously. */
        private val SLOW = setOf(R.string.tile_wifi, R.string.tile_bluetooth, R.string.tile_location)
    }

    private fun open(action: String, withPackage: Boolean = false) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) i.data = android.net.Uri.parse("package:" + context.packageName)
        try { context.startActivity(i) } catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    override fun onOpening() = refresh()
    /** Radios report the end of a change with a broadcast: repaint exactly then. */
    private val sysReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(c: android.content.Context, i: Intent) = refresh()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow(); App.I.state.listen(stateListener)
        try {
            context.registerReceiver(sysReceiver, android.content.IntentFilter().apply {
                addAction(android.net.wifi.WifiManager.WIFI_STATE_CHANGED_ACTION)
                addAction(android.bluetooth.BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED)
                addAction(android.location.LocationManager.PROVIDERS_CHANGED_ACTION)
                addAction(android.app.NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
            })
        } catch (_: Throwable) { }
    }
    override fun onDetachedFromWindow() {
        App.I.state.unlisten(stateListener)
        try { context.unregisterReceiver(sysReceiver) } catch (_: Throwable) { }
        super.onDetachedFromWindow()
    }
    override fun contentCanScroll(direction: Int) = scroll.canScrollVertically(direction)
    override fun onWheel(detents: Int): Boolean { scroll.smoothScrollBy(0, (detents * ui.u * 14).toInt()); return true }
}
