package com.hypershare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the receiver shares (QR code or nearby discovery): how to join its link, which IPs to dial,
 * and its display name. type: D = Wi-Fi Direct group, H = local-only hotspot, L = debug LAN.
 */
data class Invite(
    val type: Char,
    val ssid: String,
    val pass: String,
    val freq: Int,
    val wpa3: Boolean,
    val ips: List<String>,
    val name: String = "",
) {
    fun encode() = listOf(
        "HSX4", "$type", ssid, pass, "$freq", if (wpa3) "1" else "0", ips.joinToString(","),
        name.replace('|', ' ').take(30),
    ).joinToString("|")

    companion object {
        const val DIRECT = 'D'
        const val HOTSPOT = 'H'
        const val LAN = 'L' // debug builds only

        fun decode(s: String): Invite {
            val p = s.split('|')
            require(p.size == 8 && p[0] == "HSX4") {
                if (p.firstOrNull()?.startsWith("HSX") == true) "The other phone runs a different HyperShare version. Update both phones."
                else "That's not a HyperShare code."
            }
            return Invite(p[1][0], p[2], p[3], p[4].toIntOrNull() ?: 0, p[5] == "1", p[6].split(',').filter { it.isNotBlank() }, p[7])
        }
    }
}

const val SERVICE_TYPE = "_hypershare._tcp"

/**
 * Frequency of this phone's normal Wi-Fi connection, or -1.
 * If the transfer link runs on a different channel, one radio has to hop between both channels
 * (multi-channel concurrency), which halves throughput and makes it jumpy.
 */
@Suppress("DEPRECATION")
fun staFreq(ctx: Context): Int =
    runCatching { ctx.getSystemService(WifiManager::class.java).connectionInfo.frequency }.getOrDefault(-1)

/** 5 GHz channels without radar rules (36–48, 149–165): fine for a Wi-Fi Direct group owner. */
fun nonDfs5(f: Int) = f in 5170..5250 || f in 5735..5835

/** Group frequency, or 0 where Android (8–9) can't tell us. */
fun groupFreq(g: WifiP2pGroup) = if (Build.VERSION.SDK_INT >= 29) g.frequency else 0

/** Suspends on a WifiP2pManager action; failure becomes a readable IOException. */
internal suspend fun p2pAct(call: (WifiP2pManager.ActionListener) -> Unit) =
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

// ------------------------------------------------------------------ Wi-Fi Direct

@SuppressLint("MissingPermission")
class P2p(private val ctx: Context) : Closeable {
    companion object { const val GO_IP = "192.168.49.1" }

    private val mgr = ctx.getSystemService(WifiP2pManager::class.java)
        ?: throw IOException("This phone has no Wi-Fi Direct")
    private val ch = mgr.initialize(ctx, ctx.mainLooper, null)
        ?: throw IOException("Wi-Fi Direct is unavailable. Turn Wi-Fi on.")
    private var legacy: LegacyJoin? = null
    val legacyFreq get() = legacy?.freq ?: 0

