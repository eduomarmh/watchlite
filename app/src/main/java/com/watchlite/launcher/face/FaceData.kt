package com.watchlite.launcher.face

import android.content.Context
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.prayer.Hijri
import com.watchlite.launcher.prayer.PrayerTimes
import com.watchlite.launcher.util.Digits
import com.watchlite.launcher.util.TimeFmt
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * Values that faces/widgets display. Expensive pieces (prayer times, hijri)
 * are computed at most once per minute.
 */
class FaceData(private val ctx: Context) {

    private var minuteKey = -1L
    private var prayers: IntArray? = null
    private var prayerDay = -1
    var hijri = ""; private set
    var hijriShort = ""; private set
    var nextPrayerName = ""; private set
    var nextPrayerTime = ""; private set
    var nextPrayerIn = ""; private set

    private val dateFormats = HashMap<String, SimpleDateFormat>()
    private val templateCache = HashMap<String, Pair<Long, String>>()

    val battery get() = App.I.state.battery
    val steps get() = App.I.steps.today

    fun prayerNames(): Array<String> = arrayOf(
        ctx.getString(R.string.prayer_fajr), ctx.getString(R.string.prayer_sunrise), ctx.getString(R.string.prayer_dhuhr),
        ctx.getString(R.string.prayer_asr), ctx.getString(R.string.prayer_maghrib), ctx.getString(R.string.prayer_isha)
    )

    fun prayerTimesToday(cal: Calendar): IntArray {
        val day = cal.get(Calendar.DAY_OF_YEAR)
        if (prayers == null || day != prayerDay) {
            val p = App.prefs
            prayers = PrayerTimes.today(cal, p.latitude.toDouble(), p.longitude.toDouble(), p.prayerMethod, p.asrMethod == "hanafi")
            prayerDay = day
        }
        return prayers!!
    }

    fun refreshIfNeeded(cal: Calendar) {
        val key = cal.timeInMillis / 60_000L
        if (key == minuteKey) return
        minuteKey = key
        templateCache.clear()
        val ar = Locale.getDefault().language == "ar"
        val arDigits = Digits.useArabic()
        hijri = Digits.apply(Hijri.format(App.prefs.hijriAdjust, ar), arDigits)
        hijriShort = Digits.apply(Hijri.format(App.prefs.hijriAdjust, ar, false), arDigits)
        if (App.prefs.prayerEnabled) {
            val t = prayerTimesToday(cal)
            val now = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
            val names = prayerNames()
            var idx = -1
            for (i in t.indices) if (i != PrayerTimes.SUNRISE && t[i] > now) { idx = i; break }
            val mins: Int
            if (idx == -1) { idx = PrayerTimes.FAJR; mins = t[0] + 24 * 60 - now } else mins = t[idx] - now
            nextPrayerName = names[idx]
            nextPrayerTime = Digits.apply(minutesToClock(t[idx]), arDigits)
            nextPrayerIn = Digits.apply(if (mins >= 60) "%d:%02d".format(Locale.US, mins / 60, mins % 60) else "${mins}m", arDigits)
                .let { if (ar) it.replace("m", "د") else it }
        } else { nextPrayerName = ""; nextPrayerTime = ""; nextPrayerIn = "" }
    }

    fun minutesToClock(m: Int): String {
        val h = (m / 60) % 24
        val mm = m % 60
        return if (TimeFmt.is24(ctx)) "%02d:%02d".format(Locale.US, h, mm)
        else "%d:%02d".format(Locale.US, if (h % 12 == 0) 12 else h % 12, mm)
    }

    fun formatDate(pattern: String, cal: Calendar): String {
        // Force latin digits from the formatter; Digits.apply converts later if the user wants Arabic-Indic.
        val f = dateFormats.getOrPut(pattern) {
            SimpleDateFormat(pattern, Locale.forLanguageTag(Locale.getDefault().toLanguageTag() + "-u-nu-latn"))
        }
        return f.format(cal.time)
    }

