package com.watchlite.launcher.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Process
import android.provider.OpenableColumns
import android.provider.Settings
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.BuildConfig
import com.watchlite.launcher.R
import com.watchlite.launcher.backup.BackupManager
import com.watchlite.launcher.chime.ChimeScheduler
import com.watchlite.launcher.chime.ChimeSounds
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.input.InputMapper
import com.watchlite.launcher.input.InputProbeActivity
import com.watchlite.launcher.link.LinkManager
import com.watchlite.launcher.notifications.NotifListener
import com.watchlite.launcher.prayer.PrayerTimes
import com.watchlite.launcher.safety.CrashGuard
import com.watchlite.launcher.system.SystemActions
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Log
import com.watchlite.launcher.util.Ui
import org.json.JSONObject

class SettingsActivity : Activity() {

    companion object {
        private const val REQ_SOUND = 21
        private const val REQ_EXPORT = 22
        private const val REQ_RESTORE = 23
        private const val REQ_THEME = 24
        private const val REQ_LOCATION = 25

        val CITIES = linkedMapOf(
            "Cairo|القاهرة" to (30.0444 to 31.2357), "Giza|الجيزة" to (30.0131 to 31.2089),
            "Alexandria|الإسكندرية" to (31.2001 to 29.9187), "Mansoura|المنصورة" to (31.0409 to 31.3785),
            "Tanta|طنطا" to (30.7865 to 31.0004), "Zagazig|الزقازيق" to (30.5877 to 31.5020),
            "Port Said|بورسعيد" to (31.2653 to 32.3019), "Suez|السويس" to (29.9668 to 32.5498),
            "Ismailia|الإسماعيلية" to (30.5965 to 32.2715), "Asyut|أسيوط" to (27.1809 to 31.1837),
            "Minya|المنيا" to (28.1099 to 30.7503), "Sohag|سوهاج" to (26.5591 to 31.6957),
            "Luxor|الأقصر" to (25.6872 to 32.6396), "Aswan|أسوان" to (24.0889 to 32.8998),
            "Hurghada|الغردقة" to (27.2579 to 33.8116), "Sharm El-Sheikh|شرم الشيخ" to (27.9158 to 34.3300),
            "Mecca|مكة" to (21.3891 to 39.8579), "Medina|المدينة" to (24.5247 to 39.5692),
            "Riyadh|الرياض" to (24.7136 to 46.6753), "Jeddah|جدة" to (21.4858 to 39.1925),
            "Dubai|دبي" to (25.2048 to 55.2708), "Abu Dhabi|أبوظبي" to (24.4539 to 54.3773),
            "Kuwait|الكويت" to (29.3759 to 47.9774), "Doha|الدوحة" to (25.2854 to 51.5310),
            "Manama|المنامة" to (26.2285 to 50.5860), "Muscat|مسقط" to (23.5880 to 58.3829),
            "Amman|عمّان" to (31.9454 to 35.9284), "Jerusalem|القدس" to (31.7683 to 35.2137),
            "Beirut|بيروت" to (33.8938 to 35.5018), "Damascus|دمشق" to (33.5138 to 36.2765),
            "Baghdad|بغداد" to (33.3152 to 44.3661), "Khartoum|الخرطوم" to (15.5007 to 32.5599),
            "Tripoli|طرابلس" to (32.8872 to 13.1913), "Tunis|تونس" to (36.8065 to 10.1815),
            "Algiers|الجزائر" to (36.7538 to 3.0588), "Casablanca|الدار البيضاء" to (33.5731 to -7.5898),
            "Sanaa|صنعاء" to (15.3694 to 44.1910), "Istanbul|إسطنبول" to (41.0082 to 28.9784)
        )
    }

