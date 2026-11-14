package com.watchlite.launcher.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.IBinder
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Log
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.UUID

/**
 * Phone link over classic Bluetooth (RFCOMM / SPP). Classic Bluetooth works on every
 * watch chipset (BLE peripheral mode often doesn't on budget watches) and on phones
 * without Google services. The phone pairs with the watch in normal Bluetooth settings,
 * then the companion app connects to this server.
 *
 * Stream framing: [length:4 bytes big-endian][frame], frame = AES-GCM sealed JSON (see LinkCrypto).
 */
class LinkService : Service() {

    companion object {
        val SPP_UUID: UUID = UUID.fromString("7f1a0001-5c3e-4b8e-9a51-6c2b0e9d1a01")
        const val CHANNEL = "link"
        const val MAX_FRAME = 8 * 1024 * 1024
    }

    @Volatile private var running = true
    @Volatile private var server: BluetoothServerSocket? = null
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var out: DataOutputStream? = null
    private var thread: Thread? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.link_title), NotificationManager.IMPORTANCE_MIN))
        notify(false)
        LinkManager.transport = { bytes -> sendFrame(bytes) }
        thread = Thread({ loop() }, "wl-link").apply { isDaemon = true; start() }
    }

    private fun notify(connected: Boolean) {
        startForeground(41, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phonelink_ring)
            .setContentTitle(getString(R.string.link_title))
            .setContentText(getString(if (connected) R.string.connected else R.string.link_waiting)).build())
    }

    private fun loop() {
        while (running) {
            val adapter = BluetoothAdapter.getDefaultAdapter()
            if (adapter == null) { sleep(10000); continue }
            if (!adapter.isEnabled) {
                // phone link is on: switch Bluetooth on by itself (allowed on Android 8 with BLUETOOTH_ADMIN)
                @Suppress("DEPRECATION", "MissingPermission")
                try { adapter.enable() } catch (_: Throwable) { }
                sleep(5000); continue
            }
            try {
                val srv = adapter.listenUsingRfcommWithServiceRecord("WatchLite", SPP_UUID)
                server = srv
                Log.i("Link", "waiting for phone")
                val s = srv.accept()                       // blocks until the phone connects
                try { srv.close() } catch (_: Throwable) { }
                server = null
                handle(s)
            } catch (t: Throwable) {
                if (running) { Log.w("Link", "server error: ${t.message}"); sleep(3000) }
            }
        }
    }

    private fun handle(s: BluetoothSocket) {
        socket = s
        val input = DataInputStream(s.inputStream.buffered())
        out = DataOutputStream(s.outputStream.buffered())
        setConnected(true)
        try {
            while (running) {
                val len = input.readInt()
                if (len <= 0 || len > MAX_FRAME) error("bad frame length $len")
                val frame = ByteArray(len)
                input.readFully(frame)
                Bg.run {
                    try { LinkManager.handle(String(LinkCrypto.open(frame))) }
                    catch (t: Throwable) { Log.w("Link", "rejected frame (wrong pairing code?)") }
                }
            }
        } catch (t: Throwable) {
            Log.i("Link", "phone disconnected: ${t.message}")
        } finally {
            out = null
            try { s.close() } catch (_: Throwable) { }
            socket = null
            setConnected(false)
        }
    }

    private var connectedSince = 0L
    private val lostAlert = Runnable {
        if (!App.I.state.linkConnected && App.prefs.linkLostAlert) {
            com.watchlite.launcher.util.Haptics.tick(this, true)
            try {
                val v = getSystemService(android.content.Context.VIBRATOR_SERVICE) as android.os.Vibrator
                v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 300, 200, 300, 200, 600), -1))
            } catch (_: Throwable) { }
            android.widget.Toast.makeText(this, getString(com.watchlite.launcher.R.string.link_lost_toast), android.widget.Toast.LENGTH_LONG).show()
        }
    }

    private fun setConnected(c: Boolean) {
        // phone left behind: alert only if the link stays down for a minute after a real session
        if (c) { connectedSince = System.currentTimeMillis(); Bg.main.removeCallbacks(lostAlert) }
        else if (App.I.state.linkConnected && System.currentTimeMillis() - connectedSince > 60_000) { Bg.main.removeCallbacks(lostAlert); Bg.main.postDelayed(lostAlert, 60_000) }
        App.I.state.linkConnected = c
        App.I.state.changed()
        Bg.ui { try { notify(c) } catch (_: Throwable) { } }
        if (c) Bg.run { LinkManager.sendStats() }
    }

    @Synchronized
    private fun sendFrame(frame: ByteArray): Boolean {
        val o = out ?: return false
        return try { o.writeInt(frame.size); o.write(frame); o.flush(); true }
        catch (t: Throwable) { Log.w("Link", "send failed: ${t.message}"); false }
    }

    private fun sleep(ms: Long) = try { Thread.sleep(ms) } catch (_: InterruptedException) { }

    override fun onDestroy() {
        Bg.main.removeCallbacks(lostAlert)
        running = false
        LinkManager.transport = null
        try { server?.close() } catch (_: Throwable) { }
        try { socket?.close() } catch (_: Throwable) { }
        App.I.state.linkConnected = false
        App.I.state.changed()
        super.onDestroy()
    }
}