    /** Formats a time pattern made of HH H hh h mm ss a tokens without allocating formatters. */
    fun formatTime(pattern0: String, cal: Calendar): String {
        val pattern = if (pattern0 == "auto") (if (TimeFmt.is24(ctx)) "HH:mm" else "h:mm") else pattern0
        val h24 = cal.get(Calendar.HOUR_OF_DAY)
        val h12 = if (h24 % 12 == 0) 12 else h24 % 12
        val sb = StringBuilder()
        var i = 0
        while (i < pattern.length) {
            val rest = pattern.substring(i)
            when {
                rest.startsWith("HH") -> { sb.append(two(h24)); i += 2 }
                rest.startsWith("hh") -> { sb.append(two(h12)); i += 2 }
                rest.startsWith("mm") -> { sb.append(two(cal.get(Calendar.MINUTE))); i += 2 }
                rest.startsWith("ss") -> { sb.append(two(cal.get(Calendar.SECOND))); i += 2 }
                rest.startsWith("H") -> { sb.append(if (TimeFmt.is24(ctx)) h24 else h12); i += 1 }
                rest.startsWith("h") -> { sb.append(h12); i += 1 }
                rest.startsWith("a") -> { sb.append(if (h24 < 12) amText() else pmText()); i += 1 }
                else -> { sb.append(pattern[i]); i += 1 }
            }
        }
        return sb.toString()
    }

    fun amText() = ctx.getString(R.string.am)
    fun pmText() = ctx.getString(R.string.pm)

    private fun two(n: Int) = if (n < 10) "0$n" else n.toString()

    /**
     * Expands placeholders like {date:EEE d MMM} {battery} {steps} {hijri} {prayer.next}.
     * Results are cached for the current minute (unless seconds are used).
     */
    fun expand(template: String, cal: Calendar, arabicDigits: Boolean): String {
        val usesSeconds = template.contains("{second}")
        if (!usesSeconds) templateCache[template]?.let { if (it.first == minuteKey) return it.second }
        val sb = StringBuilder()
        var i = 0
        while (i < template.length) {
            val c = template[i]
            if (c == '{') {
                val end = template.indexOf('}', i)
                if (end > i) {
                    sb.append(value(template.substring(i + 1, end), cal))
                    i = end + 1
                    continue
                }
            }
            sb.append(c); i++
        }
        val out = Digits.apply(sb.toString(), arabicDigits)
        if (!usesSeconds) templateCache[template] = minuteKey to out
        return out
    }

    private fun value(key: String, cal: Calendar): String {
        if (key.startsWith("date:")) return formatDate(key.removePrefix("date:"), cal)
        if (key.startsWith("time:")) return formatTime(key.removePrefix("time:"), cal)
        val st = App.I.state
        return when (key) {
            "time" -> formatTime("auto", cal)
            "hour" -> formatTime("H", cal)
            "minute" -> formatTime("mm", cal)
            "second" -> formatTime("ss", cal)
            "ampm" -> if (TimeFmt.is24(ctx)) "" else formatTime("a", cal)
            "weekday" -> formatDate("EEEE", cal)
            "day" -> cal.get(Calendar.DAY_OF_MONTH).toString()
            "month" -> formatDate("MMMM", cal)
            "year" -> cal.get(Calendar.YEAR).toString()
            "battery" -> "${st.battery}%"
            "steps" -> steps.toString()
            "stepsGoal" -> App.prefs.stepsGoal.toString()
            "hijri" -> hijri
            "hijriShort" -> hijriShort
            "prayer.next" -> nextPrayerName
            "prayer.nextTime" -> nextPrayerTime
            "prayer.countdown" -> nextPrayerIn
            "phone.battery" -> if (st.phoneBattery >= 0) "${st.phoneBattery}%" else ""
            "weather.temp" -> st.weatherTemp
            "weather.desc" -> st.weatherDesc
            "notif.count" -> App.I.notifications.count().let { if (it == 0) "" else it.toString() }
            else -> ""
        }
    }
}
