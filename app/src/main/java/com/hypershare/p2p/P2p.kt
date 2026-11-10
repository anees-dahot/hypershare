package com.hypershare.p2p

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.net.wifi.p2p.WifiP2pConfig
import android.net.wifi.p2p.WifiP2pGroup
import android.net.wifi.p2p.WifiP2pInfo
import android.net.wifi.p2p.WifiP2pManager
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest
import android.os.Build
import android.os.Looper
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * What the receiver shares (QR code, nearby discovery, or a typed code): how to join its
 * Wi-Fi Direct group, which IPs to dial, and its display name. type: D = Wi-Fi Direct, L = debug LAN.
 * [token] is only in the QR code: proof the sender saw the receiver's screen, so no Accept is needed.
 */
data class Invite(
    val type: Char,
    val ssid: String,
    val pass: String,
    val freq: Int,
    val ips: List<String>,
    val name: String = "",
    val token: String = "",
) {
    fun encode(withToken: Boolean = true) = listOf(
        "HSX5", "$type", ssid, pass, "$freq", ips.joinToString(","),
        name.replace('|', ' ').take(30), if (withToken) token else "",
    ).joinToString("|")

    companion object {
        const val DIRECT = 'D'
        const val LAN = 'L' // debug builds only

        fun decode(s: String): Invite {
            val p = s.trim().split('|')
            require(p.size == 8 && p[0] == "HSX5") {
                if (p.firstOrNull()?.startsWith("HSX") == true) "The other phone runs a different HyperShare version. Update both phones."
                else "That's not a HyperShare code."
            }
            return Invite(p[1][0], p[2], p[3], p[4].toIntOrNull() ?: 0, p[5].split(',').filter { it.isNotBlank() }, p[6], p[7])
        }

        /** Invite rebuilt from a 6-character code (typed, or read from a nearby group's Wi-Fi name). */
        fun fromCode(code: String, freq: Int = 0, name: String = ""): Invite {
            val c = normCode(code)
            return Invite(DIRECT, ssidFor(c), passFor(c), freq, listOf(P2p.GO_IP), name)
        }
    }
}

/**
 * Group names look like "DIRECT-hsAB3K7Q": prefix + 6-char code. No device name in it, so a typed
 * code alone rebuilds the exact name (the name travels in the QR code / service discovery instead).
 */
const val SSID_PREFIX = "DIRECT-hs"
const val CODE_LEN = 6
internal const val CODE_ABC = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

fun normCode(c: String) = c.uppercase().filter { it in CODE_ABC }

/** The code in a HyperShare group's Wi-Fi name, or null. */
fun codeOf(ssid: String): String? {
    if (ssid.length != SSID_PREFIX.length + CODE_LEN || !ssid.startsWith(SSID_PREFIX)) return null
    return ssid.substring(SSID_PREFIX.length).takeIf { normCode(it) == it }
}

fun ssidFor(code: String) = SSID_PREFIX + code

/**
 * Passphrase derived from the code, so a phone that only knows the code (typed, or read from a
 * Wi-Fi scan) can join. Joining alone grants nothing: without the QR token the receiver must tap Accept.
 */
