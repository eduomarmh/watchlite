package com.watchlite.launcher.input

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.util.LocaleHelper
import com.watchlite.launcher.util.Log
import com.watchlite.launcher.util.Ui
import kotlin.math.abs

/**
 * Converts whatever the crown sends into wheel detents (+1 = down/clockwise).
 * Supports rotary/scroll motion events and configurable key codes.
 */
object InputMapper {
    private val DEFAULT_UP = setOf(KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_NAVIGATE_PREVIOUS, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_UP)
    private val DEFAULT_DOWN = setOf(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_NAVIGATE_NEXT, KeyEvent.KEYCODE_SYSTEM_NAVIGATION_DOWN)
    private var acc = 0f

    private fun sign(d: Int) = if (App.prefs.wheelInvert) -d else d

    fun fromKey(e: KeyEvent): Int {
        val p = App.prefs
        val k = e.keyCode
        val up = if (p.wheelUpKey > 0) k == p.wheelUpKey else k in DEFAULT_UP
        val down = if (p.wheelDownKey > 0) k == p.wheelDownKey else k in DEFAULT_DOWN
        return when { up -> sign(-1); down -> sign(1); else -> 0 }
    }

    fun fromMotion(e: MotionEvent): Int {
        if (e.action != MotionEvent.ACTION_SCROLL) return 0
        val v = when {
            e.isFromSource(InputDevice.SOURCE_ROTARY_ENCODER) -> -e.getAxisValue(MotionEvent.AXIS_SCROLL)
            e.getAxisValue(MotionEvent.AXIS_VSCROLL) != 0f -> -e.getAxisValue(MotionEvent.AXIS_VSCROLL)
            else -> -e.getAxisValue(MotionEvent.AXIS_SCROLL)
        }
        if (v == 0f) return 0
        // rotary encoders report small fractional values: accumulate to whole detents
        acc += if (abs(v) < 0.5f) v * 2f else v
        val d = acc.toInt()
        if (d == 0) return 0
        acc -= d
        return sign(d.coerceIn(-3, 3))
    }
}

/** Diagnostics: shows every key/motion event so the crown & buttons can be mapped. */
class InputProbeActivity : Activity() {
    private lateinit var log: TextView
    private lateinit var scroll: ScrollView
    private var learn: Int = 0 // 1 = up, 2 = down
    private lateinit var ui: Ui

    override fun attachBaseContext(base: Context) = super.attachBaseContext(LocaleHelper.wrap(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        val box = ui.vbox()
        box.setPadding(ui.sp.safe, ui.px(6f), ui.sp.safe, ui.px(6f))
        box.addView(ui.text(getString(R.string.input_probe), 6.5f, Ui.TEXT, 2).apply { gravity = Gravity.CENTER })
        box.addView(ui.text(getString(R.string.input_probe_help), 4.4f, Ui.TEXT2, 1, 0), ui.lp(top = 1.5f))
        val row = ui.hbox()
        row.addView(ui.pill(getString(R.string.learn_up)) { learn = 1; append("→ rotate UP now") }, ui.lp(0, end = 1f, weight = 1f))
        row.addView(ui.pill(getString(R.string.learn_down)) { learn = 2; append("→ rotate DOWN now") }, ui.lp(0, weight = 1f))
        box.addView(row, ui.lp(top = 2f))
        box.addView(ui.pill(getString(R.string.reset)) {
            App.prefs.wheelUpKey = -1; App.prefs.wheelDownKey = -1; append("mapping reset")
        }, ui.lp(top = 1.5f))
        log = ui.text("", 3.8f, Ui.GREEN, 1, 0).apply { typeface = android.graphics.Typeface.MONOSPACE }
        scroll = ScrollView(this).apply { addView(log) }
        box.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = ui.px(2f) })
        setContentView(box)
        append("screen: ${ui.sp}")
        append("wheel up=${App.prefs.wheelUpKey} down=${App.prefs.wheelDownKey}")
    }

    private fun append(s: String) {
        Log.i("Probe", s)
        log.append(s + "\n")
        scroll.post { scroll.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(e.keyCode)
        append("KEY ${if (e.action == 0) "DOWN" else "UP"} $name(${e.keyCode}) scan=${e.scanCode} src=0x${Integer.toHexString(e.source)}")
        if (e.action == KeyEvent.ACTION_DOWN && learn != 0 && e.keyCode != KeyEvent.KEYCODE_BACK) {
            if (learn == 1) App.prefs.wheelUpKey = e.keyCode else App.prefs.wheelDownKey = e.keyCode
            append("✓ saved ${if (learn == 1) "UP" else "DOWN"} = $name"); learn = 0
            return true
        }
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && e.eventTime - e.downTime > 600) { finish(); return true }
        return e.keyCode != KeyEvent.KEYCODE_BACK || super.dispatchKeyEvent(e)
    }

    override fun dispatchGenericMotionEvent(e: MotionEvent): Boolean {
        append("MOTION act=${e.action} src=0x${Integer.toHexString(e.source)} scroll=${e.getAxisValue(MotionEvent.AXIS_SCROLL)} " +
            "v=${e.getAxisValue(MotionEvent.AXIS_VSCROLL)} h=${e.getAxisValue(MotionEvent.AXIS_HSCROLL)} → detent ${InputMapper.fromMotion(e)}")
        return true
    }
}
