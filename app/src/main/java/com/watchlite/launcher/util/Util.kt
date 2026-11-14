package com.watchlite.launcher.util

import android.content.Context
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.format.DateFormat
import android.util.DisplayMetrics
import android.view.WindowManager
import com.watchlite.launcher.App
import com.watchlite.launcher.Prefs
import java.io.File
import java.util.Locale
import java.util.concurrent.Executors

/** Tiny in-memory ring-buffer logger, exportable from Diagnostics. */
object Log {
    private const val MAX = 300
    private val lines = ArrayDeque<String>()
    private val fmt = java.text.SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        if (lines.size >= MAX) lines.removeFirst()
        lines.addLast("${fmt.format(System.currentTimeMillis())} $level/$tag: $msg")
    }

    fun i(tag: String, msg: String) { android.util.Log.i("WL-$tag", msg); add("I", tag, msg) }
    fun w(tag: String, msg: String, t: Throwable? = null) {
        android.util.Log.w("WL-$tag", msg, t); add("W", tag, msg + (t?.let { " :: $it" } ?: ""))
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")
}

/** Shared background executor (single thread keeps memory & CPU low). */
object Bg {
    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "wl-bg").apply { priority = Thread.MIN_PRIORITY + 1 } }
    val main = Handler(Looper.getMainLooper())
    fun run(block: () -> Unit) = exec.execute {
        try { block() } catch (t: Throwable) { Log.w("Bg", "task failed", t) }
    }
    fun ui(block: () -> Unit) = main.post(block)
}

/** Screen geometry. All launcher layout is derived from this. */
class ScreenProfile(val width: Int, val height: Int, val dpi: Int, val isRound: Boolean) {
    val shortSide = minOf(width, height)
    /** 1 % of the short side, the base unit of every UI measurement. */
    val u: Float = shortSide / 100f
    /** Padding that keeps content away from corners on round screens. */
    val safe: Int = if (isRound) (shortSide * 0.11f).toInt() else (shortSide * 0.04f).toInt()

    companion object {
        fun of(ctx: Context): ScreenProfile {
            val wm = ctx.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            val dm = DisplayMetrics()
            @Suppress("DEPRECATION") wm.defaultDisplay.getRealMetrics(dm)
            val round = ctx.resources.configuration.isScreenRound
            return ScreenProfile(dm.widthPixels, dm.heightPixels, dm.densityDpi, round)
        }
    }
    override fun toString() = "${width}x$height @${dpi}dpi round=$isRound"
}

/** Western <-> Arabic-Indic digit conversion. */
object Digits {
    private const val AR = "٠١٢٣٤٥٦٧٨٩"

    /** Global mode: latin | arabic | auto (= Arabic-Indic when the UI language is Arabic). */
    fun useArabic(mode: String = App.prefs.digits): Boolean = when (mode) {
        "arabic", "arabicIndic" -> true
        "latin" -> false
        else -> Locale.getDefault().language == "ar"
    }

    /** Theme element mode: "auto" defers to the global setting. */
    fun forElement(mode: String?): Boolean =
        if (mode == null || mode == "auto") useArabic() else useArabic(mode)

    fun apply(s: String, arabic: Boolean): String {
        if (!arabic) return s
        val sb = StringBuilder(s.length)
        for (c in s) sb.append(if (c in '0'..'9') AR[c - '0'] else if (c == '%') '٪' else c)
        return sb.toString()
    }

    fun fmt(n: Int, arabic: Boolean = useArabic()) = apply(n.toString(), arabic)
}

object TimeFmt {
    fun is24(ctx: Context): Boolean = when (App.prefs.clock) {
        "24" -> true
        "12" -> false
        else -> DateFormat.is24HourFormat(ctx)
    }
}

object Haptics {
    fun tick(ctx: Context, strong: Boolean = false) {
        val level = App.prefs.haptics
        if (level == 0) return
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        if (!v.hasVibrator()) return
        val ms = if (strong || level == 2) 22L else 9L
        val amp = if (strong || level == 2) 220 else 90
        try {
            v.vibrate(VibrationEffect.createOneShot(ms, if (v.hasAmplitudeControl()) amp else VibrationEffect.DEFAULT_AMPLITUDE))
        } catch (_: Throwable) { }
    }

    fun pattern(ctx: Context, p: LongArray) {
        val v = ctx.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator ?: return
        try { v.vibrate(VibrationEffect.createWaveform(p, -1)) } catch (_: Throwable) { }
    }
}

/** Typeface cache. Theme fonts may come from asset fonts or from a theme folder. */
object Fonts {
    private val cache = HashMap<String, Typeface>()

    @Synchronized
    fun get(ctx: Context, name: String?, themeDir: File? = null): Typeface {
        val key = (themeDir?.path ?: "") + "|" + (name ?: "")
        cache[key]?.let { return it }
        val tf = try {
            when {
                name.isNullOrEmpty() -> Typeface.createFromAsset(ctx.assets, "fonts/tajawal_medium.ttf")
                themeDir != null && File(themeDir, name).isFile -> Typeface.createFromFile(File(themeDir, name))
                else -> Typeface.createFromAsset(ctx.assets, "fonts/${name.removeSuffix(".ttf")}.ttf")
            }
        } catch (t: Throwable) {
            Log.w("Fonts", "font $name missing")
            Typeface.DEFAULT
        }
        cache[key] = tf
        return tf
    }

    fun ui(ctx: Context) = get(ctx, "tajawal_medium")
    fun uiBold(ctx: Context) = get(ctx, "tajawal_bold")
    fun uiLight(ctx: Context) = get(ctx, "tajawal_light")
}

object LocaleHelper {
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE).getString("language", "system") ?: "system"
        if (lang == "system") return base
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }

    fun isRtl(ctx: Context) = ctx.resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
}

/** Live device state shared by the face, widgets and pages. */
class DeviceState {
    @Volatile var battery = 100
    @Volatile var charging = false
    @Volatile var phoneBattery = -1
    @Volatile var phoneCharging = false
    @Volatile var phoneNet = ""
    @Volatile var weatherTemp = ""
    @Volatile var weatherDesc = ""
    @Volatile var mediaTitle = ""
    @Volatile var mediaArtist = ""
    @Volatile var mediaPlaying = false
    /** Cover art of the track playing on the phone (small JPEG sent with media.state), or null. */
    @Volatile var mediaArt: android.graphics.Bitmap? = null
    @Volatile var linkConnected = false

    private val listeners = ArrayList<() -> Unit>()
    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    fun changed() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        Bg.ui { copy.forEach { it() } }
    }
}

fun isAtLeastP() = Build.VERSION.SDK_INT >= 28
