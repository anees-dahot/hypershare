package com.hypershare.transfer

import android.content.Context
import android.net.Uri
import com.hypershare.data.History
import com.hypershare.data.HistoryItem
import com.hypershare.data.Picked
import com.hypershare.data.Prefs
import com.hypershare.p2p.HotspotClient
import com.hypershare.p2p.HotspotHost
import com.hypershare.p2p.Invite
import com.hypershare.p2p.P2p
import com.hypershare.p2p.staFreq
import com.hypershare.service.TransferService
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min

enum class Role { Send, Receive }
enum class Phase { Idle, Preparing, Waiting, Connecting, Transferring, Done, Failed }
enum class FStatus { Queued, Active, Done, Skipped }

data class FileItem(
    val idx: Int,
    val name: String,
    val size: Long,
    val mime: String,
    val group: String,
    val uri: Uri?,
    val done: Long = 0,
    val status: FStatus = FStatus.Queued,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val role: Role? = null,
    val bench: Boolean = false,
    /** Session still open: the sender can add more files. */
    val connected: Boolean = false,
    val error: String? = null,
    val qr: String? = null,
    /** "Wi-Fi Direct" / "Hotspot". */
    val link: String? = null,
    val freqMhz: Int = 0,
    val tip: String? = null,
    val files: List<FileItem> = emptyList(),
    val total: Long = 0,
    val done: Long = 0,
    /** Smoothed current speed (EMA over ~1 s). */
    val mbps: Double = 0.0,
    val avgMbps: Double = 0.0,
    val peakMbps: Double = 0.0,
    /** Speed history for the chart, newest last. */
    val samples: List<Float> = emptyList(),
    val elapsedMs: Long = 0,
    val etaSec: Long = -1,
    /** What the connection is doing right now ("Joining Wi-Fi Direct…"). */
    val step: String? = null,
    /** How long connecting took, by stage — shown so slow phases can be spotted. */
    val connectLog: String? = null,
) {
    val active get() = phase in setOf(Phase.Preparing, Phase.Waiting, Phase.Connecting, Phase.Transferring)
}

private class Track(
    val idx: Int, val name: String, val size: Long, val mime: String, val group: String,
    val uri: Uri?, val src: Source?, val sink: Sink?,
) {
    val got = AtomicLong()
    @Volatile var done = false
    @Volatile var skipped = false
}

/** One pairing between two phones. Lives until either side says BYE or the link drops. */
private class Session(val ctx: Context, val role: Role, val bench: Boolean, val gen: Int) {
    val tracks = CopyOnWriteArrayList<Track>()
    private val open = CopyOnWriteArrayList<Closeable>()
    val end = CompletableDeferred<Unit>()
    val queue = Channel<Chunk>(Channel.UNLIMITED)
    val added = Channel<Int>(Channel.UNLIMITED)
    val addLock = Mutex()
    var dout: DataOutputStream? = null
    @Volatile var ending = false
    @Volatile var cancelled = false

    val pending get() = tracks.any { !it.done && !it.skipped }

    fun <T : Closeable> keep(c: T): T = c.also { open += it }

    fun msg(type: Int, body: DataOutputStream.() -> Unit = {}) {
        val o = dout ?: return
        synchronized(o) { o.writeByte(type); o.body(); o.flush() }
    }

    fun fail(e: Throwable) { if (!ending) end.completeExceptionally(e) }

    /** Local end: user tapped Done/Finish (graceful) or Cancel. Tells the peer, then tears down. */
    fun close(userCancel: Boolean) {
        if (ending) return
        cancelled = userCancel && pending
        ending = true
        runCatching { msg(Proto.T_BYE) }
        shutdown()
    }

    /** Peer said BYE or the control link dropped. */
    fun peerGone() {
        if (ending) return
        ending = true
        shutdown()
    }

    private fun shutdown() {
        queue.close()
        open.forEach { runCatching { it.close() } }
        tracks.forEach { t -> t.src?.close(); if (!t.done) t.sink?.abort() }
        end.complete(Unit)
    }

    fun skip(idx: Int, tell: Boolean) {
        val t = tracks.getOrNull(idx) ?: return
        if (t.done || t.skipped) return
        t.skipped = true
        t.sink?.abort()
        if (tell) runCatching { msg(Proto.T_SKIP) { writeInt(idx) } }
    }
}

/** App-wide transfer state machine. One session at a time. */
object Engine {
    const val CHART_POINTS = 120

    val state = MutableStateFlow(UiState())

    /** True while a session runs; TransferService watches it and stops itself when false. */
    val needService = MutableStateFlow(false)

