package com.watchlite.launcher.notifications

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.Intent
import android.speech.RecognizerIntent
import android.text.format.DateUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.home.HomeActivity
import com.watchlite.launcher.home.Page
import com.watchlite.launcher.util.Haptics
import com.watchlite.launcher.util.Ui
import kotlin.math.abs

class NotificationsPage(host: HomeActivity) : Page(host, FROM_TOP) {

    private val scroll = ScrollView(host).apply { isVerticalScrollBarEnabled = false; overScrollMode = OVER_SCROLL_NEVER }
    private val list = ui.vbox()
    private val listener = { if (isPresent) rebuild() }

    init {
        val pad = ui.sp.safe
        list.setPadding(pad, ui.px(if (ui.sp.isRound) 14f else 6f), pad, ui.px(18f))
        scroll.addView(list)
        addView(scroll, ui.frameLp())
        // bottom handle
        addView(View(host).apply { background = ui.rounded(0x66FFFFFF, 50f) },
            ui.frameLp(ui.px(14f), ui.px(1.4f), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = ui.px(3f) })
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); App.I.notifications.listen(listener) }
    override fun onDetachedFromWindow() { App.I.notifications.unlisten(listener); super.onDetachedFromWindow() }
    override fun onOpening() { rebuild(); scroll.scrollTo(0, 0) }
    override fun contentCanScroll(direction: Int) = scroll.canScrollVertically(direction)
    override fun closeStartAllowed(ev: MotionEvent) = ev.y > height * 0.78f || !scroll.canScrollVertically(1)
    override fun onWheel(detents: Int): Boolean { scroll.smoothScrollBy(0, (detents * ui.u * 14).toInt()); return true }

    private fun rebuild() {
        list.removeAllViews()
        val items = App.I.notifications.list()
        list.addView(ui.text(context.getString(R.string.notifications), 7f, Ui.TEXT, 2).apply { gravity = Gravity.CENTER }, ui.lp(bottom = 3f))
        if (!NotifListener.hasAccess(context)) {
            list.addView(ui.card().apply {
                addView(ui.text(context.getString(R.string.notif_access_needed), 5.2f, Ui.TEXT, 1, 4))
                addView(ui.pill(context.getString(R.string.grant), host.ui.accent, Ui.BG) { host.startActivity(NotifListener.settingsIntent()) }, ui.lp(top = 3f))
            }, ui.lp(bottom = 3f))
        }
        if (items.isEmpty()) {
            list.addView(ui.text(context.getString(R.string.no_notifications), 5.5f, Ui.TEXT2).apply { gravity = Gravity.CENTER }, ui.lp(top = 16f))
            return
        }
        items.forEach { list.addView(card(it), ui.lp(bottom = 2.5f)) }
        list.addView(ui.pill(context.getString(R.string.clear_all)) {
            Haptics.tick(context, true); App.I.notifications.clearAll()
        }.apply { gravity = Gravity.CENTER }, ui.lp(top = 2f))
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun card(n: NotificationItem): View {
        val c = ui.card()
        val head = ui.hbox()
        head.addView(ImageView(context).apply {
            if (n.icon != null) setImageBitmap(n.icon) else setImageResource(R.drawable.ic_notifications)
        }, LinearLayout.LayoutParams(ui.px(6f), ui.px(6f)))
        head.addView(ui.text(n.appLabel, 4.6f, Ui.TEXT2, 1), ui.lp(0, start = 2f, weight = 1f))
        val src = if (n.source == NotificationItem.PHONE) " · " + context.getString(R.string.phone) else ""
        head.addView(ui.text(DateUtils.getRelativeTimeSpanString(n.time, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS,
            DateUtils.FORMAT_ABBREV_RELATIVE).toString() + src, 4.2f, Ui.TEXT2))
        c.addView(head)
        if (n.title.isNotEmpty()) c.addView(ui.text(n.title, 5.6f, Ui.TEXT, 2, 2), ui.lp(top = 1.5f))
        if (n.text.isNotEmpty()) c.addView(ui.text(n.text, 5.2f, 0xFFD1D1D6.toInt(), 1, 4), ui.lp(top = 0.8f))
        val actions = ui.hbox()
        n.replyAction?.let { a ->
            actions.addView(ui.pill(context.getString(R.string.reply), host.ui.accent, Ui.BG) { replyDialog(n, a) }, ui.lp(0, start = 0f, end = 1.5f, weight = 1f))
        }
        n.actions.filter { it.remoteInput == null && it.title.isNotEmpty() }.take(if (n.replyAction != null) 1 else 2).forEach { a ->
            actions.addView(ui.pill(a.title) { App.I.notifications.action(context, n, a); host.toast(a.title) }, ui.lp(0, end = 1.5f, weight = 1f))
        }
        if (actions.childCount > 0) c.addView(actions, ui.lp(top = 2.5f))
        c.background = ui.ripple(ui.rounded(Ui.CARD, 6f * ui.u))
        c.setOnClickListener { App.I.notifications.open(context, n); host.goHome() }
        swipeToDismiss(c, n)
        return c
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun swipeToDismiss(v: View, n: NotificationItem) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f; var downY = 0f; var swiping = false
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = ev.rawX - downX
                    if (!swiping && abs(dx) > slop && abs(dx) > abs(ev.rawY - downY) * 1.5f) {
                        swiping = true; view.parent.requestDisallowInterceptTouchEvent(true)
                        view.isPressed = false
                    }
                    if (swiping) { view.translationX = dx; view.alpha = 1f - abs(dx) / view.width; return@setOnTouchListener true }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (swiping) {
                    val dx = view.translationX
                    if (abs(dx) > view.width * 0.35f) {
                        ObjectAnimator.ofFloat(view, "translationX", dx, if (dx > 0) view.width.toFloat() else -view.width.toFloat()).apply {
                            duration = 140
                            addListener(object : android.animation.AnimatorListenerAdapter() {
                                override fun onAnimationEnd(a: android.animation.Animator) { App.I.notifications.dismiss(n) }
                            })
                        }.start()
                        Haptics.tick(context)
                    } else view.animate().translationX(0f).alpha(1f).setDuration(120).start()
                    swiping = false
                    return@setOnTouchListener true
                }
            }
            false
        }
    }

    // ----------------------------------------------------------------- reply
    private var pendingReply: Pair<NotificationItem, NotifAction>? = null

    fun cannedReplies(): List<String> {
        val custom = App.prefs.cannedReplies.lines().map { it.trim() }.filter { it.isNotEmpty() }
        return custom.ifEmpty { context.resources.getStringArray(R.array.canned_replies).toList() }
    }

    private fun replyDialog(n: NotificationItem, a: NotifAction) {
        val canned = cannedReplies()
        val items = (canned + context.getString(R.string.reply_type) + context.getString(R.string.reply_voice)).toTypedArray()
        AlertDialog.Builder(context, R.style.Watch_Dialog).setTitle(n.title.ifEmpty { n.appLabel }).setItems(items) { _, i ->
            when {
                i < canned.size -> send(n, a, canned[i])
                i == canned.size -> typeDialog(n, a)
                else -> {
                    pendingReply = n to a
                    try {
                        host.startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM), HomeActivity.REQ_VOICE)
                    } catch (_: Exception) { host.toast(context.getString(R.string.voice_unavailable)) }
                }
            }
        }.show()
    }

    fun onVoiceResult(text: String?) {
        val (n, a) = pendingReply ?: return
        pendingReply = null
        if (!text.isNullOrBlank()) send(n, a, text)
    }

    private fun typeDialog(n: NotificationItem, a: NotifAction) {
        val input = EditText(context).apply { setTextColor(Ui.TEXT) }
        AlertDialog.Builder(context, R.style.Watch_Dialog).setTitle(R.string.reply).setView(input)
            .setPositiveButton(R.string.send) { _, _ -> send(n, a, input.text.toString()) }.show()
    }

    private fun send(n: NotificationItem, a: NotifAction, text: String) {
        val ok = App.I.notifications.reply(context, n, a, text)
        host.toast(context.getString(if (ok) R.string.reply_sent else R.string.reply_failed))
        if (ok) Haptics.pattern(context, longArrayOf(0, 15, 60, 15))
    }
}
