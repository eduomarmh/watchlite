package com.watchlite.launcher.widgets

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.SystemClock
import android.provider.AlarmClock
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.home.Page
import com.watchlite.launcher.link.LinkManager
import com.watchlite.launcher.prayer.PrayerTimes
import com.watchlite.launcher.system.SystemActions
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Ui
import org.json.JSONObject
import java.util.Calendar
import java.util.Locale

class WidgetsPage(host: HomeActivity) : Page(host, FROM_BOTTOM) {

    private val scroll = ScrollView(host).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val box = ui.vbox()
    private var swText: TextView? = null
    private var timerText: TextView? = null
    private val ticker = object : Runnable {
        override fun run() { updateClocks(); if (isPresent) postDelayed(this, 100) }
    }
    private val stateListener = { if (isPresent) build() }

    init {
        box.setPadding(ui.sp.safe, ui.px(if (ui.sp.isRound) 12f else 5f), ui.sp.safe, ui.px(16f))
        scroll.addView(box)
        addView(scroll, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    override fun onOpening() { build(); scroll.scrollTo(0, 0); removeCallbacks(ticker); post(ticker) }
    override fun onClosed() { removeCallbacks(ticker) }
    override fun contentCanScroll(direction: Int) = scroll.canScrollVertically(direction)
    override fun onWheel(detents: Int): Boolean { scroll.smoothScrollBy(0, (detents * ui.u * 14).toInt()); return true }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); App.I.state.listen(stateListener) }
    override fun onDetachedFromWindow() { App.I.state.unlisten(stateListener); removeCallbacks(ticker); super.onDetachedFromWindow() }

