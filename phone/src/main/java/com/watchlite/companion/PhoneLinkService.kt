package com.watchlite.companion

import android.annotation.SuppressLint
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.RingtoneManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.KeyEvent
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class CompanionApp : Application() {
    companion object {
        lateinit var I: CompanionApp
            private set
        val prefs: SharedPreferences get() = I.getSharedPreferences("companion", Context.MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        I = this
        if (prefs.getString("watch_addr", "").isNullOrEmpty().not()) PhoneLinkService.start(this)
    }
}

/** Same AES-GCM framing as the watch (docs/LINK_PROTOCOL.md). */
object Crypto {
    private val rnd = SecureRandom()
    private fun key(): SecretKeySpec {
        val code = CompanionApp.prefs.getString("code", "") ?: ""
        val d = MessageDigest.getInstance("SHA-256").digest(("watchlite-v1:$code").toByteArray())
        return SecretKeySpec(d, 0, 16, "AES")
    }

    fun seal(plain: ByteArray): ByteArray {
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, iv))
        val ct = c.doFinal(plain)
        return ByteBuffer.allocate(12 + ct.size).put(iv).put(ct).array()
    }

    fun open(frame: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, frame, 0, 12))
        return c.doFinal(frame, 12, frame.size - 12)
    }
}

/**
 * Keeps a classic-Bluetooth (RFCOMM) connection to the watch, reconnecting by itself.
 * No Google services are used anywhere, so it runs on Huawei phones without GMS.
 */
class PhoneLinkService : Service() {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("7f1a0001-5c3e-4b8e-9a51-6c2b0e9d1a01")
        private const val CH = "link"
        @Volatile var instance: PhoneLinkService? = null
        @Volatile var connected = false
            private set
        @Volatile var status = "idle"   // idle | connecting | connected | error:<msg> | auth
            private set
        private val listeners = ArrayList<() -> Unit>()
        fun listen(l: () -> Unit) = synchronized(listeners) { listeners.add(l) }
        fun unlisten(l: () -> Unit) = synchronized(listeners) { listeners.remove(l) }
        internal fun changed() {
            val copy = synchronized(listeners) { ArrayList(listeners) }
            Handler(Looper.getMainLooper()).post { copy.forEach { it() } }
        }

