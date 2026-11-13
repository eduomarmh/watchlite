package com.watchlite.launcher.face

import android.content.Context
import android.graphics.Canvas
import android.os.SystemClock
import android.view.Choreographer
import android.view.View
import com.watchlite.launcher.App
import com.watchlite.launcher.theme.Theme
import com.watchlite.launcher.theme.ThemeCustom
import com.watchlite.launcher.util.Log
import java.util.Calendar

/**
 * The home watch face. Redraw policy:
 *  - animated background: Choreographer, capped at the user's FPS
 *  - seconds shown (no animation): once per second
 *  - otherwise: only on minute tick / data change (driven by HomeActivity)
 *  - paused (screen off, AOD, page covering the face): no redraws at all
 */
class WatchFaceView(ctx: Context) : View(ctx), Choreographer.FrameCallback {

    val renderer = FaceRenderer(ctx)
    var theme: Theme? = null
        private set
    private var custom = ThemeCustom(null, null, null)
    private var layers: List<Layer> = emptyList()
    private var layersFor: String? = null
    private var layersW = 0
    private var layersH = 0
    private val cal = Calendar.getInstance()
    private var running = false
    private var animated = false
    private var lastFrame = 0L
    private var paused = true
    var lowPower = false
        set(v) { if (field != v) { field = v; rebuildLayers(); updateLoop() } }

    private val secondTick = object : Runnable {
        override fun run() {
            if (paused) return
            invalidate()
            val now = System.currentTimeMillis()
            postDelayed(this, 1000 - now % 1000 + 5)
        }
    }

    init { setLayerType(LAYER_TYPE_NONE, null) }

    fun setTheme(t: Theme) {
        theme = t
        custom = App.I.themes.custom(t.id)
        rebuildLayers()
        updateLoop()
        invalidate()
    }

    fun refreshCustom() { theme?.let { custom = App.I.themes.custom(it.id) }; updateLoop(); invalidate() }

    private fun rebuildLayers() {
        layers.forEach { it.release() }
        layers = emptyList()
        layersFor = null
        if (width > 0 && height > 0) ensureLayers()
    }

    private fun ensureLayers() {
        val t = theme ?: return
        if (layersFor == t.id && layersW == width && layersH == height) return
        layers.forEach { it.release() }
        val start = SystemClock.elapsedRealtime()
        layers = Backgrounds.build(context, t, width, height, allowAnimation = !lowPower)
        layersFor = t.id; layersW = width; layersH = height
        animated = layers.any { it.animated }
        Log.i("Face", "layers for ${t.id} in ${SystemClock.elapsedRealtime() - start}ms animated=$animated")
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layersFor = null
        ensureLayers()
        updateLoop()
    }

    fun isAnimatingFrames() = running

    fun resume() { paused = false; updateLoop(); invalidate() }

    fun pause() {
        paused = true
        stopLoop()
        removeCallbacks(secondTick)
    }

    /** Frees big bitmaps (called when the screen goes off / AOD). */
    fun releaseHeavy() { layers.forEach { it.release() }; layers = emptyList(); layersFor = null }

    private fun wantsSeconds(): Boolean {
        val t = theme ?: return false
        return custom.seconds ?: t.hasSeconds
    }

    private fun updateLoop() {
        stopLoop(); removeCallbacks(secondTick)
        if (paused || theme == null) return
        if (animated && !lowPower) startLoop()
        else if (wantsSeconds() && !lowPower) post(secondTick)
    }

    private fun startLoop() { if (!running) { running = true; Choreographer.getInstance().postFrameCallback(this) } }
    private fun stopLoop() { if (running) { running = false; Choreographer.getInstance().removeFrameCallback(this) } }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        val now = frameTimeNanos / 1_000_000
        val interval = 1000 / App.prefs.fps.coerceIn(5, 60)
        if (now - lastFrame >= interval - 2) { lastFrame = now; invalidate() }
        Choreographer.getInstance().postFrameCallback(this)
    }

    override fun onDraw(c: Canvas) {
        val t = theme ?: return
        ensureLayers()
        val now = System.currentTimeMillis()
        cal.timeInMillis = now
        val anim = SystemClock.uptimeMillis()
        for (l in layers) l.draw(c, width, height, anim)
        renderer.draw(c, t, width, height, cal, now, custom)
    }

    override fun onDetachedFromWindow() { pause(); super.onDetachedFromWindow() }
}
