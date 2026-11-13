package com.watchlite.launcher.drawer

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import com.watchlite.launcher.R
import com.watchlite.launcher.util.Log

/**
 * WatchLite's own icon theme: original, modern glyph icons for system apps
 * (phone, messages, camera, gallery, settings, clock…), in three styles:
 *  wl_line      thin white line glyph on black with a fine ring
 *  wl_gradient  white glyph on a rich two-tone gradient
 *  wl_neon      glowing coloured glyph and ring on black
 * Apps without a matching glyph get a frame in the same style, so the whole drawer matches.
 */
object IconTheme {

    val STYLES = listOf("wl_line", "wl_gradient", "wl_neon")

    class Cat(val outline: Int, val filled: Int, val c1: Int, val c2: Int)

    private fun c(o: Int, f: Int, a: Long, b: Long) = Cat(o, f, a.toInt(), b.toInt())

    val CATS: Map<String, Cat> = mapOf(
        "phone" to c(R.drawable.gl_call_o, R.drawable.gl_call_f, 0xFF34C759, 0xFF0B8457),
        "messages" to c(R.drawable.gl_sms_o, R.drawable.gl_sms_f, 0xFF5AC8FA, 0xFF0060DF),
        "camera" to c(R.drawable.gl_photo_camera_o, R.drawable.gl_photo_camera_f, 0xFF9A9AA2, 0xFF2C2C30),
        "gallery" to c(R.drawable.gl_photo_library_o, R.drawable.gl_photo_library_f, 0xFFFFB340, 0xFFFF2D6F),
        "settings" to c(R.drawable.gl_settings_o, R.drawable.gl_settings_f, 0xFF8E8E96, 0xFF3A3A40),
        "clock" to c(R.drawable.gl_schedule_o, R.drawable.gl_schedule_f, 0xFFFFA733, 0xFFE85D04),
        "alarm" to c(R.drawable.gl_alarm_o, R.drawable.gl_alarm_f, 0xFFFF6B5E, 0xFFC0182A),
        "calculator" to c(R.drawable.gl_calculate_o, R.drawable.gl_calculate_f, 0xFF6E6E78, 0xFF1C1C22),
        "music" to c(R.drawable.gl_music_note_o, R.drawable.gl_music_note_f, 0xFFFF4D7A, 0xFF9B4DFF),
        "maps" to c(R.drawable.gl_map_o, R.drawable.gl_map_f, 0xFF3DDC84, 0xFF1878F0),
        "browser" to c(R.drawable.gl_public_o, R.drawable.gl_public_f, 0xFF3D9BFF, 0xFF5E4BE6),
        "files" to c(R.drawable.gl_folder_o, R.drawable.gl_folder_f, 0xFF6ED3FF, 0xFF1A78F0),
        "contacts" to c(R.drawable.gl_contacts_o, R.drawable.gl_contacts_f, 0xFFC49A6C, 0xFF7A5230),
        "calendar" to c(R.drawable.gl_calendar_month_o, R.drawable.gl_calendar_month_f, 0xFFFF5A4E, 0xFFFF9F1A),
        "email" to c(R.drawable.gl_mail_o, R.drawable.gl_mail_f, 0xFF4FA8FF, 0xFF1F5FD6),
        "recorder" to c(R.drawable.gl_mic_o, R.drawable.gl_mic_f, 0xFFFF5147, 0xFF8E1020),
        "video" to c(R.drawable.gl_play_circle_o, R.drawable.gl_play_circle_f, 0xFFC86BFF, 0xFF5B3DE0),
        "videocam" to c(R.drawable.gl_videocam_o, R.drawable.gl_videocam_f, 0xFFFF7A59, 0xFFB8323A),
        "fitness" to c(R.drawable.gl_fitness_center_o, R.drawable.gl_fitness_center_f, 0xFF7CE36A, 0xFF1E9E55),
        "weather" to c(R.drawable.gl_cloud_o, R.drawable.gl_cloud_f, 0xFF7FD8FF, 0xFF2F86E0),
        "chat" to c(R.drawable.gl_chat_o, R.drawable.gl_chat_f, 0xFF4CD964, 0xFF0F9D58),
        "watch" to c(R.drawable.gl_watch_o, R.drawable.gl_watch_f, 0xFFFFA733, 0xFFFF5E3A)
    )

    @Volatile private var categories: Map<String, String> = emptyMap()

    /** Works out which installed package is the phone, camera, gallery… app. */
    fun detect(ctx: Context) {
        val pm = ctx.packageManager
        val out = HashMap<String, String>()
        fun add(i: Intent, cat: String) {
            try { pm.queryIntentActivities(i, 0).forEach { out.putIfAbsent(it.activityInfo.packageName, cat) } } catch (_: Throwable) { }
        }
        fun sel(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)
        out[ctx.packageName] = "watch"
        add(Intent(Intent.ACTION_DIAL), "phone")
        add(sel(Intent.CATEGORY_APP_MESSAGING), "messages")
        add(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "camera")
        add(Intent(MediaStore.ACTION_IMAGE_CAPTURE), "camera")
        add(sel(Intent.CATEGORY_APP_GALLERY), "gallery")
        add(Intent(Settings.ACTION_SETTINGS), "settings")
        add(Intent(AlarmClock.ACTION_SHOW_ALARMS), "clock")
        add(sel(Intent.CATEGORY_APP_CALCULATOR), "calculator")
        add(sel(Intent.CATEGORY_APP_MUSIC), "music")
        add(sel(Intent.CATEGORY_APP_MAPS), "maps")
        add(sel(Intent.CATEGORY_APP_BROWSER), "browser")
        add(sel(Intent.CATEGORY_APP_CONTACTS), "contacts")
        add(sel(Intent.CATEGORY_APP_CALENDAR), "calendar")
        add(sel(Intent.CATEGORY_APP_EMAIL), "email")
        add(Intent(MediaStore.Audio.Media.RECORD_SOUND_ACTION), "recorder")
        categories = out
        Log.i("IconTheme", "detected ${out.size} system apps")
    }

