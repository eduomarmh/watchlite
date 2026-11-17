package com.watchlite.companion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.telephony.PhoneStateListener
import android.telephony.TelephonyManager
import android.util.Log
import org.json.JSONObject
import java.util.concurrent.Executors

/**
 * Phone calls on the watch: ringing / in-call state goes to the watch ("call.state"), the watch's
 * buttons come back as "call.cmd" (answer, decline, end, speaker, mute, volume, silence).
 * Also mirrors the phone's ringer mode ("phone.ringer") so the watch can stay quiet when the phone is silent.
 * Event driven only (system callbacks), nothing polls.
 */
class CallBridge(private val ctx: Context) {
    private val prefs get() = CompanionApp.prefs
    private val io = Executors.newSingleThreadExecutor()
    private val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val tm = ctx.getSystemService(Context.TELEPHONY_SERVICE) as TelephonyManager
    private var state = "idle"; private var number = ""; private var name = ""
    private var offhookAt = 0L
    private var ringMuted = false

    private fun has(p: String) = ctx.checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED
    private fun enabled() = prefs.getBoolean("mod_calls", true)
    private fun send(type: String, p: JSONObject) { if (!io.isShutdown) io.execute { try { PhoneLinkService.send(type, p) } catch (t: Throwable) { Log.w("WLC", "call send", t) } } }

    @Suppress("DEPRECATION")
    private val listener = object : PhoneStateListener() {
        override fun onCallStateChanged(s: Int, incoming: String?) {
            try { onCall(s, incoming) } catch (t: Throwable) { Log.w("WLC", "call state", t) }
        }
    }

    private val ringerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) = sendRinger()
    }

    fun start() {
        try { ctx.registerReceiver(ringerRx, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION)) } catch (_: Throwable) { }
        listenCalls()
    }

    private var listening = false
    /** Called again after the user grants the phone permissions. */
    fun listenCalls() {
        if (listening || !has(Manifest.permission.READ_PHONE_STATE)) return
        listening = true
        try {
            @Suppress("DEPRECATION") tm.listen(listener, PhoneStateListener.LISTEN_CALL_STATE)
        } catch (t: Throwable) { Log.w("WLC", "listen calls", t) }
    }

    fun stop() {
        try { ctx.unregisterReceiver(ringerRx) } catch (_: Throwable) { }
        try { @Suppress("DEPRECATION") tm.listen(listener, PhoneStateListener.LISTEN_NONE) } catch (_: Throwable) { }
        io.shutdownNow()
    }

    fun onConnected() { sendRinger(); if (state != "idle") sendState() }

    private fun ringer() = when (audio.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" }
    private fun sendRinger() { if (PhoneLinkService.connected) send("phone.ringer", JSONObject().put("mode", ringer())) }

    private fun onCall(s: Int, incoming: String?) {
        val prev = state
        state = when (s) { TelephonyManager.CALL_STATE_RINGING -> "ringing"; TelephonyManager.CALL_STATE_OFFHOOK -> "offhook"; else -> "idle" }
        if (!incoming.isNullOrBlank()) { number = incoming; name = lookup(incoming) }
        if (state == "offhook" && prev != "offhook") offhookAt = SystemClock.elapsedRealtime()
        if (state == "idle") {
            if (ringMuted) { try { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0) } catch (_: Throwable) { }; ringMuted = false }
            number = ""; name = ""; offhookAt = 0L
        }
        if (enabled()) sendState()
    }

    private fun sendState() {
        if (!PhoneLinkService.connected) return
        send("call.state", JSONObject().put("state", state).put("number", number).put("name", name)
            .put("speaker", audio.isSpeakerphoneOn).put("muted", audio.isMicrophoneMute)
            .put("elapsed", if (offhookAt > 0) SystemClock.elapsedRealtime() - offhookAt else 0).put("ringer", ringer()))
    }

    private fun lookup(num: String): String {
        if (!has(Manifest.permission.READ_CONTACTS)) return ""
        return try {
            ctx.contentResolver.query(Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(num)),
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(0) else "" } ?: ""
        } catch (_: Throwable) { "" }
    }

    /** Commands from the watch. Runs on the main thread. */
    @Suppress("DEPRECATION", "MissingPermission")
    fun command(cmd: String) {
        val telecom = ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
        val canCalls = has(Manifest.permission.ANSWER_PHONE_CALLS)
        try {
            when (cmd) {
                "answer" -> if (canCalls) telecom.acceptRingingCall()
                "decline", "end" -> if (canCalls && Build.VERSION.SDK_INT >= 28) telecom.endCall()
                "silence" -> { audio.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0); ringMuted = true }
                "speaker" -> audio.isSpeakerphoneOn = !audio.isSpeakerphoneOn
                "mute" -> audio.isMicrophoneMute = !audio.isMicrophoneMute
                "volup" -> audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_RAISE, 0)
                "voldown" -> audio.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_LOWER, 0)
            }
        } catch (t: Throwable) { Log.w("WLC", "call cmd $cmd", t); CompanionApp.report(t) }
        sendState()
    }

    companion object {
        val PERMS = arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.READ_CALL_LOG,
            Manifest.permission.ANSWER_PHONE_CALLS, Manifest.permission.READ_CONTACTS)
    }
}
