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
    }

    private val scroll = ScrollView(host).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val header: TextView
    private val tiles = ArrayList<Tile>()
    private val stateListener = { refresh() }

    init {
        val box = ui.vbox()
        box.setPadding(ui.sp.safe, ui.px(if (ui.sp.isRound) 12f else 5f), ui.sp.safe, ui.px(14f))
        header = ui.text("", 5.6f, Ui.TEXT2, 2).apply { gravity = Gravity.CENTER }
        box.addView(header, ui.lp(bottom = 3f))
        val ctx = host
        tiles += Tile(R.drawable.ic_wifi, R.string.tile_wifi, { SystemActions.wifiOn(ctx) },
            { if (!SystemActions.setWifi(ctx, !SystemActions.wifiOn(ctx))) open(Settings.ACTION_WIFI_SETTINGS) }, { open(Settings.ACTION_WIFI_SETTINGS) })
        tiles += Tile(R.drawable.ic_bluetooth, R.string.tile_bluetooth, { SystemActions.btOn() },
            { if (!SystemActions.setBt(!SystemActions.btOn())) open(Settings.ACTION_BLUETOOTH_SETTINGS) }, { open(Settings.ACTION_BLUETOOTH_SETTINGS) })
        tiles += Tile(R.drawable.ic_signal_cellular_alt, R.string.tile_data, { SystemActions.mobileDataOn(ctx) },
            { open(Settings.ACTION_DATA_ROAMING_SETTINGS) })
        tiles += Tile(R.drawable.ic_airplanemode_active, R.string.tile_airplane, { SystemActions.airplaneOn(ctx) },
            { open(Settings.ACTION_AIRPLANE_MODE_SETTINGS) })
        tiles += Tile(R.drawable.ic_do_not_disturb_on, R.string.tile_dnd, { SystemActions.dndOn(ctx) }, {
            if (!SystemActions.setDnd(ctx, !SystemActions.dndOn(ctx))) {
                host.toast(ctx.getString(R.string.need_dnd_access)); open(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)
            }
        })
        tiles += Tile(R.drawable.ic_theaters, R.string.tile_theater, { App.prefs.theater }, {
            App.prefs.theater = !App.prefs.theater
            if (App.prefs.theater) { host.toast(ctx.getString(R.string.theater_on)); host.goHome(); host.screenOff() }
        })
        tiles += Tile(R.drawable.ic_flashlight_on, R.string.tile_flashlight, { SystemActions.torchState() }, { host.toggleFlashlight() })
        tiles += Tile(R.drawable.ic_volume_up, R.string.tile_volume, { null }, { SystemActions.showVolume(ctx) }, { open(Settings.ACTION_SOUND_SETTINGS) })
        tiles += Tile(R.drawable.ic_brightness_6, R.string.tile_brightness, { null }, {
            if (SystemActions.canWriteSettings(ctx)) host.toast(Digits.fmt(SystemActions.cycleBrightness(ctx)) + "%")
            else { host.toast(ctx.getString(R.string.need_write_settings)); open(Settings.ACTION_MANAGE_WRITE_SETTINGS, true) }
        }, { open(Settings.ACTION_DISPLAY_SETTINGS) })
        tiles += Tile(R.drawable.ic_watch, R.string.tile_aod, { App.prefs.aodEnabled }, {
            App.prefs.aodEnabled = !App.prefs.aodEnabled; host.applyAodSetting()
        })
        tiles += Tile(R.drawable.ic_lock, R.string.tile_touch_lock, { false }, { close { host.touchLock(false) } })
        tiles += Tile(R.drawable.ic_water_drop, R.string.tile_water_lock, { false }, { close { host.touchLock(true) } })
        tiles += Tile(R.drawable.ic_location_on, R.string.tile_location, { SystemActions.locationOn(ctx) }, { open(Settings.ACTION_LOCATION_SOURCE_SETTINGS) })
        tiles += Tile(R.drawable.ic_battery_saver, R.string.tile_saver, { App.prefs.saverManual }, {
            App.prefs.saverManual = !App.prefs.saverManual; host.applyPowerState()
        })
        tiles += Tile(R.drawable.ic_phonelink_ring, R.string.tile_find_phone, { null }, {
            if (!LinkManager.send("find.phone")) host.toast(ctx.getString(R.string.phone_not_connected))
        })
        tiles += Tile(R.drawable.ic_photo_camera, R.string.tile_camera_remote, { null }, {
            if (!LinkManager.send("camera.remote")) host.toast(ctx.getString(R.string.phone_not_connected))
        })
        tiles += Tile(R.drawable.ic_history, R.string.tile_recents, { null }, {
            if (!SystemActions.recents(ctx)) host.askAccessibility()
        })
        tiles += Tile(R.drawable.ic_power_settings_new, R.string.tile_power, { null }, {
            if (!SystemActions.powerDialog(ctx)) host.askAccessibility()
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
            setOnClickListener { Haptics.tick(context); t.tap(); postDelayed({ refresh() }, 250) }
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
        for (t in tiles) {
            val on = t.state()
            val bg = when (on) { true -> ui.accent; else -> Ui.CARD2 }
            t.circle.background = ui.ripple(ui.oval(bg), ui.oval(0xFFFFFFFF.toInt()))
            t.circle.imageTintList = ColorStateList.valueOf(if (on == true) Ui.BG else Ui.TEXT)
        }
    }

    private fun open(action: String, withPackage: Boolean = false) {
        val i = Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (withPackage) i.data = android.net.Uri.parse("package:" + context.packageName)
        try { context.startActivity(i) } catch (_: Exception) {
            try { context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { }
        }
    }

    override fun onOpening() = refresh()
    override fun onAttachedToWindow() { super.onAttachedToWindow(); App.I.state.listen(stateListener) }
    override fun onDetachedFromWindow() { App.I.state.unlisten(stateListener); super.onDetachedFromWindow() }
    override fun contentCanScroll(direction: Int) = scroll.canScrollVertically(direction)
    override fun onWheel(detents: Int): Boolean { scroll.smoothScrollBy(0, (detents * ui.u * 14).toInt()); return true }
}
