package com.watchlite.launcher.link

import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.util.Base64
import com.watchlite.launcher.App
import com.watchlite.launcher.notifications.NotifAction
import com.watchlite.launcher.notifications.NotificationItem
import com.watchlite.launcher.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Companion link: message routing + per-module switches.
 * The phone app (future project) speaks the protocol in docs/LINK_PROTOCOL.md.
 */
object LinkManager {

    val MODULES = listOf("notif", "battery", "net", "location", "contacts", "calls", "media", "weather", "themes", "find", "camera")

    private lateinit var app: Context
    @Volatile var transport: ((ByteArray) -> Boolean)? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        if (App.prefs.linkEnabled) start(ctx)
    }

    fun enabled(module: String) = App.prefs.linkEnabled && module in App.prefs.linkModules

    fun start(ctx: Context) {
        if (App.prefs.linkCode.isEmpty()) App.prefs.linkCode = newCode()
        try { ctx.startService(Intent(ctx, LinkService::class.java)) } catch (t: Throwable) { Log.w("Link", "start failed", t) }
    }

    fun stop(ctx: Context) {
        ctx.stopService(Intent(ctx, LinkService::class.java))
        App.I.state.linkConnected = false
        App.I.state.changed()
    }

    fun newCode(): String = (100000 + SecureRandom().nextInt(900000)).toString()

    /** Sends a message to the phone. Returns false when not connected. */
    fun send(type: String, payload: JSONObject = JSONObject()): Boolean {
        val t = transport ?: return false
        val env = JSONObject().put("v", 1).put("id", UUID.randomUUID().toString()).put("type", type)
            .put("ts", System.currentTimeMillis() / 1000).put("payload", payload)
        return try { t(LinkCrypto.seal(env.toString().toByteArray())) } catch (e: Throwable) { Log.w("Link", "send", e); false }
    }

    /** Entry point for decrypted (or simulated) messages from the phone. */
    fun handle(json: String) {
        val env = try { JSONObject(json) } catch (e: Exception) { Log.w("Link", "bad json"); return }
        val type = env.optString("type")
        val p = env.optJSONObject("payload") ?: JSONObject()
        val st = App.I.state
        when (type) {
            "hello", "capabilities" -> send("capabilities", JSONObject().put("modules", org.json.JSONArray(App.prefs.linkModules.toList()))
                .put("app", "watchlite").put("version", 1))
            "ping" -> send("pong")
            "stats.request" -> sendStats()
            "watch.locate" -> locate()
            "find.watch" -> findWatch(true)
            "find.watch.stop" -> findWatch(false, remote = true)
            "find.phone.stopped" -> { phoneRingingUntil = 0; App.I.state.changed() }
            "time.sync" -> Unit
            "notif.post", "notif.update" -> if (enabled("notif")) postPhoneNotification(p)
            "notif.remove" -> App.I.notifications.remove("phone:" + p.optString("key"))
            "battery.phone" -> if (enabled("battery")) { st.phoneBattery = p.optInt("level", -1); st.phoneCharging = p.optBoolean("charging") }
            "net.state" -> if (enabled("net")) st.phoneNet = p.optString("type")
            "weather.update" -> if (enabled("weather")) { st.weatherTemp = p.optString("temp"); st.weatherDesc = p.optString("desc") }
            "media.state" -> if (enabled("media")) {
                st.mediaTitle = p.optString("title"); st.mediaArtist = p.optString("artist"); st.mediaPlaying = p.optBoolean("playing")
            }
            "location.state" -> if (enabled("location") && App.prefs.locationMode == "phone") {
                App.prefs.latitude = p.optDouble("lat", App.prefs.latitude.toDouble()).toFloat()
                App.prefs.longitude = p.optDouble("lng", App.prefs.longitude.toDouble()).toFloat()
            }
            "contacts.sync" -> if (enabled("contacts")) saveContacts(p)
            "theme.apply" -> if (enabled("themes")) App.I.themes.get(p.optString("id"))?.let { App.I.themes.select(it.id) }
            "theme.install" -> if (enabled("themes")) installTheme(p)
            else -> Log.i("Link", "unhandled $type")
        }
        st.changed()
    }

    /** Watch status for the phone app's dashboard. */
    fun sendStats() {
        val st = App.I.state
        val p = App.prefs
        send("watch.stats", JSONObject()
            .put("battery", st.battery).put("charging", st.charging)
            .put("steps", App.I.steps.today).put("goal", p.stepsGoal).put("stepsSource", App.I.steps.source())
            .put("theme", App.I.themes.get(p.themeId)?.name() ?: p.themeId)
            .put("aod", p.aodEnabled).put("chime", p.chimeEnabled)
            .put("version", com.watchlite.launcher.BuildConfig.VERSION_NAME)
            .put("model", android.os.Build.MODEL).put("android", android.os.Build.VERSION.RELEASE)
            .put("uptimeMin", android.os.SystemClock.elapsedRealtime() / 60000))
    }

    /** Sends the watch position: last known fix right away, then a fresh GPS fix when it arrives. */
    @android.annotation.SuppressLint("MissingPermission")
    private fun locate() {
        val lm = app.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        if (app.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            send("watch.location", JSONObject().put("error", "permission")); return
        }
        fun sendLoc(l: android.location.Location, fresh: Boolean) = send("watch.location", JSONObject()
            .put("lat", l.latitude).put("lng", l.longitude).put("acc", l.accuracy.toDouble())
            .put("time", l.time).put("fresh", fresh).put("provider", l.provider))
        listOf(android.location.LocationManager.GPS_PROVIDER, android.location.LocationManager.NETWORK_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Throwable) { null } }
            .maxByOrNull { it.time }?.let { sendLoc(it, false) }
        com.watchlite.launcher.util.Bg.ui {
            try {
                @Suppress("DEPRECATION")
                lm.requestSingleUpdate(android.location.LocationManager.GPS_PROVIDER, object : android.location.LocationListener {
                    override fun onLocationChanged(l: android.location.Location) { com.watchlite.launcher.util.Bg.run { sendLoc(l, true) } }
                    @Deprecated("old") override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onProviderDisabled(provider: String) { send("watch.location", JSONObject().put("error", "gps_off")) }
                }, android.os.Looper.getMainLooper())
            } catch (t: Throwable) { Log.w("Link", "locate", t) }
        }
    }

    @Volatile private var finding = false
    @Volatile var phoneRingingUntil = 0L

    fun phoneRinging() = System.currentTimeMillis() < phoneRingingUntil

    /** Ring the phone, or stop it if it's already ringing. Returns false when not connected. */
    fun toggleFindPhone(): Boolean {
        return if (phoneRinging()) { phoneRingingUntil = 0; send("find.phone.stop") }
        else send("find.phone").also { if (it) phoneRingingUntil = System.currentTimeMillis() + 30_000 }
    }

    /** "Find my watch": rings and vibrates for up to 30 s (or until stopped locally or from the phone). */
    fun findWatch(on: Boolean, remote: Boolean = false) {
        val wasFinding = finding
        finding = on
        if (!on) { if (wasFinding && !remote) send("find.watch.stopped"); return }
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        val end = System.currentTimeMillis() + 30_000
        val r = object : Runnable {
            override fun run() {
                if (!finding) return
                if (System.currentTimeMillis() > end) { finding = false; send("find.watch.stopped"); return }
                com.watchlite.launcher.chime.ChimeSounds.play(app, "res:chime_digital", 1f)
                com.watchlite.launcher.util.Haptics.pattern(app, longArrayOf(0, 300, 150, 300))
                h.postDelayed(this, 1500)
            }
        }
        h.post(r)
    }

    fun isFinding() = finding

    private fun postPhoneNotification(p: JSONObject) {
        val icon = p.optString("icon").takeIf { it.isNotEmpty() }?.let {
            try { val b = Base64.decode(it, Base64.DEFAULT); BitmapFactory.decodeByteArray(b, 0, b.size) } catch (_: Exception) { null }
        }
        val acts = p.optJSONArray("actions")
        val actions = ArrayList<NotifAction>()
        if (acts != null) for (i in 0 until acts.length()) {
            val a = acts.getJSONObject(i)
            val ri = if (a.optBoolean("reply")) android.app.RemoteInput.Builder("reply").setLabel(a.optString("title")).build() else null
            actions.add(NotifAction(a.optString("title"), null, ri, a.optString("id", i.toString())))
        }
        App.I.notifications.put(NotificationItem("phone:" + p.optString("key"), NotificationItem.PHONE, p.optString("pkg"),
            p.optString("app"), p.optString("title"), p.optString("text"), p.optLong("time", System.currentTimeMillis()),
            icon, null, actions, true))
    }

    private fun saveContacts(p: JSONObject) {
        app.openFileOutput("contacts.json", Context.MODE_PRIVATE).use { it.write(p.toString().toByteArray()) }
    }

    // theme files arrive in chunks: {id, index, total, data(base64)}
    private val themeChunks = HashMap<String, ByteArrayOutputStream>()

    private fun installTheme(p: JSONObject) {
        val id = p.optString("id"); val idx = p.optInt("index"); val total = p.optInt("total")
        if (idx == 0) themeChunks[id] = ByteArrayOutputStream()
        val buf = themeChunks[id] ?: return
        buf.write(Base64.decode(p.optString("data"), Base64.DEFAULT))
        if (buf.size() > 30 * 1024 * 1024) { themeChunks.remove(id); return }
        if (idx == total - 1) {
            themeChunks.remove(id)
            val f = java.io.File(app.cacheDir, "incoming.wtheme")
            f.writeBytes(buf.toByteArray())
            try {
                val t = App.I.themes.importPackage(android.net.Uri.fromFile(f))
                send("theme.installed", JSONObject().put("id", t.id))
            } catch (e: Throwable) {
                send("theme.error", JSONObject().put("id", id).put("error", e.message ?: "invalid"))
            } finally { f.delete() }
        }
    }
}

/**
 * AES-GCM with a key derived from the 6-digit pairing code shown on the watch.
 * Frame = 12-byte nonce + ciphertext(+tag).
 */
object LinkCrypto {
    private val rnd = SecureRandom()

    private fun key(): SecretKeySpec {
        val d = MessageDigest.getInstance("SHA-256").digest(("watchlite-v1:" + App.prefs.linkCode).toByteArray())
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
        require(frame.size > 28) { "short frame" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, frame, 0, 12))
        return c.doFinal(frame, 12, frame.size - 12)
    }
}
