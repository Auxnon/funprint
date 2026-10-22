package com.auxnon.funprint

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.io.Closeable
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

class PrintException(message: String) : Exception(message)

private fun uuid(short: String) = UUID.fromString("0000$short-0000-1000-8000-00805f9b34fb")

private val SERVICE = uuid("ae30")
private val ADVERT_SERVICE = uuid("af30") // PD01 advertises this, prints over ae30
private val TX = uuid("ae01")
private val RX = uuid("ae02")
private val CCCD = uuid("2902")
private val NAME_PREFIXES = listOf("GB0", "GT0", "MX0", "MX1", "YT0", "X5", "X6", "X7", "BQ", "LY", "AI0", "PD0")

/** Finds and remembers a printer, then streams command bytes to it. Blocking; call off the main thread. */
@SuppressLint("MissingPermission")
class Printer(private val ctx: Context) {
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private val prefs = ctx.getSharedPreferences("printer", Context.MODE_PRIVATE)

    fun print(jobs: List<ByteArray>) {
        val adapter = adapter ?: throw PrintException("No Bluetooth")
        if (!adapter.isEnabled) throw PrintException("Bluetooth is off")

        // Saved printer first; scan only if there's none or it can't be reached.
        var link = prefs.getString(KEY_ADDRESS, null)?.let { connect(adapter.getRemoteDevice(it)) }
        if (link == null) {
            val device = scan() ?: throw PrintException("No printer found. Is it on, and not connected to another device?")
            link = connect(device) ?: throw PrintException("Couldn't connect to ${device.name ?: device.address}")
            prefs.edit().putString(KEY_ADDRESS, device.address).apply()
        }
        link.use { for (job in jobs) it.send(job) }
    }

    private fun connect(device: BluetoothDevice): Link? {
        val start = SystemClock.elapsedRealtime()
        Link.open(ctx, device)?.let { return it }
        // Android often fails the first attempt fast (status 133); retry once in that case only,
        // so an absent printer doesn't cost two full timeouts before we scan.
        if (SystemClock.elapsedRealtime() - start < 3000) return Link.open(ctx, device)
        return null
    }

    private fun scan(timeoutMs: Long = 8000): BluetoothDevice? {
        val scanner = adapter?.bluetoothLeScanner ?: return null
        val found = HashMap<String, ScanResult>()
        var firstHit = 0L
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (!isPrinter(result)) return
                synchronized(found) {
                    found[result.device.address] = result
                    if (firstHit == 0L) firstHit = SystemClock.elapsedRealtime()
                }
            }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, callback)
        try {
            val start = SystemClock.elapsedRealtime()
            while (SystemClock.elapsedRealtime() - start < timeoutMs) {
                Thread.sleep(200)
                // Once something turns up, give others a moment to show and pick the strongest.
                val hit = synchronized(found) { firstHit }
                if (hit != 0L && SystemClock.elapsedRealtime() - hit > 1500) break
            }
        } finally {
            scanner.stopScan(callback)
        }
        return synchronized(found) { found.values.maxByOrNull { it.rssi }?.device }
    }

    private fun isPrinter(r: ScanResult): Boolean {
        val name = (r.scanRecord?.deviceName ?: r.device.name ?: "").uppercase()
        if (name.startsWith("MXW")) return false // different protocol
        val uuids = r.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
        return SERVICE in uuids || ADVERT_SERVICE in uuids || NAME_PREFIXES.any { name.startsWith(it) }
    }

    companion object {
        private const val KEY_ADDRESS = "address"
    }
}

@SuppressLint("MissingPermission")
private class Link private constructor() : BluetoothGattCallback(), Closeable {
    private lateinit var gatt: BluetoothGatt
    private lateinit var tx: BluetoothGattCharacteristic
    private var mtu = 23

    private val connected = CompletableFuture<Boolean>()
    private val mtuChanged = CompletableFuture<Int>()
    private val discovered = CompletableFuture<Boolean>()
    private val descriptorWritten = CompletableFuture<Boolean>()
    private val writeSlot = Semaphore(1)
    @Volatile private var paused = false
    @Volatile private var lost = false

