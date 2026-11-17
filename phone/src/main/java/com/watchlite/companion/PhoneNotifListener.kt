package com.watchlite.companion

import android.app.Notification
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Reads phone notifications and mirrors them to the watch; acts on dismiss / action / reply from the watch. */
class PhoneNotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: PhoneNotifListener? = null

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, PhoneNotifListener::class.java).flattenToString())
        }

        /** Packages seen recently (for the per-app filter in the app). */
        fun seenApps(): Set<String> = CompanionApp.prefs.getStringSet("seen_apps", emptySet()) ?: emptySet()
        fun muted(): Set<String> = CompanionApp.prefs.getStringSet("muted_apps", emptySet()) ?: emptySet()
    }

    private val active = HashMap<String, StatusBarNotification>()
    private val iconCache = HashMap<String, String>()

    override fun onListenerConnected() { instance = this; resendActive(); PhoneLinkService.instance?.extras?.registerMedia() }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    fun resendActive() {
        try { activeNotifications?.forEach { onNotificationPosted(it) } } catch (_: Throwable) { }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val prefs = CompanionApp.prefs
        if (!prefs.getBoolean("mod_notif", true)) return
        val n = sbn.notification
        if (sbn.packageName == packageName) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        if (sbn.isOngoing) return
        // calls get their own screen on the watch
        if (n.category == Notification.CATEGORY_CALL && prefs.getBoolean("mod_calls", true)) return
        // remember the app for the filter list
        val seen = seenApps()
        if (sbn.packageName !in seen) prefs.edit().putStringSet("seen_apps", seen + sbn.packageName).apply()
        if (sbn.packageName in muted()) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return
        active[sbn.key] = sbn
        val acts = JSONArray()
        n.actions?.forEachIndexed { i, a ->
            val reply = a.remoteInputs?.any { it.allowFreeFormInput } == true
            acts.put(JSONObject().put("id", i.toString()).put("title", a.title?.toString() ?: "").put("reply", reply))
        }
        val p = JSONObject()
            .put("key", sbn.key).put("pkg", sbn.packageName).put("app", label(sbn.packageName))
            .put("title", title.take(200)).put("text", text.take(1000)).put("time", sbn.postTime)
            .put("actions", acts)
        icon(sbn.packageName)?.let { p.put("icon", it) }
        PhoneLinkService.send("notif.post", p)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (active.remove(sbn.key) != null) PhoneLinkService.send("notif.remove", JSONObject().put("key", sbn.key))
    }

    fun dismiss(key: String) { try { cancelNotification(key) } catch (_: Throwable) { }; active.remove(key) }

    fun action(key: String, id: String) {
        val sbn = active[key] ?: return
        if (id == "open") { try { sbn.notification.contentIntent?.send() } catch (_: Throwable) { }; return }
        val a = sbn.notification.actions?.getOrNull(id.toIntOrNull() ?: -1) ?: return
        try { a.actionIntent?.send() } catch (_: Throwable) { }
    }

    fun reply(key: String, id: String, text: String) {
        val sbn = active[key] ?: return
        val actions = sbn.notification.actions ?: return
        val a = actions.getOrNull(id.toIntOrNull() ?: -1) ?: actions.firstOrNull { it.remoteInputs?.isNotEmpty() == true } ?: return
        val ri = a.remoteInputs?.firstOrNull { it.allowFreeFormInput } ?: return
        try {
            val intent = Intent()
            val b = Bundle(); b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(ri), intent, b)
            a.actionIntent.send(this, 0, intent)
        } catch (_: Throwable) { }
    }

    private fun label(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) { pkg }

    /** App icon as a small base64 PNG (cached per app, keeps messages small). */
    private fun icon(pkg: String): String? = iconCache.getOrPut(pkg) {
        try {
            val d = packageManager.getApplicationIcon(pkg)
            val b = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, 48, 48); d.draw(Canvas(b))
            val bo = ByteArrayOutputStream(); b.compress(Bitmap.CompressFormat.PNG, 100, bo)
            Base64.encodeToString(bo.toByteArray(), Base64.NO_WRAP)
        } catch (_: Throwable) { return null }
    }
}
