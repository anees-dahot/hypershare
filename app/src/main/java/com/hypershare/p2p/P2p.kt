package com.hypershare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the receiver puts in its QR code so the sender can join without slow peer discovery. */
data class Invite(val ssid: String, val pass: String, val freq: Int) {
    fun encode() = "HSX2|$ssid|$pass|$freq"

    companion object {
        fun decode(s: String): Invite {
            val p = s.split('|')
            require(p.size == 4 && p[0] == "HSX2") {
                if (p.firstOrNull() == "HSX1") "The other phone runs an old HyperShare. Update both phones."
                else "That's not a HyperShare code."
            }
            return Invite(p[1], p[2], p[3].toInt())
        }
    }
}

/**
 * Frequency of this phone's normal Wi-Fi connection, or -1.
 * If Wi-Fi Direct runs on a different channel, one radio has to hop between both channels
 * (multi-channel concurrency), which halves throughput and makes it jumpy.
 */
@Suppress("DEPRECATION")
fun staFreq(ctx: Context): Int =
    runCatching { ctx.getSystemService(WifiManager::class.java).connectionInfo.frequency }.getOrDefault(-1)

/** Thin coroutine wrapper around WifiP2pManager. Permissions are requested by the activity. */
@SuppressLint("MissingPermission")
class P2p(ctx: Context) {
    private val mgr = ctx.getSystemService(WifiP2pManager::class.java)
        ?: throw IOException("This phone has no Wi-Fi Direct")
    private val ch = mgr.initialize(ctx, ctx.mainLooper, null)
        ?: throw IOException("Wi-Fi Direct is unavailable. Turn Wi-Fi on.")

    /**
     * Receiver side: become group owner. Channel preference:
     * 1. the 5 GHz channel this phone's Wi-Fi already uses (single channel = steady speed),
     * 2. any 5 GHz channel, 3. whatever the chip allows.
     */
    suspend fun createGroup(sta: Int): Pair<Invite, WifiP2pGroup> {
        removeGroup()
        val ssid = "DIRECT-hs-" + rnd(4)
        val pass = rnd(12)
        val tries = buildList {
            if (sta in 5150..5900) add(base(ssid, pass).setGroupOperatingFrequency(sta).build())
            add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ).build())
            add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build())
        }
        var last: IOException? = null
        for (cfg in tries) {
            last = try { act { mgr.createGroup(ch, cfg, it) }; null } catch (e: IOException) { e }
            if (last == null) break
            delay(300)
        }
        last?.let { throw it }
        repeat(40) {
            groupInfo()?.let { g -> return Invite(ssid, pass, g.frequency) to g }
            delay(250)
        }
        throw IOException("Wi-Fi Direct group did not come up")
    }

    /** Sender side: join the receiver's group by name + passphrase, scanning only its channel. */
    suspend fun join(inv: Invite) {
        removeGroup()
        val b = base(inv.ssid, inv.pass)
        val cfg = if (inv.freq > 0) b.setGroupOperatingFrequency(inv.freq).build()
        else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build()
        act { mgr.connect(ch, cfg, it) }
    }

    suspend fun groupInfo(): WifiP2pGroup? =
        suspendCancellableCoroutine { c -> mgr.requestGroupInfo(ch) { if (c.isActive) c.resume(it) } }

    suspend fun removeGroup() {
        runCatching { act { mgr.removeGroup(ch, it) } }
    }

    fun close() = ch.close()

    private fun base(ssid: String, pass: String) = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)

    private suspend fun act(call: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine<Unit> { c ->
            call(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (c.isActive) c.resume(Unit) }
                override fun onFailure(reason: Int) {
                    val why = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "not supported on this phone"
                        WifiP2pManager.BUSY -> "radio busy, try again"
                        else -> "failed (is Wi-Fi on?)"
                    }
                    if (c.isActive) c.resumeWithException(IOException("Wi-Fi Direct $why"))
                }
            })
        }

    private fun rnd(n: Int): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..n).map { abc.random() }.joinToString("")
    }
}
