package com.watchlite.launcher.prayer

import java.time.LocalDate
import java.time.chrono.HijrahChronology
import java.time.chrono.HijrahDate
import java.time.temporal.ChronoField
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/**
 * Offline prayer-time calculator (standard astronomical method, as used by
 * most prayer apps). Results are minutes after local midnight.
 */
object PrayerTimes {

    class Method(val fajr: Double, val isha: Double, val ishaMinutes: Int = 0)

    val METHODS = linkedMapOf(
        "egypt" to Method(19.5, 17.5),
        "mwl" to Method(18.0, 17.0),
        "ummalqura" to Method(18.5, 0.0, 90),
        "isna" to Method(15.0, 15.0),
        "karachi" to Method(18.0, 18.0),
        "dubai" to Method(18.2, 18.2),
        "kuwait" to Method(18.0, 17.5),
        "qatar" to Method(18.0, 0.0, 90)
    )

    const val FAJR = 0
    const val SUNRISE = 1
    const val DHUHR = 2
    const val ASR = 3
    const val MAGHRIB = 4
    const val ISHA = 5

    /** Returns 6 values (minutes from midnight, local time) for the given day. */
    fun compute(year: Int, month: Int, day: Int, lat: Double, lng: Double, tzHours: Double,
                method: String, hanafi: Boolean): DoubleArray {
        val m = METHODS[method] ?: METHODS.getValue("egypt")
        val jd = julian(year, month, day) - lng / (15 * 24.0)

        fun sunPos(t: Double): Pair<Double, Double> { // declination, equation of time
            val d = jd + t - 2451545.0
            val g = fix(357.529 + 0.98560028 * d, 360.0)
            val q = fix(280.459 + 0.98564736 * d, 360.0)
            val l = fix(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g), 360.0)
            val e = 23.439 - 0.00000036 * d
            val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15.0
            val eqt = q / 15.0 - fix(ra, 24.0)
            val decl = darcsin(dsin(e) * dsin(l))
            return decl to eqt
        }

        fun midDay(t: Double): Double = fix(12 - sunPos(t).second, 24.0)

        fun sunAngleTime(angle: Double, t: Double, ccw: Boolean): Double {
            val decl = sunPos(t).first
            val noon = midDay(t)
            val v = (-dsin(angle) - dsin(decl) * dsin(lat)) / (dcos(decl) * dcos(lat))
            val tt = darccos(v.coerceIn(-1.0, 1.0)) / 15.0
            return noon + if (ccw) -tt else tt
        }

        fun asrTime(factor: Double, t: Double): Double {
            val decl = sunPos(t).first
            val angle = -darccot(factor + dtan(abs(lat - decl)))
            return sunAngleTime(angle, t, false)
        }

        // initial guesses as day portions
        var times = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0).map { it / 24.0 }.toDoubleArray()
        repeat(2) {
            times = doubleArrayOf(
                sunAngleTime(m.fajr, times[0], true),
                sunAngleTime(0.833, times[1], true),
                midDay(times[2]),
                asrTime(if (hanafi) 2.0 else 1.0, times[3]),
                sunAngleTime(0.833, times[4], false),
                sunAngleTime(if (m.ishaMinutes > 0) 0.833 else m.isha, times[5], false),
                0.0
            ).map { it / 24.0 }.toDoubleArray()
        }
        val out = DoubleArray(6)
        for (i in 0 until 6) {
            var hrs = times[i] * 24.0 + tzHours - lng / 15.0
            if (i == ISHA && m.ishaMinutes > 0) hrs = times[MAGHRIB] * 24.0 + tzHours - lng / 15.0 + m.ishaMinutes / 60.0
            if (i == DHUHR) hrs += 1 / 60.0 // safety minute after zenith
            out[i] = fix(hrs, 24.0) * 60.0
        }
        return out
    }

    /** Convenience for "today" in the device timezone. */
    fun today(cal: Calendar, lat: Double, lng: Double, method: String, hanafi: Boolean): IntArray {
        val tz = TimeZone.getDefault().getOffset(cal.timeInMillis) / 3_600_000.0
        val r = compute(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH), lat, lng, tz, method, hanafi)
        return IntArray(6) { Math.round(r[it]).toInt() }
    }

    private fun julian(y0: Int, m0: Int, d: Int): Double {
        var y = y0; var m = m0
        if (m <= 2) { y -= 1; m += 12 }
        val a = floor(y / 100.0)
        val b = 2 - a + floor(a / 4)
        return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + d + b - 1524.5
    }

    private fun fix(a: Double, b: Double): Double { val r = a - b * floor(a / b); return if (r < 0) r + b else r }
    private fun rad(d: Double) = d * Math.PI / 180.0
    private fun deg(r: Double) = r * 180.0 / Math.PI
    private fun dsin(d: Double) = sin(rad(d))
    private fun dcos(d: Double) = cos(rad(d))
    private fun dtan(d: Double) = tan(rad(d))
    private fun darcsin(x: Double) = deg(asin(x))
    private fun darccos(x: Double) = deg(acos(x))
    private fun darctan2(y: Double, x: Double) = deg(atan2(y, x))
    private fun darccot(x: Double) = deg(atan(1 / x))
}

object Hijri {
    private val AR = arrayOf("محرم", "صفر", "ربيع الأول", "ربيع الآخر", "جمادى الأولى", "جمادى الآخرة",
        "رجب", "شعبان", "رمضان", "شوال", "ذو القعدة", "ذو الحجة")
    private val EN = arrayOf("Muharram", "Safar", "Rabi I", "Rabi II", "Jumada I", "Jumada II",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qa'dah", "Dhu al-Hijjah")

    fun date(adjust: Int): HijrahDate? = try {
        HijrahChronology.INSTANCE.date(LocalDate.now().plusDays(adjust.toLong()))
    } catch (_: Throwable) { null }

    /** e.g. "14 جمادى الأولى 1448" */
    fun format(adjust: Int, arabic: Boolean, withYear: Boolean = true): String {
        val d = date(adjust) ?: return ""
        val day = d.get(ChronoField.DAY_OF_MONTH)
        val month = d.get(ChronoField.MONTH_OF_YEAR)
        val year = d.get(ChronoField.YEAR)
        val name = (if (arabic) AR else EN)[month - 1]
        return if (withYear) "$day $name $year" else "$day $name"
    }
}
