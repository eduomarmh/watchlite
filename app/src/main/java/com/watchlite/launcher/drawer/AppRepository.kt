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
    val pkg: String get() = component.packageName
}

/**
 * Lists launchable apps, keeps a small icon cache, applies hide/rename/favorites.
 */
class AppRepository(private val ctx: Context) {

    private val la = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as LauncherApps
    @Volatile private var all: List<AppEntry> = emptyList()
    private val listeners = ArrayList<() -> Unit>()
    @Volatile var iconSize = 96
    private var loading = false

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

    fun reload() {
        if (loading) return
        loading = true
        Bg.run {
            try {
                val me = ctx.packageName
                val renamed = try { JSONObject(App.prefs.renamedApps) } catch (_: Exception) { JSONObject() }
                val old = all.associateBy { it.key }
                val list = la.getActivityList(null, Process.myUserHandle()).mapNotNull { info ->
                    val cn = info.componentName
                    if (cn.packageName == me && cn.className.endsWith("HomeActivity")) return@mapNotNull null
                    AppEntry(cn, info.label?.toString() ?: cn.packageName, info.user).also { e ->
                        e.label = renamed.optString(e.key, e.originalLabel).ifEmpty { e.originalLabel }
                        e.icon = old[e.key]?.icon
                    }
                }
                all = list
                changed()
                // icons: icon pack first, then the app's own icon, rendered in the chosen style
                val packPkg = App.prefs.iconPack
                if (packPkg != (pack?.pkg ?: "")) pack = if (packPkg.isEmpty()) null else IconPack(ctx, packPkg).takeIf { it.load() }
                val style = App.prefs.iconStyle
                val accent = App.prefs.accent
                for (e in list) {
                    if (e.icon != null) continue
                    try {
                        val packIcon = pack?.drawableFor(e.component)
                        val d = packIcon ?: la.resolveActivity(Intent(Intent.ACTION_MAIN).setComponent(e.component), e.user)?.getBadgedIcon(0) ?: continue
                        e.icon = IconStyler.render(d, iconSize, style, accent, packIcon != null)
                    } catch (t: Throwable) { Log.w("Apps", "icon ${e.key}", t) }
                }
                changed()
            } finally { loading = false }
        }
    }

    fun requestIconSize(px: Int) {
        val target = px.coerceIn(48, 192)
        if (target <= iconSize) return
        iconSize = target
        all.forEach { it.icon?.recycle(); it.icon = null }
        reload()
    }

    private var pack: IconPack? = null

    /** Re-renders every icon (style or icon pack changed). */
    fun restyle() {
        all.forEach { it.icon = null }
        reload()
    }

    fun trimIcons() { /* icons are small; kept so the drawer opens instantly */ }

    fun launch(e: AppEntry, opts: android.os.Bundle?) {
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
