package com.watchlite.launcher.chime

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.Uri
import android.os.PowerManager
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Log
import java.io.File
import java.util.Calendar

object ChimeSounds {
    /** Bundled sounds: id -> label res. */
    val BUILT_IN = linkedMapOf(
        "chime_soft_bell" to R.string.snd_soft_bell,
        "chime_double_chime" to R.string.snd_double,
        "chime_marimba" to R.string.snd_marimba,
        "chime_digital" to R.string.snd_digital,
        "chime_tower_bell" to R.string.snd_tower,
        "chime_water_drop" to R.string.snd_drop,
        "chime_harp" to R.string.snd_harp,
        "chime_glass" to R.string.snd_glass,
        "chime_wood" to R.string.snd_wood,
        "chime_music_box" to R.string.snd_music_box
    )

    fun dir(ctx: Context) = File(ctx.filesDir, "sounds").apply { mkdirs() }
    fun userSounds(ctx: Context): List<File> = dir(ctx).listFiles()?.sortedBy { it.name } ?: emptyList()

    fun uri(ctx: Context, id: String): Uri? = when {
        id.startsWith("res:") -> {
            val name = id.removePrefix("res:")
            val res = ctx.resources.getIdentifier(name, "raw", ctx.packageName)
            if (res == 0) null else Uri.parse("android.resource://${ctx.packageName}/$res")
        }
        id.startsWith("file:") -> File(dir(ctx), id.removePrefix("file:")).takeIf { it.isFile }?.let { Uri.fromFile(it) }
        else -> null
    }

    fun label(ctx: Context, id: String): String = when {
        id.startsWith("res:") -> BUILT_IN[id.removePrefix("res:")]?.let { ctx.getString(it) } ?: id
        else -> id.removePrefix("file:").substringBeforeLast('.')
    }

    /** Copies a user-picked audio file into app storage. */
    fun import(ctx: Context, uri: Uri, name: String): String {
        val safe = name.replace(Regex("[^\\p{L}\\p{N}._ -]"), "_").take(60).ifEmpty { "sound" }
        val f = File(dir(ctx), safe)
        ctx.contentResolver.openInputStream(uri)!!.use { input ->
            f.outputStream().use { out ->
                val buf = ByteArray(16 * 1024); var total = 0
                while (true) { val n = input.read(buf); if (n < 0) break; total += n; require(total < 5 * 1024 * 1024) { "file too large" }; out.write(buf, 0, n) }
            }
        }
        return "file:" + f.name
    }

    /** Plays [count] times in a row. Calls [done] at the end. */
    fun play(ctx: Context, id: String, volume: Float, count: Int = 1, done: () -> Unit = {}) {
        val u = uri(ctx, id) ?: uri(ctx, "res:chime_soft_bell") ?: run { done(); return }
        var left = count
        fun once() {
            val mp = MediaPlayer()
            try {
                mp.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                mp.setDataSource(ctx, u)
                mp.setVolume(volume, volume)
                mp.setOnCompletionListener { it.release(); left--; if (left > 0) once() else done() }
                mp.setOnErrorListener { p, _, _ -> p.release(); done(); true }
                mp.prepare()
                mp.start()
            } catch (t: Throwable) { Log.w("Chime", "play failed", t); mp.release(); done() }
        }
        once()
    }
}

object ChimeScheduler {
    private fun pi(ctx: Context) = PendingIntent.getBroadcast(ctx, 11, Intent(ctx, ChimeReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    /** Next exact top-of-hour inside the active hours/days, or null. Pure (unit tested). */
    fun nextTrigger(now: Long, from: Int, to: Int, days: Int): Long? {
        val c = Calendar.getInstance().apply { timeInMillis = now; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }
        repeat(24 * 8) {
            c.add(Calendar.HOUR_OF_DAY, 1)
            val h = c.get(Calendar.HOUR_OF_DAY)
            val dow = (c.get(Calendar.DAY_OF_WEEK) + 5) % 7 // Monday = 0
            val hourOk = if (from <= to) h in from..to else h >= from || h <= to
            if (hourOk && (days shr dow) and 1 == 1) return c.timeInMillis
        }
        return null
    }

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.cancel(pi(ctx))
        val p = App.prefs
        if (!p.chimeEnabled) return
        val t = nextTrigger(System.currentTimeMillis(), p.chimeFrom, p.chimeTo, p.chimeDays) ?: return
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, t, pi(ctx))
    }
}

class ChimeReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ChimeScheduler.schedule(ctx)
        val p = App.prefs
        if (!p.chimeEnabled || p.theater) return
        if (p.chimeRespectDnd) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.currentInterruptionFilter > NotificationManager.INTERRUPTION_FILTER_ALL) return
        }
        val pending = goAsync()
        val wl = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "watchlite:chime").apply { acquire(20_000) }
        val h = Calendar.getInstance().get(Calendar.HOUR)
        val count = if (p.chimeMode == "strikes") (if (h == 0) 12 else h) else 1
        if (p.chimeVibrate) Haptics.pattern(ctx, longArrayOf(0, 40, 90, 40))
        var finished = false
        val finish = {
            if (!finished) { finished = true; if (wl.isHeld) wl.release(); try { pending.finish() } catch (_: Throwable) { } }
        }
        ChimeSounds.play(ctx, p.chimeSound, p.chimeVolume, count) { finish() }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({ finish() }, 9_000)
    }
}
