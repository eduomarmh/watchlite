package com.watchlite.launcher.drawer

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Process
import android.os.UserHandle
import com.watchlite.launcher.App
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Log
import org.json.JSONObject
import java.text.Collator
import java.util.Locale

class AppEntry(val component: ComponentName, val originalLabel: String, val user: UserHandle) {
    val key: String = component.flattenToShortString()
    var label: String = originalLabel
    @Volatile var icon: Bitmap? = null
    /** Signature (style/pack/accent/size) the current [icon] was rendered with. */
    @Volatile var iconSig: String? = null
    val pkg: String get() = component.packageName
}

/**
 * Lists launchable apps, keeps a small icon cache, applies hide/rename/favorites.
 */
class AppRepository(private val ctx: Context) {
    companion object { const val ICON_VER = 3 }

    private val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    @Volatile private var all: List<AppEntry> = emptyList()
    private val listeners = ArrayList<() -> Unit>()
    @Volatile var iconSize = App.prefs.sp.getInt("icon_size_px", 96)
    @Volatile private var loading = false
    @Volatile private var pending = false
    private val cacheDir = java.io.File(ctx.cacheDir, "icons").apply { mkdirs() }

    fun listen(l: () -> Unit) { listeners.add(l) }
    fun unlisten(l: () -> Unit) { listeners.remove(l) }
    private fun changed() = Bg.ui { listeners.toList().forEach { it() } }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String, user: UserHandle) = reload()
        override fun onPackageAdded(packageName: String, user: UserHandle) = reload()
        override fun onPackageChanged(packageName: String, user: UserHandle) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = reload()
    }

    init {
        la.registerCallback(callback, Bg.main)
        reload()
    }

    /** All visible apps (hidden removed), favorites first then alphabetical. */
    fun visible(): List<AppEntry> {
        val hidden = App.prefs.hiddenApps
        val fav = App.prefs.favoriteApps
        return all.filter { it.key !in hidden }.sortedWith(compareBy<AppEntry> { if (it.key in fav) 0 else 1 }.thenComparator { a, b -> collator.compare(a.label, b.label) })
    }

    fun everything(): List<AppEntry> = all

    private val collator: Collator get() = Collator.getInstance(Locale.getDefault())

    /**
     * Lists apps and renders icons in the background.
     * - Rendered icons are cached on disk, so after the launcher process is killed
     *   (frequent on Android Go) icons come back instantly instead of re-rendering.
     * - The UI is refreshed progressively while icons load, not only at the end.
     * - An icon is replaced only when a new one is ready (no blank bubbles while zooming).
     * - A reload requested while one is running is queued, never dropped.
     */
    fun reload() {
        synchronized(this) { if (loading) { pending = true; return }; loading = true; pending = false }
        Bg.run {
            try { doReload() } finally {
                val again = synchronized(this) { loading = false; pending }
                if (again) reload()
            }
        }
    }

    private fun doReload() {
        val me = ctx.packageName
        val renamed = try { JSONObject(App.prefs.renamedApps) } catch (_: Exception) { JSONObject() }
        val old = all.associateBy { it.key }
        val list = la.getActivityList(null, Process.myUserHandle()).mapNotNull { info ->
            val cn = info.componentName
            if (cn.packageName == me && cn.className.endsWith("HomeActivity")) return@mapNotNull null
            AppEntry(cn, info.label?.toString() ?: cn.packageName, info.user).also { e ->
                e.label = renamed.optString(e.key, e.originalLabel).ifEmpty { e.originalLabel }
                old[e.key]?.let { o -> e.icon = o.icon; e.iconSig = o.iconSig }
            }
        }
        all = list
        changed()
        val packPkg = App.prefs.iconPack
        if (packPkg != (pack?.pkg ?: "")) pack = if (packPkg.isEmpty()) null else IconPack(ctx, packPkg).takeIf { it.load() }
        val style = App.prefs.iconStyle
        val accent = App.prefs.accent
        val size = iconSize
        val sig = "$style|$packPkg|${Integer.toHexString(accent)}|$size|$ICON_VER"
        // 1) fast pass: everything already cached on disk
        var n = 0
        for (e in list) {
            if (e.iconSig == sig) continue
            val f = cacheFile(e, sig, list)
            if (f.exists()) try {
                android.graphics.BitmapFactory.decodeFile(f.path)?.let { e.icon = it; e.iconSig = sig; n++ }
            } catch (_: Throwable) { }
        }
        if (n > 0) changed()
        // 2) slow pass: render the rest, refreshing the UI every few icons
        var detected = false
        n = 0
        for (e in list) {
            if (e.iconSig == sig) continue
            if (iconSize != size || App.prefs.iconStyle != style) { pending = true; return }
            try {
                if (!detected && style in IconTheme.STYLES) { IconTheme.detect(ctx); detected = true }
                val packIcon = pack?.drawableFor(e.component)
                val d = packIcon ?: la.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(e.component), e.user)?.getBadgedIcon(0) ?: continue
                val bmp = if (style in IconTheme.STYLES && packIcon == null) IconTheme.render(ctx, style, e.pkg, d, size, accent)
                    else IconStyler.render(d, size, style, accent, packIcon != null)
                e.icon = bmp; e.iconSig = sig
                try { java.io.FileOutputStream(cacheFile(e, sig, list)).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } } catch (_: Throwable) { }
                if (++n % 6 == 0) changed()
            } catch (t: Throwable) { Log.w("Apps", "icon ${e.key}", t) }
        }
        if (n > 0) changed()
        pruneCache(sig)
    }

    private fun cacheFile(e: AppEntry, sig: String, @Suppress("UNUSED_PARAMETER") l: List<AppEntry>): java.io.File {
        val ver = try { ctx.packageManager.getPackageInfo(e.pkg, 0).lastUpdateTime } catch (_: Exception) { 0L }
        val name = Integer.toHexString("${e.key}|$ver".hashCode()) + "_" + Integer.toHexString(sig.hashCode())
        return java.io.File(cacheDir, "$name.png")
    }

    /** Drops cached icons rendered with old settings so the cache never grows. */
    private fun pruneCache(sig: String) {
        val tag = "_" + Integer.toHexString(sig.hashCode()) + ".png"
        cacheDir.listFiles()?.forEach { if (!it.name.endsWith(tag)) it.delete() }
    }

    /** Larger icons are rendered in steps; old icons stay visible until replaced. */
    fun requestIconSize(px: Int) {
        val target = ((px.coerceIn(48, 192) + 23) / 24) * 24
        if (target <= iconSize) return
        iconSize = target
        App.prefs.sp.edit().putInt("icon_size_px", target).apply()   // remembered → disk cache stays valid
        reload()
    }

    private var pack: IconPack? = null

    /** Re-renders every icon (style or icon pack changed). */
    fun restyle() = reload()   // signature changes → icons re-render, old ones shown meanwhile

    fun trimIcons() { /* icons are small; kept so the drawer opens instantly */ }

    fun launch(e: AppEntry, opts: android.os.Bundle?) {
        com.watchlite.launcher.system.RecentApps.record(ctx, e.pkg)
        try { la.startMainActivity(e.component, e.user, null, opts) }
        catch (t: Throwable) { Log.w("Apps", "launch failed ${e.key}", t) }
    }

    fun rename(e: AppEntry, label: String?) {
        val o = try { JSONObject(App.prefs.renamedApps) } catch (_: Exception) { JSONObject() }
        if (label.isNullOrBlank()) o.remove(e.key) else o.put(e.key, label)
        App.prefs.renamedApps = o.toString()
        e.label = label?.takeIf { it.isNotBlank() } ?: e.originalLabel
        changed()
    }

    fun toggleHidden(key: String) {
        val s = App.prefs.hiddenApps.toMutableSet(); if (!s.remove(key)) s.add(key); App.prefs.hiddenApps = s; changed()
    }

    fun toggleFavorite(key: String) {
        val s = App.prefs.favoriteApps.toMutableSet(); if (!s.remove(key)) s.add(key); App.prefs.favoriteApps = s; changed()
    }
}