    /** Category for a package (intent detection first, then common package-name hints). */
    fun categoryFor(pkg: String): String? {
        categories[pkg]?.let { return it }
        val p = pkg.lowercase()
        return when {
            "documentsui" in p || "filemanager" in p || p.endsWith(".files") || "fileexplorer" in p -> "files"
            "deskclock" in p || p.endsWith(".clock") -> "clock"
            "calculator" in p -> "calculator"
            "soundrecorder" in p || "recorder" in p -> "recorder"
            "weather" in p -> "weather"
            "videoplayer" in p || p.endsWith(".video") -> "video"
            "fitness" in p || "health" in p || "sport" in p || "pedometer" in p -> "fitness"
            "music" in p || "fmradio" in p -> "music"
            "gallery" in p -> "gallery"
            "camera" in p -> "camera"
            "contacts" in p -> "contacts"
            "calendar" in p -> "calendar"
            "dialer" in p -> "phone"
            "mms" in p || "messaging" in p -> "messages"
            p == "com.android.settings" -> "settings"
            else -> null
        }
    }

    private val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    fun render(ctx: Context, iconStyle: String, pkg: String, original: Drawable, size: Int, accent: Int,
               forceCat: String? = null): Bitmap {
        val cat = (forceCat ?: categoryFor(pkg))?.let { CATS[it] }
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val s = size.toFloat(); val r = s / 2
        p.reset(); p.isAntiAlias = true; p.isFilterBitmap = true
        when (iconStyle) {
            "wl_gradient" -> {
                val c1 = cat?.c1 ?: lighten(accent); val c2 = cat?.c2 ?: darken(accent)
                p.shader = LinearGradient(0f, 0f, s, s, c1, c2, Shader.TileMode.CLAMP)
                c.drawCircle(r, r, r, p); p.shader = null
                // soft top light for depth
                p.shader = RadialGradient(r * 0.7f, r * 0.45f, r * 1.1f, 0x40FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
                c.drawCircle(r, r, r, p); p.shader = null
                if (cat != null) glyph(ctx, c, cat.filled, s, 0.5f, Color.WHITE, shadow = true)
                else inset(c, original, s, 0.6f)
            }
            "wl_neon" -> {
                val col = cat?.c1 ?: accent
                p.color = 0xFF050507.toInt(); c.drawCircle(r, r, r, p)
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.035f; color = col }
                val glow = Paint(ring).apply { maskFilter = BlurMaskFilter(s * 0.05f, BlurMaskFilter.Blur.NORMAL); strokeWidth = s * 0.06f; alpha = 170 }
                c.drawCircle(r, r, r * 0.86f, glow)
                c.drawCircle(r, r, r * 0.86f, ring)
                if (cat != null) glyph(ctx, c, cat.outline, s, 0.46f, col, glow = true)
                else inset(c, original, s, 0.56f)
            }
            else -> { // wl_line
                p.color = 0xFF0E0E11.toInt(); c.drawCircle(r, r, r, p)
                val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = s * 0.022f; color = 0xCCFFFFFF.toInt() }
                c.drawCircle(r, r, r - ring.strokeWidth * 1.5f, ring)
                if (cat != null) glyph(ctx, c, cat.outline, s, 0.48f, Color.WHITE)
                else inset(c, original, s, 0.58f)
            }
        }
        return out
    }

    private fun glyph(ctx: Context, c: Canvas, res: Int, s: Float, frac: Float, color: Int, shadow: Boolean = false, glow: Boolean = false) {
        val d = ctx.getDrawable(res)?.mutate() ?: return
        val g = (s * frac).toInt()
        val bmp = Bitmap.createBitmap(g, g, Bitmap.Config.ARGB_8888)
        d.colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN)
        d.setBounds(0, 0, g, g); d.draw(Canvas(bmp))
        val x = (s - g) / 2f; val y = (s - g) / 2f
        if (shadow || glow) {
            val bp = Paint().apply { maskFilter = BlurMaskFilter(s * (if (glow) 0.05f else 0.03f), BlurMaskFilter.Blur.NORMAL) }
            val off = IntArray(2)
            val mask = bmp.extractAlpha(bp, off)
            val mp = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = if (glow) color else 0x55000000; alpha = if (glow) 200 else 90 }
            c.drawBitmap(mask, x + off[0], y + off[1] + if (shadow) s * 0.02f else 0f, mp)
            mask.recycle()
        }
        c.drawBitmap(bmp, x, y, p)
        bmp.recycle()
    }

    private fun inset(c: Canvas, d: Drawable, s: Float, frac: Float) {
        val pad = s * (1 - frac) / 2
        val b = Bitmap.createBitmap(s.toInt(), s.toInt(), Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, s.toInt(), s.toInt()); d.draw(Canvas(b))
        c.drawBitmap(b, null, RectF(pad, pad, s - pad, s - pad), p)
        b.recycle()
    }

    private fun lighten(c: Int) = Color.rgb((Color.red(c) + 255) / 2, (Color.green(c) + 255) / 2, (Color.blue(c) + 255) / 2)
    private fun darken(c: Int) = Color.rgb(Color.red(c) * 2 / 3, Color.green(c) * 2 / 3, Color.blue(c) * 2 / 3)
}
