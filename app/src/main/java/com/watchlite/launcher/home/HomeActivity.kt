package com.watchlite.launcher.home

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.BatteryManager
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognizerIntent
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.aod.AodController
import com.watchlite.launcher.control.ControlPage
import com.watchlite.launcher.drawer.DrawerPage
import com.watchlite.launcher.face.WatchFaceView
import com.watchlite.launcher.input.InputMapper
import com.watchlite.launcher.notifications.NotificationsPage
import com.watchlite.launcher.safety.CrashGuard
import com.watchlite.launcher.safety.SafeModeActivity
import com.watchlite.launcher.settings.SettingsActivity
import com.watchlite.launcher.system.SystemActions
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Log
import com.watchlite.launcher.util.Ui
import com.watchlite.launcher.widgets.WidgetsPage
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sin

class HomeActivity : Activity() {

    companion object {
        const val REQ_VOICE = 1
        const val REQ_PHOTO = 2
        const val REQ_THEME = 3
    }

    lateinit var ui: Ui
    lateinit var face: WatchFaceView
    private lateinit var root: FrameLayout
    private lateinit var aod: AodController
    private lateinit var editor: FaceEditor
    private var drawer: DrawerPage? = null
    private var notifs: NotificationsPage? = null
    private var control: ControlPage? = null
    private var widgets: WidgetsPage? = null
    private val pages get() = listOfNotNull(drawer, notifs, control, widgets)

    private var lockOverlay: View? = null
    private var waterLock = false
    private var curtain: View? = null
    private var flash: View? = null
    private var resumed = false
    private var hasFocus = false
    var powerSaving = false
        private set

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    // ------------------------------------------------------------------ lifecycle
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (CrashGuard.isLooping(this)) {
            startActivity(Intent(this, SafeModeActivity::class.java)); finish(); return
        }
        ui = Ui(this)
        root = FrameLayout(this).apply { setBackgroundColor(Ui.BG) }
        face = WatchFaceView(this)
        root.addView(face, ui.frameLp())
        editor = FaceEditor(this)
        aod = AodController(this)
        root.addView(editor, ui.frameLp())
        root.addView(aod.view, ui.frameLp())
        setContentView(root)
        face.setOnTouchListener { _, ev -> faceTouch(ev) }