    /**
     * Receiver side: become group owner. Channel preference:
     * 1. the 5 GHz channel this phone's Wi-Fi already uses (single channel = steady speed),
     * 2. any 5 GHz channel, 3. whatever the chip allows.
     */
    suspend fun host(sta: Int, name: String, only24: Boolean = false): Pair<Invite, WifiP2pGroup> {
        if (groupInfo() != null) removeGroup()
        if (Build.VERSION.SDK_INT < 29) return hostLegacy(name)
        val ssid = "DIRECT-hs-" + rnd(4)
        val pass = rnd(12)
        val tries = buildList {
            // Compatibility mode for 2.4 GHz-only senders: same channel as our Wi-Fi if it's 2.4, else any 2.4.
            if (only24) {
                if (sta in 2400..2500) add(base(ssid, pass).setGroupOperatingFrequency(sta).build())
                add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build())
            }
            if (!only24) {
                // Share the router's channel only if it's not a DFS (radar) channel: a group there is
                // hard or impossible for the other phone to find, which made joins take minutes.
                if (nonDfs5(sta)) add(base(ssid, pass).setGroupOperatingFrequency(sta).build())
                // Otherwise pick a known-good 5 GHz channel ourselves (149, then 36).
                add(base(ssid, pass).setGroupOperatingFrequency(5745).build())
                add(base(ssid, pass).setGroupOperatingFrequency(5180).build())
                add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ).build())
            }
            add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build())
        }
        var last: IOException? = null
        for (cfg in tries) {
            last = try { p2pAct { mgr.createGroup(ch, cfg, it) }; null } catch (e: IOException) { e }
            if (last == null) break
            delay(300)
        }
        last?.let { throw it }
        repeat(40) {
            groupInfo()?.let { g -> return Invite(Invite.DIRECT, ssid, pass, groupFreq(g), false, listOf(GO_IP), name) to g }
            delay(200)
        }
        throw IOException("Wi-Fi Direct group did not come up")
    }

    /** Android 8–9: no config builder, so let the chip pick name/passphrase and read them back. */
    private suspend fun hostLegacy(name: String): Pair<Invite, WifiP2pGroup> {
        p2pAct { mgr.createGroup(ch, it) }
        repeat(40) {
            groupInfo()?.takeIf { !it.passphrase.isNullOrEmpty() }?.let { g ->
                return Invite(Invite.DIRECT, g.networkName, g.passphrase, 0, false, listOf(GO_IP), name) to g
            }
            delay(200)
        }
        throw IOException("Wi-Fi Direct group did not come up")
    }

    /**
     * Receiver: announce this group to nearby HyperShare phones (Wi-Fi Direct service discovery),
     * so they can connect without scanning. Runs until cancelled.
     */
    suspend fun advertise(inv: Invite) {
        val info = WifiP2pDnsSdServiceInfo.newInstance("HyperShare-" + rnd(4), SERVICE_TYPE, mapOf("i" to inv.encode()))
        runCatching { p2pAct { mgr.clearLocalServices(ch, it) } }
        p2pAct { mgr.addLocalService(ch, info, it) }
        // One discovery round makes us answer service queries. Don't repeat it: a group owner that
        // keeps scanning leaves its channel and makes the sender's join slow or fail.
        runCatching { p2pAct { mgr.discoverPeers(ch, it) } }
    }

    fun stopAdvertising() {
        runCatching { mgr.clearLocalServices(ch, null) }
        runCatching { mgr.stopPeerDiscovery(ch, null) }
    }

    /** False on 2.4 GHz-only phones (e.g. many budget Android 9 models). */
    @Suppress("DEPRECATION")
    fun supports5GHz(): Boolean = runCatching { ctx.getSystemService(WifiManager::class.java).is5GHzBandSupported }.getOrDefault(true)

    /**
     * Sender side: join the receiver's group by name + passphrase and return the owner's address
     * once Android reports the link is up.
     *
     * Made for speed: clear anything that blocks a join first (running discovery, a stale
     * connect, an old group), scan only the receiver's channel when we know it, and if a join
     * attempt stalls, cancel and retry quickly instead of waiting it out.
     */
    suspend fun join(inv: Invite, onRetry: (Int) -> Unit = {}): String? {
        if (Build.VERSION.SDK_INT < 29) {
            legacy = LegacyJoin(ctx).also { it.join(inv.ssid, inv.pass) }
            return null
        }
        runCatching { p2pAct { mgr.stopPeerDiscovery(ch, it) } }
        runCatching { p2pAct { mgr.cancelConnect(ch, it) } }
        if (groupInfo() != null) removeGroup()
        val b = base(inv.ssid, inv.pass)
        val cfg = if (inv.freq > 0) b.setGroupOperatingFrequency(inv.freq).build()
        else b.setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build()
        // One channel to scan → seconds; unknown channel (Android 8–9 receiver) → scan everything, allow longer.
        val wait = if (inv.freq > 0) 8_000L else 15_000L
        repeat(3) { attempt ->
            if (attempt > 0) {
                onRetry(attempt + 1)
                runCatching { p2pAct { mgr.cancelConnect(ch, it) } }
                if (groupInfo() != null) removeGroup()
                delay(400)
            }
            val sent = runCatching { p2pAct { mgr.connect(ch, cfg, it) } }
            if (sent.isFailure) { if (attempt == 2) throw sent.exceptionOrNull()!!; delay(700); return@repeat }
            awaitGroup(wait)?.let { return it }
        }
        throw IOException(
            "Couldn't join ${inv.name.ifBlank { "the receiver" }}'s Wi-Fi Direct. Move the phones closer and scan again." +
                if (inv.freq <= 0) " If it still fails, turn this phone's Wi-Fi off and on." else ""
        )
    }

    /**
     * Sender: wait until Android reports the group is formed, and return the owner's address.
     * Much faster than blindly retrying TCP connects while the link is still coming up.
     */
    suspend fun awaitGroup(timeoutMs: Long): String? {
        if (legacy != null) return null
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val info = suspendCancellableCoroutine<WifiP2pInfo?> { c ->
                mgr.requestConnectionInfo(ch) { if (c.isActive) c.resume(it) }
            }
            if (info != null && info.groupFormed && !info.isGroupOwner) return info.groupOwnerAddress?.hostAddress ?: GO_IP
            delay(120)
        }
        return null
    }

    private suspend fun groupInfo(): WifiP2pGroup? =
        suspendCancellableCoroutine { c -> mgr.requestGroupInfo(ch) { if (c.isActive) c.resume(it) } }

    private suspend fun removeGroup() { runCatching { p2pAct { mgr.removeGroup(ch, it) } } }

    /** Tears the group down without waiting (safe from any thread). */
    override fun close() {
        legacy?.close(); legacy = null
        stopAdvertising()
        runCatching { mgr.removeGroup(ch, null) }
        if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
    }

    @androidx.annotation.RequiresApi(29)
    private fun base(ssid: String, pass: String) = WifiP2pConfig.Builder().setNetworkName(ssid).setPassphrase(pass)
}

