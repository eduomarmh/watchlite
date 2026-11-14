package com.watchlite.companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

/**
 * Extra phone → watch features. Everything is event driven (system callbacks), except the
 * weather which is fetched at most once an hour and only while the watch is connected.
 * No GPS: the phone's last known coarse location is reused.
 */
class LinkExtras(private val ctx: Context) {
    private val prefs get() = CompanionApp.prefs
    private val main = Handler(Looper.getMainLooper())
    private fun on(key: String, def: Boolean = true) = prefs.getBoolean(key, def)

    fun start() {
        registerNet()
        try { ctx.registerReceiver(dndReceiver, IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)) } catch (_: Throwable) { }
        registerMedia()
    }

    fun stop() {
        try { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(netCb) } catch (_: Throwable) { }
        try { ctx.unregisterReceiver(dndReceiver) } catch (_: Throwable) { }
        unregisterMedia()
        main.removeCallbacksAndMessages(null)
    }

    /** Called each time the link (re)connects: push the current state once. */
    fun onConnected() {
        lastNet = ""; lastMedia = ""; lastTrack = ""
        sendNet(currentNet())
        sendMedia()
        maybeWeather(force = System.currentTimeMillis() - prefs.getLong("weather_time", 0) > 45 * 60_000L)
    }

    // ------------------------------------------------------------------ network type
    private var lastNet = ""
    private val netCb = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(n: Network) { main.postDelayed({ sendNet(currentNet()) }, 1500) }
        override fun onLost(n: Network) { main.postDelayed({ sendNet(currentNet()) }, 1500) }
        override fun onCapabilitiesChanged(n: Network, c: NetworkCapabilities) { main.post { sendNet(currentNet()) } }
    }

    private fun registerNet() {
        try { (ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).registerDefaultNetworkCallback(netCb) } catch (t: Throwable) { Log.w("WLC", "net cb", t) }
    }

    private fun currentNet(): String = try {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val c = cm.getNetworkCapabilities(cm.activeNetwork)
        when {
            c == null -> "none"
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobile"
            else -> "other"
        }
    } catch (_: Throwable) { "none" }

    private fun sendNet(type: String) {
        if (!on("mod_net") || type == lastNet || !PhoneLinkService.connected) return
        if (PhoneLinkService.send("net.state", JSONObject().put("type", type))) lastNet = type
    }

    // ------------------------------------------------------------------ do-not-disturb sync
    private val dndReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (!on("mod_dnd_sync", false)) return
            val nm = ctx.getSystemService(NotificationManager::class.java)
            val dnd = nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
            PhoneLinkService.send("ctl.set", JSONObject().put("k", "dnd").put("v", dnd))
        }
    }

    // ------------------------------------------------------------------ now playing (needs notification access)
    private var msm: MediaSessionManager? = null
    private var controller: MediaController? = null
    private var lastMedia = ""
    private var lastTrack = ""

    private fun artBase64(md: MediaMetadata?): String? {
        md ?: return null
        val src = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON) ?: return null
        return try {
            val side = minOf(src.width, src.height)
            val sq = android.graphics.Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
            val small = android.graphics.Bitmap.createScaledBitmap(sq, 104, 104, true)
            val out = java.io.ByteArrayOutputStream()
            small.compress(android.graphics.Bitmap.CompressFormat.JPEG, 78, out)
            if (small !== sq) small.recycle(); if (sq !== src) sq.recycle()
            android.util.Base64.encodeToString(out.toByteArray(), android.util.Base64.NO_WRAP)
        } catch (_: Throwable) { null }
    }
    private val listenerCn get() = ComponentName(ctx, PhoneNotifListener::class.java)

    private val sessionsCb = MediaSessionManager.OnActiveSessionsChangedListener { list -> attach(list?.firstOrNull()) }
    private val mediaCb = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = sendMedia()
        override fun onPlaybackStateChanged(state: PlaybackState?) = sendMedia()
        override fun onSessionDestroyed() { attach(null) }
    }

    fun registerMedia() {
        if (msm != null || !PhoneNotifListener.hasAccess(ctx)) return
        try {
            val m = ctx.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            m.addOnActiveSessionsChangedListener(sessionsCb, listenerCn, main)
            msm = m
            attach(m.getActiveSessions(listenerCn).firstOrNull())
        } catch (t: Throwable) { Log.w("WLC", "media", t) }
    }

    private fun unregisterMedia() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsCb) } catch (_: Throwable) { }
        controller?.unregisterCallback(mediaCb); controller = null; msm = null
    }

    private fun attach(c: MediaController?) {
        if (controller?.sessionToken == c?.sessionToken) return
        controller?.unregisterCallback(mediaCb)
        controller = c
        c?.registerCallback(mediaCb, main)
        sendMedia()
    }

    private fun sendMedia() {
        if (!on("mod_media") || !PhoneLinkService.connected) return
        val c = controller
        val md = c?.metadata
        val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        val playing = c?.playbackState?.state == PlaybackState.STATE_PLAYING
        val key = "$title|$artist|$playing"
        if (key == lastMedia) return
        val o = JSONObject().put("title", title).put("artist", artist).put("playing", playing)
        // cover art: only when the track changes, ~100 px JPEG (a few KB)
        val track = "$title|$artist"
        if (track != lastTrack) o.put("art", artBase64(md) ?: "")
        if (PhoneLinkService.send("media.state", o)) { lastMedia = key; lastTrack = track }
    }

    // ------------------------------------------------------------------ weather + location (hourly, coarse, no GPS)
    private val weatherTick = Runnable { maybeWeather(force = true) }

    private fun maybeWeather(force: Boolean) {
        main.removeCallbacks(weatherTick)
        main.postDelayed(weatherTick, 60 * 60_000L)
        if (!force || !PhoneLinkService.connected) return
        val wantWeather = on("mod_weather"); val wantLoc = on("mod_location", false)
        if (!wantWeather && !wantLoc) return
        val loc = lastLocation() ?: return
        if (wantLoc) PhoneLinkService.send("location.state", JSONObject().put("lat", loc.first).put("lng", loc.second))
        if (!wantWeather) return
        Thread {
            try {
                val u = URL("https://api.open-meteo.com/v1/forecast?latitude=%.3f&longitude=%.3f&current=temperature_2m,weather_code,is_day&timezone=auto"
                    .format(Locale.US, loc.first, loc.second))
                val con = (u.openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
                val body = con.inputStream.bufferedReader().use { it.readText() }
                con.disconnect()
                val cur = JSONObject(body).getJSONObject("current")
                val temp = Math.round(cur.getDouble("temperature_2m")).toString() + "°"
                val desc = describe(cur.optInt("weather_code"), cur.optInt("is_day", 1) == 1)
                PhoneLinkService.send("weather.update", JSONObject().put("temp", temp).put("desc", desc))
                prefs.edit().putLong("weather_time", System.currentTimeMillis()).putString("weather_last", "$temp $desc").apply()
                PhoneLinkService.changed()
            } catch (t: Throwable) { Log.w("WLC", "weather ${t.message}") }
        }.start()
    }

    @Suppress("MissingPermission")
    private fun lastLocation(): Pair<Double, Double>? {
        if (ctx.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)
            .mapNotNull { try { lm.getLastKnownLocation(it) } catch (_: Throwable) { null } }.maxByOrNull { it.time }
        if (best != null) return best.latitude to best.longitude
        // no cached fix yet: ask once for a cheap network fix; it is used next hour
        try { @Suppress("DEPRECATION") lm.requestSingleUpdate(LocationManager.NETWORK_PROVIDER, { }, Looper.getMainLooper()) } catch (_: Throwable) { }
        return null
    }

    private fun describe(code: Int, day: Boolean): String {
        val ar = Locale.getDefault().language == "ar"
        return when (code) {
            0 -> if (ar) (if (day) "مشمس" else "صافي") else (if (day) "Sunny" else "Clear")
            1, 2 -> if (ar) "غائم جزئياً" else "Partly cloudy"
            3 -> if (ar) "غائم" else "Cloudy"
            45, 48 -> if (ar) "شبورة" else "Fog"
            in 51..57 -> if (ar) "رذاذ" else "Drizzle"
            in 61..67, in 80..82 -> if (ar) "مطر" else "Rain"
            in 71..77, 85, 86 -> if (ar) "ثلج" else "Snow"
            in 95..99 -> if (ar) "عواصف رعدية" else "Thunderstorm"
            else -> ""
        }
    }

    // ------------------------------------------------------------------ watch battery alerts (from the stats we already get)
    fun onWatchStats(s: JSONObject) {
        if (!on("mod_watch_alerts")) return
        val lvl = s.optInt("battery", -1); val chg = s.optBoolean("charging")
        if (lvl < 0) return
        val e = prefs.edit()
        if (chg) e.putBoolean("alert_low_done", false)
        if (!chg) e.putBoolean("alert_full_done", false)
        e.apply()
        if (!chg && lvl <= 15 && !prefs.getBoolean("alert_low_done", false)) {
            notify(11, ctx.getString(R.string.alert_watch_low, lvl)); prefs.edit().putBoolean("alert_low_done", true).apply()
        }
        if (chg && lvl >= 100 && !prefs.getBoolean("alert_full_done", false)) {
            notify(12, ctx.getString(R.string.alert_watch_full)); prefs.edit().putBoolean("alert_full_done", true).apply()
        }
    }

    fun notifyDisconnected(show: Boolean) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!show) { nm.cancel(13); return }
        if (on("mod_lost_alert", false)) notify(13, ctx.getString(R.string.alert_watch_lost))
    }

    private fun notify(id: Int, text: String) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("watch", ctx.getString(R.string.alert_channel), NotificationManager.IMPORTANCE_DEFAULT))
        val pi = android.app.PendingIntent.getActivity(ctx, id, Intent(ctx, MainActivity::class.java), android.app.PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, "watch") else @Suppress("DEPRECATION") Notification.Builder(ctx)
        nm.notify(id, b.setSmallIcon(R.drawable.ic_watch).setContentTitle("WatchLite").setContentText(text).setContentIntent(pi).setAutoCancel(true).build())
    }
}
