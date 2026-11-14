package com.watchlite.launcher

import com.watchlite.launcher.aod.AodPolicy
import com.watchlite.launcher.chime.ChimeScheduler
import com.watchlite.launcher.prayer.PrayerTimes
import com.watchlite.launcher.theme.ThemeRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.abs

class LogicTest {

    private fun hm(m: Double) = "%02d:%02d".format((m / 60).toInt(), (m % 60).toInt())

    @Test fun prayerTimesCairo() {
        // Cairo, 13 Nov 2026, UTC+2, Egyptian method. Reference values from published timetables (±6 min).
        val t = PrayerTimes.compute(2026, 11, 13, 30.0444, 31.2357, 2.0, "egypt", false)
        val expected = intArrayOf(4 * 60 + 48, 6 * 60 + 17, 11 * 60 + 41, 14 * 60 + 39, 16 * 60 + 59, 18 * 60 + 20)
        for (i in 0 until 6) assertTrue("prayer $i got ${hm(t[i])}", abs(t[i] - expected[i]) <= 8)
    }

    @Test fun prayerOrder() {
        val t = PrayerTimes.compute(2026, 6, 21, 21.3891, 39.8579, 3.0, "ummalqura", false)
        for (i in 1 until 6) assertTrue(t[i] > t[i - 1])
        assertEquals(90.0, t[5] - t[4], 1.5)
    }

    @Test fun daylightSavingSwitch() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Cairo"))
        val cal = Calendar.getInstance().apply { set(2026, Calendar.JULY, 1, 12, 0) }
        val on = PrayerTimes.today(cal, 30.0444, 31.2357, "egypt", false, "on")
        val off = PrayerTimes.today(cal, 30.0444, 31.2357, "egypt", false, "off")
        for (i in 0 until 6) assertEquals(60, on[i] - off[i])
        // Cairo, 1 July, summer time: Maghrib ≈ 19:59
        assertTrue("maghrib ${on[4]}", abs(on[4] - (19 * 60 + 59)) <= 6)
    }

    @Test fun aodHysteresis() {
        val p = AodPolicy()
        assertTrue(p.update(50, false, 30, true))
        assertFalse(p.update(29, false, 30, true))
        assertFalse(p.update(30, false, 30, true))   // still blocked (hysteresis)
        assertFalse(p.update(31, false, 30, true))
        assertTrue(p.update(32, false, 30, true))
        assertTrue(p.update(10, true, 30, true))     // charging allowed
        assertFalse(p.update(10, true, 30, false))
    }

    @Test fun aodChargingModes() {
        val p = AodPolicy()
        assertFalse(p.update(90, true, 30, "never"))   // charging + "off while charging"
        assertTrue(p.update(90, false, 30, "never"))   // unplugged: normal rules again
        assertTrue(p.update(10, true, 30, "always"))   // charging ignores the battery limit
        assertFalse(p.update(10, true, 30, "normal"))  // charging, but below the limit
    }

    @Test fun chimeNextHour() {
        TimeZone.setDefault(TimeZone.getTimeZone("Africa/Cairo"))
        val c = Calendar.getInstance().apply { set(2026, Calendar.NOVEMBER, 13, 14, 59, 30); set(Calendar.MILLISECOND, 0) }
        val next = ChimeScheduler.nextTrigger(c.timeInMillis, 8, 23, 0x7F)
        assertNotNull(next)
        val n = Calendar.getInstance().apply { timeInMillis = next!! }
        assertEquals(15, n.get(Calendar.HOUR_OF_DAY)); assertEquals(0, n.get(Calendar.MINUTE)); assertEquals(0, n.get(Calendar.SECOND))
        // outside hours -> next morning 08:00
        c.set(Calendar.HOUR_OF_DAY, 23); c.set(Calendar.MINUTE, 10)
        val m = Calendar.getInstance().apply { timeInMillis = ChimeScheduler.nextTrigger(c.timeInMillis, 8, 23, 0x7F)!! }
        assertEquals(8, m.get(Calendar.HOUR_OF_DAY)); assertEquals(14, m.get(Calendar.DAY_OF_MONTH))
    }

    @Test fun nightRange() {
        assertTrue(ThemeRepository.inRange(22, 20, 6))
        assertTrue(ThemeRepository.inRange(3, 20, 6))
        assertFalse(ThemeRepository.inRange(12, 20, 6))
        assertTrue(ThemeRepository.inRange(10, 9, 17))
    }

    @Test fun burnInOrbitIsEvenAndBounded() {
        val r = 8f
        val seen = HashSet<String>()
        for (m in 0L until 16L) {
            val o = com.watchlite.launcher.aod.AodView.burnInOffset(m, r)
            assertTrue(Math.hypot(o[0].toDouble(), o[1].toDouble()) <= r + 0.01)
            seen.add("%.2f,%.2f".format(o[0], o[1]))
            val n = com.watchlite.launcher.aod.AodView.burnInOffset(m + 8, r)
            assertEquals(o[0], n[0], 0.001f); assertEquals(o[1], n[1], 0.001f)
        }
        assertEquals(8, seen.size)
        // consecutive minutes move only a small step (never a full jump across)
        val a = com.watchlite.launcher.aod.AodView.burnInOffset(5, r)
        val b = com.watchlite.launcher.aod.AodView.burnInOffset(6, r)
        assertTrue(Math.hypot((a[0] - b[0]).toDouble(), (a[1] - b[1]).toDouble()) < r)
    }
}
