package com.watchlite.launcher.system

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.app.NotificationManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import com.watchlite.launcher.chime.ChimeScheduler
import com.watchlite.launcher.util.Log

class AdminReceiver : DeviceAdminReceiver()

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ChimeScheduler.schedule(ctx)
        com.watchlite.launcher.widgets.TimerReceiver.reschedule(ctx)
    }
}

/** Optional accessibility service: opens the system power dialog / recents (Android 8 has no lock action). */
class PowerAccessibilityService : AccessibilityService() {
    companion object { @Volatile var instance: PowerAccessibilityService? = null }
    override fun onServiceConnected() { instance = this }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}
    override fun onDestroy() { instance = null; super.onDestroy() }
}

object SystemActions {

    fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)
    fun isAdmin(ctx: Context) = (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin(ctx))

    fun requestAdmin(a: Activity, explanation: String) {
        a.startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
            .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin(a))
            .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, explanation))
    }

    /** Turns the screen off. Returns false if no method is available. */
    fun lockScreen(ctx: Context): Boolean {
        if (isAdmin(ctx)) {
            try { (ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).lockNow(); return true }
            catch (t: Throwable) { Log.w("Sys", "lockNow failed", t) }
        }
        return false
    }

    fun powerDialog(ctx: Context): Boolean =
        PowerAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG) ?: false

    fun recents(ctx: Context): Boolean =
        PowerAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS) ?: false

    fun notificationShade(): Boolean =
        PowerAccessibilityService.instance?.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS) ?: false

    // ------------------------------------------------------------------ radios
    @Suppress("DEPRECATION")
    fun wifiOn(ctx: Context) = (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled

    @Suppress("DEPRECATION")
    fun setWifi(ctx: Context, on: Boolean): Boolean = try {
        (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).setWifiEnabled(on)
    } catch (t: Throwable) { false }

    fun btOn(): Boolean = try { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true } catch (_: Throwable) { false }

    @Suppress("DEPRECATION", "MissingPermission")
    fun setBt(on: Boolean): Boolean = try {
        val a = BluetoothAdapter.getDefaultAdapter() ?: return false
        if (on) a.enable() else a.disable()
    } catch (t: Throwable) { false }

    fun airplaneOn(ctx: Context) = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1

    fun locationOn(ctx: Context) = try {
        (ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager).isProviderEnabled(LocationManager.GPS_PROVIDER)
    } catch (_: Throwable) { false }

    fun mobileDataOn(ctx: Context): Boolean = try {
        Settings.Global.getInt(ctx.contentResolver, "mobile_data", 0) == 1
    } catch (_: Throwable) { false }

    // ------------------------------------------------------------------ DND
    fun dndOn(ctx: Context): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        return nm.currentInterruptionFilter != NotificationManager.INTERRUPTION_FILTER_ALL
    }

    fun hasDndAccess(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).isNotificationPolicyAccessGranted

    fun setDnd(ctx: Context, on: Boolean): Boolean {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (!nm.isNotificationPolicyAccessGranted) return false
        nm.setInterruptionFilter(if (on) NotificationManager.INTERRUPTION_FILTER_PRIORITY else NotificationManager.INTERRUPTION_FILTER_ALL)
        return true
    }

    // ------------------------------------------------------------------ torch
    private var torchOn = false
    fun torchState() = torchOn

    fun hasTorch(ctx: Context): Boolean = torchCamera(ctx) != null

    private fun torchCamera(ctx: Context): String? = try {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
    } catch (_: Throwable) { null }

    /** Returns false if there is no flash LED (caller shows the screen flashlight instead). */
    fun setTorch(ctx: Context, on: Boolean): Boolean {
        val id = torchCamera(ctx) ?: return false
        return try {
            (ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager).setTorchMode(id, on); torchOn = on; true
        } catch (t: Throwable) { Log.w("Sys", "torch", t); false }
    }

    // ------------------------------------------------------------------ brightness / volume
    fun canWriteSettings(ctx: Context) = Settings.System.canWrite(ctx)

    fun systemBrightness(ctx: Context): Int = try {
        Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
    } catch (_: Throwable) { 128 }

    /** Cycles system brightness 25/50/75/100 %. Only from the explicit Control Center tile. */
    fun cycleBrightness(ctx: Context): Int {
        val cur = systemBrightness(ctx)
        val steps = intArrayOf(40, 100, 170, 255)
        val next = steps.firstOrNull { it > cur + 5 } ?: steps[0]
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, next)
        return next * 100 / 255
    }

    /** Sets system brightness (0..255) from the Control Center slider; only on explicit user action. */
    fun setBrightness(ctx: Context, v: Int) {
        if (!canWriteSettings(ctx)) return
        try {
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
            Settings.System.putInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, v.coerceIn(1, 255))
        } catch (_: Throwable) { }
    }

    fun showVolume(ctx: Context) {
        (ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
            .adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
    }

    fun hasTelephony(ctx: Context) = ctx.packageManager.hasSystemFeature("android.hardware.telephony")

    /** Root-only extras, behind the hidden "root features" switch. */
    fun rootExec(cmd: String): Boolean = try {
        Runtime.getRuntime().exec(arrayOf("su", "-c", cmd)).waitFor() == 0
    } catch (_: Throwable) { false }

    fun isAccessibilityOn(ctx: Context): Boolean {
        val s = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains(ctx.packageName + "/")
    }
}