    private lateinit var ui: Ui
    private lateinit var scroll: ScrollView
    private lateinit var list: LinearLayout
    private val stack = ArrayList<Pair<String, () -> Unit>>()
    private val p get() = App.prefs
    private var current: (() -> Unit)? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        list = ui.vbox()
        list.setPadding(ui.sp.safe, ui.px(if (ui.sp.isRound) 12f else 5f), ui.sp.safe, ui.px(if (ui.sp.isRound) 18f else 8f))
        scroll = ScrollView(this).apply { isVerticalScrollBarEnabled = false; addView(list); setBackgroundColor(Ui.BG) }
        setContentView(scroll)
        open(getString(R.string.settings), ::mainScreen)
    }

    override fun onResume() { super.onResume(); current?.invoke() }

    // ------------------------------------------------------------------ mini framework
    private fun open(title: String, screen: () -> Unit) {
        stack.add(title to screen); render()
    }

    private fun render() {
        val (title, screen) = stack.last()
        list.removeAllViews()
        list.addView(ui.text(title, 7f, Ui.TEXT, 2).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 3f))
        current = { val y = scroll.scrollY; list.removeViews(1, list.childCount - 1); screen(); scroll.post { scroll.scrollTo(0, y) } }
        screen()
        scroll.scrollTo(0, 0)
    }

    private fun refresh() = current?.invoke()

    @Deprecated("framework") override fun onBackPressed() {
        if (stack.size > 1) { stack.removeAt(stack.size - 1); render() } else super.onBackPressed()
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

    private fun header(t: String) {
        list.addView(ui.text(t, 4.6f, ui.accent, 2), ui.lp(top = 3f, bottom = 1.2f, start = 2f))
    }

    private fun row(title: String, summary: String?, right: View? = null, onClick: (() -> Unit)? = null): LinearLayout {
        val r = ui.hbox()
        r.background = if (onClick != null) ui.ripple(ui.rounded(Ui.CARD, 5f * ui.u)) else ui.rounded(Ui.CARD, 5f * ui.u)
        r.setPadding(ui.px(4.5f), ui.px(3.2f), ui.px(4f), ui.px(3.2f))
        val col = ui.vbox()
        col.addView(ui.text(title, 5.4f, Ui.TEXT, 1, 2))
        if (!summary.isNullOrEmpty()) col.addView(ui.text(summary, 4.3f, Ui.TEXT2, 1, 3), ui.lp(top = 0.6f))
        r.addView(col, ui.lp(0, weight = 1f))
        if (right != null) r.addView(right, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, start = 2f))
        if (onClick != null) { r.isClickable = true; r.setOnClickListener { Haptics.tick(this); onClick() } }
        list.addView(r, ui.lp(bottom = 1.5f))
        return r
    }

    private fun nav(title: String, summary: String? = null, screen: () -> Unit) =
        row(title, summary, ui.text(if (LocaleHelper.isRtl(this)) "‹" else "›", 7f, Ui.TEXT2)) { open(title, screen) }

    private fun action(title: String, summary: String? = null, onClick: () -> Unit) = row(title, summary, null, onClick)

    @SuppressLint("UseSwitchCompatOrMaterialCode")
    private fun switch(title: String, summary: String?, get: () -> Boolean, set: (Boolean) -> Unit) {
        val sw = Switch(this).apply {
            isChecked = get(); isClickable = false
            thumbTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(ui.accent, 0xFFBBBBBB.toInt()))
            trackTintList = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf((ui.accent and 0x00FFFFFF) or 0x88000000.toInt(), Ui.CARD2))
        }
        row(title, summary, sw) { val v = !get(); set(v); sw.isChecked = v }
    }

    private fun slider(title: String, min: Int, max: Int, get: () -> Int, set: (Int) -> Unit, fmt: (Int) -> String) {
        val r = ui.card()
        val top = ui.hbox()
        top.addView(ui.text(title, 5.4f, Ui.TEXT, 1, 2), ui.lp(0, weight = 1f))
        val value = ui.text(fmt(get()), 5.2f, ui.accent, 2)
        top.addView(value)
        r.addView(top)
        val sb = SeekBar(this).apply {
            this.max = max - min; progress = get() - min
            progressTintList = ColorStateList.valueOf(ui.accent); thumbTintList = ColorStateList.valueOf(ui.accent)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, v: Int, fromUser: Boolean) { if (fromUser) { set(v + min); value.text = fmt(v + min) } }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) { Haptics.tick(this@SettingsActivity) }
            })
        }
        r.addView(sb, ui.lp(top = 1.5f))
        list.addView(r, ui.lp(bottom = 1.5f))
    }

    private fun <T> choice(title: String, options: List<Pair<T, String>>, get: () -> T, set: (T) -> Unit) {
        val cur = options.firstOrNull { it.first == get() }?.second ?: ""
        row(title, cur) {
            AlertDialog.Builder(this, Ui.DIALOG).setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), options.indexOfFirst { it.first == get() }) { d, i ->
                    set(options[i].first); d.dismiss(); refresh()
                }.show()
        }
    }

    private fun s(id: Int) = getString(id)
    private fun hours() = (0..23).map { it to Digits.apply("%02d:00".format(java.util.Locale.US, it), Digits.useArabic()) }

    // ------------------------------------------------------------------ screens
    private fun mainScreen() {
        nav(s(R.string.st_appearance), s(R.string.st_appearance_sum), ::appearance)
        nav(s(R.string.st_aod), if (p.aodEnabled) s(R.string.on) else s(R.string.off), ::aodScreen)
        nav(s(R.string.st_chime), if (p.chimeEnabled) s(R.string.on) else s(R.string.off), ::chimeScreen)
        nav(s(R.string.st_gestures), s(R.string.st_gestures_sum), ::gestures)
        nav(s(R.string.st_drawer), s(R.string.st_drawer_sum), ::drawerScreen)
        nav(s(R.string.st_steps), Digits.fmt(p.stepsGoal), ::stepsScreen)
        nav(s(R.string.st_notifications), null, ::notifScreen)
        nav(s(R.string.st_prayer), cityLabel(), ::prayerScreen)
        nav(s(R.string.st_power), null, ::powerScreen)
        nav(s(R.string.st_link), if (p.linkEnabled) s(R.string.on) else s(R.string.off), ::linkScreen)
        nav(s(R.string.st_permissions), s(R.string.st_permissions_sum), ::permissionsScreen)
        nav(s(R.string.st_backup), null, ::backupScreen)
        nav(s(R.string.st_about), s(R.string.developed_by), ::aboutScreen)
    }

    private fun appearance() {
        nav(s(R.string.st_themes), App.I.themes.get(p.themeId)?.name() ?: p.themeId, ::themesScreen)
        choice(s(R.string.st_language), listOf("system" to s(R.string.lang_system), "ar" to "العربية", "en" to "English"), { p.language }) {
            if (it != p.language) { p.language = it; restartApp() }
        }
        choice(s(R.string.st_ui_mode), listOf("dark" to s(R.string.mode_dark), "light" to s(R.string.mode_light), "auto" to s(R.string.mode_auto)),
            { p.uiMode }) { if (it != p.uiMode) { p.uiMode = it; recreate() } }
        choice(s(R.string.st_digits), listOf("latin" to s(R.string.digits_latin), "arabic" to s(R.string.digits_arabic), "auto" to s(R.string.auto)),
            { p.digits }) { p.digits = it; App.I.themes.notifyChanged() }
        choice(s(R.string.st_clock), listOf("system" to s(R.string.lang_system), "12" to s(R.string.clock_12), "24" to s(R.string.clock_24)),
            { p.clock }) { p.clock = it; App.I.themes.notifyChanged() }
        header(s(R.string.st_accent))
        for (rowColors in Ui.ACCENTS.toList().chunked(7)) {
            val grid = ui.hbox().apply { gravity = Gravity.CENTER }
            rowColors.forEach { c ->
                grid.addView(View(this).apply {
                    background = ui.oval(c)
                    if (c == p.accent) { scaleX = 1.3f; scaleY = 1.3f }
                    setOnClickListener { setAccent(c) }
                }, ui.lp(ui.px(8f), ui.px(8f), top = 1f, bottom = 1f, start = 0.8f, end = 0.8f))
            }
            list.addView(grid)
        }
        action(s(R.string.custom_color), s(R.string.custom_color_sum)) { customColorDialog() }
        switch(s(R.string.accent_on_faces), s(R.string.accent_on_faces_sum), { p.accentOnFaces }) { p.accentOnFaces = it; App.I.themes.notifyChanged() }
        slider(s(R.string.st_fps), 10, 30, { p.fps }, { p.fps = it }) { Digits.fmt(it) + " fps" }
        header(s(R.string.st_auto_theme))
        switch(s(R.string.st_auto_theme), s(R.string.st_auto_theme_sum), { p.autoTheme }) { p.autoTheme = it; App.I.themes.notifyChanged(); refresh() }
        if (p.autoTheme) {
            val themes = App.I.themes.all().map { it.id to it.name() }
            choice(s(R.string.day_theme), themes, { p.dayTheme }) { p.dayTheme = it; App.I.themes.notifyChanged() }
            choice(s(R.string.night_theme), themes, { p.nightTheme }) { p.nightTheme = it; App.I.themes.notifyChanged() }
            choice(s(R.string.night_from), hours(), { p.nightFrom }) { p.nightFrom = it; App.I.themes.notifyChanged() }
            choice(s(R.string.night_to), hours(), { p.nightTo }) { p.nightTo = it; App.I.themes.notifyChanged() }
            choice(s(R.string.charging_theme), listOf("" to s(R.string.none)) + themes, { p.chargingTheme }) { p.chargingTheme = it; App.I.themes.notifyChanged() }
        }
    }

    private fun setAccent(c: Int) {
        p.accent = c
        App.I.themes.notifyChanged()
        if (p.iconStyle in com.watchlite.launcher.drawer.IconTheme.STYLES || p.iconStyle == "outline") App.I.apps.restyle()
        refresh()
    }

    /** Hue slider: every position gives a well-balanced, readable color. */
    private fun customColorDialog() {
        val box = ui.vbox().apply { setPadding(ui.px(5f), ui.px(4f), ui.px(5f), ui.px(2f)); gravity = Gravity.CENTER_HORIZONTAL }
        val hsv = FloatArray(3); android.graphics.Color.colorToHSV(p.accent, hsv)
        var hue = hsv[0]; var light = if (hsv[2] > 0.95f && hsv[1] < 0.6f) 1 else 0
        val preview = View(this)
        fun color() = android.graphics.Color.HSVToColor(floatArrayOf(hue, if (light == 1) 0.45f else 0.72f, 1f))
        fun upd() { preview.background = ui.oval(color()) }
        box.addView(preview, ui.lp(ui.px(16f), ui.px(16f), bottom = 3f)); upd()
        val bar = SeekBar(this).apply {
            max = 360; progress = hue.toInt()
            progressDrawable = android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                IntArray(7) { android.graphics.Color.HSVToColor(floatArrayOf(it * 60f, 0.72f, 1f)) }).apply { cornerRadius = 50f * ui.u }
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) { hue = v.toFloat(); upd() }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        box.addView(bar, ui.lp(bottom = 2f).apply { height = ui.px(7f) })
        val tone = Switch(this).apply { text = s(R.string.pastel); setTextColor(Ui.TEXT); isChecked = light == 1
            setOnCheckedChangeListener { _, on -> light = if (on) 1 else 0; upd() } }
        box.addView(tone, ui.lp())
        AlertDialog.Builder(this, Ui.DIALOG).setTitle(R.string.custom_color).setView(box)
            .setPositiveButton(android.R.string.ok) { _, _ -> setAccent(color()) }.setNegativeButton(R.string.cancel, null).show()
    }

    private fun themesScreen() {
        action(s(R.string.theme_import), s(R.string.theme_import_sum)) { pick(REQ_THEME, "*/*") }
        for (t in App.I.themes.all()) {
            val sel = t.id == p.themeId
            row(t.name(), if (t.builtIn) null else s(R.string.custom_theme), if (sel) ui.icon(R.drawable.ic_watch, 6f, ui.accent) else null) {
                App.I.themes.select(t.id); refresh()
            }.setOnLongClickListener {
                if (!t.builtIn) AlertDialog.Builder(this, Ui.DIALOG).setTitle(t.name()).setMessage(R.string.delete_theme_q)
                    .setPositiveButton(R.string.delete) { _, _ -> App.I.themes.delete(t.id); refresh() }.setNegativeButton(R.string.cancel, null).show()
                true
            }
        }
    }

    private fun aodScreen() {
        switch(s(R.string.st_aod), s(R.string.aod_sum), { p.aodEnabled }) { p.aodEnabled = it; refresh() }
        if (!p.aodEnabled) return
        slider(s(R.string.aod_brightness), 1, 40, { (p.aodBrightness * 100 * 4).toInt().coerceIn(1, 40) }, { p.aodBrightness = it / 400f }) {
            Digits.apply("%.1f%%".format(java.util.Locale.US, it / 4f), Digits.useArabic())
        }
        slider(s(R.string.aod_opacity), 20, 100, { (p.aodOpacity * 100).toInt() }, { p.aodOpacity = it / 100f }) { Digits.fmt(it) + "%" }
        choice(s(R.string.aod_idle), listOf(-1 to s(R.string.aod_idle_auto), 0 to s(R.string.aod_idle_home), 5 to "5s", 10 to "10s", 15 to "15s", 20 to "20s", 30 to "30s", 60 to "60s"),
            { p.aodIdleSec }) { p.aodIdleSec = it }
        slider(s(R.string.aod_battery_min), 5, 90, { p.aodBatteryMin }, { p.aodBatteryMin = it }) { Digits.fmt(it) + "%" }
        switch(s(R.string.aod_charging), null, { p.aodWhileCharging }) { p.aodWhileCharging = it }
        choice(s(R.string.aod_wake), listOf("double" to s(R.string.aod_wake_double), "single" to s(R.string.aod_wake_single)), { p.aodWake }) { p.aodWake = it }
        switch(s(R.string.aod_raise), s(R.string.aod_raise_sum), { p.aodRaise }) { p.aodRaise = it }
        switch(s(R.string.aod_shift), s(R.string.aod_shift_sum), { p.aodPixelShift }) { p.aodPixelShift = it }
        header(s(R.string.aod_color))
        val colors = ui.hbox().apply { gravity = Gravity.CENTER }
        colors.addView(ui.text(s(R.string.aod_color_theme), 4.2f, if (p.aodColor == 0) ui.accent else Ui.TEXT2, 2).apply {
            gravity = Gravity.CENTER; background = ui.rounded(Ui.CARD2, 100f * ui.u); setPadding(ui.px(2.5f), ui.px(1.5f), ui.px(2.5f), ui.px(1.5f))
            setOnClickListener { p.aodColor = 0; refresh() }
        }, ui.lp(LinearLayout.LayoutParams.WRAP_CONTENT, end = 1.5f))
        intArrayOf(0xFFFFFFFF.toInt(), 0xFF7B6CFF.toInt(), 0xFF30D158.toInt(), 0xFF0A84FF.toInt(), 0xFFFF375F.toInt(), 0xFFBF5AF2.toInt(), 0xFFE8C77A.toInt()).forEach { c ->
            colors.addView(View(this).apply {
                background = ui.oval(c)
                if (c == p.aodColor) { scaleX = 1.3f; scaleY = 1.3f }
                setOnClickListener { p.aodColor = c; refresh() }
            }, ui.lp(ui.px(6.5f), ui.px(6.5f), start = 0.8f, end = 0.8f))
        }
        list.addView(colors, ui.lp(bottom = 2f))
        header(s(R.string.aod_prayer_header))
        switch(s(R.string.aod_prayer), s(R.string.aod_prayer_sum), { p.aodPrayer }) { p.aodPrayer = it; if (it && !p.prayerEnabled) p.prayerEnabled = true; refresh() }
        if (p.aodPrayer) switch(s(R.string.aod_prayer_cd), null, { p.aodPrayerCountdown }) { p.aodPrayerCountdown = it }
        header(s(R.string.aod_widgets))
        val wl = listOf("battery" to R.string.aodw_battery, "notif" to R.string.aodw_notif, "date" to R.string.aodw_date,
            "steps" to R.string.aodw_steps, "prayer" to R.string.aodw_prayer, "phone" to R.string.aodw_phone)
        for ((k, label) in wl) switch(s(label), null, { k in p.aodWidgets }) {
            val set = p.aodWidgets.toMutableSet(); if (!set.remove(k)) set.add(k); p.aodWidgets = set
        }
        header(s(R.string.options))
        switch(s(R.string.aod_night), null, { p.aodNightOff }) { p.aodNightOff = it; refresh() }
        if (p.aodNightOff) {
            choice(s(R.string.from), hours(), { p.aodNightFrom }) { p.aodNightFrom = it }
            choice(s(R.string.to), hours(), { p.aodNightTo }) { p.aodNightTo = it }
        }
        if (!SystemActions.isAdmin(this)) action(s(R.string.enable_admin), s(R.string.enable_admin_sum)) {
            com.watchlite.launcher.system.Permissions.help(this, com.watchlite.launcher.system.Permissions.Kind.ADMIN) { refresh() }
        }
    }

    private fun chimeScreen() {
        switch(s(R.string.st_chime), s(R.string.chime_sum), { p.chimeEnabled }) { p.chimeEnabled = it; ChimeScheduler.schedule(this); refresh() }
        if (!p.chimeEnabled) return
        header(s(R.string.chime_sound))
        val all = ChimeSounds.BUILT_IN.keys.map { "res:$it" } + ChimeSounds.userSounds(this).map { "file:" + it.name }
        for (id in all) {
            val sel = id == p.chimeSound
            row(ChimeSounds.label(this, id), if (id.startsWith("file:")) s(R.string.my_sound) else null,
                if (sel) ui.icon(R.drawable.ic_music_note, 6f, ui.accent) else null) {
                p.chimeSound = id; ChimeSounds.play(this, id, p.chimeVolume); refresh()
            }.setOnLongClickListener {
                if (id.startsWith("file:")) {
                    java.io.File(ChimeSounds.dir(this), id.removePrefix("file:")).delete()
                    if (p.chimeSound == id) p.chimeSound = "res:chime_soft_bell"
                    refresh()
                }
                true
            }
        }
        action(s(R.string.add_sound), s(R.string.add_sound_sum)) { pick(REQ_SOUND, "audio/*") }
        header(s(R.string.options))
        choice(s(R.string.chime_mode), listOf("once" to s(R.string.chime_once), "strikes" to s(R.string.chime_strikes)), { p.chimeMode }) { p.chimeMode = it }
        choice(s(R.string.from), hours(), { p.chimeFrom }) { p.chimeFrom = it; ChimeScheduler.schedule(this) }
        choice(s(R.string.to), hours(), { p.chimeTo }) { p.chimeTo = it; ChimeScheduler.schedule(this) }
        action(s(R.string.chime_days), daysLabel()) { daysDialog() }
        choice(s(R.string.chime_stream), listOf("alarm" to s(R.string.stream_alarm), "media" to s(R.string.stream_media), "notification" to s(R.string.stream_notification)),
            { p.chimeStream }) { p.chimeStream = it }
        val sv = ChimeSounds.streamPercent(this)
        row(s(R.string.stream_volume), Digits.fmt(sv) + "%" + if (sv < 30) "  ·  " + s(R.string.stream_volume_low) else "")
        slider(s(R.string.volume), 5, 100, { (p.chimeVolume * 100).toInt() }, { p.chimeVolume = it / 100f }) { Digits.fmt(it) + "%" }
        switch(s(R.string.vibrate), null, { p.chimeVibrate }) { p.chimeVibrate = it }
        switch(s(R.string.respect_dnd), null, { p.chimeRespectDnd }) { p.chimeRespectDnd = it }
        action(s(R.string.test)) { ChimeSounds.play(this, p.chimeSound, p.chimeVolume) }
    }

    private fun dayNames() = java.text.DateFormatSymbols.getInstance().shortWeekdays.let { w ->
        // Monday first
        listOf(w[2], w[3], w[4], w[5], w[6], w[7], w[1])
    }

    private fun daysLabel(): String {
        val n = dayNames(); return (0 until 7).filter { (p.chimeDays shr it) and 1 == 1 }.joinToString(" ") { n[it] }
    }

    private fun daysDialog() {
        val names = dayNames().toTypedArray()
        val checked = BooleanArray(7) { (p.chimeDays shr it) and 1 == 1 }
        AlertDialog.Builder(this, Ui.DIALOG).setMultiChoiceItems(names, checked) { _, i, v -> checked[i] = v }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                p.chimeDays = checked.foldIndexed(0) { i, acc, v -> if (v) acc or (1 shl i) else acc }
                ChimeScheduler.schedule(this); refresh()
            }.show()
    }

    private val actionsList get() = listOf("none" to s(R.string.none), "flashlight" to s(R.string.tile_flashlight), "aod" to s(R.string.tile_aod),
        "settings" to s(R.string.tile_settings), "drawer" to s(R.string.apps), "notifications" to s(R.string.notifications),
        "control" to s(R.string.control_center), "widgets" to s(R.string.widgets), "themes" to s(R.string.st_themes),
        "lock" to s(R.string.lock_screen), "theater" to s(R.string.tile_theater))

    private fun gestures() {
        header(s(R.string.wheel))
        choice(s(R.string.wheel_on_face), listOf("none" to s(R.string.none), "themes" to s(R.string.switch_themes)), { p.wheelOnFace }) { p.wheelOnFace = it }
        choice(s(R.string.wheel_in_drawer), listOf("zoom" to s(R.string.zoom), "scroll" to s(R.string.scroll)), { p.wheelInDrawer }) { p.wheelInDrawer = it }
        switch(s(R.string.wheel_invert), null, { p.wheelInvert }) { p.wheelInvert = it }
        action(s(R.string.input_probe), s(R.string.input_probe_sum)) { startActivity(Intent(this, InputProbeActivity::class.java)) }
        header(s(R.string.buttons))
        choice(s(R.string.back_double), actionsList, { p.backDouble }) { p.backDouble = it }
        choice(s(R.string.back_long), actionsList, { p.backLong }) { p.backLong = it }
        choice(s(R.string.haptics), listOf(0 to s(R.string.off), 1 to s(R.string.light), 2 to s(R.string.strong)), { p.haptics }) { p.haptics = it }
        header(s(R.string.gesture_map))
        list.addView(ui.text(s(R.string.gesture_help), 4.6f, Ui.TEXT2, 1, 0), ui.lp(start = 2f, end = 2f))
    }

    private fun drawerScreen() {
        choice(s(R.string.drawer_layout), listOf("honeycomb" to s(R.string.drawer_honeycomb), "grid" to s(R.string.drawer_grid), "list" to s(R.string.drawer_list)),
            { p.drawerMode }) { p.drawerMode = it }
        slider(s(R.string.icon_size), 0, 4, { 4 - p.drawerZoom }, { p.drawerZoom = 4 - it }) { Digits.fmt(it + 1) }
        choice(s(R.string.icon_style), listOf("wl_gradient" to s(R.string.is_wl_gradient), "wl_line" to s(R.string.is_wl_line),
            "wl_neon" to s(R.string.is_wl_neon), "circle" to s(R.string.is_circle), "squircle" to s(R.string.is_squircle),
            "original" to s(R.string.is_original), "mono" to s(R.string.is_mono), "glass" to s(R.string.is_glass), "outline" to s(R.string.is_outline)),
            { p.iconStyle }) { p.iconStyle = it; App.I.apps.restyle() }
        val packs = com.watchlite.launcher.drawer.IconPack.installed(this)
        choice(s(R.string.icon_pack), listOf("" to s(R.string.none)) + packs.map { it.key to it.value }, { p.iconPack }) {
            p.iconPack = it; App.I.apps.restyle()
        }
        if (packs.isEmpty()) action(s(R.string.get_icon_packs), s(R.string.get_icon_packs_sum)) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=icon%20pack&c=apps")))
            } catch (_: Exception) { toast(s(R.string.no_app)) }
        }
        header(s(R.string.hidden_apps))
        val hidden = p.hiddenApps
        val apps = App.I.apps.everything().sortedBy { it.label }
        for (a in apps) switch(a.label, if (a.key in hidden) s(R.string.hidden) else null, { a.key !in p.hiddenApps }) { App.I.apps.toggleHidden(a.key) }
    }

    private fun stepsScreen() {
        row(s(R.string.steps_today), Digits.fmt(App.I.steps.today) + " / " + Digits.fmt(p.stepsGoal))
        slider(s(R.string.steps_goal), 10, 300, { p.stepsGoal / 100 }, { p.stepsGoal = it * 100 }) { Digits.fmt(it * 100) }
        header(s(R.string.quick_goals))
        val row = ui.hbox().apply { gravity = Gravity.CENTER }
        for (g in intArrayOf(3000, 5000, 8000, 10000, 15000)) {
            row.addView(ui.pill(Digits.fmt(g / 1000) + "k", if (g == p.stepsGoal) ui.accent else Ui.CARD2, if (g == p.stepsGoal) Ui.BG else Ui.TEXT) {
                p.stepsGoal = g; refresh()
            }, ui.lp(0, start = 0.5f, end = 0.5f, weight = 1f))
        }
        list.addView(row, ui.lp(bottom = 2f))
        if (!App.I.steps.available) list.addView(ui.text(s(R.string.no_step_sensor), 4.4f, Ui.TEXT2, 1, 0), ui.lp(start = 2f))
    }

    private fun notifScreen() {
        val access = NotifListener.hasAccess(this)
        action(s(R.string.notif_access), if (access) s(R.string.granted) else s(R.string.not_granted)) {
            com.watchlite.launcher.system.Permissions.help(this, com.watchlite.launcher.system.Permissions.Kind.NOTIFICATIONS) { refresh() }
        }
        choice(s(R.string.swipe_down), listOf("auto" to s(R.string.swipe_down_auto), "launcher" to s(R.string.swipe_down_launcher), "system" to s(R.string.swipe_down_system)),
            { p.swipeDown }) { p.swipeDown = it }
        action(s(R.string.canned_replies), s(R.string.canned_replies_sum)) {
            val input = EditText(this).apply {
                setText(p.cannedReplies.ifEmpty { resources.getStringArray(R.array.canned_replies).joinToString("\n") })
                setTextColor(Ui.TEXT); minLines = 4
            }
            AlertDialog.Builder(this, Ui.DIALOG).setTitle(R.string.canned_replies).setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> p.cannedReplies = input.text.toString() }.show()
        }
        header(s(R.string.blocked_apps))
        val pm = packageManager
        val seen = App.I.notifications.list().map { it.pkg }.toSet() + p.blockedNotifApps
        if (seen.isEmpty()) list.addView(ui.text(s(R.string.blocked_apps_empty), 4.6f, Ui.TEXT2, 1, 0), ui.lp(start = 2f))
        for (pkg in seen) {
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
            switch(label, null, { pkg !in p.blockedNotifApps }) {
                val s = p.blockedNotifApps.toMutableSet(); if (!s.remove(pkg)) s.add(pkg); p.blockedNotifApps = s
            }
        }
    }

    private fun prayerScreen() {
        switch(s(R.string.st_prayer), s(R.string.prayer_sum), { p.prayerEnabled }) { p.prayerEnabled = it; refresh() }
        if (!p.prayerEnabled) return
        val ar = java.util.Locale.getDefault().language == "ar"
        choice(s(R.string.city), CITIES.keys.map { it to (if (ar) it.substringAfter('|') else it.substringBefore('|')) },
            { CITIES.keys.firstOrNull { it.substringBefore('|') == p.cityName } ?: "" }) {
            val (lat, lng) = CITIES.getValue(it)
            p.cityName = it.substringBefore('|'); p.latitude = lat.toFloat(); p.longitude = lng.toFloat(); p.locationMode = "manual"
        }
        action(s(R.string.use_gps), Digits.apply("%.3f, %.3f".format(java.util.Locale.US, p.latitude, p.longitude), Digits.useArabic())) { requestGps() }
        choice(s(R.string.calc_method), PrayerTimes.METHODS.keys.map { it to methodName(it) }, { p.prayerMethod }) { p.prayerMethod = it }
        choice(s(R.string.asr_method), listOf("shafii" to s(R.string.asr_standard), "hanafi" to s(R.string.asr_hanafi)), { p.asrMethod }) { p.asrMethod = it }
        choice(s(R.string.dst), listOf("auto" to s(R.string.dst_auto), "on" to s(R.string.dst_on), "off" to s(R.string.dst_off)), { p.dstMode }) { p.dstMode = it }
        row(s(R.string.dst_now), dstStatus())
        choice(s(R.string.hijri_adjust), (-2..2).map { it to Digits.apply(if (it > 0) "+$it" else "$it", Digits.useArabic()) }, { p.hijriAdjust }) { p.hijriAdjust = it }
    }

    private fun dstStatus(): String {
        val z = java.util.TimeZone.getDefault()
        val sys = z.inDaylightTime(java.util.Date())
        val active = when (p.dstMode) { "on" -> true; "off" -> false; else -> sys }
        val off = (z.rawOffset + if (active) 3_600_000 else 0) / 3_600_000.0
        val gmt = "GMT" + (if (off >= 0) "+" else "") + (if (off % 1.0 == 0.0) off.toInt().toString() else off.toString())
        return Digits.apply(gmt, Digits.useArabic()) + " · " + s(if (active) R.string.dst_active else R.string.dst_inactive) +
            " · " + s(R.string.dst_watch_says) + " " + s(if (sys) R.string.on else R.string.off)
    }

    private fun cityLabel(): String {
        val key = CITIES.keys.firstOrNull { it.substringBefore('|') == p.cityName } ?: return p.cityName
        return if (java.util.Locale.getDefault().language == "ar") key.substringAfter('|') else key.substringBefore('|')
    }

    private fun methodName(k: String) = when (k) {
        "egypt" -> s(R.string.m_egypt); "mwl" -> s(R.string.m_mwl); "ummalqura" -> s(R.string.m_ummalqura); "isna" -> s(R.string.m_isna)
        "karachi" -> s(R.string.m_karachi); "dubai" -> s(R.string.m_dubai); "kuwait" -> s(R.string.m_kuwait); else -> s(R.string.m_qatar)
    }

    private fun powerScreen() {
        switch(s(R.string.saver_manual), s(R.string.saver_sum), { p.saverManual }) { p.saverManual = it }
        switch(s(R.string.saver_auto), null, { p.saverAuto }) { p.saverAuto = it; refresh() }
        if (p.saverAuto) slider(s(R.string.saver_threshold), 5, 50, { p.saverThreshold }, { p.saverThreshold = it }) { Digits.fmt(it) + "%" }
        switch(s(R.string.tile_theater), s(R.string.theater_sum), { p.theater }) { p.theater = it }
        choice(s(R.string.flash_mode), listOf("auto" to s(R.string.flash_auto), "screen" to s(R.string.flash_screen)), { p.flashMode }) { p.flashMode = it }
        action(s(R.string.lock_screen), if (SystemActions.isAdmin(this)) s(R.string.granted) else s(R.string.enable_admin_sum)) {
            if (!SystemActions.isAdmin(this)) com.watchlite.launcher.system.Permissions.help(this, com.watchlite.launcher.system.Permissions.Kind.ADMIN) { refresh() }
        }
    }

    private fun linkScreen() {
        switch(s(R.string.st_link), s(R.string.link_sum), { p.linkEnabled }) {
            p.linkEnabled = it
            if (it) {
                if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
                LinkManager.start(this)
            } else LinkManager.stop(this)
            refresh()
        }
        if (!p.linkEnabled) return
        val st = App.I.state
        row(s(R.string.pairing_code), Digits.apply(p.linkCode.chunked(3).joinToString(" "), Digits.useArabic()) + " · " +
            s(if (st.linkConnected) R.string.connected else R.string.not_connected))
        action(s(R.string.new_code)) { p.linkCode = LinkManager.newCode(); LinkManager.stop(this); LinkManager.start(this); refresh() }
        header(s(R.string.modules))
        val names = mapOf("notif" to R.string.mod_notif, "battery" to R.string.mod_battery, "net" to R.string.mod_net, "location" to R.string.mod_location,
            "contacts" to R.string.mod_contacts, "calls" to R.string.mod_calls, "media" to R.string.mod_media, "weather" to R.string.mod_weather,
            "themes" to R.string.mod_themes, "find" to R.string.mod_find, "camera" to R.string.mod_camera)
        for (m in LinkManager.MODULES) switch(s(names.getValue(m)), null, { m in p.linkModules }) {
            val set = p.linkModules.toMutableSet(); if (!set.remove(m)) set.add(m); p.linkModules = set
        }
        header(s(R.string.simulator))
        action(s(R.string.sim_notif)) {
            LinkManager.handle(JSONObject().put("type", "notif.post").put("payload", JSONObject().put("key", "sim" + System.currentTimeMillis())
                .put("app", "WhatsApp").put("pkg", "com.whatsapp").put("title", "Ahmed").put("text", s(R.string.sim_text))
                .put("actions", org.json.JSONArray().put(JSONObject().put("id", "reply").put("title", s(R.string.reply)).put("reply", true)))).toString())
            toast(s(R.string.done))
        }
        action(s(R.string.sim_battery)) {
            LinkManager.handle(JSONObject().put("type", "battery.phone").put("payload", JSONObject().put("level", 42).put("charging", false)).toString())
            LinkManager.handle(JSONObject().put("type", "weather.update").put("payload", JSONObject().put("temp", "24°").put("desc", s(R.string.sim_weather))).toString())
            LinkManager.handle(JSONObject().put("type", "media.state").put("payload", JSONObject().put("title", "Track").put("artist", "Artist").put("playing", true)).toString())
            toast(s(R.string.done))
        }
    }

    private fun permissionsScreen() {
        val P = com.watchlite.launcher.system.Permissions
        fun status(ok: Boolean) = s(if (ok) R.string.granted else R.string.not_granted)
        row(s(R.string.perm_adb_status), status(P.hasSecure(this)) + " · " + s(R.string.perm_adb_status_sum))
        action(s(R.string.perm_adb), s(R.string.perm_adb_short)) { P.adbDialog(this) }
        header(s(R.string.st_permissions))
        for (k in com.watchlite.launcher.system.Permissions.Kind.values()) action(P.title(this, k), status(P.granted(this, k))) { P.help(this, k) { refresh() } }
        action(s(R.string.location_perm), status(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 0)
        }
        action(s(R.string.default_launcher), s(R.string.default_launcher_sum)) { startActivity(Intent(Settings.ACTION_HOME_SETTINGS)) }
    }

    private fun backupScreen() {
        action(s(R.string.backup_export), s(R.string.backup_export_sum)) {
            try {
                startActivityForResult(Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("application/zip")
                    .putExtra(Intent.EXTRA_TITLE, "watchlite-${System.currentTimeMillis() / 1000}.wlbackup"), REQ_EXPORT)
            } catch (_: Exception) { toast(s(R.string.no_file_picker)) }
        }
        action(s(R.string.backup_restore), s(R.string.backup_restore_sum)) { pick(REQ_RESTORE, "*/*") }
        action(s(R.string.reset_all), s(R.string.reset_all_sum)) {
            AlertDialog.Builder(this, Ui.DIALOG).setMessage(R.string.reset_all_q).setPositiveButton(R.string.reset) { _, _ ->
                p.sp.edit().clear().commit(); restartApp()
            }.setNegativeButton(R.string.cancel, null).show()
        }
    }

    private fun aboutScreen() {
        val credit = ui.card().apply { gravity = Gravity.CENTER_HORIZONTAL }
        credit.addView(ui.icon(R.mipmap.ic_launcher, 16f, 0).apply { imageTintList = null })
        credit.addView(ui.text("WatchLite", 7.5f, Ui.TEXT, 2).apply { gravity = Gravity.CENTER }, ui.lp(top = 1.5f))
        credit.addView(ui.text("v${BuildConfig.VERSION_NAME}", 4.6f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, ui.lp(top = 0.5f))
        credit.addView(ui.text(s(R.string.developed_by), 5.4f, ui.accent, 2, 2).apply { gravity = Gravity.CENTER }, ui.lp(top = 2.5f))
        list.addView(credit, ui.lp(bottom = 2f))
        row(s(R.string.screen), "${ui.sp}")
        row(s(R.string.memory), memoryInfo())
        action(s(R.string.input_probe), s(R.string.input_probe_sum)) { startActivity(Intent(this, InputProbeActivity::class.java)) }
        action(s(R.string.show_log)) {
            val t = (CrashGuard.lastCrash(this)?.let { "LAST CRASH:\n$it\n\n" } ?: "") + Log.dump()
            AlertDialog.Builder(this, Ui.DIALOG).setMessage(t.takeLast(6000)).setPositiveButton(android.R.string.ok, null).show()
        }
        action(s(R.string.export_log)) {
            val f = java.io.File(getExternalFilesDir(null), "watchlite-log.txt")
            f.writeText((CrashGuard.lastCrash(this) ?: "") + "\n" + Log.dump())
            toast(f.absolutePath)
        }
        switch(s(R.string.root_features), s(R.string.root_features_sum), { p.rootFeatures }) { p.rootFeatures = it; refresh() }
        if (p.rootFeatures) {
            action(s(R.string.reboot)) { if (!SystemActions.rootExec("reboot")) toast(s(R.string.root_failed)) }
            action(s(R.string.power_off)) { if (!SystemActions.rootExec("reboot -p")) toast(s(R.string.root_failed)) }
        }
        row(s(R.string.licenses), s(R.string.licenses_sum))
    }

    private fun memoryInfo(): String {
        val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo(); am.getMemoryInfo(mi)
        val pss = am.getProcessMemoryInfo(intArrayOf(Process.myPid()))[0].totalPss / 1024
        return "App: ${pss} MB · RAM: ${mi.availMem / 1048576}/${mi.totalMem / 1048576} MB"
    }

    // ------------------------------------------------------------------ helpers
    private fun toast(t: String) = android.widget.Toast.makeText(this, t, android.widget.Toast.LENGTH_SHORT).show()

    private fun pick(req: Int, mime: String) {
        try { startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime), req) }
        catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(mime), req) } catch (_: Exception) { toast(s(R.string.no_file_picker)) }
        }
    }

    private fun restartApp() {
        startActivity(Intent(this, HomeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        Bg.main.postDelayed({ Process.killProcess(Process.myPid()) }, 300)
    }

    private fun displayName(uri: Uri): String = try {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        } ?: "sound"
    } catch (_: Exception) { "sound" }

    @SuppressLint("MissingPermission")
    private fun requestGps() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), REQ_LOCATION); return
        }
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER).mapNotNull {
            try { lm.getLastKnownLocation(it) } catch (_: Exception) { null }
        }.maxByOrNull { it.time }
        if (last != null && System.currentTimeMillis() - last.time < 6 * 3600_000L) { setLocation(last); return }
        toast(s(R.string.locating))
        try {
            @Suppress("DEPRECATION")
            lm.requestSingleUpdate(LocationManager.GPS_PROVIDER, object : LocationListener {
                override fun onLocationChanged(l: Location) = setLocation(l)
                @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) { toast(s(R.string.gps_off)) }
            }, mainLooper)
        } catch (e: Exception) { toast(s(R.string.gps_off)) }
    }

    private fun setLocation(l: Location) {
        p.latitude = l.latitude.toFloat(); p.longitude = l.longitude.toFloat(); p.locationMode = "gps"; p.cityName = "GPS"
        toast(s(R.string.location_updated)); refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) requestGps()
        refresh()
    }

    @Deprecated("framework")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        Bg.run {
            val msg = try {
                when (requestCode) {
                    REQ_SOUND -> { val id = ChimeSounds.import(this, uri, displayName(uri)); p.chimeSound = id; s(R.string.sound_added) }
                    REQ_EXPORT -> { BackupManager.export(this, uri); s(R.string.backup_done) }
                    REQ_RESTORE -> { BackupManager.restore(this, uri); s(R.string.restore_done) }
                    REQ_THEME -> { val t = App.I.themes.importPackage(uri); App.I.themes.select(t.id); getString(R.string.theme_imported, t.name()) }
                    else -> null
                }
            } catch (e: Throwable) { Log.w("Settings", "result $requestCode", e); getString(R.string.failed, e.message ?: "") }
            Bg.ui {
                msg?.let { toast(it) }
                if (requestCode == REQ_RESTORE) restartApp() else refresh()
            }
        }
    }

}
