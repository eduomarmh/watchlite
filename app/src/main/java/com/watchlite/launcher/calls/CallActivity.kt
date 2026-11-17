package com.watchlite.launcher.calls

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.aod.AodGate
import com.watchlite.launcher.link.LinkManager
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Log
import com.watchlite.launcher.util.Ui
import org.json.JSONObject

/**
 * Phone calls mirrored from the companion ("call.state") and controlled from the watch ("call.cmd").
 * The watch only remote-controls the phone's call: the audio itself stays on the phone (or its headset).
 */
object CallManager {
    @Volatile var state = "idle"          // ringing | offhook | idle
    @Volatile var number = ""
    @Volatile var name = ""
    @Volatile var speaker = false
    @Volatile var muted = false
    @Volatile var since = 0L              // elapsedRealtime when the call was picked up
    private val h = Handler(Looper.getMainLooper())
    private val listeners = ArrayList<() -> Unit>()
    fun listen(l: () -> Unit) { listeners += l }
    fun unlisten(l: () -> Unit) { listeners -= l }

    private val buzz = object : Runnable {
        override fun run() {
            if (state != "ringing") return
            Haptics.pattern(App.I, longArrayOf(0, 500, 300, 500))   // obeys charging / phone-silent rules
            h.postDelayed(this, 2200)
        }
    }

    fun onState(ctx: Context, p: JSONObject) = h.post {
        val prev = state
        state = p.optString("state", "idle")
        number = p.optString("number"); name = p.optString("name")
        speaker = p.optBoolean("speaker"); muted = p.optBoolean("muted")
        if (p.has("ringer")) App.I.state.phoneRinger = p.optString("ringer")
        if (state == "offhook" && prev != "offhook") since = SystemClock.elapsedRealtime() - p.optLong("elapsed", 0)
        Log.i("Call", "state $prev -> $state")
        h.removeCallbacks(buzz)
        when (state) {
            "ringing" -> { h.post(buzz); show(ctx) }
            "offhook" -> show(ctx)
            else -> since = 0L
        }
        listeners.toList().forEach { it() }
    }

    private fun show(ctx: Context) {
        if (CallActivity.visible) return
        AodGate.wake(ctx)
        ctx.startActivity(Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun cmd(c: String) {
        if (c == "silence") h.removeCallbacks(buzz)
        LinkManager.send("call.cmd", JSONObject().put("cmd", c))
    }
}

class CallActivity : Activity() {
    companion object { @Volatile var visible = false }

    private lateinit var ui: Ui
    private lateinit var root: LinearLayout
    private val h = Handler(Looper.getMainLooper())
    private val listener = { render() }
    private var shownState = ""
    private var timer: TextView? = null
    private var pulse: ValueAnimator? = null

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        ui = Ui(this)
        root = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setBackgroundColor(Ui.BG) }
        root.setPadding(ui.sp.safe, ui.px(6f), ui.sp.safe, ui.px(6f))
        setContentView(root)
        render()
    }

    override fun onResume() { super.onResume(); visible = true; CallManager.listen(listener); render(); tick() }
    override fun onPause() { visible = false; CallManager.unlisten(listener); h.removeCallbacksAndMessages(null); super.onPause() }

    private fun tick() {
        timer?.let { t ->
            if (CallManager.state == "offhook" && CallManager.since > 0) {
                val s = (SystemClock.elapsedRealtime() - CallManager.since) / 1000
                t.text = Digits.apply("%02d:%02d".format(s / 60, s % 60), Digits.useArabic())
            }
        }
        h.postDelayed({ tick() }, 1000)
    }

    private fun s(id: Int) = getString(id)

