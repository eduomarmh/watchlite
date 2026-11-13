package com.watchlite.launcher.drawer

import android.app.ActivityOptions
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.widget.EditText
import android.widget.FrameLayout
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.home.Page
import com.watchlite.launcher.util.Ui

class DrawerPage(host: HomeActivity) : Page(host, FROM_RIGHT) {

    private val grid = AppDrawerView(host, ui.sp)
    private val modeBtn = ui.circleButton(R.drawable.ic_apps, 13f, 0xCC2C2C2E.toInt()) { cycleMode() }
    private val appsListener = { grid.setApps(App.I.apps.visible()) }

    init {
        addView(grid, ui.frameLp())
        modeBtn.layoutParams = FrameLayout.LayoutParams(ui.px(13f), ui.px(13f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            bottomMargin = ui.px(if (ui.sp.isRound) 6f else 3f)
        }
        addView(modeBtn)
        grid.onLaunch = { e, _, r ->
            val opts = ActivityOptions.makeScaleUpAnimation(grid, r.left.toInt(), r.top.toInt(), r.width().toInt(), r.height().toInt()).toBundle()
            App.I.apps.launch(e, opts)
        }
        grid.onLongPress = { appMenu(it) }
        grid.setApps(App.I.apps.visible())
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); App.I.apps.listen(appsListener) }
    override fun onDetachedFromWindow() { App.I.apps.unlisten(appsListener); super.onDetachedFromWindow() }

    override fun onOpening() { grid.setApps(App.I.apps.visible()) }
    override fun onClosed() { grid.resetScroll() }

    override fun onWheel(detents: Int): Boolean {
        if (App.prefs.wheelInDrawer == "zoom") grid.zoomBy(-detents) else grid.scrollByDetents(detents)
        return true
    }

    private fun cycleMode() {
        val modes = listOf("honeycomb", "grid", "list")
        App.prefs.drawerMode = modes[(modes.indexOf(App.prefs.drawerMode) + 1) % modes.size]
        grid.setApps(App.I.apps.visible())
        val label = when (App.prefs.drawerMode) {
            "grid" -> R.string.drawer_grid; "list" -> R.string.drawer_list; else -> R.string.drawer_honeycomb
        }
        host.toast(context.getString(label))
    }

    private fun appMenu(e: AppEntry) {
        val fav = e.key in App.prefs.favoriteApps
        val items = arrayOf(
            context.getString(if (fav) R.string.app_unfavorite else R.string.app_favorite),
            context.getString(R.string.app_rename),
            context.getString(R.string.app_hide),
            context.getString(R.string.app_info),
            context.getString(R.string.app_uninstall)
        )
        AlertDialog.Builder(context, R.style.Watch_Dialog).setTitle(e.label).setItems(items) { _, which ->
            when (which) {
                0 -> App.I.apps.toggleFavorite(e.key)
                1 -> rename(e)
                2 -> { App.I.apps.toggleHidden(e.key); host.toast(context.getString(R.string.app_hidden_hint)) }
                3 -> context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + e.pkg)))
                4 -> context.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:" + e.pkg)))
            }
        }.show()
    }

    private fun rename(e: AppEntry) {
        val input = EditText(context).apply { setText(e.label); setSelection(text.length); setTextColor(Ui.TEXT) }
        AlertDialog.Builder(context, R.style.Watch_Dialog).setTitle(R.string.app_rename).setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> App.I.apps.rename(e, input.text.toString()) }
            .setNeutralButton(R.string.reset) { _, _ -> App.I.apps.rename(e, null) }
            .show()
    }

}
