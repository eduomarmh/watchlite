package com.watchlite.launcher.steps

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.watchlite.launcher.App
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Daily steps from the hardware TYPE_STEP_COUNTER (counts since boot).
 * Keeps a per-day baseline and survives reboots (counter going backwards).
 * Only listens while the face is visible; the hardware keeps counting anyway.
 */
class StepTracker(ctx: Context) : SensorEventListener {

    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val sensor: Sensor? = sm.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
    private var listening = false
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US)

    val available get() = sensor != null
    @Volatile var today = 0
        private set

    init {
        val p = App.prefs
        if (p.stepsDay == dayFmt.format(Date()) && p.stepsBase >= 0) today = p.stepsCarry + (p.stepsLastRaw - p.stepsBase).coerceAtLeast(0)
    }

    fun start() {
        if (sensor == null || listening) return
        listening = sm.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL, 30_000_000)
    }

    fun stop() {
        if (!listening) return
        sm.unregisterListener(this); listening = false
    }

    override fun onSensorChanged(e: SensorEvent) {
        val raw = e.values[0].toInt()
        today = compute(raw, dayFmt.format(Date()))
        App.I.state.changed()
    }

    /** Pure logic (unit tested). */
    fun compute(raw: Int, day: String): Int {
        val p = App.prefs
        if (p.stepsDay != day) {
            // new day: baseline = current raw value
            p.stepsDay = day; p.stepsBase = raw; p.stepsCarry = 0
        } else if (raw < p.stepsLastRaw) {
            // reboot: counter restarted from 0, keep what we had today
            p.stepsCarry = p.stepsCarry + (p.stepsLastRaw - p.stepsBase)
            p.stepsBase = 0
        }
        if (p.stepsBase < 0) p.stepsBase = raw
        p.stepsLastRaw = raw
        return p.stepsCarry + (raw - p.stepsBase).coerceAtLeast(0)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