// ------------------------------------------------------------------ Nearby discovery (sender)

data class NearbyPeer(val address: String, val name: String, val invite: String, val freq: Int, val seen: Long)

/** Sender: finds receivers that advertise HyperShare over Wi-Fi Direct service discovery. */
@SuppressLint("MissingPermission")
class Nearby(ctx: Context) {
    val peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    private val mgr = ctx.getSystemService(WifiP2pManager::class.java)

    /** Scans until the calling coroutine is cancelled. */
    suspend fun run() {
        val mgr = mgr ?: return
        val ch = mgr.initialize(ctxHolder ?: return, Looper.getMainLooper(), null) ?: return
        try {
            mgr.setDnsSdResponseListeners(ch, { _, _, _ -> }, { _, txt, dev ->
                val raw = txt["i"] ?: return@setDnsSdResponseListeners
                val inv = runCatching { Invite.decode(raw) }.getOrNull() ?: return@setDnsSdResponseListeners
                val peer = NearbyPeer(dev.deviceAddress, inv.name.ifBlank { dev.deviceName ?: "Phone" }, raw, inv.freq, System.currentTimeMillis())
                peers.update { list -> list.filter { it.address != peer.address } + peer }
            })
            runCatching { p2pAct { mgr.addServiceRequest(ch, WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE), it) } }
            while (true) {
                runCatching { p2pAct { mgr.discoverPeers(ch, it) } }
                runCatching { p2pAct { mgr.discoverServices(ch, it) } }
                delay(6_000)
                val now = System.currentTimeMillis()
                peers.update { list -> list.filter { now - it.seen < 40_000 } }
            }
        } finally {
            // Don't stopPeerDiscovery here: this runs as the screen closes, which can be right as a
            // join starts, and would abort Android's own scan for the receiver. join() stops it safely.
            runCatching { mgr.clearServiceRequests(ch, null) }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
    }

    private val ctxHolder: Context? = ctx.applicationContext
}

// ------------------------------------------------------------------ Local-only hotspot

/**
 * Receiver side of hotspot mode. Many phones run their hotspot on a wider 5 GHz channel
 * (80 MHz) than Wi-Fi Direct, so this can be noticeably faster on the same pair of phones.
 */
@SuppressLint("MissingPermission")
class HotspotHost(private val ctx: Context) : Closeable {
    private var res: WifiManager.LocalOnlyHotspotReservation? = null