fun passFor(code: String): String {
    val d = java.security.MessageDigest.getInstance("SHA-256").digest("hypershare-v5:$code".toByteArray())
    return d.take(14).joinToString("") { CODE_ABC[(it.toInt() and 0xff) % CODE_ABC.length].toString() }
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

internal fun rnd(n: Int, abc: String = "abcdefghjkmnpqrstuvwxyz23456789"): String {
    val r = java.security.SecureRandom()
    return (1..n).map { abc[r.nextInt(abc.length)] }.joinToString("")
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
     * 1. the 5 GHz channel this phone's Wi-Fi already uses: the sender is usually on the same
     *    router, so both radios stay on one channel. Phones whose chip can't run two channels at
     *    once (many MediaTek/Unisoc models) can't join a group on another channel at all.
     * 2. a known-good 5 GHz channel, 3. whatever the chip allows.
     */
    suspend fun host(sta: Int, name: String, only24: Boolean = false): Pair<Invite, WifiP2pGroup> {
        if (groupInfo() != null) removeGroup()
        val token = rnd(10)
        if (Build.VERSION.SDK_INT < 29) return hostLegacy(name, token)
        val code = rnd(CODE_LEN, CODE_ABC)
        val ssid = ssidFor(code)
        val pass = passFor(code)
        val tries = buildList {
            if (only24) {
                if (sta in 2400..2500) add(base(ssid, pass).setGroupOperatingFrequency(sta).build())
                add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_2GHZ).build())
            } else {
                // Router's channel, but never a radar (DFS) one: a group there is hard or impossible
                // for the other phone to find (passive scan only), which made joins take minutes.
                if (nonDfs5(sta)) add(base(ssid, pass).setGroupOperatingFrequency(sta).build())
                add(base(ssid, pass).setGroupOperatingFrequency(5745).build())
                add(base(ssid, pass).setGroupOperatingFrequency(5180).build())
                add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_5GHZ).build())
                // 2.4 GHz router: same channel still beats a group the sender can't reach.
                if (sta in 2400..2500) add(base(ssid, pass).setGroupOperatingFrequency(sta).build())
            }
            add(base(ssid, pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build())
        }
        var last: IOException? = null
        for (cfg in tries) {
            last = try { p2pAct { mgr.createGroup(ch, cfg, it) }; null } catch (e: IOException) { e }
            if (last == null) {
                // Some chips accept the request and then never bring the group up on that channel.
                waitGroup()?.let { g -> return Invite(Invite.DIRECT, ssid, pass, groupFreq(g), listOf(GO_IP), name, token) to g }
                removeGroup()
                last = IOException("Wi-Fi Direct group did not come up")
            }
            delay(300)
        }
        throw last ?: IOException("Wi-Fi Direct group did not come up")
    }

    private suspend fun waitGroup(): WifiP2pGroup? {
        repeat(25) { groupInfo()?.let { return it }; delay(200) }
        return null
    }

    /** Android 8–9: no config builder, so let the chip pick name/passphrase and read them back. */
    private suspend fun hostLegacy(name: String, token: String): Pair<Invite, WifiP2pGroup> {
        p2pAct { mgr.createGroup(ch, it) }
        repeat(40) {
            groupInfo()?.takeIf { !it.passphrase.isNullOrEmpty() }?.let { g ->
                return Invite(Invite.DIRECT, g.networkName, g.passphrase, 0, listOf(GO_IP), name, token) to g
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
        val info = WifiP2pDnsSdServiceInfo.newInstance("HyperShare-" + rnd(4), SERVICE_TYPE, mapOf("i" to inv.encode(withToken = false)))
        runCatching { p2pAct { mgr.clearLocalServices(ch, it) } }
        p2pAct { mgr.addLocalService(ch, info, it) }
        // A phone only answers service queries while its discovery runs, and Android ends discovery
        // after ~2 min (some OEMs much sooner). Renew it, but rarely: a scanning group owner briefly
        // leaves its channel. Senders also find us by our Wi-Fi name, which needs none of this.
        // Once a phone has joined our group, stop: discovery makes the radio leave the group's
        // channel, which slows the join and the transfer that follows.
        var lastFind = 0L
        var stopped = false
        while (true) {
            val joined = groupInfo()?.clientList?.isNotEmpty() == true
            if (joined && !stopped) { runCatching { p2pAct { mgr.stopPeerDiscovery(ch, it) } }; stopped = true }
            if (!joined) {
                stopped = false
                val now = System.currentTimeMillis()
                if (now - lastFind > 45_000) { runCatching { p2pAct { mgr.discoverPeers(ch, it) } }; lastFind = now }
            }
            delay(1_000)
        }
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
    suspend fun join(inv: Invite, sta: Int, onRetry: (Int) -> Unit = {}): String? {
        if (Build.VERSION.SDK_INT < 29) {
            legacy = LegacyJoin(ctx).also { it.join(inv.ssid, inv.pass) }
            return null
        }
        runCatching { p2pAct { mgr.stopPeerDiscovery(ch, it) } }
        runCatching { p2pAct { mgr.cancelConnect(ch, it) } }
        if (groupInfo() != null) removeGroup()
        val b = base(inv.ssid, inv.pass)
        val pinned = if (inv.freq > 0) b.setGroupOperatingFrequency(inv.freq).build() else null
        val any = base(inv.ssid, inv.pass).setGroupOperatingBand(WifiP2pConfig.GROUP_OWNER_BAND_AUTO).build()
        // Our own Wi-Fi on another channel: the radio hops between both, so the scan takes longer.
        val hop = sta > 0 && inv.freq > 0 && sta != inv.freq
        // 1: scan only the receiver's channel (fast). 2: full scan, in case the channel hint is wrong
        // or the chip won't scan a single channel while on Wi-Fi. 3: channel hint again, longer.
        val plan = listOfNotNull(
            pinned?.let { it to if (hop) 12_000L else 8_000L },
            any to 15_000L,
            (pinned ?: any) to 15_000L,
        )
        plan.forEachIndexed { attempt, (cfg, wait) ->
            if (attempt > 0) {
                onRetry(attempt + 1)
                runCatching { p2pAct { mgr.cancelConnect(ch, it) } }
                if (groupInfo() != null) removeGroup()
                delay(500)
            }
            val sent = runCatching { p2pAct { mgr.connect(ch, cfg, it) } }
            if (sent.isFailure) { if (attempt == plan.lastIndex) throw sent.exceptionOrNull()!!; delay(800); return@forEachIndexed }
            awaitGroup(wait)?.let { return it }
        }
        throw IOException(
            "Couldn't join ${inv.name.ifBlank { "the receiver" }}'s Wi-Fi Direct." +
                if (sta > 0) " This phone is connected to a Wi-Fi network, and some phones can't do both at once: " +
                    "disconnect from that network (keep Wi-Fi on) and try again."
                else " Move the phones closer and try again. If it still fails, turn Wi-Fi off and on."
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

data class NearbyPeer(val key: String, val name: String, val invite: String, val freq: Int, val seen: Long)

/**
 * Sender: finds receivers two ways at once:
 * - Wi-Fi Direct service discovery (works on Android 13+ without location),
 * - the group's Wi-Fi name in normal Wi-Fi scan results ("DIRECT-hsCODE-Name"): group owners beacon
 *   all the time, so this works even when the receiver's chip stopped answering discovery.
 */
@SuppressLint("MissingPermission")
class Nearby(ctx: Context) {
    val peers = MutableStateFlow<List<NearbyPeer>>(emptyList())
    private val app = ctx.applicationContext
    private val mgr = app.getSystemService(WifiP2pManager::class.java)
    private val wm = app.getSystemService(WifiManager::class.java)

    private fun put(p: NearbyPeer) = peers.update { list ->
        // The same receiver can show up via both paths; keep one entry, preferring the fresher one.
        list.filter { it.key != p.key } + p
    }

    /** Frequency of a nearby group with this code, if a scan has seen it. */
    fun freqFor(code: String): Int = peers.value.firstOrNull { it.key == code }?.freq ?: 0

    /** Scans until the calling coroutine is cancelled. */
    suspend fun run() {
        coroutineScope {
            launch { scanLoop() }
            serviceLoop()
        }
    }

    private suspend fun serviceLoop() {
        val mgr = mgr ?: return
        val ch = mgr.initialize(app, Looper.getMainLooper(), null) ?: return
        try {
            discoverServices(mgr, ch)
        } finally {
            // Don't stopPeerDiscovery here: this runs as the screen closes, which can be right as a
            // join starts, and would abort Android's own scan for the receiver. join() stops it safely.
            runCatching { mgr.clearServiceRequests(ch, null) }
            if (Build.VERSION.SDK_INT >= 27) runCatching { ch.close() }
        }
    }

    private suspend fun discoverServices(mgr: WifiP2pManager, ch: WifiP2pManager.Channel) {
        mgr.setDnsSdResponseListeners(ch, { _, _, _ -> }, { _, txt, dev ->
            val raw = txt["i"] ?: return@setDnsSdResponseListeners
            val inv = runCatching { Invite.decode(raw) }.getOrNull() ?: return@setDnsSdResponseListeners
            val key = codeOf(inv.ssid) ?: dev.deviceAddress
            put(NearbyPeer(key, inv.name.ifBlank { dev.deviceName ?: "Phone" }, raw, inv.freq, System.currentTimeMillis()))
        })
        val req = WifiP2pDnsSdServiceRequest.newInstance(SERVICE_TYPE)
        while (true) {
            // Re-adding the request each round: many OEM builds answer only the first
            // discoverServices() after a request was added, then go quiet.
            runCatching { p2pAct { mgr.clearServiceRequests(ch, it) } }
            runCatching { p2pAct { mgr.addServiceRequest(ch, req, it) } }
            // discoverServices() runs its own peer scan; calling discoverPeers() first just makes it BUSY.
            val started = runCatching { p2pAct { mgr.discoverServices(ch, it) } }.isSuccess
            if (!started) runCatching { p2pAct { mgr.discoverPeers(ch, it) } }
            delay(8_000)
            prune()
        }
    }

    private fun prune() {
        val now = System.currentTimeMillis()
        peers.update { list -> list.filter { now - it.seen < 40_000 } }
    }

    /** Adds HyperShare groups from the latest Wi-Fi scan results (needs location; skipped quietly without it). */
    @Suppress("DEPRECATION")
    private fun ingest() {
        val results = runCatching { wm?.scanResults }.getOrNull() ?: return
        for (r in results) {
            val ssid = (if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.removeSurrounding("\"") else r.SSID) ?: continue
            val code = codeOf(ssid) ?: continue
            // Scan results can be minutes old; only trust recent ones (timestamp is µs since boot).
            val ageMs = android.os.SystemClock.elapsedRealtime() - r.timestamp / 1000
            if (ageMs > 60_000) continue
            // Prefer the service-discovery entry if we have one: it carries the receiver's name.
            val old = peers.value.firstOrNull { it.key == code }
            put(NearbyPeer(code, old?.name ?: "Phone $code", old?.invite ?: Invite.fromCode(code, r.frequency).encode(), r.frequency, System.currentTimeMillis()))
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun scanLoop() {
        var lastScan = 0L
        while (true) {
            val now = System.currentTimeMillis()
            // Android allows 4 scans per 2 minutes per app; results from other apps' scans come free.
            if (now - lastScan > 30_000) { runCatching { wm?.startScan() }; lastScan = now }
            ingest()
            delay(3_000)
        }
    }
}
