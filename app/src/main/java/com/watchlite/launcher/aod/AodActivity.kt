package com.watchlite.launcher.aod

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.GestureDetector
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.watchlite.launcher.App
import com.watchlite.launcher.face.FaceRenderer
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.theme.ThemeRepository
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Log
import java.util.Calendar

/** Shared AOD rules + the "screen went off inside another app" trigger. */
object AodGate {
    val policy = AodPolicy()
    /** Last moment an AOD screen was showing: a screen-off right after it means "turn off for real". */
    @Volatile var lastAodVisible = 0L
    @Volatile var homeResumed = false
    @Volatile var homeAodActive = false

    fun powerSaving(): Boolean {
        val p = App.prefs; val st = App.I.state
        return p.saverManual || (p.saverAuto && st.battery <= p.saverThreshold && !st.charging)
    }

    fun canEnter(): Boolean {
        val p = App.prefs; val st = App.I.state
        if (!p.aodEnabled || p.theater || powerSaving()) return false
        if (!policy.update(st.battery, st.charging, p.aodBatteryMin, p.aodWhileCharging)) return false
        if (p.aodNightOff) {
            val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            if (ThemeRepository.inRange(h, p.aodNightFrom, p.aodNightTo)) return false
        }
        return true
    }

    private val main = Handler(Looper.getMainLooper())

    /** Turns the screen back on: window flags alone are ignored by some Android 8 builds. */
    @Suppress("DEPRECATION")
    fun wake(c: Context) {
        try {
            val pm = c.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.newWakeLock(android.os.PowerManager.SCREEN_DIM_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP or
                android.os.PowerManager.ON_AFTER_RELEASE, "watchlite:aod").acquire(3000)
        } catch (t: Throwable) { Log.w("AOD", "wake", t) }
    }

    /** Registered once by the app: when the screen turns off in any app, bring up the AOD screen. */
    fun register(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (homeAodActive || AodActivity.showing || System.currentTimeMillis() - lastAodVisible < 1500) return   // power button pressed while in AOD: stay off
                if (!App.prefs.aodEverywhere || !canEnter()) return
                main.postDelayed({
                    wake(c)
                    try {
                        c.startActivity(Intent(c, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
                    } catch (t: Throwable) { Log.w("AOD", "start", t) }
                }, 350)
            }
        }, IntentFilter(Intent.ACTION_SCREEN_OFF))
    }
}

/**
 * Stand-alone always-on screen: turns the screen back on (dim) above whatever app was open.
 * Double tap / any button returns to that app. Its brightness is per-window only.
 */
class AodActivity : Activity() {

    companion object { @Volatile var showing = false }

    private lateinit var view: AodView
    private val tick = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!AodGate.canEnter()) { finish(); return }
            view.tick()
        }
    }
    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { AodGate.lastAodVisible = System.currentTimeMillis(); finish() }
    }

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    @Suppress("DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN or
            WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD)
        val lp = window.attributes
        lp.screenBrightness = App.prefs.aodBrightness.coerceIn(0.004f, 0.6f)
        window.attributes = lp
        view = AodView(this, FaceRenderer(this))
        view.visibility = View.VISIBLE
        setContentView(view)
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION)
        registerReceiver(tick, IntentFilter(Intent.ACTION_TIME_TICK))
        registerReceiver(screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF))
        view.post { view.tick() }
    }

    override fun onResume() { super.onResume(); showing = true; AodGate.lastAodVisible = System.currentTimeMillis() }
    override fun onPause() { AodGate.lastAodVisible = System.currentTimeMillis(); showing = false; super.onPause() }

    override fun onDestroy() {
        try { unregisterReceiver(tick); unregisterReceiver(screenOff) } catch (_: Throwable) { }
        super.onDestroy()
    }

    private val gestures by lazy {
        GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onDoubleTap(e: MotionEvent): Boolean { wake(); return true }
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (App.prefs.aodWake == "single") wake(); return true }
        })
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { gestures.onTouchEvent(ev); return true }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode != KeyEvent.KEYCODE_POWER) wake()
        return event.keyCode != KeyEvent.KEYCODE_POWER || super.dispatchKeyEvent(event)
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean { wake(); return true }

    /** Back to the app that was open (or the watch face if it was the launcher). */
    private fun wake() {
        val lp = window.attributes; lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE; window.attributes = lp
        finish()
        overridePendingTransition(0, 0)
    }

    @Deprecated("framework") override fun onBackPressed() { wake() }

    @Suppress("unused") private fun home() = startActivity(Intent(this, HomeActivity::class.java))
}