    suspend fun start(name: String): Invite {
        val wm = ctx.getSystemService(WifiManager::class.java)
        val r = suspendCancellableCoroutine { c ->
            wm.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(reservation: WifiManager.LocalOnlyHotspotReservation) {
                    if (c.isActive) c.resume(reservation) else reservation.close()
                }
                override fun onFailed(reason: Int) {
                    if (c.isActive) c.resumeWithException(IOException(
                        "Couldn't start the hotspot. Turn off your normal hotspot" +
                            (if (Build.VERSION.SDK_INT < 33) " and turn on Location" else "") + ", then try again."
                    ))
                }
            }, Handler(Looper.getMainLooper()))
        }
        res = r
        val (ssid, pass, wpa3) = if (Build.VERSION.SDK_INT >= 30) {
            val c = r.softApConfiguration
            @Suppress("DEPRECATION")
            val n = if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.removeSurrounding("\"") else c.ssid
            Triple(n ?: "", c.passphrase ?: "", c.securityType == SoftApConfiguration.SECURITY_TYPE_WPA3_SAE)
        } else {
            @Suppress("DEPRECATION")
            val w = r.wifiConfiguration ?: throw IOException("Hotspot has no configuration")
            @Suppress("DEPRECATION")
            Triple(w.SSID.removeSurrounding("\""), w.preSharedKey.removeSurrounding("\""), false)
        }
        // The hotspot interface gets its address a moment after start.
        repeat(20) {
            val ips = apIps()
            if (ips.isNotEmpty()) return Invite(Invite.HOTSPOT, ssid, pass, 0, wpa3, ips, name)
            delay(250)
        }
        throw IOException("Hotspot started but has no address")
    }

    /** IPv4 addresses on Wi-Fi-like interfaces, minus the one used for the normal Wi-Fi connection. */
    private fun apIps(): List<String> {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val sta = cm.activeNetwork?.let { cm.getLinkProperties(it) }?.linkAddresses
            ?.mapNotNull { (it.address as? Inet4Address)?.hostAddress }.orEmpty().toSet()
        return NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback && !it.name.startsWith("p2p") && !it.name.startsWith("rmnet") && !it.name.startsWith("ccmni") }
            .flatMap { i -> i.inetAddresses.toList().filterIsInstance<Inet4Address>().map { i.name to it.hostAddress!! } }
            .filter { (_, ip) -> ip !in sta && (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) }
            .sortedBy { (n, _) -> if (n.startsWith("wlan") || n.startsWith("swlan") || n.startsWith("ap")) 0 else 1 }
            .map { it.second }
    }

    override fun close() { runCatching { res?.close() }; res = null }
}

/** Sender side of hotspot mode: join via WifiNetworkSpecifier and route this app's sockets over it. */
class HotspotClient(private val ctx: Context) : Closeable {
    private val cm = ctx.getSystemService(ConnectivityManager::class.java)
    private var cb: ConnectivityManager.NetworkCallback? = null
    var freq = 0; private set
    private var legacy: LegacyJoin? = null

    suspend fun join(inv: Invite) {
        if (Build.VERSION.SDK_INT < 29) {
            legacy = LegacyJoin(ctx).also { it.join(inv.ssid, inv.pass); freq = it.freq }
            return
        }
        joinModern(inv)
    }

    @androidx.annotation.RequiresApi(29)
    private suspend fun joinModern(inv: Invite) {
        val spec = WifiNetworkSpecifier.Builder().setSsid(inv.ssid).apply {
            if (inv.wpa3) setWpa3Passphrase(inv.pass) else setWpa2Passphrase(inv.pass)
        }.build()
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(spec)
            .build()
        val net = withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine<Network> { c ->
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) { if (c.isActive) c.resume(network) }
                    override fun onUnavailable() {
                        if (c.isActive) c.resumeWithException(IOException("Couldn't join the receiver's hotspot"))
                    }
                }
                cb = callback
                try {
                    cm.requestNetwork(req, callback)
                } catch (e: Exception) {
                    // Some OEM builds refuse specifier requests outright (e.g. missing location permission).
                    if (c.isActive) c.resumeWithException(IOException("This phone refused to join the hotspot (${e.javaClass.simpleName}: ${e.message}). Use Wi-Fi Direct instead."))
                }
            }
        } ?: throw IOException(
            "Couldn't join the receiver's hotspot. Check that Wi-Fi and Location are on, " +
                "or switch the receiving phone to Wi-Fi Direct in Settings."
        )
        // Route all of this app's sockets over the hotspot while the transfer runs.
        cm.bindProcessToNetwork(net)
        freq = (cm.getNetworkCapabilities(net)?.transportInfo as? WifiInfo)?.frequency ?: 0
    }

    override fun close() {
        legacy?.close(); legacy = null
        runCatching { cm.bindProcessToNetwork(null) }
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
    }
}