        fun start(ctx: Context) {
            val i = Intent(ctx, PhoneLinkService::class.java)
            try { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i) } catch (t: Throwable) { Log.w("WLC", "start", t) }
        }

        fun send(type: String, payload: JSONObject = JSONObject()): Boolean = instance?.sendMsg(type, payload) ?: false

        /** Watch is ringing because we asked it to ("Find my watch"). */
        @Volatile var watchRingingUntil = 0L
        fun watchRinging() = System.currentTimeMillis() < watchRingingUntil
    }

    @Volatile private var running = true
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: DataOutputStream? = null
    private var thread: Thread? = null
    private val main = Handler(Looper.getMainLooper())
    private var lastBattery = -1
    private var lastBatteryCharging = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel(CH, getString(R.string.link_channel), NotificationManager.IMPORTANCE_MIN))
        foreground()
        registerReceiver(battery, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        thread = Thread({ loop() }, "wlc-link").apply { isDaemon = true; start() }
        main.postDelayed(statsTick, 60_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "reconnect") try { socket?.close() } catch (_: Throwable) { }
        if (intent?.action == "stop_ring") stopRing()
        return START_STICKY
    }

    private fun foreground() {
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CH) else @Suppress("DEPRECATION") Notification.Builder(this)
        startForeground(1, b.setSmallIcon(R.drawable.ic_watch).setContentIntent(pi)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(if (connected) R.string.connected else R.string.not_connected)).build())
    }

    private fun setStatus(s: String, c: Boolean) {
        status = s; connected = c
        main.post { try { foreground() } catch (_: Throwable) { } }
        changed()
    }

    @SuppressLint("MissingPermission")
    private fun loop() {
        var backoff = 3000L
        while (running) {
            val addr = CompanionApp.prefs.getString("watch_addr", "") ?: ""
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (addr.isEmpty() || adapter == null || !adapter.isEnabled) { setStatus("idle", false); sleep(5000); continue }
            try {
                setStatus("connecting", false)
                adapter.cancelDiscovery()
                val dev = adapter.getRemoteDevice(addr)
                val s = dev.createRfcommSocketToServiceRecord(SPP_UUID)
                s.connect()
                socket = s
                out = DataOutputStream(s.outputStream.buffered())
                setStatus("connected", true)
                backoff = 3000L
                onConnected()
                val input = DataInputStream(s.inputStream.buffered())
                while (running) {
                    val len = input.readInt()
                    if (len <= 0 || len > 8 * 1024 * 1024) error("bad frame")
                    val f = ByteArray(len); input.readFully(f)
                    val json = try { String(Crypto.open(f)) } catch (t: Throwable) { setStatus("auth", true); continue }
                    if (status == "auth") setStatus("connected", true)
                    handle(json)
                }
            } catch (t: Throwable) {
                Log.i("WLC", "link: ${t.message}")
                if (running) setStatus("error:" + (t.message ?: ""), false)
            } finally {
                out = null
                try { socket?.close() } catch (_: Throwable) { }
                socket = null
            }
            sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(60_000L)
        }
    }

    private fun onConnected() {
        sendMsg("hello", JSONObject().put("app", "companion").put("version", 1))
        lastBattery = -1
        sendBattery(force = true)
        sendMsg("stats.request")
        PhoneNotifListener.instance?.resendActive()
    }

    @Synchronized
    fun sendMsg(type: String, payload: JSONObject = JSONObject()): Boolean {
        val o = out ?: return false
        val env = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
            .put("ts", System.currentTimeMillis() / 1000).put("payload", payload)
        return try {
            val f = Crypto.seal(env.toString().toByteArray())
            o.writeInt(f.size); o.write(f); o.flush(); true
        } catch (t: Throwable) { Log.w("WLC", "send ${t.message}"); false }
    }

    // ------------------------------------------------------------------ incoming from the watch
    private fun handle(json: String) {
        val env = try { JSONObject(json) } catch (_: Exception) { return }
        val p = env.optJSONObject("payload") ?: JSONObject()
        val prefs = CompanionApp.prefs
        when (env.optString("type")) {
            "watch.stats" -> {
                val e = prefs.edit()
                // the face list only comes with full stats; keep it separately so periodic stats don't wipe it
                p.optJSONObject("ctl")?.optJSONArray("themes")?.let { e.putString("themes", it.toString()); p.getJSONObject("ctl").remove("themes") }
                e.putString("stats", p.toString()).putLong("stats_time", System.currentTimeMillis()).apply(); changed()
            }
            "watch.location" -> {
                if (p.has("lat")) prefs.edit().putString("loc", p.toString()).putLong("loc_time", System.currentTimeMillis()).apply()
                else prefs.edit().putString("loc_error", p.optString("error")).apply()
                changed()
            }
            "find.phone" -> if (prefs.getBoolean("mod_find", true)) ring()
            "find.phone.stop" -> stopRing(notifyWatch = false)
            "find.watch.stopped" -> { watchRingingUntil = 0; changed() }
            "notif.dismiss" -> PhoneNotifListener.instance?.dismiss(p.optString("key"))
            "notif.action" -> PhoneNotifListener.instance?.action(p.optString("key"), p.optString("action"))
            "notif.reply" -> PhoneNotifListener.instance?.reply(p.optString("key"), p.optString("action"), p.optString("text"))
            "media.cmd" -> media(p.optString("cmd"))
            "theme.thumb" -> { ThemeStore.saveThumb(this, p); changed() }
            "theme.thumbs.done" -> { ThemeStore.done(this, p); changed() }
            "theme.progress" -> { ThemeStore.event = "progress:" + (p.optInt("index") + 1) + "/" + p.optInt("total"); changed() }
            "theme.installed" -> { ThemeStore.event = "installed:" + p.optString("id"); changed() }
            "theme.error" -> { ThemeStore.event = "error:" + p.optString("error"); changed() }
            "capabilities", "pong" -> Unit
        }
    }

    private var ringing: android.media.Ringtone? = null

    /** "Find my phone": loud ringtone + vibration for 30 s, even in silent mode (alarm stream). */
    fun ring() {
        main.post {
            try {
                ringing?.stop()
                val uri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                val r = RingtoneManager.getRingtone(this, uri)
                r.audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.setStreamVolume(AudioManager.STREAM_ALARM, am.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
                r.play(); ringing = r
                val v = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                if (Build.VERSION.SDK_INT >= 26) v.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
                main.removeCallbacks(autoStop); main.postDelayed(autoStop, 30_000)
                // notification with a Stop button
                val nm = getSystemService(NotificationManager::class.java)
                if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("find", getString(R.string.find_phone_title), NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
                val stop = PendingIntent.getService(this, 3, Intent(this, PhoneLinkService::class.java).setAction("stop_ring"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, "find") else @Suppress("DEPRECATION") Notification.Builder(this)
                nm.notify(7, b.setSmallIcon(R.drawable.ic_phonelink_ring).setContentTitle(getString(R.string.find_phone_title))
                    .setContentText(getString(R.string.find_phone_text)).setContentIntent(stop).setDeleteIntent(stop).setAutoCancel(true)
                    .addAction(Notification.Action.Builder(null, getString(R.string.stop), stop).build()).build())
            } catch (t: Throwable) { Log.w("WLC", "ring", t) }
        }
    }

    private val autoStop = Runnable { stopRing() }

    /** Stops "find my phone" (from the app, the notification, the timeout, or the watch). */
    fun stopRing(notifyWatch: Boolean = true) {
        val was = ringing != null
        main.post {
            ringing?.stop(); ringing = null
            (getSystemService(Context.VIBRATOR_SERVICE) as Vibrator).cancel()
            getSystemService(NotificationManager::class.java).cancel(7)
            main.removeCallbacks(autoStop)
        }
        if (was && notifyWatch) sendMsg("find.phone.stopped")
    }

    private fun media(cmd: String) {
        val am = getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val key = when (cmd) { "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS; else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE }
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, key))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, key))
    }

    // ------------------------------------------------------------------ phone battery → watch
    private val battery = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            lastBatteryLevelSeen = level
            lastChargingSeen = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
            sendBattery()
        }
    }
    private var lastBatteryLevelSeen = -1
    private var lastChargingSeen = false

    private fun sendBattery(force: Boolean = false) {
        if (!CompanionApp.prefs.getBoolean("mod_battery", true) || lastBatteryLevelSeen < 0) return
        if (!force && lastBatteryLevelSeen == lastBattery && lastChargingSeen == lastBatteryCharging) return
        if (sendMsg("battery.phone", JSONObject().put("level", lastBatteryLevelSeen).put("charging", lastChargingSeen))) {
            lastBattery = lastBatteryLevelSeen; lastBatteryCharging = lastChargingSeen
        }
    }

    private val statsTick = object : Runnable {
        override fun run() {
            if (connected) sendMsg("stats.request")
            main.postDelayed(this, 10 * 60_000L)
        }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { }

    override fun onDestroy() {
        running = false
        instance = null
        main.removeCallbacksAndMessages(null)
        try { unregisterReceiver(battery) } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
        connected = false; status = "idle"; changed()
        super.onDestroy()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (!CompanionApp.prefs.getString("watch_addr", "").isNullOrEmpty()) PhoneLinkService.start(ctx)
    }
}
