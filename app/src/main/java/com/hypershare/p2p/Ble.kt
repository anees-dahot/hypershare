package com.hypershare.p2p

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanRecord
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import kotlinx.coroutines.delay

/**
 * Bluetooth LE beacon: the third way a sender finds receivers, and the dependable one.
 *
 * Both Wi-Fi paths need the Wi-Fi radio to leave its channel and scan, which many chips do badly
 * while Wi-Fi Direct is busy. A BLE advert is a few bytes on its own radio: no pairing, no
 * connection and (Android 12+) no location. It carries just enough to rebuild the invite, the
 * 6-character group code and its channel. No file data ever goes over Bluetooth.
 */
@SuppressLint("MissingPermission")
object Ble {
    private const val ID = 0xFFFF      // "no company" id. Advert: magic + code + channel + short name
    private const val ID_NAME = 0xFFFE // scan response: the full device name
    private val MAGIC = byteArrayOf('H'.code.toByte(), 'S'.code.toByte(), 5)
    private const val SHORT_NAME = 13  // advert stays at 28 of its 31 bytes
    private const val FULL_NAME = 26

    class Found(val code: String, val freq: Int, val name: String)

    fun perms(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_ADVERTISE)
        else emptyArray()

    fun allowed(ctx: Context) = perms().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun adapter(ctx: Context) = runCatching { ctx.getSystemService(BluetoothManager::class.java)?.adapter }.getOrNull()

    /** False when the phone has no Bluetooth LE at all. */
    fun supported(ctx: Context) =
        ctx.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE) && adapter(ctx) != null

    fun on(ctx: Context) = runCatching { adapter(ctx)?.isEnabled == true }.getOrDefault(false)

    /** UTF-8 bytes of [s], at most [max], never cutting a character in half. */
    private fun cut(s: String, max: Int): ByteArray {
        var end = s.length
        while (end > 0 && s.substring(0, end).toByteArray().size > max) end--
        if (end in 1 until s.length && Character.isHighSurrogate(s[end - 1])) end--
        return s.substring(0, end).toByteArray()
    }

    /** Receiver: beacon until cancelled. Quietly idle while Bluetooth is off or not allowed. */
    suspend fun advertise(ctx: Context, inv: Invite) {
        // Android 8–9 groups get a chip-chosen name: there is no code to beacon.
        val code = codeOf(inv.ssid) ?: return
        val payload = MAGIC + code.toByteArray(Charsets.US_ASCII) +
            byteArrayOf((inv.freq shr 8).toByte(), inv.freq.toByte()) + cut(inv.name, SHORT_NAME)
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(false).setTimeout(0).build()
        val data = AdvertiseData.Builder().setIncludeDeviceName(false).addManufacturerData(ID, payload).build()
        val resp = AdvertiseData.Builder().setIncludeDeviceName(false).addManufacturerData(ID_NAME, cut(inv.name, FULL_NAME)).build()
        val cb = object : AdvertiseCallback() {}
        var live: BluetoothLeAdvertiser? = null
        try {
            // Bluetooth can be switched on (or allowed) while we wait: keep checking.
            while (true) {
                val ready = allowed(ctx) && on(ctx)
                if (live == null && ready) {
                    live = runCatching { adapter(ctx)?.bluetoothLeAdvertiser?.also { it.startAdvertising(settings, data, resp, cb) } }.getOrNull()
                } else if (live != null && !ready) live = null // the stack dropped it with Bluetooth
                delay(2_000)
            }
        } finally {
            runCatching { live?.stopAdvertising(cb) }
        }
    }

    private fun parse(rec: ScanRecord): Found? {
        val d = rec.getManufacturerSpecificData(ID) ?: return null
        val head = MAGIC.size + CODE_LEN + 2
        if (d.size < head || !d.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) return null
        val code = String(d, MAGIC.size, CODE_LEN, Charsets.US_ASCII)
        if (normCode(code) != code) return null
        val p = MAGIC.size + CODE_LEN
        val freq = ((d[p].toInt() and 0xff) shl 8) or (d[p + 1].toInt() and 0xff)
        val short = String(d, head, d.size - head)
        val full = rec.getManufacturerSpecificData(ID_NAME)?.let { String(it) }
        return Found(code, freq, (full?.takeIf { it.isNotBlank() } ?: short).trim())
    }

    /**
     * Sender: reports beacons until cancelled. No hardware filter on purpose: manufacturer-data
     * filters are broken on some chips, and this only runs while the Send screen is open.
     */
    suspend fun scan(ctx: Context, onFound: (Found) -> Unit) {
        val cb = object : ScanCallback() {
            override fun onScanResult(type: Int, r: ScanResult) { r.scanRecord?.let(::parse)?.let(onFound) }
            override fun onBatchScanResults(rs: MutableList<ScanResult>) { rs.forEach { onScanResult(0, it) } }
        }
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        var live: BluetoothLeScanner? = null
        try {
            while (true) {
                val ready = allowed(ctx) && on(ctx)
                if (live == null && ready) {
                    live = runCatching { adapter(ctx)?.bluetoothLeScanner?.also { it.startScan(null, settings, cb) } }.getOrNull()
                } else if (live != null && !ready) live = null
                delay(2_000)
            }
        } finally {
            runCatching { live?.stopScan(cb) }
        }
    }
}