    private fun build() {
        box.removeAllViews()
        val ar = Digits.useArabic()
        val ctx = context

        // Steps
        if (App.I.steps.available) {
            val c = ui.card()
            val row = ui.hbox()
            val goal = App.prefs.stepsGoal
            val steps = App.I.steps.today
            row.addView(RingView(ctx, steps / goal.toFloat().coerceAtLeast(1f), Ui.GREEN), LinearLayout.LayoutParams(ui.px(16f), ui.px(16f)))
            val col = ui.vbox()
            col.addView(ui.text(Digits.apply("$steps", ar), 8f, Ui.TEXT, 2))
            col.addView(ui.text(ctx.getString(R.string.steps_of, Digits.apply("$goal", ar)), 4.6f, Ui.TEXT2))
            row.addView(col, ui.lp(0, start = 4f, weight = 1f))
            c.addView(row)
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Prayer
        if (App.prefs.prayerEnabled) {
            val c = ui.card()
            val data = host.face.renderer.data
            val cal = Calendar.getInstance()
            data.refreshIfNeeded(cal)
            c.addView(ui.text(ctx.getString(R.string.next_prayer), 4.6f, Ui.TEXT2))
            c.addView(ui.text("${data.nextPrayerName}  ${data.nextPrayerTime}", 7f, ui.accent, 2), ui.lp(top = 1f))
            c.addView(ui.text(ctx.getString(R.string.in_time, data.nextPrayerIn), 4.8f, Ui.TEXT2), ui.lp(top = 0.5f))
            val all = ui.vbox().apply { visibility = View.GONE }
            val times = data.prayerTimesToday(cal); val names = data.prayerNames()
            for (i in 0 until 6) {
                val r = ui.hbox()
                r.addView(ui.text(names[i], 5f, Ui.TEXT), ui.lp(0, weight = 1f))
                r.addView(ui.text(Digits.apply(data.minutesToClock(times[i]), ar), 5f, if (i == PrayerTimes.SUNRISE) Ui.TEXT2 else Ui.TEXT))
                all.addView(r, ui.lp(top = 1.2f))
            }
            c.addView(all, ui.lp(top = 1.5f))
            c.isClickable = true
            c.background = ui.ripple(ui.rounded(Ui.CARD, 6f * ui.u))
            c.setOnClickListener { all.visibility = if (all.visibility == View.GONE) View.VISIBLE else View.GONE }
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Battery
        run {
            val st = App.I.state
            val c = ui.card()
            val row = ui.hbox()
            row.addView(RingView(ctx, st.battery / 100f, if (st.battery <= 20) Ui.RED else Ui.BLUE), LinearLayout.LayoutParams(ui.px(12f), ui.px(12f)))
            val col = ui.vbox()
            col.addView(ui.text(ctx.getString(R.string.watch) + "  " + Digits.apply("${st.battery}%", ar), 5.6f, Ui.TEXT, 2))
            if (st.phoneBattery >= 0) col.addView(ui.text(ctx.getString(R.string.phone) + "  " + Digits.apply("${st.phoneBattery}%", ar), 5.2f, Ui.TEXT2))
            row.addView(col, ui.lp(0, start = 3.5f, weight = 1f))
            c.addView(row)
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Stopwatch
        run {
            val c = ui.card()
            c.addView(ui.text(ctx.getString(R.string.stopwatch), 4.6f, Ui.TEXT2))
            swText = ui.text("00:00.0", 9f, Ui.TEXT, 0).apply { gravity = Gravity.CENTER }
            c.addView(swText, ui.lp(top = 1f))
            val row = ui.hbox()
            val running = App.prefs.swStart > 0
            row.addView(ui.pill(ctx.getString(if (running) R.string.stop else R.string.start), if (running) Ui.RED else Ui.GREEN, Ui.BG) {
                val p = App.prefs
                if (p.swStart > 0) { p.swAccum += SystemClock.elapsedRealtime() - p.swStart; p.swStart = 0 }
                else p.swStart = SystemClock.elapsedRealtime()
                build()
            }, ui.lp(0, end = 1.5f, weight = 1f))
            row.addView(ui.pill(ctx.getString(R.string.reset)) { App.prefs.swStart = 0; App.prefs.swAccum = 0; build() }, ui.lp(0, weight = 1f))
            c.addView(row, ui.lp(top = 2f))
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Timer
        run {
            val c = ui.card()
            c.addView(ui.text(ctx.getString(R.string.timer), 4.6f, Ui.TEXT2))
            timerText = ui.text("", 9f, Ui.TEXT, 0).apply { gravity = Gravity.CENTER }
            c.addView(timerText, ui.lp(top = 1f))
            if (App.prefs.timerEnd > System.currentTimeMillis()) {
                c.addView(ui.pill(ctx.getString(R.string.cancel), Ui.RED, Ui.BG) { TimerReceiver.cancel(ctx); build() }, ui.lp(top = 2f))
            } else {
                val row = ui.hbox()
                for (m in intArrayOf(1, 3, 5, 10, 15)) {
                    row.addView(ui.pill(Digits.apply("$m", ar)) { TimerReceiver.start(ctx, m * 60); build() }, ui.lp(0, end = 1f, weight = 1f))
                }
                c.addView(row, ui.lp(top = 2f))
            }
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Weather (from phone)
        val st = App.I.state
        if (st.weatherTemp.isNotEmpty()) {
            val c = ui.card()
            c.addView(ui.text(ctx.getString(R.string.weather), 4.6f, Ui.TEXT2))
            c.addView(ui.text(Digits.apply(st.weatherTemp, ar), 9f, Ui.TEXT, 2))
            c.addView(ui.text(st.weatherDesc, 5f, Ui.TEXT2))
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Music (from phone)
        if (st.mediaTitle.isNotEmpty() && LinkManager.enabled("media")) {
            val c = ui.card()
            c.addView(ui.text(st.mediaTitle, 5.6f, Ui.TEXT, 2))
            c.addView(ui.text(st.mediaArtist, 4.8f, Ui.TEXT2))
            val row = ui.hbox().apply { gravity = Gravity.CENTER }
            fun cmd(label: String, a: String) = ui.pill(label) { LinkManager.send("media.cmd", JSONObject().put("cmd", a)) }
            row.addView(cmd("⏮", "prev"), ui.lp(0, end = 1f, weight = 1f))
            row.addView(cmd(if (st.mediaPlaying) "⏸" else "▶", "toggle"), ui.lp(0, end = 1f, weight = 1f))
            row.addView(cmd("⏭", "next"), ui.lp(0, weight = 1f))
            c.addView(row, ui.lp(top = 2f))
            box.addView(c, ui.lp(bottom = 2.5f))
        }

        // Shortcuts
        val sc = ui.hbox().apply { gravity = Gravity.CENTER }
        sc.addView(ui.circleButton(R.drawable.ic_timer, 14f) { launch(Intent(AlarmClock.ACTION_SHOW_ALARMS)) }, ui.lp(ui.px(14f), ui.px(14f), end = 2f))
        if (SystemActions.hasTelephony(ctx)) {
            sc.addView(ui.circleButton(R.drawable.ic_call, 14f) { launch(Intent(Intent.ACTION_DIAL)) }, ui.lp(ui.px(14f), ui.px(14f), end = 2f))
            sc.addView(ui.circleButton(R.drawable.ic_chat, 14f) {
                launch(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING))
            }, ui.lp(ui.px(14f), ui.px(14f)))
        }
        box.addView(sc, ui.lp(top = 1f))
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).nextAlarmClock?.let { next ->
            val f = java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault())
            box.addView(ui.text(ctx.getString(R.string.next_alarm, Digits.apply(f.format(next.triggerTime), ar)), 4.8f, Ui.TEXT2)
                .apply { gravity = Gravity.CENTER }, ui.lp(top = 2f))
        }
        updateClocks()
    }

    private fun launch(i: Intent) {
        try { context.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) { host.toast(context.getString(R.string.no_app)) }
    }

    private fun updateClocks() {
        val ar = Digits.useArabic()
        val p = App.prefs
        val ms = p.swAccum + if (p.swStart > 0) SystemClock.elapsedRealtime() - p.swStart else 0
        swText?.text = Digits.apply("%02d:%02d.%d".format(Locale.US, ms / 60000, (ms / 1000) % 60, (ms / 100) % 10), ar)
        val left = (p.timerEnd - System.currentTimeMillis()).coerceAtLeast(0)
        timerText?.text = if (left > 0) Digits.apply("%02d:%02d".format(Locale.US, left / 60000, (left / 1000) % 60), ar)
        else context.getString(R.string.timer_pick)
    }

    /** Small progress ring used by widget cards. */
    class RingView(ctx: Context, private val p: Float, private val color: Int) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val sw = width * 0.12f
            r.set(sw, sw, width - sw, height - sw)
            paint.strokeWidth = sw
            paint.color = (color and 0x00FFFFFF) or 0x33000000
            c.drawArc(r, 0f, 360f, false, paint)
            paint.color = color
            c.drawArc(r, -90f, 360f * p.coerceIn(0f, 1f), false, paint)
        }
    }
}

/** Countdown timer that works with the screen off (exact alarm). */
class TimerReceiver : BroadcastReceiver() {
    companion object {
        private const val CH = "timer"

        private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 7, Intent(ctx, TimerReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        fun start(ctx: Context, seconds: Int) {
            val end = System.currentTimeMillis() + seconds * 1000L
            App.prefs.timerEnd = end; App.prefs.timerLast = seconds
            schedule(ctx, end)
            Haptics.tick(ctx)
        }

        private fun schedule(ctx: Context, end: Long) {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.setAlarmClock(AlarmManager.AlarmClockInfo(end, null), pi(ctx))
        }

        fun cancel(ctx: Context) {
            App.prefs.timerEnd = 0
            (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pi(ctx))
        }

        fun reschedule(ctx: Context) {
            val end = App.prefs.timerEnd
            if (end > System.currentTimeMillis()) schedule(ctx, end) else App.prefs.timerEnd = 0
        }
    }

    override fun onReceive(ctx: Context, intent: Intent) {
        App.prefs.timerEnd = 0
        val nm = ctx.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CH, ctx.getString(R.string.timer), NotificationManager.IMPORTANCE_HIGH).apply {
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build())
            enableVibration(true); vibrationPattern = longArrayOf(0, 400, 200, 400, 200, 400)
        }
        nm.createNotificationChannel(ch)
        nm.notify(77, Notification.Builder(ctx, CH).setSmallIcon(R.drawable.ic_timer)
            .setContentTitle(ctx.getString(R.string.timer_done)).setAutoCancel(true)
            .setCategory(Notification.CATEGORY_ALARM).build())
        Haptics.pattern(ctx, longArrayOf(0, 400, 200, 400, 200, 400))
    }
}
