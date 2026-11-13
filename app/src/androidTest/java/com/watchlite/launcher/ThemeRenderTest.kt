package com.watchlite.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.watchlite.launcher.face.Backgrounds
import com.watchlite.launcher.face.FaceRenderer
import com.watchlite.launcher.theme.ThemeCustom
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.Calendar

/** Renders every bundled theme (normal + AOD) at several watch sizes into PNGs for visual review in CI. */
@RunWith(AndroidJUnit4::class)
class ThemeRenderTest {

    /** Skip the first-run overlay so the CI gesture flow sees the watch face. */
    @Test fun markOnboarded() {
        App.prefs.sp.edit().putBoolean("onboarded", true).putString("theme_id", "flow")
            .putBoolean("aod_prayer", true).putBoolean("aod_prayer_cd", true).commit()
    }

    @Test fun renderIconThemes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "shots").apply { mkdirs() }
        val cats = com.watchlite.launcher.drawer.IconTheme.CATS.keys.toList() + listOf("", "")
        val fallback = ctx.packageManager.getApplicationIcon("com.android.settings")
        val size = 96; val gap = 12; val cols = 8
        for (style in com.watchlite.launcher.drawer.IconTheme.STYLES) {
            val rows = (cats.size + cols - 1) / cols
            val sheet = Bitmap.createBitmap(cols * (size + gap) + gap, rows * (size + gap) + gap, Bitmap.Config.ARGB_8888)
            val c = Canvas(sheet); c.drawColor(0xFF000000.toInt())
            cats.forEachIndexed { i, cat ->
                val b = com.watchlite.launcher.drawer.IconTheme.render(ctx, style, "x.unknown", fallback, size, 0xFFFF9F0A.toInt(), cat.ifEmpty { null })
                c.drawBitmap(b, (gap + (i % cols) * (size + gap)).toFloat(), (gap + (i / cols) * (size + gap)).toFloat(), null)
            }
            File(out, "icons_$style.png").outputStream().use { sheet.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test fun renderAllThemes() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val out = File(ctx.getExternalFilesDir(null), "shots").apply { deleteRecursively(); mkdirs() }
        val themes = App.I.themes.all()
        assertTrue("themes loaded: ${themes.size}", themes.size >= 20)
        val cal = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 10); set(Calendar.MINUTE, 9); set(Calendar.SECOND, 36) }
        val sizes = listOf(320 to 385, 360 to 360)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val r = FaceRenderer(ctx)
            for ((w, h) in sizes) for (t in themes) {
                val start = System.nanoTime()
                val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val c = Canvas(bmp)
                val layers = Backgrounds.build(ctx, t, w, h, true)
                // draw a few frames so animated layers reach a representative state
                for (f in 0..3) layers.forEach { it.draw(c, w, h, 4_000L + f * 40L) }
                r.draw(c, t, w, h, cal, cal.timeInMillis, ThemeCustom(null, null, null))
                val ms = (System.nanoTime() - start) / 1_000_000
                File(out, "${w}x${h}_${t.id}.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                if (w == 320 && t.elements.any { it.defaultOff }) { // same theme with every complication switched on
                    val all = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888); val ca = Canvas(all)
                    layers.forEach { it.draw(ca, w, h, 4_000L) }
                    r.draw(ca, t, w, h, cal, cal.timeInMillis, ThemeCustom(null, null, null, emptySet()))
                    File(out, "${w}x${h}_${t.id}_all.png").outputStream().use { all.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    all.recycle()
                }
                // AOD
                val a = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val ac = Canvas(a); ac.drawColor(0xFF000000.toInt())
                r.draw(ac, t, w, h, cal, cal.timeInMillis, ThemeCustom(null, null, false), aod = true, aodAlpha = 0.75f)
                r.drawAodWidgets(ac, w, h, cal, 0.75f)
                File(out, "${w}x${h}_${t.id}_aod.png").outputStream().use { a.compress(Bitmap.CompressFormat.PNG, 100, it) }
                // per-frame cost of animated layers
                val f0 = System.nanoTime(); repeat(10) { k -> layers.forEach { it.draw(c, w, h, 5_000L + k * 40L) } }
                val frame = (System.nanoTime() - f0) / 10_000_000.0
                File(out, "timings.txt").appendText("${w}x$h ${t.id} build=${ms}ms frame=${"%.2f".format(frame)}ms\n")
                layers.forEach { it.release() }; bmp.recycle(); a.recycle()
            }
        }
    }
}
