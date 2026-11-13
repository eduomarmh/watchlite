package com.watchlite.launcher.safety

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.format.DateFormat
import android.view.Gravity
import android.widget.ScrollView
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.Prefs
import com.watchlite.launcher.R
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.util.Ui
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Crash-loop guard: 3 crashes within 60 s switch the launcher to Safe mode,
 * so the watch never ends up without a working home screen.
 */
object CrashGuard {
    private const val WINDOW = 60_000L
    private const val LIMIT = 3

    fun install(ctx: Context) {
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val sp = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE)
                val now = System.currentTimeMillis()
                val times = (sp.getString("crash_times", "") ?: "").split(',').mapNotNull { it.toLongOrNull() }
                    .filter { now - it < WINDOW } + now
                sp.edit().putString("crash_times", times.joinToString(",")).commit()
                val sw = StringWriter(); e.printStackTrace(PrintWriter(sw))
                File(ctx.filesDir, "last_crash.txt").writeText("${java.util.Date(now)}\n$sw\n\n" + com.watchlite.launcher.util.Log.dump())
            } catch (_: Throwable) { }
            prev?.uncaughtException(t, e)
        }
    }

    fun isLooping(ctx: Context): Boolean {
        val now = System.currentTimeMillis()
        val n = App.prefs.crashTimes.split(',').mapNotNull { it.toLongOrNull() }.count { now - it < WINDOW }
        return n >= LIMIT
    }

    fun markHealthy() { if (App.prefs.crashTimes.isNotEmpty()) App.prefs.crashTimes = "" }

    fun lastCrash(ctx: Context): String? = File(ctx.filesDir, "last_crash.txt").takeIf { it.isFile }?.readText()
}

/** Minimal, dependency-free home screen used after repeated crashes. */
class SafeModeActivity : Activity() {
    private lateinit var clock: TextView
    private val tick = object : Runnable {
        override fun run() { clock.text = DateFormat.format("HH:mm", System.currentTimeMillis()); clock.postDelayed(this, 10_000) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val box = ui.vbox().apply { gravity = Gravity.CENTER_HORIZONTAL; setPadding(ui.sp.safe, ui.px(8f), ui.sp.safe, ui.px(10f)) }
        clock = ui.text("", 20f, Ui.TEXT, 0).apply { gravity = Gravity.CENTER }
        box.addView(clock)
        box.addView(ui.text(getString(R.string.safe_mode), 6f, Ui.RED, 2).apply { gravity = Gravity.CENTER }, ui.lp(top = 1f))
        box.addView(ui.text(getString(R.string.safe_mode_body), 4.6f, Ui.TEXT2, 1, 0).apply { gravity = Gravity.CENTER }, ui.lp(top = 2f))
        box.addView(ui.pill(getString(R.string.safe_restart)) {
            App.prefs.crashTimes = ""; App.prefs.themeId = "oled"; App.prefs.autoTheme = false
            startActivity(Intent(this, HomeActivity::class.java)); finish()
        }, ui.lp(top = 3f))
        box.addView(ui.pill(getString(R.string.switch_launcher)) {
            App.prefs.crashTimes = ""
            startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        }, ui.lp(top = 2f))
        box.addView(ui.pill(getString(R.string.all_apps)) {
            startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).let { Intent.createChooser(it, getString(R.string.all_apps)) })
        }, ui.lp(top = 2f))
        CrashGuard.lastCrash(this)?.let {
            box.addView(ui.text(it.take(1200), 3.2f, Ui.TEXT2, 1, 0), ui.lp(top = 3f))
        }
        setContentView(ScrollView(this).apply { addView(box); setBackgroundColor(Ui.BG) })
        tick.run()
    }

    override fun onDestroy() { clock.removeCallbacks(tick); super.onDestroy() }
}
