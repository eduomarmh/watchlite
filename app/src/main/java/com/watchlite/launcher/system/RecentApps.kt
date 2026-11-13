package com.watchlite.launcher.system

import android.app.Activity
import android.app.ActivityManager
import android.app.AlertDialog
import android.app.usage.UsageStatsManager
import android.content.Context
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Ui
import org.json.JSONArray

/**
 * Recent apps without Accessibility: apps opened from the launcher (always available)
 * plus system usage stats when usage access is granted. "Close" kills the app's
 * background processes (KILL_BACKGROUND_PROCESSES, a normal permission).
 */
object RecentApps {
    private const val KEY = "recent_apps"
    private const val MAX = 12

    fun record(ctx: Context, pkg: String) {
        if (pkg == ctx.packageName) return
        val sp = App.prefs.sp
        val list = load().toMutableList()
        list.remove(pkg); list.add(0, pkg)
        sp.edit().putString(KEY, JSONArray(list.take(MAX)).toString()).apply()
    }

    private fun load(): List<String> = try {
        val a = JSONArray(App.prefs.sp.getString(KEY, "[]"))
        (0 until a.length()).map { a.getString(it) }
    } catch (_: Exception) { emptyList() }

    private fun forget(pkg: String) {
        App.prefs.sp.edit().putString(KEY, JSONArray(load().filter { it != pkg }).toString()).apply()
    }

    fun list(ctx: Context): List<String> {
        val out = LinkedHashSet<String>()
        if (Permissions.hasUsage(ctx)) {
            try {
                val usm = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val now = System.currentTimeMillis()
                usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 6 * 3600_000L, now)
                    ?.filter { it.lastTimeUsed > now - 6 * 3600_000L && it.totalTimeInForeground > 0 }
                    ?.sortedByDescending { it.lastTimeUsed }
                    ?.forEach { out.add(it.packageName) }
            } catch (_: Throwable) { }
        }
        out.addAll(load())
        val pm = ctx.packageManager
        return out.filter { it != ctx.packageName && pm.getLaunchIntentForPackage(it) != null }.take(MAX)
    }

    fun close(ctx: Context, pkg: String) {
        try { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg) } catch (_: Throwable) { }
        forget(pkg)
    }

    /** Lightweight sheet: tap to open, X to close, "Close all". */
    fun show(a: Activity, ui: Ui) {
        val pm = a.packageManager
        val box = ui.vbox().apply { setPadding(ui.px(3f), ui.px(2f), ui.px(3f), ui.px(2f)) }
        val scroll = ScrollView(a).apply { addView(box) }
        val dlg = AlertDialog.Builder(a, Ui.DIALOG).setTitle(R.string.tile_recents).setView(scroll)
            .setNegativeButton(R.string.close_all, null).setPositiveButton(android.R.string.ok, null).create()
        fun build() {
            box.removeAllViews()
            val apps = list(a)
            if (apps.isEmpty()) {
                box.addView(ui.text(a.getString(R.string.no_recents), 5f, Ui.TEXT2, 1, 0).apply { gravity = Gravity.CENTER }, ui.lp(top = 4f, bottom = 4f))
                return
            }
            for (pkg in apps) {
                val row = ui.hbox()
                row.background = ui.ripple(ui.rounded(Ui.CARD2, 5f * ui.u))
                row.setPadding(ui.px(3f), ui.px(2.5f), ui.px(2f), ui.px(2.5f))
                val icon = ImageView(a).apply { try { setImageDrawable(pm.getApplicationIcon(pkg)) } catch (_: Exception) { } }
                row.addView(icon, LinearLayout.LayoutParams(ui.px(9f), ui.px(9f)))
                val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg }
                row.addView(ui.text(label, 5.2f, Ui.TEXT, 1), ui.lp(0, start = 3f, weight = 1f))
                row.addView(ui.circleButton(R.drawable.ic_close, 9f) { close(a, pkg); Haptics.tick(a); build() })
                row.setOnClickListener { pm.getLaunchIntentForPackage(pkg)?.let { a.startActivity(it) }; dlg.dismiss() }
                box.addView(row, ui.lp(bottom = 1.5f))
            }
        }
        build()
        dlg.setOnShowListener {
            dlg.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
                list(a).forEach { close(a, it) }
                Haptics.tick(a, true); build()
            }
        }
        dlg.show()
    }
}
