package com.watchlite.launcher.link

import android.content.Context
import android.media.AudioManager
import com.watchlite.launcher.App
import com.watchlite.launcher.system.SystemActions
import com.watchlite.launcher.util.Log
import org.json.JSONArray
import org.json.JSONObject

/**
 * Watch controls driven from the phone app ("ctl.set" messages).
 * Every value is reported back in watch.stats → "ctl", together with what is
 * possible on this firmware (so the phone greys out what can't work).
 * Bluetooth is deliberately not switchable remotely: turning it off would cut the link.
 */
object RemoteControl {

    private fun am(ctx: Context) = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    private fun pct(am: AudioManager, stream: Int): Int {
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        return am.getStreamVolume(stream) * 100 / max
    }

    fun state(ctx: Context, withThemes: Boolean = false): JSONObject {
        val p = App.prefs
        val am = am(ctx)
        val o = JSONObject()
            .put("wifi", SystemActions.wifiOn(ctx))
            .put("dnd", SystemActions.dndOn(ctx)).put("canDnd", SystemActions.hasDndAccess(ctx))
            .put("brightness", SystemActions.systemBrightness(ctx) * 100 / 255).put("canBrightness", SystemActions.canWriteSettings(ctx))
            .put("volMedia", pct(am, AudioManager.STREAM_MUSIC))
            .put("volRing", pct(am, AudioManager.STREAM_RING))
            .put("volAlarm", pct(am, AudioManager.STREAM_ALARM))
            .put("ringer", when (am.ringerMode) { AudioManager.RINGER_MODE_SILENT -> "silent"; AudioManager.RINGER_MODE_VIBRATE -> "vibrate"; else -> "normal" })
            .put("aod", p.aodEnabled).put("aodBrightness", (p.aodBrightness * 100).toInt())
            .put("chime", p.chimeEnabled)
            .put("chargeAnim", p.chargeAnim).put("chargeSound", p.chargeSound).put("chargeSoundId", p.chargeSoundId).put("chargeAnimSec", p.chargeAnimSec)
            .put("goal", p.stepsGoal)
            .put("notifBanner", p.notifBanner).put("notifVibrate", p.notifVibrate)
            .put("theme", p.themeId)
            .put("canLock", SystemActions.isAdmin(ctx))
            .put("location", SystemActions.locationOn(ctx))
            .also { o -> com.watchlite.launcher.util.ScreenProfile.of(ctx).let { sp -> o.put("screenW", sp.width).put("screenH", sp.height) } }
        if (withThemes) {
            val a = JSONArray()
            App.I.themes.all().forEach { a.put(JSONObject().put("id", it.id).put("name", it.name())) }
            o.put("themes", a)
        }
        return o
    }

    private fun setPct(am: AudioManager, stream: Int, v: Int) {
        val max = am.getStreamMaxVolume(stream)
        am.setStreamVolume(stream, (v.coerceIn(0, 100) * max + 50) / 100, 0)
    }

    /** Applies one control. Must run on the main thread (settings, UI state). */
    fun apply(ctx: Context, k: String, p: JSONObject) {
        val prefs = App.prefs
        val am = am(ctx)
        try {
            when (k) {
                "wifi" -> SystemActions.setWifi(ctx, p.optBoolean("v"))
                "dnd" -> SystemActions.setDnd(ctx, p.optBoolean("v"))
                "brightness" -> SystemActions.setBrightness(ctx, p.optInt("v") * 255 / 100)
                "volMedia" -> setPct(am, AudioManager.STREAM_MUSIC, p.optInt("v"))
                "volRing" -> setPct(am, AudioManager.STREAM_RING, p.optInt("v"))
                "volAlarm" -> setPct(am, AudioManager.STREAM_ALARM, p.optInt("v"))
                "ringer" -> am.ringerMode = when (p.optString("v")) {
                    "silent" -> AudioManager.RINGER_MODE_SILENT
                    "vibrate" -> AudioManager.RINGER_MODE_VIBRATE
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                "aod" -> prefs.aodEnabled = p.optBoolean("v")
                "aodBrightness" -> prefs.aodBrightness = (p.optInt("v").coerceIn(1, 60)) / 100f
                "chime" -> { prefs.chimeEnabled = p.optBoolean("v"); com.watchlite.launcher.chime.ChimeScheduler.schedule(ctx) }
                "chargeAnim" -> prefs.chargeAnim = p.optBoolean("v")
                "chargeSound" -> prefs.chargeSound = p.optBoolean("v")
                "chargeSoundId" -> { prefs.chargeSoundId = p.optString("v"); com.watchlite.launcher.charge.ChargeSounds.play(ctx, prefs.chargeSoundId) }
                "chargeAnimSec" -> prefs.chargeAnimSec = p.optInt("v").coerceIn(3, 30)
                "goal" -> prefs.stepsGoal = p.optInt("v").coerceIn(500, 100_000)
                "notifBanner" -> prefs.notifBanner = p.optBoolean("v")
                "notifVibrate" -> prefs.notifVibrate = p.optBoolean("v")
                "theme" -> App.I.themes.get(p.optString("v"))?.let { App.I.themes.select(it.id) }
                "lock" -> SystemActions.lockScreen(ctx)
                "wake" -> com.watchlite.launcher.aod.AodGate.wake(ctx)
                else -> Log.w("Remote", "unknown control $k")
            }
            Log.i("Remote", "ctl $k")
            App.I.state.changed()
        } catch (t: Throwable) { Log.w("Remote", "ctl $k failed", t) }
    }
}
