package com.watchlite.launcher

import android.content.Context
import android.content.SharedPreferences
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/**
 * Typed wrapper over a single SharedPreferences file. Every setting of the
 * launcher lives here so backup/restore can dump one file.
 */
class Prefs(ctx: Context) {

    val sp: SharedPreferences = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ---- Appearance
    var themeId by str("theme_id", "flow")
    var digits by str("digits", "latin")            // latin | arabic | auto
    var clock by str("clock", "system")             // system | 12 | 24
    var language by str("language", "system")       // system | en | ar
    var accent by int("accent", 0xFF7B6CFF.toInt())
    var accentOnFaces by bool("accent_faces", true)   // user color replaces theme accents
    var fps by int("fps", 24)
    var uiMode by str("ui_mode", "dark")             // dark | light | auto

    // ---- Auto themes
    var autoTheme by bool("auto_theme", false)
    var dayTheme by str("day_theme", "flow")
    var nightTheme by str("night_theme", "oled")
    var nightFrom by int("night_from", 20)
    var nightTo by int("night_to", 6)
    var chargingTheme by str("charging_theme", "")

    // ---- Drawer
    var drawerMode by str("drawer_mode", "honeycomb") // honeycomb | grid | list
    var drawerZoom by int("drawer_zoom", 2)            // 0..4
    var wheelInDrawer by str("wheel_drawer", "zoom")   // zoom | scroll
    var hiddenApps by set("hidden_apps")
    var favoriteApps by set("favorite_apps")
    var renamedApps by str("renamed_apps", "{}")       // JSON {component: label}
    var iconStyle by str("icon_style", "wl_gradient")       // circle | squircle | original | mono | glass | outline
    var iconPack by str("icon_pack", "")               // package of an installed icon pack

    // ---- Input
    var wheelInvert by bool("wheel_invert", false)
    var wheelUpKey by int("wheel_up_key", -1)
    var wheelDownKey by int("wheel_down_key", -1)
    var wheelOnFace by str("wheel_face", "none")      // none | themes
    var haptics by int("haptics", 1)                    // 0 off, 1 light, 2 strong
    var backDouble by str("back_double", "none")
    var backLong by str("back_long", "flashlight")

    // ---- AOD
    var aodEnabled by bool("aod_enabled", false)
    var aodBrightness by float("aod_brightness", 0.02f)
    var aodOpacity by float("aod_opacity", 0.75f)
    var aodIdleSec by int("aod_idle", -1)               // -1 auto (system timeout - 3 s), 0 = only by Home button
    var aodBatteryMin by int("aod_batt_min", 30)
    var aodWhileCharging by bool("aod_charging", true)
    var aodNightOff by bool("aod_night_off", false)
    var aodNightFrom by int("aod_night_from", 0)
    var aodNightTo by int("aod_night_to", 6)
    var aodWake by str("aod_wake", "double")           // double | single
    var aodRaise by bool("aod_raise", false)
    var aodPixelShift by bool("aod_shift", true)
    var aodColor by int("aod_color", 0)                 // 0 = theme colors
    var aodWidgets by set("aod_widgets", setOf("battery", "notif"))

    // ---- Chime
    var chimeEnabled by bool("chime_enabled", false)
    var chimeSound by str("chime_sound", "res:chime_soft_bell")
    var chimeMode by str("chime_mode", "once")         // once | strikes
    var chimeFrom by int("chime_from", 8)
    var chimeTo by int("chime_to", 23)
    var chimeDays by int("chime_days", 0x7F)            // bit0 = Monday
    var chimeVolume by float("chime_volume", 0.8f)
    var chimeVibrate by bool("chime_vibrate", true)
    var chimeRespectDnd by bool("chime_dnd", true)

    // ---- Notifications
    var cannedReplies by str("canned_replies", "")
    var notifWake by bool("notif_wake", false)
    var blockedNotifApps by set("blocked_notif_apps")

    // ---- Prayer / calendar
    var prayerEnabled by bool("prayer_enabled", true)
    var prayerMethod by str("prayer_method", "egypt")
    var asrMethod by str("asr_method", "shafii")
    var latitude by float("lat", 30.0444f)
    var longitude by float("lng", 31.2357f)
    var cityName by str("city", "Cairo")
    var locationMode by str("loc_mode", "manual")       // manual | gps
    var hijriAdjust by int("hijri_adjust", 0)
    var prayerAlert by bool("prayer_alert", false)
    var dstMode by str("dst_mode", "auto")             // auto | on | off  (prayer-time offset)
    var aodPrayer by bool("aod_prayer", false)         // next prayer line on the always-on screen
    var aodPrayerCountdown by bool("aod_prayer_cd", false)

    // ---- Power
    var saverManual by bool("saver_manual", false)
    var saverAuto by bool("saver_auto", true)
    var saverThreshold by int("saver_threshold", 15)
    var theater by bool("theater", false)

    // ---- Steps
    var stepsGoal by int("steps_goal", 6000)
    var stepsDay by str("steps_day", "")
    var stepsBase by int("steps_base", -1)
    var stepsLastRaw by int("steps_last_raw", 0)
    var stepsCarry by int("steps_carry", 0)

    // ---- Stopwatch / timer
    var swStart by long("sw_start", 0L)
    var swAccum by long("sw_accum", 0L)
    var timerEnd by long("timer_end", 0L)
    var timerLast by int("timer_last", 300)

    // ---- Link
    var linkEnabled by bool("link_enabled", false)
    var linkCode by str("link_code", "")
    var linkModules by set("link_modules", setOf("notif", "battery", "net", "weather", "media", "themes", "find", "camera"))

    // ---- Misc
    var onboarded by bool("onboarded", false)
    var crashTimes by str("crash_times", "")
    var rootFeatures by bool("root_features", false)

    fun themeCustom(id: String): String = sp.getString("custom_$id", "{}") ?: "{}"
    fun setThemeCustom(id: String, json: String) = sp.edit().putString("custom_$id", json).apply()

    // ------------------------------------------------------------------ delegates
    private fun bool(k: String, d: Boolean) = object : ReadWriteProperty<Prefs, Boolean> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getBoolean(k, d)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Boolean) { sp.edit().putBoolean(k, value).apply() }
    }

    private fun int(k: String, d: Int) = object : ReadWriteProperty<Prefs, Int> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getInt(k, d)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Int) { sp.edit().putInt(k, value).apply() }
    }

    private fun long(k: String, d: Long) = object : ReadWriteProperty<Prefs, Long> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getLong(k, d)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Long) { sp.edit().putLong(k, value).apply() }
    }

    private fun float(k: String, d: Float) = object : ReadWriteProperty<Prefs, Float> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getFloat(k, d)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Float) { sp.edit().putFloat(k, value).apply() }
    }

    private fun str(k: String, d: String) = object : ReadWriteProperty<Prefs, String> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>) = sp.getString(k, d) ?: d
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: String) { sp.edit().putString(k, value).apply() }
    }

    private fun set(k: String, d: Set<String> = emptySet()) = object : ReadWriteProperty<Prefs, Set<String>> {
        override fun getValue(thisRef: Prefs, property: KProperty<*>): Set<String> = HashSet(sp.getStringSet(k, d) ?: d)
        override fun setValue(thisRef: Prefs, property: KProperty<*>, value: Set<String>) { sp.edit().putStringSet(k, HashSet(value)).apply() }
    }

    companion object {
        const val FILE = "watchlite"
    }
}