    /** Any stray coroutine error is recorded and shown, never allowed to close the app. */
    private val guardHandler = CoroutineExceptionHandler { _, e ->
        com.hypershare.CrashLog.save(e, "Unhandled transfer error (app kept running)")
        state.update { it.copy(phase = Phase.Failed, connected = false, mbps = 0.0, error = friendly(e)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guardHandler)
    private var job: Job? = null
    @Volatile private var gen = 0
    @Volatile private var sess: Session? = null

    private fun set(g: Int, f: (UiState) -> UiState) { if (g == gen) state.update(f) }

    /** [lan]: debug builds only — skip Wi-Fi setup and listen on the existing network (emulator testing). */
    fun startReceive(ctx: Context, lan: Boolean = false) = run(ctx, Role.Receive, false) { receive(it, lan) }

    fun startSend(ctx: Context, items: List<Picked>, qr: String, bench: Boolean) {
        val inv = try { Invite.decode(qr) } catch (e: Exception) {
            gen++; state.value = UiState(phase = Phase.Failed, role = Role.Send, error = e.message); return
        }
        if (!bench && items.isEmpty()) {
            gen++; state.value = UiState(phase = Phase.Failed, role = Role.Send, error = "Nothing selected to send."); return
        }
        run(ctx, Role.Send, bench) { send(it, if (bench) listOf(Proto.benchItem()) else items, inv) }
    }

    /** Sender: queue more files on the open session. */
    fun addFiles(items: List<Picked>) {
        val s = sess ?: return
        if (s.role != Role.Send || s.ending || items.isEmpty()) return
        scope.launch { try { addSend(s, items) } catch (e: Throwable) { s.fail(e) } }
    }

    /** Either side: cancel one file. */
    fun skip(idx: Int) { val s = sess ?: return; scope.launch { s.skip(idx, tell = true) } }

    /** Stop now; stays on screen showing what happened. */
    fun cancel() { val s = sess ?: return; scope.launch { s.close(userCancel = true) } }

    /** Leave the transfer screen: ends the session (tells the other phone) and returns home. */
    fun reset() {
        val s = sess
        sess = null
        gen++
        state.value = UiState()
        if (s != null) scope.launch { s.close(userCancel = true) } else job?.cancel()
    }

    private fun run(ctx: Context, role: Role, bench: Boolean, block: suspend CoroutineScope.(Session) -> Unit) {
        sess?.let { old -> scope.launch { old.close(userCancel = true) } }
        job?.cancel()
        val g = ++gen
        val app = ctx.applicationContext
        val s = Session(app, role, bench, g).also { sess = it }
        state.value = UiState(phase = Phase.Preparing, role = role, bench = bench)
        needService.value = true
        TransferService.start(app)
        job = scope.launch {
            try {
                coroutineScope {
                    block(s)
                    s.end.await()
                    coroutineContext.cancelChildren()
                }
                finalState(s)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!s.ending) com.hypershare.CrashLog.save(e, "Transfer failed: ${friendly(e)}")
                if (s.ending) finalState(s)
                else set(g) { it.copy(phase = Phase.Failed, qr = null, connected = false, mbps = 0.0, error = friendly(e), files = items(s)) }
            } finally {
                withContext(NonCancellable) { s.close(userCancel = false) }
                if (sess === s) sess = null
                if (sess == null) needService.value = false
                val doneFiles = s.tracks.filter { it.done && it.name != Proto.BENCH_NAME }
                if (doneFiles.isNotEmpty()) History.add(app, doneFiles.map {
                    HistoryItem(role.name, it.name.substringAfterLast('/'), it.size, it.mime,
                        (it.sink?.readyUri ?: it.uri)?.toString(), System.currentTimeMillis())
                })
            }
        }
    }

    private fun finalState(s: Session) {
        val left = s.pending
        set(s.gen) {
            it.copy(
                phase = when { s.cancelled -> Phase.Failed; left -> Phase.Failed; else -> Phase.Done },
                error = when { s.cancelled -> "Cancelled"; left -> "The other phone stopped the transfer."; else -> null },
                connected = false, qr = null, mbps = 0.0, etaSec = 0, files = items(s),
            )
        }
    }

    private fun friendly(e: Throwable) = when (e) {
        is EOFException -> "Connection lost. The other phone cancelled or went out of range."
        else -> e.message ?: e.toString()
    }

    private fun tipFor(sta: Int, grp: Int): String? = when {
        grp in 1..4999 -> "Linked on 2.4 GHz. Speed tops out near 15 MB/s on this band."
        sta > 0 && grp > 0 && sta != grp ->
            "This phone is also connected to a Wi-Fi network on another channel, so its radio " +
                "switches back and forth. For steady top speed, disconnect from Wi-Fi networks " +
                "(keep Wi-Fi itself on) or try Hotspot mode in Settings."
        else -> null
    }

    private fun linkLabel(type: Char, freq: Int) =
        (when (type) { Invite.HOTSPOT -> "Hotspot"; Invite.LAN -> "LAN (debug)"; else -> "Wi-Fi Direct" }) +
            when { freq <= 0 -> ""; freq < 5000 -> " · 2.4 GHz"; else -> " · 5 GHz ch ${(freq - 5000) / 5}" }

    // ---------------------------------------------------------------- receiver

    private suspend fun CoroutineScope.receive(s: Session, lan: Boolean) {
        val ctx = s.ctx
        val inv: Invite
        if (lan) {
            inv = Invite(Invite.LAN, "-", "-", 0, false, listOf("10.0.2.2"))
        } else if (Prefs.link(ctx) == Prefs.LINK_HOTSPOT) {
            val h = s.keep(HotspotHost(ctx))
            inv = h.start()
        } else {
            val p = s.keep(P2p(ctx))
            val sta = staFreq(ctx)
            val (i, g) = p.host(sta)
            inv = i
            set(s.gen) { it.copy(tip = tipFor(sta, com.hypershare.p2p.groupFreq(g))) }
        }
        set(s.gen) {
            it.copy(phase = Phase.Waiting, qr = inv.encode(), freqMhz = inv.freq, link = linkLabel(inv.type, inv.freq))
        }

        val ctrlServer = s.keep(ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.CTRL_PORT)) })
        val dataServer = s.keep(ServerSocketChannel.open().apply { socket().reuseAddress = true; bind(InetSocketAddress(Proto.DATA_PORT)) })

        val ctrl = s.keep(runInterruptible { ctrlServer.accept() }).apply { tcpNoDelay = true }
        val din = DataInputStream(BufferedInputStream(ctrl.getInputStream()))
        s.dout = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))
        if (din.readInt() != Proto.MAGIC) throw IOException("The other phone runs a different HyperShare version. Update both.")
        val streams = din.readInt().coerceIn(1, 16)
        s.msg(1)
        set(s.gen) { it.copy(phase = Phase.Connecting, qr = null, connected = true) }

        // Data streams, accepted as the sender opens them.
        launch {
            repeat(streams) {
                val sc = s.keep(dataServer.accept()).also { tune(it) }
                launch { guard(s) { recvLoop(sc, s) } }
            }
        }
        launch { guard(s) { recvControl(din, s) } }
        ticker(s)
    }

    private fun recvControl(din: DataInputStream, s: Session) {
        while (true) {
            val type = try { din.readByte().toInt() } catch (e: IOException) {
                if (!s.pending) { s.peerGone(); return } else throw e
            }
            when (type) {
                Proto.T_ADD -> {
                    val n = din.readInt()
                    val metas = List(n) { Meta(din.readUTF(), din.readLong(), din.readUTF(), din.readUTF()) }
                    val base = s.tracks.size
                    val new = metas.mapIndexed { i, m ->
                        Track(base + i, m.name, m.size, m.mime, m.group, null, null, Sink.open(s.ctx, m.name, m.mime))
                    }
                    s.tracks.addAll(new)
                    s.msg(Proto.T_ADDED) { writeInt(s.tracks.size) }
                    new.filter { it.size == 0L }.forEach { t ->
                        t.sink?.finish(); t.done = true
                        s.msg(Proto.T_FILE_DONE) { writeInt(t.idx) }
                    }
                }
                Proto.T_SKIP -> s.skip(din.readInt(), tell = false)
                Proto.T_BYE -> { s.peerGone(); return }
                else -> throw IOException("Unknown message from sender")
            }
        }
    }

    private class Meta(val name: String, val size: Long, val mime: String, val group: String)

    private fun recvLoop(sc: SocketChannel, s: Session) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        val buf = ByteBuffer.allocateDirect(Proto.CHUNK)
        val crc = CRC32()
        while (true) {
            hdr.clear(); readFully(sc, hdr); hdr.flip()
            val idx = hdr.int
            if (idx < 0) break
            val off = hdr.long; val len = hdr.int; val sum = hdr.int
            val t = s.tracks.getOrNull(idx)
            if (t == null || len !in 1..Proto.CHUNK) throw IOException("Bad data from sender")
            buf.clear(); buf.limit(len); readFully(sc, buf); buf.flip()
            if (t.skipped) continue // in flight when cancelled: drop it
            crc.reset(); crc.update(buf); buf.rewind()
            if (crc.value.toInt() != sum) throw IOException("Corrupted data (checksum mismatch)")
            try { t.sink?.write(buf, off) } catch (e: IOException) { if (t.skipped) continue else throw e }
            if (t.got.addAndGet(len.toLong()) == t.size) {
                t.sink?.finish(); t.done = true
                s.msg(Proto.T_FILE_DONE) { writeInt(idx) }
            }
        }
    }

    // ---------------------------------------------------------------- sender

    private suspend fun CoroutineScope.send(s: Session, items: List<Picked>, inv: Invite) {
        val ctx = s.ctx
        val t0 = System.nanoTime()
        fun secs() = (System.nanoTime() - t0) / 1e9
        set(s.gen) { it.copy(phase = Phase.Connecting, link = linkLabel(inv.type, inv.freq), freqMhz = inv.freq, step = "Joining the receiver…") }
        var linkSecs = 0.0
        var ips = inv.ips
        if (inv.type == Invite.LAN) {
            // debug: already on a shared network
        } else if (inv.type == Invite.HOTSPOT) {
            set(s.gen) { it.copy(step = "Joining the receiver's hotspot…", tip = "If Android shows a \"connect to device\" popup, tap the network in it.") }
            val h = s.keep(HotspotClient(ctx))
            h.join(inv)
            linkSecs = secs()
            set(s.gen) { it.copy(freqMhz = h.freq, link = linkLabel(inv.type, h.freq), tip = null) }
        } else {
            set(s.gen) { it.copy(step = "Joining Wi-Fi Direct…") }
            val p = s.keep(P2p(ctx))
            p.join(inv)
            // Wait for Android to say the link is up, then connect at once to the owner it reports.
            p.awaitGroup(30_000)?.let { go -> ips = (listOf(go) + ips).distinct() }
            linkSecs = secs()
            val f = if (inv.freq > 0) inv.freq else p.legacyFreq
            set(s.gen) { it.copy(freqMhz = f, link = linkLabel(inv.type, f), tip = if (p.legacyFreq > 0) null else tipFor(staFreq(ctx), f)) }
        }
        set(s.gen) { it.copy(step = "Linked. Opening connection…") }

        val (ctrl, ip) = connectCtrl(ips)
        s.keep(ctrl)
        val total = secs()
        val log = String.format(java.util.Locale.US, "Connected in %.1f s (link %.1f s, network %.1f s)", total, linkSecs, total - linkSecs)
        set(s.gen) { it.copy(step = null, connectLog = log) }
        val din = DataInputStream(BufferedInputStream(ctrl.getInputStream()))
        s.dout = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))
        val streams = Prefs.streams(ctx)
        synchronized(s.dout!!) { s.dout!!.writeInt(Proto.MAGIC); s.dout!!.writeInt(streams); s.dout!!.flush() }
        if (din.readByte().toInt() != 1) throw IOException("Receiver rejected the connection")
        set(s.gen) { it.copy(connected = true) }

        launch { guard(s) { sendControl(din, s) } }
        addSend(s, items)
        repeat(streams) {
            launch { guard(s) { sendLoop(s.keep(openDataRetry(ip)), s) } }
        }
        ticker(s)
    }

    private suspend fun addSend(s: Session, items: List<Picked>) = s.addLock.withLock {
        if (s.ending) return@withLock
        val base = s.tracks.size
        val new = items.mapIndexed { i, p ->
            Track(base + i, p.name, p.size, p.mime, p.group, p.uri.takeIf { it != Uri.EMPTY }, Source.open(s.ctx, p), null)
        }
        s.tracks.addAll(new)
        s.msg(Proto.T_ADD) {
            writeInt(new.size)
            new.forEach { writeUTF(it.name); writeLong(it.size); writeUTF(it.mime); writeUTF(it.group) }
        }
        withTimeoutOrNull(20_000) { s.added.receive() } ?: throw IOException("The receiver stopped responding.")
        new.forEach { t -> chunksOf(t.idx, t.size).forEach { s.queue.trySend(it) } }
    }

    private fun sendControl(din: DataInputStream, s: Session) {
        while (true) {
            val type = try { din.readByte().toInt() } catch (e: IOException) {
                if (!s.pending) { s.peerGone(); return } else throw e
            }
            when (type) {
                Proto.T_ADDED -> s.added.trySend(din.readInt())
                Proto.T_FILE_DONE -> {
                    s.tracks.getOrNull(din.readInt())?.done = true
                    // Speed test: finish as soon as the receiver has everything.
                    if (s.bench && !s.pending) { s.close(userCancel = false); return }
                }
                Proto.T_SKIP -> s.skip(din.readInt(), tell = false)
                Proto.T_BYE -> { s.peerGone(); return }
                else -> throw IOException("Unknown message from receiver")
            }
        }
    }

    private suspend fun sendLoop(sc: SocketChannel, s: Session) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        val buf = ByteBuffer.allocateDirect(Proto.CHUNK)
        val crc = CRC32()
        for (c in s.queue) {
            val t = s.tracks[c.file]
            if (t.skipped) continue
            buf.clear(); buf.limit(c.len); t.src!!.read(buf, c.off); buf.flip()
            crc.reset(); crc.update(buf); buf.rewind()
            hdr.clear(); hdr.putInt(c.file).putLong(c.off).putInt(c.len).putInt(crc.value.toInt()).flip()
            writeFully(sc, hdr, buf) // gathering write: one syscall for header + data
            t.got.addAndGet(c.len.toLong())
        }
        hdr.clear(); hdr.putInt(-1).putLong(0).putInt(0).putInt(0).flip()
        runCatching { writeFully(sc, hdr) }
    }

    /** Link formation takes a few seconds after join, so retry each advertised IP. */
    private suspend fun connectCtrl(ips: List<String>): Pair<Socket, String> {
        if (ips.isEmpty()) throw IOException("Receiver's code has no address")
        // Short attempts, quick retries: right after the link comes up the address
        // (DHCP) may need a moment, and each failed try should cost little.
        val end = System.currentTimeMillis() + 45_000
        var i = 0
        while (System.currentTimeMillis() < end) {
            val ip = ips[i++ % ips.size]
            val sock = Socket()
            try {
                runInterruptible { sock.connect(InetSocketAddress(ip, Proto.CTRL_PORT), 1200) }
                sock.tcpNoDelay = true
                return sock to ip
            } catch (e: IOException) {
                sock.close(); delay(250)
            }
        }
        throw IOException("Can't reach the receiver. Is its code still on screen?")
    }

    private suspend fun openDataRetry(ip: String): SocketChannel {
        repeat(10) { try { return openData(ip) } catch (e: IOException) { delay(300) } }
        return openData(ip)
    }

    /** Errors after the session is ending are just sockets closing; otherwise they fail the session. */
    private suspend fun guard(s: Session, block: suspend () -> Unit) {
        try { block() } catch (e: CancellationException) { throw e } catch (e: Throwable) { if (!s.ending) s.fail(e) }
    }

    // ---------------------------------------------------------------- progress

    private fun items(s: Session) = s.tracks.map { t ->
        FileItem(
            t.idx, t.name, t.size, t.mime, t.group,
            uri = if (s.role == Role.Receive) t.sink?.readyUri else t.uri,
            done = min(t.got.get(), t.size),
            status = when { t.skipped -> FStatus.Skipped; t.done -> FStatus.Done; t.got.get() > 0 -> FStatus.Active; else -> FStatus.Queued },
        )
    }

    private fun CoroutineScope.ticker(s: Session) = launch {
        var lastB = 0L; var lastT = System.nanoTime(); var ema = 0.0; var peak = 0.0
        var activeNs = 0L; var moved = 0L
        while (isActive) {
            delay(250)
            val now = System.nanoTime()
            val b = s.tracks.sumOf { it.got.get() }
            val dt = now - lastT; val db = b - lastB
            val busy = s.pending
            val moving = busy || db > 0
            if (moving) { activeNs += dt; moved += db }
            val inst = db / 1e6 / (dt / 1e9)
            ema = if (moving) (if (ema == 0.0) inst else ema * 0.75 + inst * 0.25) else 0.0
            peak = max(peak, ema)
            val avg = if (activeNs > 0) moved / 1e6 / (activeNs / 1e9) else 0.0
            val live = s.tracks.filter { !it.skipped }
            val total = live.sumOf { it.size }
            val done = live.sumOf { min(it.got.get(), it.size) }
            val eta = if (avg > 0.5 && busy) ((total - done) / 1e6 / avg).toLong() else -1
            set(s.gen) {
                it.copy(
                    phase = when { s.tracks.isEmpty() -> Phase.Connecting; busy -> Phase.Transferring; else -> Phase.Done },
                    bench = s.bench || s.tracks.any { t -> t.name == Proto.BENCH_NAME },
                    total = total, done = done, mbps = ema, avgMbps = avg, peakMbps = peak, etaSec = eta,
                    elapsedMs = activeNs / 1_000_000,
                    samples = if (moving) (it.samples + ema.toFloat()).takeLast(CHART_POINTS) else it.samples,
                    files = items(s),
                )
            }
            lastB = b; lastT = now
        }
    }
}