    private fun render() {
        val st = CallManager.state
        if (st == "idle") { h.postDelayed({ if (CallManager.state == "idle") finish() }, 600); if (shownState.isNotEmpty()) return }
        val key = st + CallManager.speaker + CallManager.muted + CallManager.name + CallManager.number
        if (key == shownState) return
        shownState = key
        pulse?.cancel(); pulse = null
        root.removeAllViews()
        val ringing = st == "ringing"
        val title = ui.text(if (ringing) s(R.string.call_incoming) else s(R.string.call_ongoing), 4.6f, Ui.TEXT2, 1, 1).apply { gravity = Gravity.CENTER }
        root.addView(title, ui.lp(top = 1f))
        timer = if (!ringing) ui.text("00:00", 4.6f, ui.accent, 1, 1).apply { gravity = Gravity.CENTER }.also { root.addView(it) } else null

        // avatar with the caller's initial; pulses while ringing
        val label = CallManager.name.ifBlank { CallManager.number }.ifBlank { s(R.string.call_unknown) }
        val avatar = FrameLayout(this)
        val glowV = android.view.View(this).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor((ui.accent and 0x00FFFFFF) or 0x44000000) } }
        avatar.addView(glowV, FrameLayout.LayoutParams(-1, -1))
        val initial = ui.text(label.trim().take(1).uppercase(), 11f, Ui.TEXT, 2, 1).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(ui.accent) }
        }
        val pad = ui.px(3.5f)
        avatar.addView(initial, FrameLayout.LayoutParams(-1, -1).apply { setMargins(pad, pad, pad, pad) })
        root.addView(avatar, ui.lp(ui.px(30f), ui.px(30f), top = 2.5f))
        if (ringing) pulse = ObjectAnimator.ofFloat(glowV, "scaleX", 1f, 1.18f).apply {
            duration = 700; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE
            addUpdateListener { glowV.scaleY = glowV.scaleX; glowV.alpha = 1.6f - glowV.scaleX }
            start()
        }
        root.addView(ui.text(label, 7f, Ui.TEXT, 2, 2).apply { gravity = Gravity.CENTER }, ui.lp(top = 2f))
        if (CallManager.name.isNotBlank() && CallManager.number.isNotBlank())
            root.addView(ui.text(Digits.apply(CallManager.number, Digits.useArabic()), 4.4f, Ui.TEXT2, 1, 1).apply { gravity = Gravity.CENTER })

        val spacer = android.view.View(this)
        root.addView(spacer, ui.lp(h = 0, weight = 1f))
        val row = ui.hbox().apply { gravity = Gravity.CENTER; layoutDirection = android.view.View.LAYOUT_DIRECTION_LTR }
        if (ringing) {
            row.addView(ui.circleButton(R.drawable.ic_call_end, 19f, bg = Ui.RED, tint = 0xFFFFFFFF.toInt()) { CallManager.cmd("decline") }, ui.lp(ui.px(19f), ui.px(19f), end = 14f))
            row.addView(ui.circleButton(R.drawable.ic_call, 19f, bg = 0xFF30D158.toInt(), tint = 0xFFFFFFFF.toInt()) { CallManager.cmd("answer") }, ui.lp(ui.px(19f), ui.px(19f)))
            root.addView(row)
            root.addView(ui.pill(s(R.string.call_silence)) { CallManager.cmd("silence") }, ui.lp(top = 3f, bottom = 1f))
        } else {
            fun toggle(icon: Int, on: Boolean, c: String) = ui.circleButton(icon, 13f, bg = if (on) ui.accent else Ui.CARD2, tint = if (on) Ui.BG else Ui.TEXT) { CallManager.cmd(c) }
            row.addView(toggle(if (CallManager.muted) R.drawable.ic_mic_off else R.drawable.ic_mic, CallManager.muted, "mute"), ui.lp(ui.px(13f), ui.px(13f), end = 4f))
            row.addView(ui.circleButton(R.drawable.ic_volume_down, 13f) { CallManager.cmd("voldown") }, ui.lp(ui.px(13f), ui.px(13f), end = 4f))
            row.addView(ui.circleButton(R.drawable.ic_volume_up, 13f) { CallManager.cmd("volup") }, ui.lp(ui.px(13f), ui.px(13f), end = 4f))
            row.addView(toggle(R.drawable.ic_volume_up, CallManager.speaker, "speaker").apply { contentDescription = s(R.string.call_speaker) }, ui.lp(ui.px(13f), ui.px(13f)))
            root.addView(row)
            root.addView(ui.text(s(if (CallManager.speaker) R.string.call_speaker_on else R.string.call_speaker_off), 4.2f, Ui.TEXT2, 1, 1).apply { gravity = Gravity.CENTER }, ui.lp(top = 1.5f))
            root.addView(ui.circleButton(R.drawable.ic_call_end, 17f, bg = Ui.RED, tint = 0xFFFFFFFF.toInt()) { CallManager.cmd("end") }, ui.lp(ui.px(17f), ui.px(17f), top = 3f, bottom = 2f))
        }
    }

    override fun onDestroy() { pulse?.cancel(); super.onDestroy() }
}
