package com.hypershare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** What the receiver puts in its QR code so the sender can join without slow peer discovery. */
data class Invite(val ssid: String, val pass: String, val band: Int) {
    fun encode() = "HSX1|$ssid|$pass|$band"

    companion object {
        fun decode(s: String): Invite {
            val p = s.split('|')
            require(p.size == 4 && p[0] == "HSX1") { "Not a HyperShare QR code" }
            return Invite(p[1], p[2], p[3].toInt())
        }
    }
}

/** Thin coroutine wrapper around WifiP2pManager. Permissions are requested by the activity. */
@SuppressLint("MissingPermission")
class P2p(ctx: Context) {
    private val mgr = ctx.getSystemService(WifiP2pManager::class.java)
        ?: throw IOException("This phone has no Wi-Fi Direct")
    private val ch = mgr.initialize(ctx, ctx.mainLooper, null)

    /** Receiver side: become group owner, preferring 5 GHz. Returns the invite and the group. */
    suspend fun createGroup(): Pair<Invite, WifiP2pGroup> {
        removeGroup()
        val ssid = "DIRECT-hs-" + rnd(4)
        val pass = rnd(12)
        var band = WifiP2pConfig.GROUP_OWNER_BAND_5GHZ
        try {
            act { mgr.createGroup(ch, cfg(ssid, pass, band), it) }
        } catch (e: IOException) {
            // Some chips refuse a forced 5 GHz band (country code, STA on a DFS channel...). Fall back.
            band = WifiP2pConfig.GROUP_OWNER_BAND_AUTO
            act { mgr.createGroup(ch, cfg(ssid, pass, band), it) }
        }
        repeat(40) {
            groupInfo()?.let { return Invite(ssid, pass, band) to it }
            delay(250)
        }
        throw IOException("Wi-Fi Direct group did not come up")
    }

    /** Sender side: join the receiver's group directly by name + passphrase. */
    suspend fun join(inv: Invite) {
        removeGroup()
        act { mgr.connect(ch, cfg(inv.ssid, inv.pass, inv.band), it) }
    }

    suspend fun groupInfo(): WifiP2pGroup? =
        suspendCancellableCoroutine { c -> mgr.requestGroupInfo(ch) { if (c.isActive) c.resume(it) } }

    suspend fun removeGroup() {
        runCatching { act { mgr.removeGroup(ch, it) } }
    }

    fun close() = ch.close()

    private fun cfg(ssid: String, pass: String, band: Int) = WifiP2pConfig.Builder()
        .setNetworkName(ssid)
        .setPassphrase(pass)
        .setGroupOperatingBand(band)
        .build()

    private suspend fun act(call: (WifiP2pManager.ActionListener) -> Unit) =
        suspendCancellableCoroutine<Unit> { c ->
            call(object : WifiP2pManager.ActionListener {
                override fun onSuccess() { if (c.isActive) c.resume(Unit) }
                override fun onFailure(reason: Int) {
                    val why = when (reason) {
                        WifiP2pManager.P2P_UNSUPPORTED -> "unsupported"
                        WifiP2pManager.BUSY -> "busy"
                        else -> "error $reason (is Wi-Fi on?)"
                    }
                    if (c.isActive) c.resumeWithException(IOException("Wi-Fi Direct: $why"))
                }
            })
        }

    private fun rnd(n: Int): String {
        val abc = "abcdefghjkmnpqrstuvwxyz23456789"
        return (1..n).map { abc.random() }.joinToString("")
    }
}
