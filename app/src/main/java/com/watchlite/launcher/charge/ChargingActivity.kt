package com.watchlite.launcher.charge

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.os.BatteryManager
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.aod.AodActivity
import com.watchlite.launcher.aod.AodGate
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Fonts
import com.watchlite.launcher.util.Log
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * Charger connected → a short full-screen animation, then back to whatever was showing
 * (or to AOD / screen off if the screen was off). Registered at runtime from App
 * (ACTION_POWER_CONNECTED can't be a manifest receiver on Android 8).
 */
object ChargeAnim {
    @Volatile var lastShown = 0L

    fun register(ctx: Context) {
        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (!App.prefs.chargeAnim) return
                val now = SystemClock.elapsedRealtime()
                if (now - lastShown < 8000) return          // flaky contacts: once per plug-in
                lastShown = now
                val pm = c.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wasOn = pm.isInteractive && !AodActivity.showing && !AodGate.homeAodActive
                Log.i("Charge", "power connected, screen on=$wasOn")
                if (!pm.isInteractive) AodGate.wake(c)
                c.startActivity(Intent(c, ChargingActivity::class.java)
                    .putExtra("wasOn", wasOn)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
            }
        }, IntentFilter(Intent.ACTION_POWER_CONNECTED))
    }
}

class ChargingActivity : Activity() {
    private var wasOn = true
    private lateinit var view: ChargeView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        // over the lock screen only when there is one (occluding a missing keyguard crashes some SystemUIs)
        val locked = (getSystemService(Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isKeyguardLocked
        if (locked) window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
        window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or WindowManager.LayoutParams.FLAG_FULLSCREEN)
        wasOn = intent.getBooleanExtra("wasOn", true)
        val level = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let {
            it.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) * 100 / max(1, it.getIntExtra(BatteryManager.EXTRA_SCALE, 100))
        } ?: 50
        view = ChargeView(this, level, App.prefs.accent) { close() }
        setContentView(view)
    }

    private var closing = false
    private fun close() {
        if (closing || isFinishing) return
        closing = true
        finish()
        overridePendingTransition(0, android.R.anim.fade_out)
        // the screen was off: go back to the same quiet state
        if (!wasOn) {
            if (AodGate.canEnter()) startActivity(Intent(this, AodActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            else com.watchlite.launcher.system.SystemActions.lockScreen(this)
        }
    }

    override fun onUserLeaveHint() { close() }

    class ChargeView(ctx: Context, private val level: Int, private val accent: Int, private val done: () -> Unit) : View(ctx) {
        private val start = SystemClock.uptimeMillis()
        private val duration = 3800L
        private val rnd = Random(3)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val glass = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        private val liquid = Paint(Paint.ANTI_ALIAS_FLAG)
        private val liquid2 = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        private val halo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Color.WHITE }
        private val sub = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
        private val bolt = Path()
        private val wave = Path()
        private val clip = Path()
        // energy particles streaming from the bottom edge into the circle
        private val pn = 26
        private val px = FloatArray(pn) { rnd.nextFloat() }
        private val pp = FloatArray(pn) { rnd.nextFloat() }
        private val pv = FloatArray(pn) { 0.35f + rnd.nextFloat() * 0.5f }
        // bubbles inside the liquid
        private val bn = 12
        private val bx = FloatArray(bn) { rnd.nextFloat() * 1.4f - 0.7f }
        private val bp = FloatArray(bn) { rnd.nextFloat() }
        private val bs = FloatArray(bn) { 0.015f + rnd.nextFloat() * 0.03f }
        private var lw = 0
        private val ar = Digits.useArabic()
        private val low = level <= 20

        private val c1 = if (low) 0xFFFF6B3D.toInt() else 0xFF2EE59D.toInt()
        private val c2 = if (low) 0xFFFFC83D.toInt() else accent

        init { setBackgroundColor(Color.BLACK); isClickable = true }

        override fun onTouchEvent(event: MotionEvent): Boolean { if (event.action == MotionEvent.ACTION_UP) done(); return true }

        private fun ease(x: Float) = 1 - (1 - x) * (1 - x) * (1 - x)

        override fun onDraw(c: Canvas) {
            val t = (SystemClock.uptimeMillis() - start).toFloat()
            if (t > duration) { done(); return }
            val w = width.toFloat(); val h = height.toFloat(); val m = min(w, h)
            val cx = w / 2f; val cy = h * 0.44f; val r = m * 0.3f
            if (lw != width) {
                lw = width
                liquid.shader = LinearGradient(0f, cy + r, 0f, cy - r, c1, c2, Shader.TileMode.CLAMP)
                liquid2.shader = LinearGradient(0f, cy + r, 0f, cy - r, (c1 and 0x00FFFFFF) or 0x88000000.toInt(), (c2 and 0x00FFFFFF) or 0x66000000.toInt(), Shader.TileMode.CLAMP)
                halo.shader = RadialGradient(cx, cy, r * 1.9f, intArrayOf((c2 and 0x00FFFFFF) or 0x55000000, Color.TRANSPARENT), null, Shader.TileMode.CLAMP)
                text.typeface = Fonts.get(context, "tajawal_extrabold"); text.textSize = m * 0.17f
                sub.typeface = Fonts.get(context, "tajawal_medium"); sub.textSize = m * 0.06f
                clip.reset(); clip.addCircle(cx, cy, r * 0.9f, Path.Direction.CW)
                // lightning bolt
                val s = m * 0.05f; val bxo = cx; val byo = cy + r + m * 0.13f
                bolt.reset()
                bolt.moveTo(bxo + 0.15f * s, byo - 1f * s); bolt.lineTo(bxo - 0.55f * s, byo + 0.12f * s); bolt.lineTo(bxo - 0.05f * s, byo + 0.12f * s)
                bolt.lineTo(bxo - 0.2f * s, byo + 1f * s); bolt.lineTo(bxo + 0.55f * s, byo - 0.15f * s); bolt.lineTo(bxo + 0.05f * s, byo - 0.15f * s); bolt.close()
            }
            val fadeIn = min(1f, t / 300f)
            val fadeOut = min(1f, (duration - t) / 450f)
            val alpha = min(fadeIn, fadeOut).coerceIn(0f, 1f)
            val grow = ease(min(1f, t / 600f))
            val fill = ease(min(1f, t / 1600f)) * level / 100f
            val sec = t / 1000f

            c.saveLayerAlpha(0f, 0f, w, h, (alpha * 255).toInt())
            c.save(); c.scale(0.85f + 0.15f * grow, 0.85f + 0.15f * grow, cx, cy)

            // halo + energy particles rising from the bottom edge towards the circle
            c.drawCircle(cx, cy, r * 1.9f, halo)
            for (i in 0 until pn) {
                val p = (pp[i] + sec * pv[i]) % 1f
                val sx = w * (0.1f + px[i] * 0.8f); val sy = h + m * 0.05f
                val ex = cx + (px[i] - 0.5f) * r * 0.8f; val ey = cy + r * 0.85f
                val x = sx + (ex - sx) * p; val y = sy + (ey - sy) * p
                dot.color = c2; dot.alpha = (sin(p * PI).toFloat() * 230).toInt()
                c.drawCircle(x, y, m * (0.006f + 0.006f * (1 - p)), dot)
            }

            // glass circle
            glass.color = 0x33FFFFFF; glass.strokeWidth = m * 0.008f
            c.drawCircle(cx, cy, r * 0.93f, glass)

            // liquid with two waves
            val top = cy + r * 0.9f - 2f * r * 0.9f * fill
            c.save(); c.clipPath(clip)
            fun waveAt(phase: Float, amp: Float, k: Float, p: Paint) {
                wave.reset(); wave.moveTo(cx - r, cy + r)
                var x = cx - r
                while (x <= cx + r) { wave.lineTo(x, top + amp * sin((x - cx) / r * k + phase)); x += m * 0.01f }
                wave.lineTo(cx + r, cy + r); wave.close()
                c.drawPath(wave, p)
            }
            waveAt(sec * 3.1f + 1.3f, m * 0.018f, 2.6f, liquid2)
            waveAt(sec * 2.2f, m * 0.014f, 3.4f, liquid)
            for (i in 0 until bn) {        // bubbles rising in the liquid
                val p = (bp[i] + sec * 0.45f) % 1f
                val y = cy + r * 0.9f - (cy + r * 0.9f - top) * p
                val x = cx + bx[i] * r * 0.8f + sin(sec * 3f + i) * m * 0.01f
                dot.color = Color.WHITE; dot.alpha = (120 * (1 - p)).toInt()
                if (y > top + m * 0.01f) c.drawCircle(x, y, bs[i] * m * 0.6f, dot)
            }
            c.restore()

            // rotating light ring
            ring.shader = SweepGradient(cx, cy, intArrayOf(Color.TRANSPARENT, c1, c2, Color.TRANSPARENT), floatArrayOf(0f, 0.35f, 0.7f, 1f))
            ring.strokeWidth = m * 0.022f
            c.save(); c.rotate(sec * 160f, cx, cy)
            c.drawCircle(cx, cy, r * 1.06f, ring)
            c.restore()

            // percentage (counts up with the fill)
            val shown = (fill * 100).toInt().coerceAtMost(level)
            val pct = Digits.apply("$shown%", ar)
            text.setShadowLayer(m * 0.03f, 0f, 0f, 0xAA000000.toInt())
            c.drawText(pct, cx, cy + text.textSize * 0.35f, text)
            c.restore()

            // bolt + label under the circle
            dot.color = c2; dot.alpha = (200 + 55 * sin(sec * 6f)).toInt()
            c.drawPath(bolt, dot)
            sub.color = 0xCCFFFFFF.toInt()
            c.drawText(context.getString(if (level >= 100) R.string.charged_full else R.string.charging_now), cx, cy + r + m * 0.26f, sub)
            c.restore()
            postInvalidateOnAnimation()
        }

        @Suppress("unused") private fun unused() = cos(0.0)
    }
}
