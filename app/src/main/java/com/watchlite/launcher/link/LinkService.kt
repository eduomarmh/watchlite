package com.watchlite.launcher.link

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.ParcelUuid
import com.watchlite.launcher.App
import com.watchlite.launcher.R
import com.watchlite.launcher.util.Bg
import com.watchlite.launcher.util.Log
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * BLE GATT server (the watch is the peripheral, the phone is the central).
 * Runs as a foreground service only while the link is enabled.
 */
class LinkService : Service() {

    companion object {
        val SERVICE: UUID = UUID.fromString("7f1a0001-5c3e-4b8e-9a51-6c2b0e9d1a01")
        val RX: UUID = UUID.fromString("7f1a0002-5c3e-4b8e-9a51-6c2b0e9d1a01")   // phone -> watch (write)
        val TX: UUID = UUID.fromString("7f1a0003-5c3e-4b8e-9a51-6c2b0e9d1a01")   // watch -> phone (notify)
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val CHANNEL = "link"
    }

    private var server: BluetoothGattServer? = null
    private var tx: BluetoothGattCharacteristic? = null
    private var device: BluetoothDevice? = null
    private var mtu = 23
    private var nextId = 1
    private val assemblers = HashMap<Int, ByteArrayOutputStream>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.link_title), NotificationManager.IMPORTANCE_MIN))
        startForeground(41, Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_phonelink_ring)
            .setContentTitle(getString(R.string.link_title)).setContentText(getString(R.string.link_waiting)).build())
        startGatt()
    }

    private fun startGatt() {
        val bm = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        val adapter: BluetoothAdapter = bm.adapter ?: run { Log.w("Link", "no bluetooth"); stopSelf(); return }
        if (!adapter.isEnabled) { Log.w("Link", "bluetooth off"); return }
        try {
            server = bm.openGattServer(this, callback)
            val svc = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val rx = BluetoothGattCharacteristic(RX,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE)
            val t = BluetoothGattCharacteristic(TX, BluetoothGattCharacteristic.PROPERTY_NOTIFY, BluetoothGattCharacteristic.PERMISSION_READ)
            t.addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
            svc.addCharacteristic(rx); svc.addCharacteristic(t)
            tx = t
            server?.addService(svc)
            adapter.bluetoothLeAdvertiser?.startAdvertising(
                AdvertiseSettings.Builder().setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER)
                    .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM).setConnectable(true).build(),
                AdvertiseData.Builder().addServiceUuid(ParcelUuid(SERVICE)).setIncludeDeviceName(false).build(),
                advCallback)
            LinkManager.transport = { bytes -> sendFrame(bytes) }
            Log.i("Link", "GATT server up")
        } catch (t: SecurityException) { Log.w("Link", "no permission", t) }
    }

    private val advCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) { Log.w("Link", "advertise failed $errorCode") }
    }

    private val callback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(d: BluetoothDevice, status: Int, newState: Int) {
            val connected = newState == BluetoothProfile.STATE_CONNECTED
            device = if (connected) d else null
            App.I.state.linkConnected = connected
            App.I.state.changed()
            Log.i("Link", "connection $connected")
        }

        override fun onMtuChanged(d: BluetoothDevice, m: Int) { mtu = m }

        override fun onCharacteristicWriteRequest(d: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
                                                  preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
            if (c.uuid == RX) onChunk(value)
        }

        override fun onDescriptorWriteRequest(d: BluetoothDevice, requestId: Int, desc: BluetoothGattDescriptor,
                                              preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray) {
            if (responseNeeded) server?.sendResponse(d, requestId, BluetoothGatt.GATT_SUCCESS, offset, null)
        }
    }

    /** Chunk = [flags:1 (1=first,2=last)][msgId:2][seq:2][data...] */
    private fun onChunk(v: ByteArray) {
        if (v.size < 5) return
        val flags = v[0].toInt()
        val id = ((v[1].toInt() and 0xFF) shl 8) or (v[2].toInt() and 0xFF)
        if (flags and 1 != 0) assemblers[id] = ByteArrayOutputStream()
        val buf = assemblers[id] ?: return
        buf.write(v, 5, v.size - 5)
        if (buf.size() > 40 * 1024 * 1024) { assemblers.remove(id); return }
        if (flags and 2 != 0) {
            assemblers.remove(id)
            val frame = buf.toByteArray()
            Bg.run {
                try { LinkManager.handle(String(LinkCrypto.open(frame))) }
                catch (t: Throwable) { Log.w("Link", "rejected frame (wrong pairing code?)") }
            }
        }
    }

    @Synchronized
    private fun sendFrame(frame: ByteArray): Boolean {
        val d = device ?: return false
        val s = server ?: return false
        val c = tx ?: return false
        val id = (nextId++) and 0xFFFF
        val payload = (mtu - 3 - 5).coerceAtLeast(15)
        var off = 0; var seq = 0
        while (off < frame.size || seq == 0) {
            val n = minOf(payload, frame.size - off)
            var flags = 0
            if (seq == 0) flags = flags or 1
            if (off + n >= frame.size) flags = flags or 2
            val chunk = ByteArray(5 + n)
            chunk[0] = flags.toByte(); chunk[1] = (id shr 8).toByte(); chunk[2] = id.toByte()
            chunk[3] = (seq shr 8).toByte(); chunk[4] = seq.toByte()
            System.arraycopy(frame, off, chunk, 5, n)
            c.value = chunk
            if (!s.notifyCharacteristicChanged(d, c, false)) return false
            off += n; seq++
            Thread.sleep(8)
        }
        return true
    }

    override fun onDestroy() {
        LinkManager.transport = null
        try {
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter?.bluetoothLeAdvertiser?.stopAdvertising(advCallback)
            server?.close()
        } catch (_: Throwable) { }
        super.onDestroy()
    }
}
