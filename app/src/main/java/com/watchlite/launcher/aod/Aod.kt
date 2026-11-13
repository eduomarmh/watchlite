package com.watchlite.launcher.aod

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.watchlite.launcher.App
import com.watchlite.launcher.face.FaceRenderer
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.theme.ThemeCustom
import com.watchlite.launcher.theme.ThemeRepository
import com.watchlite.launcher.util.Log
import java.util.Calendar
import kotlin.random.Random

/**
 * Battery policy with hysteresis (pure logic, unit tested).
 * Below [min] (and not charging-allowed) AOD is blocked; it is allowed again at min + 2.
 */
class AodPolicy {
    var allowed = true
        private set

    fun update(level: Int, charging: Boolean, min: Int, allowWhileCharging: Boolean): Boolean {
        allowed = when {
            charging && allowWhileCharging -> true
            level < min -> false
            level >= min + HYST -> true
            else -> allowed
        }
        return allowed
    }

    companion object { const val HYST = 2 }
}

/** Minimal clock drawn on black, refreshed once per minute. */
class AodView(ctx: Context, private val renderer: FaceRenderer) : View(ctx) {
    private val cal = Calendar.getInstance()
    private var shiftX = 0f
    private var shiftY = 0f
    private val rnd = Random(System.nanoTime())

    init { setBackgroundColor(0xFF000000.toInt()) }

    fun tick() {
        if (App.prefs.aodPixelShift) {
            val m = minOf(width, height) * 0.015f
            shiftX = (rnd.nextFloat() * 2 - 1) * m
            shiftY = (rnd.nextFloat() * 2 - 1) * m
        }
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val theme = App.I.themes.get(App.I.themes.effectiveId(App.I.state.charging)) ?: App.I.themes.default()
        cal.timeInMillis = System.currentTimeMillis()
        c.save(); c.translate(shiftX, shiftY)
        renderer.draw(c, theme, width, height, cal, cal.timeInMillis, ThemeCustom(null, null, false), aod = true, aodAlpha = App.prefs.aodOpacity)
        renderer.drawAodWidgets(c, width, height, cal, App.prefs.aodOpacity)
        c.restore()
    }
}

/**
 * Enters/leaves AOD. Brightness is applied ONLY to our window
 * (WindowManager.LayoutParams.screenBrightness); the system brightness setting
 * is never touched, so it can't drift.
 */
class AodController(private val host: HomeActivity) : SensorEventListener {

    val policy get() = AodGate.policy
    var active = false
        private set
    private var anim: ValueAnimator? = null
    val view = AodView(host, FaceRenderer(host)).apply { visibility = View.GONE }

    private val gestures = GestureDetector(host, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onDoubleTap(e: MotionEvent): Boolean { exit(); return true }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean { if (App.prefs.aodWake == "single") exit(); return true }
    })

    fun onTouch(ev: MotionEvent): Boolean { gestures.onTouchEvent(ev); return true }

    /** Whether AOD may start now (setting, battery, schedule, theater, saver). */
    fun canEnter(): Boolean = AodGate.canEnter()

    fun enter() {
        if (active) return
        active = true
        AodGate.homeAodActive = true
        Log.i("AOD", "enter")
        val win = host.window
        val start = currentBrightness()
        val target = App.prefs.aodBrightness.coerceIn(0.004f, 0.6f)
        anim?.cancel()
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            addUpdateListener {
                val f = it.animatedValue as Float
                setBrightness(start + (target - start) * f)
                host.face.alpha = 1f - f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(a: android.animation.Animator) {
                    if (!active) return
                    view.visibility = View.VISIBLE
                    view.tick()
                    host.face.pause()
                    host.face.releaseHeavy()
                    host.face.alpha = 1f
                    host.face.visibility = View.INVISIBLE
                    win.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    if (App.prefs.aodRaise) registerRaise()
                }
            })
            start()
        }
    }

    fun exit(screenGoingOff: Boolean = false) {
        if (!active) return
        active = false
        AodGate.homeAodActive = false
        AodGate.lastAodVisible = System.currentTimeMillis()
        anim?.cancel()
        unregisterRaise()
        Log.i("AOD", "exit")
        view.visibility = View.GONE
        host.face.alpha = 1f
        host.face.visibility = View.VISIBLE
        setBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        if (!screenGoingOff) { host.face.resume(); host.resetIdle() }
    }

    fun minuteTick() {
        if (!active) return
        // battery may have dropped below the threshold while in AOD
        if (!canEnter()) { exit(true); host.screenOff(force = true); return }
        view.tick()
    }

    private fun currentBrightness(): Float {
        val wb = host.window.attributes.screenBrightness
        if (wb >= 0f) return wb
        return (com.watchlite.launcher.system.SystemActions.systemBrightness(host) / 255f).coerceIn(0.05f, 1f)
    }

    private fun setBrightness(v: Float) {
        val lp = host.window.attributes
        lp.screenBrightness = v
        host.window.attributes = lp
    }

    // ------------------------------------------------------------ raise to wake
    private var sm: SensorManager? = null
    private var lowSince = 0L
    private var wasLow = false

    private fun registerRaise() {
        sm = host.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val acc = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
        sm?.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL)
    }

    private fun unregisterRaise() { sm?.unregisterListener(this); sm = null }

    /** Raise heuristic: watch face goes from tilted/hanging (z small) to facing up (z high) quickly. */
    override fun onSensorChanged(e: SensorEvent) {
        val z = e.values[2]
        val now = System.currentTimeMillis()
        if (z < 3.5f) { wasLow = true; lowSince = now }
        else if (z > 8.0f && wasLow && now - lowSince < 900) { wasLow = false; host.runOnUiThread { exit() } }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
