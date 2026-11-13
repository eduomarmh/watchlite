package com.watchlite.launcher.system

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.AppOpsManager
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Process
import android.provider.Settings
import com.watchlite.launcher.R
import com.watchlite.launcher.notifications.NotifListener
import com.watchlite.launcher.util.Log
import com.watchlite.launcher.util.Ui

/**
 * Some watch firmwares strip settings pages (e.g. Accessibility opens the main Settings list).
 * This helper:
 *  1. tries several ways to open each permission page (action, explicit components, sub-settings fragments);
 *  2. if the app was granted WRITE_SECURE_SETTINGS once over ADB, enables accessibility and
 *     notification access by itself, no settings page needed;
 *  3. shows the exact one-time ADB commands as the last resort.
 */
object Permissions {

    const val PKG = "com.watchlite.launcher"
    private const val SETTINGS = "com.android.settings"

    enum class Kind { ACCESSIBILITY, NOTIFICATIONS, ADMIN, WRITE_SETTINGS, DND, USAGE }

    fun hasSecure(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun hasUsage(ctx: Context): Boolean = try {
        val ops = ctx.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), ctx.packageName) == AppOpsManager.MODE_ALLOWED
    } catch (_: Throwable) { false }

    fun granted(ctx: Context, k: Kind): Boolean = when (k) {
        Kind.ACCESSIBILITY -> SystemActions.isAccessibilityOn(ctx)
        Kind.NOTIFICATIONS -> NotifListener.hasAccess(ctx)
        Kind.ADMIN -> SystemActions.isAdmin(ctx)
        Kind.WRITE_SETTINGS -> SystemActions.canWriteSettings(ctx)
        Kind.DND -> SystemActions.hasDndAccess(ctx)
        Kind.USAGE -> hasUsage(ctx)
    }

    /** With WRITE_SECURE_SETTINGS the launcher turns its own services on (no settings page needed). */
    fun autoEnable(ctx: Context) {
        if (!hasSecure(ctx)) return
        try {
            addToList(ctx, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                ComponentName(ctx, PowerAccessibilityService::class.java).flattenToString())
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            addToList(ctx, "enabled_notification_listeners", ComponentName(ctx, NotifListener::class.java).flattenToString())
        } catch (t: Throwable) { Log.w("Perm", "autoEnable failed", t) }
    }

    private fun addToList(ctx: Context, key: String, item: String) {
        val cur = Settings.Secure.getString(ctx.contentResolver, key) ?: ""
        if (cur.split(':').any { it.equals(item, true) }) return
        Settings.Secure.putString(ctx.contentResolver, key, if (cur.isBlank()) item else "$cur:$item")
        Log.i("Perm", "enabled $item in $key")
    }

    /** Location on/off directly (needs WRITE_SECURE_SETTINGS). */
    @Suppress("DEPRECATION")
    fun setLocation(ctx: Context, on: Boolean): Boolean {
        if (!hasSecure(ctx)) return false
        return try {
            Settings.Secure.putInt(ctx.contentResolver, Settings.Secure.LOCATION_MODE,
                if (on) Settings.Secure.LOCATION_MODE_HIGH_ACCURACY else Settings.Secure.LOCATION_MODE_OFF)
        } catch (_: Throwable) { false }
    }

    // ------------------------------------------------------------------ ways to open each page
    private fun sub(fragment: String) = Intent().setComponent(ComponentName(SETTINGS, "$SETTINGS.SubSettings"))
        .putExtra(":settings:show_fragment", fragment)

    private fun comp(cls: String) = Intent().setComponent(ComponentName(SETTINGS, "$SETTINGS.$cls"))

    private fun candidates(ctx: Context, k: Kind): List<Intent> {
        val pkgUri = Uri.parse("package:" + ctx.packageName)
        return when (k) {
            Kind.ACCESSIBILITY -> listOf(
                comp("Settings\$AccessibilitySettingsActivity"),
                sub("$SETTINGS.accessibility.AccessibilitySettings"),
                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Kind.NOTIFICATIONS -> listOf(
                Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"),
                comp("Settings\$NotificationAccessSettingsActivity"),
                sub("$SETTINGS.notification.NotificationAccessSettings"))
            Kind.ADMIN -> listOf(
                Intent(android.app.admin.DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_DEVICE_ADMIN, SystemActions.admin(ctx))
                    .putExtra(android.app.admin.DevicePolicyManager.EXTRA_ADD_EXPLANATION, ctx.getString(R.string.enable_admin_sum)),
                comp("Settings\$DeviceAdminSettingsActivity"),
                sub("$SETTINGS.DeviceAdminSettings"))
            Kind.WRITE_SETTINGS -> listOf(
                Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, pkgUri),
                comp("Settings\$WriteSettingsActivity"),
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkgUri))
            Kind.DND -> listOf(
                Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS),
                comp("Settings\$ZenAccessSettingsActivity"),
                sub("$SETTINGS.notification.ZenAccessSettings"))
            Kind.USAGE -> listOf(
                Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS),
                comp("Settings\$UsageAccessSettingsActivity"),
                sub("$SETTINGS.applications.UsageAccessDetails"))
        }
    }

    /** Tries way number [attempt] (wrapping). Returns false when nothing could be started. */
    fun tryOpen(a: Activity, k: Kind, attempt: Int): Boolean {
        val list = candidates(a, k)
        for (i in list.indices) {
            val intent = list[(attempt + i) % list.size]
            if (intent.resolveActivity(a.packageManager) == null) continue
            try { a.startActivity(intent); return true } catch (t: Throwable) { Log.w("Perm", "open failed: $intent", t) }
        }
        return false
    }

    fun adbCommands(): String = """
        adb shell pm grant $PKG android.permission.WRITE_SECURE_SETTINGS
        adb shell appops set $PKG WRITE_SETTINGS allow
        adb shell appops set $PKG GET_USAGE_STATS allow
        adb shell dpm set-active-admin $PKG/.system.AdminReceiver
        adb shell cmd notification allow_listener $PKG/$PKG.notifications.NotifListener
    """.trimIndent()

    /**
     * One dialog for every permission: try opening the page (each press tries another way),
     * or show the one-time ADB setup.
     */
    fun help(a: Activity, k: Kind, onDone: () -> Unit = {}) {
        if (k == Kind.ACCESSIBILITY || k == Kind.NOTIFICATIONS) autoEnable(a)
        if (granted(a, k)) { onDone(); return }
        var attempt = 0
        val msg = a.getString(R.string.perm_help_body)
        val d = AlertDialog.Builder(a, Ui.DIALOG).setTitle(title(a, k)).setMessage(msg)
            .setPositiveButton(R.string.perm_try_open, null)
            .setNeutralButton(R.string.perm_adb, null)
            .setNegativeButton(R.string.cancel, null).create()
        d.setOnShowListener {
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                if (!tryOpen(a, k, attempt)) android.widget.Toast.makeText(a, R.string.perm_no_page, android.widget.Toast.LENGTH_LONG).show()
                attempt++
                d.getButton(AlertDialog.BUTTON_POSITIVE).setText(R.string.perm_try_other)
            }
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener { d.dismiss(); adbDialog(a) }
        }
        d.show()
    }

    fun adbDialog(a: Activity) {
        val cmds = adbCommands()
        AlertDialog.Builder(a, Ui.DIALOG).setTitle(R.string.perm_adb)
            .setMessage(a.getString(R.string.perm_adb_body) + "\n\n" + cmds)
            .setPositiveButton(R.string.copy) { _, _ ->
                (a.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("adb", cmds))
            }.setNegativeButton(android.R.string.ok, null).show()
    }

    fun title(ctx: Context, k: Kind): String = ctx.getString(when (k) {
        Kind.ACCESSIBILITY -> R.string.a11y_label
        Kind.NOTIFICATIONS -> R.string.notif_access
        Kind.ADMIN -> R.string.lock_screen
        Kind.WRITE_SETTINGS -> R.string.write_settings
        Kind.DND -> R.string.dnd_access
        Kind.USAGE -> R.string.usage_access
    })
}
