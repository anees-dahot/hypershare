package com.hypershare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import kotlinx.coroutines.delay
import java.io.Closeable
import java.io.IOException

/**
 * Android 8–9 sender: join the receiver's Wi-Fi Direct group or hotspot as an ordinary Wi-Fi client.
 * (Android 10+ uses WifiP2pConfig.Builder / WifiNetworkSpecifier instead, which don't exist on 8–9.)
 * A Wi-Fi Direct group owner is a normal WPA2 access point, so this works for both link types.
 * The phone leaves its current Wi-Fi network while joined; [close] reconnects it.
 */
@SuppressLint("MissingPermission")
@Suppress("DEPRECATION")
class LegacyJoin(private val ctx: Context) : Closeable {
    private val wm = ctx.applicationContext.getSystemService(WifiManager::class.java)
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var netId = -1
    var freq = 0; private set

    suspend fun join(ssid: String, pass: String) {
        if (!wm.isWifiEnabled) throw IOException("Turn Wi-Fi on, then scan again.")
        val quoted = "\"$ssid\""
        // Reuse an existing profile with the same name if Android kept one.
        wm.configuredNetworks?.filter { it.SSID == quoted }?.forEach { wm.removeNetwork(it.networkId) }
        val cfg = WifiConfiguration().apply {
            SSID = quoted
            preSharedKey = "\"$pass\""
            allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK)
        }
        netId = wm.addNetwork(cfg)
        if (netId == -1) throw IOException("This phone refused to add the receiver's network.")
        wm.disconnect()
        if (!wm.enableNetwork(netId, true)) throw IOException("This phone refused to join the receiver's network.")
        wm.reconnect()

        // Wait until we're on it with an address (up to 30 s).
        repeat(60) {
            val info = wm.connectionInfo
            if (info != null && info.networkId == netId && info.ipAddress != 0) {
                freq = info.frequency
                bindToWifi()
                return
            }
            delay(500)
        }
        throw IOException("Couldn't join the receiver's network. Turn on Location and try again.")
    }

    /** The joined network has no internet; make sure our sockets still go over it, not mobile data. */
    private fun bindToWifi() {
        val wifi = cm.allNetworks.firstOrNull { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true }
        if (wifi != null) cm.bindProcessToNetwork(wifi)
    }

    override fun close() {
        runCatching { cm.bindProcessToNetwork(null) }
        if (netId != -1) {
            runCatching { wm.removeNetwork(netId); wm.reconnect() }
            netId = -1
        }
    }
}
