package com.watchlite.launcher.notifications

import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.watchlite.launcher.App
import com.watchlite.launcher.link.LinkManager
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Log

class NotifAction(val title: String, val intent: PendingIntent?, val remoteInput: RemoteInput?, val id: String)

class NotificationItem(
    val key: String,
    val source: Int,          // LOCAL or PHONE
    val pkg: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val time: Long,
    val icon: Bitmap?,
    val contentIntent: PendingIntent?,
    val actions: List<NotifAction>,
    val clearable: Boolean
) {
    val replyAction: NotifAction? get() = actions.firstOrNull { it.remoteInput != null }

    companion object {
        const val LOCAL = 0
        const val PHONE = 1
    }
}

/** In-memory list shared by the listener service, the phone link and the UI. */
class NotificationStore {
    private val items = LinkedHashMap<String, NotificationItem>()
    private val listeners = ArrayList<() -> Unit>()

    fun listen(l: () -> Unit) { synchronized(listeners) { listeners.add(l) } }
    fun unlisten(l: () -> Unit) { synchronized(listeners) { listeners.remove(l) } }
    private fun changed() {
        val copy = synchronized(listeners) { ArrayList(listeners) }
        Bg.ui { copy.forEach { it() } }
    }

    @Synchronized fun list(): List<NotificationItem> = items.values.sortedByDescending { it.time }
    @Synchronized fun count() = items.size

    /** Called (on the UI thread) for every newly posted notification: used for the on-face banner. */
    var onNew: ((NotificationItem) -> Unit)? = null

    fun put(item: NotificationItem) {
        val isNew = synchronized(this) { val n = !items.containsKey(item.key); items[item.key] = item; n }
        changed()
        if (isNew) onNew?.let { cb -> Bg.ui { cb(item) } }
    }
    fun remove(key: String) { synchronized(this) { items.remove(key) }; changed() }
    fun replaceLocal(list: List<NotificationItem>) {
        synchronized(this) {
            items.entries.removeAll { it.value.source == NotificationItem.LOCAL }
            list.forEach { items[it.key] = it }
        }
        changed()
    }

    /** User swiped an item away. */
    fun dismiss(item: NotificationItem) {
        remove(item.key)
        if (item.source == NotificationItem.LOCAL) NotifListener.instance?.cancel(item.key)
        else LinkManager.send("notif.dismiss", org.json.JSONObject().put("key", item.key.removePrefix("phone:")))
    }

    fun clearAll() {
        val all = list()
        all.filter { it.clearable }.forEach { dismiss(it) }
    }

    fun open(ctx: Context, item: NotificationItem) {
        if (item.source == NotificationItem.PHONE) {
            LinkManager.send("notif.action", org.json.JSONObject().put("key", item.key.removePrefix("phone:")).put("action", "open"))
            return
        }
        try { item.contentIntent?.send() } catch (t: Throwable) { Log.w("Notif", "open failed", t) }
    }

    fun action(ctx: Context, item: NotificationItem, a: NotifAction) {
        if (item.source == NotificationItem.PHONE) {
            LinkManager.send("notif.action", org.json.JSONObject().put("key", item.key.removePrefix("phone:")).put("action", a.id))
            return
        }
        try { a.intent?.send() } catch (t: Throwable) { Log.w("Notif", "action failed", t) }
    }

    fun reply(ctx: Context, item: NotificationItem, a: NotifAction, text: String): Boolean {
        if (item.source == NotificationItem.PHONE) {
            return LinkManager.send("notif.reply", org.json.JSONObject().put("key", item.key.removePrefix("phone:")).put("action", a.id).put("text", text))
        }
        val ri = a.remoteInput ?: return false
        val pi = a.intent ?: return false
        return try {
            val intent = Intent()
            val b = Bundle(); b.putCharSequence(ri.resultKey, text)
            RemoteInput.addResultsToIntent(arrayOf(ri), intent, b)
            pi.send(ctx, 0, intent)
            true
        } catch (t: Throwable) { Log.w("Notif", "reply failed", t); false }
    }
}

/** Reads the watch's own notifications. User must grant access once. */
class NotifListener : NotificationListenerService() {

    companion object {
        @Volatile var instance: NotifListener? = null

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            return flat.contains(ComponentName(ctx, NotifListener::class.java).flattenToString())
        }

        fun settingsIntent() = Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS")
    }

    override fun onListenerConnected() { instance = this; rebuild() }
    override fun onListenerDisconnected() { if (instance === this) instance = null }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }

    override fun onNotificationPosted(sbn: StatusBarNotification) { convert(sbn)?.let { App.I.notifications.put(it) } }
    override fun onNotificationRemoved(sbn: StatusBarNotification) { App.I.notifications.remove(sbn.key) }

    fun cancel(key: String) { try { cancelNotification(key) } catch (_: Throwable) { } }

    private fun rebuild() {
        val list = try { activeNotifications?.mapNotNull { convert(it) } ?: emptyList() } catch (_: Throwable) { emptyList() }
        App.I.notifications.replaceLocal(list)
    }

    private fun convert(sbn: StatusBarNotification): NotificationItem? {
        val n = sbn.notification
        if (sbn.packageName == packageName) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return null
        if (sbn.packageName in App.prefs.blockedNotifApps) return null
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
        if (title.isEmpty() && text.isEmpty()) return null
        val pm = packageManager
        val label = try { pm.getApplicationLabel(pm.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        val icon = try { toBitmap(pm.getApplicationIcon(sbn.packageName), 48) } catch (_: Exception) { null }
        val actions = n.actions?.mapIndexed { i, a ->
            NotifAction(a.title?.toString() ?: "", a.actionIntent, a.remoteInputs?.firstOrNull { it.allowFreeFormInput }, i.toString())
        } ?: emptyList()
        return NotificationItem(sbn.key, NotificationItem.LOCAL, sbn.packageName, label, title, text, sbn.postTime, icon,
            n.contentIntent, actions, sbn.isClearable)
    }

    private fun toBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, size, size); d.draw(Canvas(b)); return b
    }
}