    override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
        if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
            connected.complete(true)
        } else {
            lost = true
            connected.complete(false)
            writeSlot.release() // unblock a pending write so it notices
        }
    }

    override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
        mtuChanged.complete(if (status == BluetoothGatt.GATT_SUCCESS) mtu else 23)
    }

    override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
        discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
        descriptorWritten.complete(status == BluetoothGatt.GATT_SUCCESS)
    }

    override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
        writeSlot.release()
    }

    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        onNotify(value)
    }

    @Deprecated("Pre-Android 13 callback")
    @Suppress("DEPRECATION")
    override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (Build.VERSION.SDK_INT < 33) onNotify(c.value ?: return)
    }

    private fun onNotify(v: ByteArray) {
        // 51 78 ae 00 01 00 <nonzero=pause | 00=resume>: printer flow control
        if (v.size >= 7 && v[2] == 0xAE.toByte()) paused = v[6].toInt() != 0
    }

    private fun setup(): Boolean {
        if (!connected.await(10, false)) return false
        gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        if (gatt.requestMtu(512)) mtu = mtuChanged.await(5, 23)
        if (!gatt.discoverServices() || !discovered.await(10, false)) return false
        val service = gatt.getService(SERVICE) ?: return false
        tx = service.getCharacteristic(TX) ?: return false
        val rx = service.getCharacteristic(RX)
        if (rx != null && gatt.setCharacteristicNotification(rx, true)) {
            rx.getDescriptor(CCCD)?.let { d ->
                val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = if (Build.VERSION.SDK_INT >= 33) {
                    gatt.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    d.value = value
                    @Suppress("DEPRECATION")
                    gatt.writeDescriptor(d)
                }
                if (started) descriptorWritten.await(5, false)
            }
        }
        return !lost
    }

    /** Send at a fixed byte rate that stays ahead of the printhead; the printer's pause/resume does the rest. */
    fun send(data: ByteArray, rate: Int = 16000) {
        val chunk = maxOf(20, mtu - 3)
        var base = SystemClock.elapsedRealtime()
        var i = 0
        while (i < data.size) {
            if (paused) {
                while (paused && !lost) Thread.sleep(20)
                base = SystemClock.elapsedRealtime() - i * 1000L / rate
            }
            if (lost) throw PrintException("Printer disconnected")
            val end = minOf(i + chunk, data.size)
            write(data.copyOfRange(i, end))
            i = end
            val ahead = base + i * 1000L / rate - SystemClock.elapsedRealtime()
            if (ahead > 0) Thread.sleep(ahead)
        }
        Thread.sleep(2000) // let the printer's buffer drain before disconnecting
    }

    private fun write(bytes: ByteArray) {
        // Android allows one outstanding write; onCharacteristicWrite frees the slot.
        if (!writeSlot.tryAcquire(5, TimeUnit.SECONDS)) throw PrintException("Printer stopped responding")
        if (lost) throw PrintException("Printer disconnected")
        repeat(100) {
            val ok = if (Build.VERSION.SDK_INT >= 33) {
                gatt.writeCharacteristic(tx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) ==
                    BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                tx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                @Suppress("DEPRECATION")
                tx.value = bytes
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(tx)
            }
            if (ok) return
            Thread.sleep(10) // stack busy
        }
        writeSlot.release()
        throw PrintException("Couldn't write to printer")
    }

    override fun close() {
        gatt.disconnect()
        gatt.close()
    }

    companion object {
        fun open(ctx: Context, device: BluetoothDevice): Link? {
            val link = Link()
            link.gatt = device.connectGatt(ctx, false, link, BluetoothDevice.TRANSPORT_LE) ?: return null
            if (link.setup()) return link
            link.close()
            return null
        }
    }
}

private fun <T> CompletableFuture<T>.await(seconds: Long, fallback: T): T =
    try {
        get(seconds, TimeUnit.SECONDS)
    } catch (e: TimeoutException) {
        fallback
    }
