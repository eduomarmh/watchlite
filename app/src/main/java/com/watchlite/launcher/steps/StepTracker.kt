package com.watchlite.launcher.steps

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.IBinder
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.sqrt

/**
 * Daily steps with three sources:
 *  - "sensor": hardware TYPE_STEP_COUNTER (or STEP_DETECTOR). Cheapest; the chip counts on its own.
 *  - "motion": accelerometer pedometer (for watches whose step sensor is missing or fake).
 *  - "gps":    walked distance from GPS ÷ stride (outdoor only).
 * "auto" = sensor if the watch reports one and it actually produces events, otherwise motion.
 * Motion and GPS run in a small foreground service so they keep counting with the screen off.
 */
class StepTracker(private val ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    val counter: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    val detector: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
    val accel: Sensor? = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private var listening = false
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    @Volatile var lastEvent = 0L
        private set
    @Volatile var lastRaw = -1
        private set

    val available get() = counter != null || detector != null || accel != null || hasGps()

    private fun hasGps() = ctx.packageManager.hasSystemFeature("android.hardware.location.gps")

    /** Effective source right now. */
    fun source(): String = when (val m = App.prefs.stepsSource) {
        "sensor", "motion", "gps" -> m
        else -> when {
            (counter != null || detector != null) && !App.prefs.stepsSensorDead -> "sensor"
            accel != null -> "motion"
            else -> "gps"
        }
    }

    @Volatile var today = 0
        private set

    init {
        val p = App.prefs
        if (p.stepsDay == day()) today = p.stepsCarry + (if (p.stepsBase >= 0) (p.stepsLastRaw - p.stepsBase).coerceAtLeast(0) else 0)
    }

    private fun day() = dayFmt.format(Date())

    /** Called when the face is visible (sensor mode) and on app start (service modes). */
    fun start(force: Boolean = false) {
        when (source()) {
            "sensor" -> {
                ctx.stopService(Intent(ctx, StepService::class.java))
                if (listening) return
                val s = counter ?: detector ?: return
                listening = sm.registerListener(this, s, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000)
                watchdogStart = System.currentTimeMillis()
            }
            else -> if (force || !StepService.running) {
                try { ctx.startService(Intent(ctx, StepService::class.java)) } catch (t: Throwable) { Log.w("Steps", "service", t) }
            }
        }
    }

    fun stop() {
        if (!listening) return
        sm.unregisterListener(this); listening = false
    }

    fun restart() { stop(); start(force = true) }

    private var watchdogStart = 0L

    override fun onSensorChanged(e: SensorEvent) {
        lastEvent = System.currentTimeMillis()
        if (e.sensor.type == Sensor.TYPE_STEP_DETECTOR) addSteps(e.values.size.coerceAtLeast(1))
        else setRaw(e.values[0].toInt())
    }

    /** Hardware counter value (since boot). */
    fun setRaw(raw: Int) {
        lastRaw = raw
        today = compute(raw, day())
        App.I.state.changed()
    }

    /** For detector / motion / GPS sources: add counted steps. */
    @Synchronized
    fun addSteps(n: Int) {
        val p = App.prefs
        val d = day()
        if (p.stepsDay != d) { p.stepsDay = d; p.stepsBase = -1; p.stepsCarry = 0; p.stepsLastRaw = 0 }
        p.stepsCarry = p.stepsCarry + n
        today = p.stepsCarry + if (p.stepsBase >= 0) (p.stepsLastRaw - p.stepsBase).coerceAtLeast(0) else 0
        lastEvent = System.currentTimeMillis()
        App.I.state.changed()
    }

    /** Pure logic for the hardware counter (unit tested). */
    fun compute(raw: Int, day: String): Int {
        val p = App.prefs
        if (p.stepsDay != day) {
            p.stepsDay = day; p.stepsBase = raw; p.stepsCarry = 0
        } else if (raw < p.stepsLastRaw) {
            p.stepsCarry = p.stepsCarry + (p.stepsLastRaw - p.stepsBase)
            p.stepsBase = 0
        }
        if (p.stepsBase < 0) p.stepsBase = raw
        p.stepsLastRaw = raw
        return p.stepsCarry + (raw - p.stepsBase).coerceAtLeast(0)
    }

    /** If "auto" picked the hardware sensor but it never reports anything while you walk, switch to motion. */
    fun markSensorDead() { App.prefs.stepsSensorDead = true; restart() }

    fun resetToday() {
        val p = App.prefs
        p.stepsDay = day(); p.stepsBase = if (lastRaw >= 0) lastRaw else -1; p.stepsCarry = 0
        today = 0; App.I.state.changed()
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Background counting for "motion" (accelerometer pedometer, batched to let the CPU sleep)
 * and "gps" (distance ÷ stride). Low-importance ongoing notification as Android requires.
 */
class StepService : Service(), SensorEventListener, LocationListener {

    companion object { @Volatile var running = false }

    private lateinit var sm: SensorManager
    private var lm: LocationManager? = null
    private var mode = "motion"

    // pedometer state
    private var avg = 9.81f
    private var lastStep = 0L
    private var above = false
    private var pending = 0

    // gps state
    private var lastLoc: Location? = null
    private var meters = 0.0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("steps", getString(R.string.slot_steps), NotificationManager.IMPORTANCE_MIN))
        startForeground(52, Notification.Builder(this, "steps").setSmallIcon(R.drawable.ic_directions_walk)
            .setContentTitle(getString(R.string.steps_counting)).build())
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        stopAll()
        mode = App.I.steps.source()
        sm = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        when (mode) {
            "motion" -> App.I.steps.accel?.let {
                // 50 Hz sampling, delivered in 10 s batches when the chip has a FIFO: the CPU stays asleep in between
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, 10_000_000)
            }
            "gps" -> try {
                lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                lm?.requestLocationUpdates(LocationManager.GPS_PROVIDER, 4000L, 4f, this)
            } catch (t: Throwable) { Log.w("Steps", "gps", t) }
            else -> { stopSelf(); return START_NOT_STICKY }
        }
        Log.i("Steps", "service mode $mode")
        return START_STICKY
    }

    private fun stopAll() {
        if (::sm.isInitialized) sm.unregisterListener(this)
        try { lm?.removeUpdates(this) } catch (_: Throwable) { }
    }

    override fun onDestroy() { running = false; stopAll(); flush(); super.onDestroy() }

    override fun onSensorChanged(e: SensorEvent) {
        val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
        val mag = sqrt(x * x + y * y + z * z)
        avg = avg * 0.9f + mag * 0.1f                    // slow baseline (gravity)
        val dyn = mag - avg
        val t = e.timestamp / 1_000_000L                 // ms
        // a step = rise above threshold after being below, at a human cadence (0.25–2 s apart)
        if (!above && dyn > App.prefs.stepsSensitivity / 10f) {
            above = true
            val dt = t - lastStep
            if (dt in 250..2000) pending++
            lastStep = t
        } else if (above && dyn < 0.3f) above = false
        if (pending >= 4) flush()
    }

    private fun flush() { if (pending > 0) { App.I.steps.addSteps(pending); pending = 0 } }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onLocationChanged(l: Location) {
        val prev = lastLoc
        lastLoc = l
        if (prev == null || l.accuracy > 25) return
        val d = prev.distanceTo(l).toDouble()
        val dt = (l.time - prev.time) / 1000.0
        val speed = if (dt > 0) d / dt else 0.0
        if (speed in 0.4..2.8) {                         // walking / jogging, not driving
            meters += d
            val stride = App.prefs.stepsStrideCm / 100.0
            val steps = (meters / stride).toInt()
            if (steps > 0) { App.I.steps.addSteps(steps); meters -= steps * stride }
        }
    }

    @Deprecated("old API") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}
}