        App.I.apps.iconSize = (ui.sp.shortSide * 0.24f).toInt()
        App.I.notifications.onNew = { showBanner(it) }
        App.I.themes.listen(themeListener)
        App.I.state.listen(stateListener)
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        })
        applyPowerState()
        applyTheme()
        if (!App.prefs.onboarded) showOnboarding()
        Log.i("Home", "screen ${ui.sp}")
    }

    override fun onStart() {
        super.onStart()
        registerReceiver(tickReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK); addAction(Intent.ACTION_TIME_CHANGED); addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
    }

    override fun onStop() {
        try { unregisterReceiver(tickReceiver) } catch (_: Exception) { }
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        com.watchlite.launcher.aod.AodGate.homeResumed = true
        CrashGuard.markHealthy()
        com.watchlite.launcher.system.Permissions.autoEnable(this)
        refreshUiMode()
        if (!aod.active && !editor.isOpen && pages.none { it.isOpen }) face.resume()
        App.I.steps.start()
        applyAodSetting()
        immersive()
    }

    override fun onPause() {
        resumed = false
        com.watchlite.launcher.aod.AodGate.homeResumed = false
        // another app came to the front while the face was in AOD: leave AOD so the
        // stand-alone AOD screen can take over when that app's screen turns off
        if (aod.active) aod.exit(screenGoingOff = true)
        face.pause()
        App.I.steps.stop()
        cancelIdle()
        super.onPause()
    }

    override fun onDestroy() {
        if (::ui.isInitialized) {
            App.I.themes.unlisten(themeListener)
            App.I.state.unlisten(stateListener)
            try { unregisterReceiver(batteryReceiver); unregisterReceiver(screenReceiver) } catch (_: Exception) { }
        }
        super.onDestroy()
    }

    override fun onWindowFocusChanged(focus: Boolean) {
        super.onWindowFocusChanged(focus)
        hasFocus = focus
        if (focus) immersive()
    }

    @Suppress("DEPRECATION")
    private fun immersive() {
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }

    // ------------------------------------------------------------------ theme / state
    private val themeListener = { applyTheme() }
    private val stateListener = { if (!face.isAnimatingFrames()) face.invalidate() }

    fun applyTheme() {
        val repo = App.I.themes
        val t = repo.get(repo.effectiveId(App.I.state.charging)) ?: repo.default()
        if (face.theme?.id != t.id) face.setTheme(t) else face.refreshCustom()
    }

    fun applyPowerState() {
        val p = App.prefs; val st = App.I.state
        val saving = p.saverManual || (p.saverAuto && st.battery <= p.saverThreshold && !st.charging)
        if (saving != powerSaving) Log.i("Home", "power saving $saving")
        powerSaving = saving
        face.lowPower = saving
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, 100)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val status = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val st = App.I.state
            val newLevel = level * 100 / scale
            val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
            val changedCharging = charging != st.charging
            st.battery = newLevel; st.charging = charging
            aod.policy.update(newLevel, charging, App.prefs.aodBatteryMin, App.prefs.aodWhileCharging)
            applyPowerState()
            if (changedCharging) applyTheme()
            if (!aod.policy.allowed && aod.active) { aod.exit(true); screenOff(force = true) }
            val now = System.currentTimeMillis()
            if (st.linkConnected && now - lastStatsSent > 120_000) { lastStatsSent = now; Bg.run { com.watchlite.launcher.link.LinkManager.sendStats() } }
            st.changed()
        }
    }

    private var lastStatsSent = 0L

    // ------------------------------------------------------------------ notification banner
    private var banner: View? = null
    private val hideBanner = Runnable { dismissBanner() }

    /** Heads-up card sliding from the top for new notifications (phone or watch). */
    private fun showBanner(n: com.watchlite.launcher.notifications.NotificationItem) {
        val p = App.prefs
        if (p.theater || SystemActions.dndOn(this)) return
        if (p.notifVibrate) Haptics.pattern(this, longArrayOf(0, 70, 80, 70))
        if (p.notifWake) wakeScreen()
        if (!p.notifBanner || !resumed || pages.any { it.isOpen && it.side == Page.FROM_TOP }) return
        if (aod.active) aod.exit()
        dismissBanner(immediate = true)
        val c = ui.card()
        c.background = ui.ripple(ui.rounded(Ui.CARD, 6f * ui.u))
        val head = ui.hbox()
        head.addView(android.widget.ImageView(this).apply {
            if (n.icon != null) setImageBitmap(n.icon) else setImageResource(R.drawable.ic_notifications)
        }, android.widget.LinearLayout.LayoutParams(ui.px(6f), ui.px(6f)))
        head.addView(ui.text(n.appLabel + if (n.source == com.watchlite.launcher.notifications.NotificationItem.PHONE) "  ·  " + getString(R.string.phone) else "",
            4.4f, Ui.TEXT2), ui.lp(0, start = 2f, weight = 1f))
        c.addView(head)
        if (n.title.isNotEmpty()) c.addView(ui.text(n.title, 5.6f, Ui.TEXT, 2, 1), ui.lp(top = 1f))
        if (n.text.isNotEmpty()) c.addView(ui.text(n.text, 5f, Ui.BODY, 1, 2), ui.lp(top = 0.5f))
        c.setOnClickListener { dismissBanner(true); openPage(Page.FROM_TOP) }
        val lp = ui.frameLp(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            leftMargin = ui.sp.safe / 2 + ui.px(2f); rightMargin = leftMargin; topMargin = ui.px(if (ui.sp.isRound) 8f else 3f)
        }
        root.addView(c, lp)
        banner = c
        c.translationY = -ui.sp.height * 0.4f
        c.animate().translationY(0f).setDuration(220).start()
        root.removeCallbacks(hideBanner); root.postDelayed(hideBanner, 4500)
    }

    private fun dismissBanner(immediate: Boolean = false) {
        val b = banner ?: return
        banner = null
        root.removeCallbacks(hideBanner)
        if (immediate) root.removeView(b)
        else b.animate().translationY(-b.height.toFloat() - ui.px(10f)).setDuration(180).withEndAction { root.removeView(b) }.start()
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (pm.isInteractive) return
            pm.newWakeLock(android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP, "watchlite:notif")
                .acquire(5000)
        } catch (_: Throwable) { }
    }

    private val tickReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (aod.active) aod.minuteTick()
            else if (!face.isAnimatingFrames()) face.invalidate()
            if (i.action == Intent.ACTION_TIME_TICK) {
                refreshUiMode()
                // backup for firmwares that drop exact alarms
                if (com.watchlite.launcher.chime.ChimeScheduler.dueNow()) com.watchlite.launcher.chime.ChimeScheduler.ring(this@HomeActivity)
                val repo = App.I.themes
                if (face.theme?.id != repo.effectiveId(App.I.state.charging)) applyTheme()
            } else {
                com.watchlite.launcher.chime.ChimeScheduler.schedule(this@HomeActivity)
            }
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            when (i.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    if (aod.active) aod.exit(true)
                    hideCurtain()
                    if (flash != null) toggleFlashlight()
                    goHome(animate = false)
                    face.pause()
                    App.I.steps.stop()
                }
                else -> if (resumed) {
                    face.resume(); App.I.steps.start(); applyAodSetting(); resetIdle()
                }
            }
        }
    }

    // ------------------------------------------------------------------ pages
    private fun page(side: Int): Page {
        val p: Page = when (side) {
            Page.FROM_RIGHT -> drawer ?: DrawerPage(this).also { drawer = it }
            Page.FROM_LEFT -> control ?: ControlPage(this).also { control = it }
            Page.FROM_TOP -> notifs ?: NotificationsPage(this).also { notifs = it }
            else -> widgets ?: WidgetsPage(this).also { widgets = it }
        }
        if (p.parent == null) root.addView(p, root.indexOfChild(editor), ui.frameLp())
        return p
    }

    fun onPageProgress(p: Page, progress: Float) {
        // when any page fully covers the face, stop drawing the face
        if (pages.any { it.isOpen }) face.pause()
        else if (resumed && !aod.active && !editor.isOpen) face.resume()
        face.alpha = 1f - 0.6f * pages.maxOf { it.progress }
    }

    private var builtLight: Boolean? = null

    /** Rebuilds the pages when light/dark mode changed (pages are created lazily again). */
    fun refreshUiMode() {
        val l = Ui.light
        if (builtLight == null) { builtLight = l; return }
        if (l == builtLight || pages.any { it.isPresent }) return
        builtLight = l
        for (p in pages) root.removeView(p)
        drawer = null; notifs = null; control = null; widgets = null
    }

    fun goHome(animate: Boolean = true) {
        if (editor.isOpen) editor.close()
        for (p in pages) if (p.isPresent) { if (animate) p.close() else p.setProgress(0f) }
    }

    fun openPage(side: Int) { goHome(false); page(side).open() }

    // ------------------------------------------------------------------ face gestures
    private val slop by lazy { ViewConfiguration.get(this).scaledTouchSlop * 1.5f }
    private val minFling by lazy { ViewConfiguration.get(this).scaledMinimumFlingVelocity * 3f }
    private var gDownX = 0f
    private var gDownY = 0f
    private var gPage: Page? = null
    private var gTracking = false
    private var vt: VelocityTracker? = null
    private val longPress = Runnable {
        if (gTracking && gPage == null) { Haptics.tick(this, true); gTracking = false; editor.open() }
    }

    private fun faceTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                gDownX = ev.rawX; gDownY = ev.rawY; gPage = null; gTracking = true
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(ev)
                face.postDelayed(longPress, 550)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!gTracking) return true
                vt?.addMovement(ev)
                val dx = ev.rawX - gDownX; val dy = ev.rawY - gDownY
                if (gPage == null && hypot(dx, dy) > slop) {
                    face.removeCallbacks(longPress)
                    val side = if (abs(dx) > abs(dy)) (if (dx < 0) Page.FROM_RIGHT else Page.FROM_LEFT)
                    else (if (dy > 0) Page.FROM_TOP else Page.FROM_BOTTOM)
                    if (side == Page.FROM_TOP && useSystemShade()) {
                        gTracking = false
                        if (SystemActions.expandSystemNotifications(this)) { Haptics.tick(this); return true }
                        gTracking = true
                    }
                    gPage = page(side).also { it.onOpening() }
                }
                gPage?.let { p ->
                    val d = when (p.side) { Page.FROM_RIGHT -> -dx; Page.FROM_LEFT -> dx; Page.FROM_TOP -> dy; else -> -dy }
                    val ext = if (p.side <= Page.FROM_LEFT) ui.sp.width else ui.sp.height
                    p.setProgress(((d - slop) / ext).coerceIn(0f, 1f))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                face.removeCallbacks(longPress)
                gPage?.let { p ->
                    vt?.computeCurrentVelocity(1000)
                    val v = when (p.side) {
                        Page.FROM_RIGHT -> -(vt?.xVelocity ?: 0f); Page.FROM_LEFT -> vt?.xVelocity ?: 0f
                        Page.FROM_TOP -> vt?.yVelocity ?: 0f; else -> -(vt?.yVelocity ?: 0f)
                    }
                    if (p.progress > 0.3f || v > minFling) { p.animateTo(1f); Haptics.tick(this) } else p.animateTo(0f)
                } ?: run {
                    if (gTracking && ev.actionMasked == MotionEvent.ACTION_UP) onFaceTap(ev.x, ev.y)
                }
                gPage = null; gTracking = false
                vt?.recycle(); vt = null
            }
        }
        return true
    }

    /** Swipe down → the system notification shade when the launcher can't read notifications itself. */
    fun useSystemShade(): Boolean = when (App.prefs.swipeDown) {
        "system" -> true
        "launcher" -> false
        else -> !com.watchlite.launcher.notifications.NotifListener.hasAccess(this) && App.I.notifications.count() == 0
    }

    /** Tapping a complication opens its details. */
    private fun onFaceTap(x: Float, y: Float) {
        val slot = face.renderer.hitTest(x, y) ?: return
        Haptics.tick(this)
        when (slot) {
            "battery" -> openPage(Page.FROM_LEFT)
            "notif" -> if (useSystemShade() && SystemActions.expandSystemNotifications(this)) Unit else openPage(Page.FROM_TOP)
            else -> openPage(Page.FROM_BOTTOM) // steps, prayer, hijri, weather, phone → widgets
        }
    }

    // ------------------------------------------------------------------ touch routing
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) resetIdle()
        curtain?.let { if (ev.actionMasked == MotionEvent.ACTION_DOWN) hideCurtain(); return true }
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && com.watchlite.launcher.link.LinkManager.isFinding())
            com.watchlite.launcher.link.LinkManager.findWatch(false)
        if (aod.active) return aod.onTouch(ev)
        if (lockOverlay != null) return true
        return super.dispatchTouchEvent(ev)
    }

    // ------------------------------------------------------------------ hardware keys
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (Intent.ACTION_MAIN != intent.action) return
        val alreadyOnHome = hasFocus && (intent.flags and Intent.FLAG_ACTIVITY_BROUGHT_TO_FRONT) == 0
        if (alreadyOnHome) homePressedOnHome() else { goHome(false); hideCurtain() }
    }

    private fun homePressedOnHome() {
        resetIdle()
        when {
            lockOverlay != null -> Haptics.tick(this)
            curtain != null -> hideCurtain()
            aod.active -> aod.exit()
            flash != null -> toggleFlashlight()
            editor.isOpen || pages.any { it.isPresent } -> goHome()
            else -> {
                if (aod.canEnter()) aod.enter() else screenOff()
            }
        }
    }

    /** Turns the screen off: Device-Admin lock if granted, otherwise a black curtain until the system timeout. */
    fun screenOff(force: Boolean = false) {
        if (aod.active && !force) return
        if (SystemActions.lockScreen(this)) return
        showCurtain()
        if (!warnedAdmin) { warnedAdmin = true; toast(getString(R.string.enable_admin_hint)) }
    }
    private var warnedAdmin = false

    private fun showCurtain() {
        if (curtain != null) return
        curtain = View(this).apply { setBackgroundColor(0xFF000000.toInt()) }
        root.addView(curtain, ui.frameLp())
        val lp = window.attributes; lp.screenBrightness = 0f; window.attributes = lp
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        face.pause()
    }

    private fun hideCurtain() {
        val c = curtain ?: return
        root.removeView(c); curtain = null
        val lp = window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = lp
        if (resumed) face.resume()
        applyAodSetting()
    }

    private var lastBack = 0L
    private var backLongFired = false

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val d = InputMapper.fromKey(event)
        if (d != 0) {
            if (event.action == KeyEvent.ACTION_DOWN) onWheel(d)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        val d = InputMapper.fromMotion(event)
        if (d != 0) { onWheel(d); return true }
        return super.onGenericMotionEvent(event)
    }

    private var lockWheel = 0
    private fun onWheel(d: Int) {
        resetIdle()
        when {
            curtain != null -> hideCurtain()
            aod.active -> aod.exit()
            lockOverlay != null -> { lockWheel += abs(d); if (lockWheel >= 4) touchUnlock() else Haptics.tick(this) }
            editor.isOpen -> editor.onWheel(d)
            else -> {
                val open = pages.firstOrNull { it.isOpen }
                if (open != null) { open.onWheel(d); Haptics.tick(this) }
                else if (App.prefs.wheelOnFace == "themes") {
                    val all = App.I.themes.all(); val i = all.indexOfFirst { it.id == face.theme?.id }
                    val next = all[((i + d) % all.size + all.size) % all.size]
                    App.I.themes.select(next.id); Haptics.tick(this)
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.repeatCount == 0) { event.startTracking(); backLongFired = false }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            backLongFired = true
            Haptics.tick(this, true)
            buttonAction(App.prefs.backLong)
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (event.isTracking && !event.isCanceled && !backLongFired) back()
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    @Deprecated("handled in onKeyUp")
    override fun onBackPressed() { back() }

    private fun back() {
        resetIdle()
        val now = System.currentTimeMillis()
        val double = now - lastBack < 380
        lastBack = now
        when {
            curtain != null -> hideCurtain()
            aod.active -> aod.exit()
            lockOverlay != null -> if (double) touchUnlock() else Haptics.tick(this)
            flash != null -> toggleFlashlight()
            editor.isOpen -> editor.close()
            pages.any { it.isPresent } -> goHome()
            double -> { lastBack = 0; buttonAction(App.prefs.backDouble) }
        }
    }

    fun buttonAction(a: String) {
        when (a) {
            "flashlight" -> toggleFlashlight()
            "aod" -> { App.prefs.aodEnabled = !App.prefs.aodEnabled; applyAodSetting()
                toast(getString(if (App.prefs.aodEnabled) R.string.aod_on else R.string.aod_off)) }
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "drawer" -> openPage(Page.FROM_RIGHT)
            "notifications" -> openPage(Page.FROM_TOP)
            "control" -> openPage(Page.FROM_LEFT)
            "widgets" -> openPage(Page.FROM_BOTTOM)
            "themes" -> { goHome(false); editor.open() }
            "lock" -> { goHome(false); screenOff() }
            "theater" -> { App.prefs.theater = !App.prefs.theater; if (App.prefs.theater) screenOff() }
        }
    }

    // ------------------------------------------------------------------ AOD / idle
    private val idle = Runnable { onIdle() }

    /** AOD idle delay in ms. -1 = automatic: a few seconds before the system screen timeout. */
    private fun idleDelay(): Long {
        val s = App.prefs.aodIdleSec
        if (s > 0) return s * 1000L
        if (s == 0) return -1
        val sys = try { Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) } catch (_: Exception) { 15000 }
        return (sys - 3000L).coerceIn(5000L, 60_000L)
    }

    fun applyAodSetting() {
        if (!aod.canEnter() && aod.active) aod.exit()
        if (App.prefs.aodEnabled && idleDelay() > 0 && resumed) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            resetIdle()
        } else if (!aod.active) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            cancelIdle()
        }
    }

    fun resetIdle() {
        root.removeCallbacks(idle)
        if (flash != null) return
        val d = idleDelay()
        if (App.prefs.aodEnabled && d > 0 && resumed && !aod.active && curtain == null) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            root.postDelayed(idle, d)
        }
    }

    fun cancelIdle() { if (::root.isInitialized) root.removeCallbacks(idle) }

    private fun onIdle() {
        if (lockOverlay != null || flash != null) { resetIdle(); return }
        if (aod.canEnter()) { goHome(false); aod.enter() }
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) // let the system time out normally
    }

    // ------------------------------------------------------------------ flashlight
    /**
     * LED torch when the watch has one; otherwise (or if it fails) the whole screen turns
     * pure white at maximum brightness and stays on until you tap anywhere.
     */
    fun toggleFlashlight() {
        if (flash != null) {
            root.removeView(flash); flash = null
            val lp = window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = lp
            applyAodSetting()
            if (resumed) face.resume()
            return
        }
        if (App.prefs.flashMode != "screen" && (SystemActions.torchState() || SystemActions.hasTorch(this))) {
            if (SystemActions.setTorch(this, !SystemActions.torchState())) return
        }
        goHome(false)
        val v = FrameLayout(this).apply {
            setBackgroundColor(0xFFFFFFFF.toInt())
            isClickable = true
            setOnClickListener { Haptics.tick(this@HomeActivity); toggleFlashlight() }
        }
        v.addView(ui.text(getString(R.string.tap_to_turn_off), 4.2f, 0xFFBDBDBD.toInt()).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply { bottomMargin = ui.px(6f) })
        flash = v
        root.addView(v, ui.frameLp())
        face.pause()
        val lp = window.attributes; lp.screenBrightness = 1f; window.attributes = lp
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cancelIdle()
        Haptics.tick(this)
    }

    // ------------------------------------------------------------------ touch / water lock
    fun touchLock(water: Boolean) {
        if (lockOverlay != null) return
        waterLock = water; lockWheel = 0
        val v = FrameLayout(this).apply { setBackgroundColor(0x33000000) }
        v.addView(ui.icon(if (water) R.drawable.ic_water_drop else R.drawable.ic_lock, 9f, Ui.BLUE).apply {
            layoutParams = FrameLayout.LayoutParams(ui.px(9f), ui.px(9f), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = ui.px(6f) }
        })
        v.addView(ui.text(getString(R.string.unlock_hint), 4.4f, Ui.TEXT2, 1, 2).apply { gravity = Gravity.CENTER },
            FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM).apply {
                bottomMargin = ui.px(8f); leftMargin = ui.sp.safe; rightMargin = ui.sp.safe
            })
        root.addView(v, ui.frameLp())
        lockOverlay = v
        Haptics.tick(this, true)
    }

    private fun touchUnlock() {
        val v = lockOverlay ?: return
        root.removeView(v); lockOverlay = null
        Haptics.pattern(this, longArrayOf(0, 20, 60, 20))
        if (waterLock) ejectWater()
    }

    /** Plays a low tone for a few seconds to push water out of the speaker grill. */
    private fun ejectWater() {
        toast(getString(R.string.ejecting_water))
        Bg.run {
            val rate = 22050; val secs = 4
            val n = rate * secs
            val buf = ShortArray(n)
            for (i in 0 until n) {
                val env = minOf(1f, i / 2000f, (n - i) / 2000f)
                buf[i] = (sin(2 * Math.PI * 165 * i / rate) * 32000 * env).toInt().toShort()
            }
            val track = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                .setBufferSizeInBytes(n * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
            track.write(buf, 0, n)
            track.play()
            Thread.sleep(secs * 1000L + 200)
            track.release()
        }
    }

    // ------------------------------------------------------------------ helpers
    fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    fun askAccessibility() = com.watchlite.launcher.system.Permissions.help(this, com.watchlite.launcher.system.Permissions.Kind.ACCESSIBILITY)

    /** Power options that work without Accessibility. */
    fun powerSheet() {
        val items = ArrayList<Pair<String, () -> Unit>>()
        items += getString(R.string.lock_screen) to { goHome(false); screenOff() }
        if (App.prefs.rootFeatures) {
            items += getString(R.string.reboot) to { if (!SystemActions.rootExec("reboot")) toast(getString(R.string.root_failed)) }
            items += getString(R.string.power_off) to { if (!SystemActions.rootExec("reboot -p")) toast(getString(R.string.root_failed)) }
        }
        items += getString(R.string.a11y_label) to { askAccessibility() }
        AlertDialog.Builder(this, Ui.DIALOG).setTitle(R.string.tile_power).setMessage(R.string.power_hint)
            .setItems(items.map { it.first }.toTypedArray()) { _, i -> items[i].second() }
            .setNegativeButton(R.string.cancel, null).show()
    }

    fun pickDocument(req: Int, mime: String) {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType(mime), req)
        } catch (_: Exception) {
            try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType(mime), req) }
            catch (_: Exception) { toast(getString(R.string.no_file_picker)) }
        }
    }

    @Deprecated("framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION") super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_VOICE -> notifs?.onVoiceResult(data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull())
            REQ_THEME -> data?.data?.let { uri ->
                Bg.run {
                    try {
                        val t = App.I.themes.importPackage(uri)
                        Bg.ui { toast(getString(R.string.theme_imported, t.name())); editor.reloadThemes(t.id) }
                    } catch (e: Throwable) { Bg.ui { toast(getString(R.string.theme_import_failed, e.message ?: "")) } }
                }
            }
            REQ_PHOTO -> data?.data?.let { uri -> photoThemeDialog(uri) }
        }
    }

    private fun photoThemeDialog(uri: android.net.Uri) {
        val positions = arrayOf("top", "center", "bottom")
        val labels = arrayOf(getString(R.string.pos_top), getString(R.string.pos_center), getString(R.string.pos_bottom))
        AlertDialog.Builder(this, Ui.DIALOG).setTitle(R.string.clock_position).setItems(labels) { _, i ->
            val styles = arrayOf("bold", "light", "bebas")
            val sl = arrayOf(getString(R.string.style_bold), getString(R.string.style_light), getString(R.string.style_condensed))
            AlertDialog.Builder(this, Ui.DIALOG).setTitle(R.string.clock_style).setItems(sl) { _, k ->
                Bg.run {
                    try {
                        val t = App.I.themes.createFromImage(uri, positions[i], styles[k])
                        Bg.ui { App.I.themes.select(t.id); applyTheme(); editor.reloadThemes(t.id); toast(getString(R.string.theme_created)) }
                    } catch (e: Throwable) { Bg.ui { toast(getString(R.string.theme_import_failed, e.message ?: "")) } }
                }
            }.show()
        }.show()
    }

    private fun showOnboarding() {
        val c = ui.card()
        c.addView(ui.text(getString(R.string.welcome), 7f, Ui.TEXT, 2))
        c.addView(ui.text(getString(R.string.welcome_body), 4.8f, Ui.BODY, 1, 0), ui.lp(top = 2f))
        c.addView(ui.pill(getString(R.string.got_it), ui.accent, Ui.BG) {
            App.prefs.onboarded = true; root.removeView(c.parent as View)
        }, ui.lp(top = 3f))
        val wrap = android.widget.ScrollView(this).apply {
            setBackgroundColor((Ui.BG and 0x00FFFFFF) or 0xE6000000.toInt())
            setPadding(ui.sp.safe, ui.sp.safe + ui.px(4f), ui.sp.safe, ui.sp.safe)
            addView(c)
        }
        root.addView(wrap, ui.frameLp())
    }
}
