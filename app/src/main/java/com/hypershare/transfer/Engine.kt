package com.hypershare.transfer

import android.content.Context
import android.net.Uri
import com.hypershare.CrashLog
import com.hypershare.data.History
import com.hypershare.data.HistoryItem
import com.hypershare.data.Partial
import com.hypershare.data.Partials
import com.hypershare.data.Picked
import com.hypershare.data.Prefs
import com.hypershare.p2p.HotspotClient
import com.hypershare.p2p.HotspotHost
import com.hypershare.p2p.Invite
import com.hypershare.p2p.P2p
import com.hypershare.p2p.groupFreq
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
import java.util.BitSet
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32
import kotlin.math.max
import kotlin.math.min

/** Who started the session: the phone that scanned/picked (Send) or the one that showed its code (Receive). */
enum class Role { Send, Receive }
enum class Phase { Idle, Preparing, Waiting, Connecting, Transferring, Done, Failed }
enum class FStatus { Queued, Active, Done, Skipped }

data class FileItem(
    val idx: Int,
    /** true = this phone is sending it. Both phones can send in one session. */
    val outgoing: Boolean,
    val name: String,
    val size: Long,
    val mime: String,
    val group: String,
    val uri: Uri?,
    val done: Long = 0,
    val status: FStatus = FStatus.Queued,
    /** Continued from an earlier, interrupted transfer. */
    val resumed: Boolean = false,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val role: Role? = null,
    val bench: Boolean = false,
    /** Session still open: either phone can add more files. */
    val connected: Boolean = false,
    val error: String? = null,
    val qr: String? = null,
    /** "Wi-Fi Direct · 5 GHz ch 149" etc. */
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
    /** How long connecting took, by stage. */
    val connectLog: String? = null,
    /** The other phone's name. */
    val peer: String? = null,
    /** Receiver: a phone found us nearby and asks to connect (needs Accept). */
    val request: String? = null,
    /** Sender: the transfer broke with files left; "Try again" can resume them. */
    val canRetry: Boolean = false,
    /** Receiver: advertising to nearby phones (Wi-Fi Direct mode). */
    val discoverable: Boolean = false,
) {
    val active get() = phase in setOf(Phase.Preparing, Phase.Waiting, Phase.Connecting, Phase.Transferring)
}

/**
 * One file in the session. File handles open lazily (first chunk) and close as soon as the file is
 * done, so sending a folder of thousands of photos never holds thousands of descriptors or creates
 * thousands of MediaStore entries before the first byte moves.
 */
private class Track(
    val ctx: Context, val seq: Int, val idx: Int, val outgoing: Boolean,
    val name: String, val size: Long, val mime: String, val group: String,
    val uri: Uri?, val pick: Picked?,
    @Volatile var sink: Sink? = null,
) {
    val got = AtomicLong()
    /** Incoming: chunk indexes written (for resume). */
    val chunks = BitSet()
    /** Outgoing: chunks not yet sent or skipped; the source closes when this reaches 0. */
    val chunksLeft = AtomicInteger()
    @Volatile var done = false
    @Volatile var skipped = false
    @Volatile var resumed = false
    private var src: Source? = null

    @Synchronized fun source(): Source = src ?: Source.open(ctx, pick!!).also { src = it }
    @Synchronized fun closeSource() { src?.close(); src = null }
    @Synchronized fun openSink(): Sink = sink ?: Sink.open(ctx, name, mime).also { sink = it }
}

private fun chunkLen(size: Long, ci: Int) = min(Proto.CHUNK.toLong(), size - ci.toLong() * Proto.CHUNK).toInt()
private fun BitSet.indices(): List<Int> = buildList { var i = nextSetBit(0); while (i >= 0) { add(i); i = nextSetBit(i + 1) } }

/** One pairing between two phones. Lives until either side says BYE or the link drops. */
private class Session(val ctx: Context, val role: Role, val bench: Boolean, val gen: Int, val viaQr: Boolean) {
    val outs = CopyOnWriteArrayList<Track>()
    val ins = CopyOnWriteArrayList<Track>()
    val seq = AtomicInteger()
    private val open = CopyOnWriteArrayList<Closeable>()
    val end = CompletableDeferred<Unit>()
    val queue = Channel<Chunk>(Channel.UNLIMITED)
    val added = Channel<List<IntArray>>(Channel.UNLIMITED)
    val addLock = Mutex()
    @Volatile var decision = CompletableDeferred<Boolean>()
    @Volatile var dout: DataOutputStream? = null
    @Volatile var ending = false
    @Volatile var cancelled = false

    val all get() = ins + outs
    val pending get() = all.any { !it.done && !it.skipped }

    fun <T : Closeable> keep(c: T): T = c.also { open += it }

    fun msg(type: Int, body: DataOutputStream.() -> Unit = {}) {
        val o = dout ?: return
        synchronized(o) { o.writeByte(type); o.body(); o.flush() }
    }

    fun fail(e: Throwable) { if (!ending) end.completeExceptionally(e) }

    /** Local end: user tapped Finish/Done (graceful) or Cancel. Tells the peer, then tears down. */
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
        outs.forEach { it.closeSource() }
        end.complete(Unit)
    }

    /** Unfinished incoming files: keep the ones worth resuming, delete the rest. */
    fun settleIncoming() {
        ins.forEach { t ->
            if (t.done) return@forEach
            val loc = t.sink?.locator
            if (cancelled || t.skipped || t.got.get() == 0L || loc == null || t.name == Proto.BENCH_NAME) t.sink?.abort()
            else {
                t.sink?.closeKeep()
                Partials.put(ctx, Partial(t.name, t.size, t.mime, loc, synchronized(t.chunks) { t.chunks.indices() }))
            }
        }
    }

    fun skipOut(idx: Int, tell: Boolean) {
        val t = outs.getOrNull(idx) ?: return
        if (t.done || t.skipped) return
        t.skipped = true
        if (tell) runCatching { msg(Proto.T_SKIP_MINE) { writeInt(idx) } }
    }

    fun skipIn(idx: Int, tell: Boolean) {
        val t = ins.getOrNull(idx) ?: return
        if (t.done || t.skipped) return
        t.skipped = true
        t.sink?.abort()
        if (tell) runCatching { msg(Proto.T_SKIP_YOURS) { writeInt(idx) } }
    }

    fun finishIn(t: Track) {
        if (t.done) return
        t.openSink().finish(); t.done = true // openSink: empty files still get created
        runCatching { msg(Proto.T_FILE_DONE) { writeInt(t.idx) } }
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
        CrashLog.save(e, "Unhandled transfer error (app kept running)")
        state.update { it.copy(phase = Phase.Failed, connected = false, mbps = 0.0, error = friendly(e)) }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + guardHandler)
    private var job: Job? = null
    @Volatile private var gen = 0
    @Volatile private var sess: Session? = null
    @Volatile private var last: Session? = null

    private fun set(g: Int, f: (UiState) -> UiState) { if (g == gen) state.update(f) }

    /** [lan]: debug builds only — skip Wi-Fi setup and listen on the existing network (emulator testing). */
    fun startReceive(ctx: Context, lan: Boolean = false) = run(ctx, Role.Receive, false, true) { receive(it, lan) }

    /** [viaQr] false = picked from the nearby list; the receiver must accept first. */
    fun startSend(ctx: Context, items: List<Picked>, code: String, bench: Boolean, viaQr: Boolean = true) {
        val inv = try { Invite.decode(code) } catch (e: Exception) {
            gen++; state.value = UiState(phase = Phase.Failed, role = Role.Send, error = e.message); return
        }
        if (!bench && items.isEmpty()) {
            gen++; state.value = UiState(phase = Phase.Failed, role = Role.Send, error = "Nothing selected to send."); return
        }
        run(ctx, Role.Send, bench, viaQr) { send(it, if (bench) listOf(Proto.benchItem()) else items, inv) }
    }

    /** Either phone: queue more files on the open session. */
    fun addFiles(items: List<Picked>) {
        val s = sess ?: return
        if (s.ending || items.isEmpty()) return
        scope.launch { try { addOut(s, items) } catch (e: Throwable) { s.fail(e) } }
    }

    /** Cancel one file, either direction. */
    fun skip(f: FileItem) {
        val s = sess ?: return
        scope.launch { if (f.outgoing) s.skipOut(f.idx, tell = true) else s.skipIn(f.idx, tell = true) }
    }

    /** Receiver: answer a nearby connection request. */
    fun respond(accept: Boolean) { sess?.decision?.complete(accept) }

    /** Files that didn't make it in the last failed send, to try again (the receiver resumes partials). */
    fun retryItems(): List<Picked> = last?.outs?.filter { !it.done && !it.skipped }?.mapNotNull { it.pick }.orEmpty()

    /** Stop now; stays on screen showing what happened (or goes home if nothing was sent yet). */
    fun cancel() {
        val s = sess ?: return
        if (state.value.phase == Phase.Waiting || state.value.phase == Phase.Preparing) { reset(); return }
        scope.launch { s.close(userCancel = true) }
    }

    /** Leave the transfer screen: ends the session (tells the other phone) and returns home. */
    fun reset() {
        val s = sess
        sess = null
        gen++
        state.value = UiState()
        if (s != null) scope.launch { s.close(userCancel = true) } else job?.cancel()
    }

    private fun run(ctx: Context, role: Role, bench: Boolean, viaQr: Boolean, block: suspend CoroutineScope.(Session) -> Unit) {
        sess?.let { old -> scope.launch { old.close(userCancel = true) } }
        job?.cancel()
        val g = ++gen
        val app = ctx.applicationContext
        val s = Session(app, role, bench, g, viaQr).also { sess = it; last = it }
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
                if (!s.ending) CrashLog.save(e, "Transfer failed: ${friendly(e)}")
                if (s.ending) finalState(s)
                else set(g) {
                    it.copy(
                        phase = Phase.Failed, qr = null, connected = false, mbps = 0.0, error = friendly(e),
                        files = items(s), request = null, discoverable = false,
                        canRetry = role == Role.Send && s.outs.any { t -> !t.done && !t.skipped && t.pick != null },
                    )
                }
            } finally {
                withContext(NonCancellable) { s.close(userCancel = false) }
                s.settleIncoming()
                if (sess === s) sess = null
                if (sess == null) needService.value = false
                val doneFiles = s.all.filter { it.done && it.name != Proto.BENCH_NAME }
                if (doneFiles.isNotEmpty()) History.add(app, doneFiles.sortedBy { it.seq }.map {
                    HistoryItem(if (it.outgoing) "Send" else "Receive", it.name.substringAfterLast('/'), it.size, it.mime,
                        (it.sink?.readyUri ?: it.uri)?.toString(), System.currentTimeMillis())
                })
            }
        }
    }

    private fun finalState(s: Session) {
        // Ended before any file was added (e.g. receiver closed while waiting): just go home.
        if (s.all.isEmpty() && !s.cancelled) { set(s.gen) { UiState() }; return }
        val left = s.pending
        set(s.gen) {
            it.copy(
                phase = when { s.cancelled -> Phase.Failed; left -> Phase.Failed; else -> Phase.Done },
                error = when { s.cancelled -> "Cancelled"; left -> "The other phone stopped the transfer."; else -> null },
                connected = false, qr = null, mbps = 0.0, etaSec = 0, files = items(s), request = null, discoverable = false,
                canRetry = !s.cancelled && s.role == Role.Send && s.outs.any { t -> !t.done && !t.skipped && t.pick != null },
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
                "(keep Wi-Fi itself on)."
        else -> null
    }

    private fun linkLabel(type: Char, freq: Int) =
        (when (type) { Invite.HOTSPOT -> "Hotspot"; Invite.LAN -> "LAN (debug)"; else -> "Wi-Fi Direct" }) +
            when { freq <= 0 -> ""; freq < 5000 -> " · 2.4 GHz"; else -> " · 5 GHz ch ${(freq - 5000) / 5}" }

    // ---------------------------------------------------------------- receiver (shows the code)

    private suspend fun CoroutineScope.receive(s: Session, lan: Boolean) {
        val ctx = s.ctx
        val me = Prefs.deviceName(ctx)
        // Sockets first, so the sender can connect the moment its link is up.
        val ctrlServer = s.keep(bindRetry { ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(Proto.CTRL_PORT)) } })
        val dataServer = s.keep(bindRetry { ServerSocketChannel.open().apply { socket().reuseAddress = true; bind(InetSocketAddress(Proto.DATA_PORT)) } })

        var p2p: P2p? = null
        val inv: Invite = when {
            lan -> Invite(Invite.LAN, "-", "-", 0, false, listOf("10.0.2.2"), me)
            Prefs.link(ctx) == Prefs.LINK_HOTSPOT -> s.keep(HotspotHost(ctx)).start(me)
            else -> {
                val p = s.keep(P2p(ctx)).also { p2p = it }
                val sta = staFreq(ctx)
                val (i, g) = p.host(sta, me, Prefs.only24(ctx))
                set(s.gen) { it.copy(tip = tipFor(sta, groupFreq(g))) }
                i
            }
        }
        set(s.gen) {
            it.copy(phase = Phase.Waiting, qr = inv.encode(), freqMhz = inv.freq, link = linkLabel(inv.type, inv.freq), discoverable = p2p != null)
        }
        // Let nearby phones find us without scanning (Wi-Fi Direct only).
        val advert = p2p?.let { p -> launch { runCatching { p.advertise(inv) } } }

        while (true) {
            val ctrl = s.keep(runInterruptible { ctrlServer.accept() }).apply { tcpNoDelay = true }
            val din = DataInputStream(BufferedInputStream(ctrl.getInputStream()))
            val dout = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))
            if (din.readInt() != Proto.MAGIC) throw IOException("The other phone runs a different HyperShare version. Update both.")
            val streams = din.readInt().coerceIn(1, 16)
            val peer = din.readUTF().ifBlank { "Another phone" }
            val viaQr = din.readBoolean()
            if (!viaQr) {
                // Found via the nearby list, not by scanning our code: ask first.
                s.decision = CompletableDeferred()
                set(s.gen) { it.copy(request = peer) }
                val ok = withTimeoutOrNull(60_000) { s.decision.await() } ?: false
                set(s.gen) { it.copy(request = null) }
                if (!ok) {
                    runCatching { dout.writeByte(0); dout.flush(); ctrl.close() }
                    continue
                }
            }
            dout.writeByte(1); dout.writeUTF(me); dout.flush()
            s.dout = dout
            advert?.cancel(); p2p?.stopAdvertising()
            set(s.gen) { it.copy(phase = Phase.Connecting, qr = null, connected = true, peer = peer, discoverable = false) }

            launch {
                repeat(streams) {
                    val sc = s.keep(dataServer.accept()).also { tune(it) }
                    launch { guard(s) { recvLoop(sc, s) } }
                    launch { guard(s) { sendLoop(sc, s) } }
                }
            }
            launch { guard(s) { control(din, s) } }
            ticker(s)
            return
        }
    }

    // ---------------------------------------------------------------- sender (scanned / picked)

    private suspend fun CoroutineScope.send(s: Session, items: List<Picked>, inv: Invite) {
        val ctx = s.ctx
        val t0 = System.nanoTime()
        fun secs() = (System.nanoTime() - t0) / 1e9
        set(s.gen) {
            it.copy(phase = Phase.Connecting, link = linkLabel(inv.type, inv.freq), freqMhz = inv.freq,
                peer = inv.name.ifBlank { null }, step = "Joining ${inv.name.ifBlank { "the receiver" }}…")
        }
        var linkSecs = 0.0
        var ips = inv.ips
        when (inv.type) {
            Invite.LAN -> Unit // debug: already on a shared network
            Invite.HOTSPOT -> {
                set(s.gen) { it.copy(step = "Joining the receiver's hotspot…", tip = "If Android shows a \"connect to device\" popup, tap the network in it.") }
                val h = s.keep(HotspotClient(ctx))
                h.join(inv)
                linkSecs = secs()
                set(s.gen) { it.copy(freqMhz = h.freq, link = linkLabel(inv.type, h.freq), tip = null) }
            }
            else -> {
                val p = s.keep(P2p(ctx))
                if (inv.freq >= 5000 && !p.supports5GHz()) throw IOException(
                    "This phone only has 2.4 GHz Wi-Fi, but ${inv.name.ifBlank { "the receiver" }} is on 5 GHz. " +
                        "On that phone, tap \"Use 2.4 GHz\" on the Receive screen, then scan again."
                )
                // Returns once Android says the link is up; we dial the owner it reports straight away.
                val sta = staFreq(ctx)
                p.join(inv) { n ->
                    set(s.gen) {
                        it.copy(
                            step = "Joining… retry $n of 3",
                            // The usual reason a join stalls: this phone's own Wi-Fi sits on another channel.
                            tip = if (sta > 0 && inv.freq > 0 && sta != inv.freq)
                                "Slow to connect? This phone's Wi-Fi network is on a different channel than the transfer link. " +
                                    "Disconnect from your Wi-Fi network (keep Wi-Fi on), then scan again."
                            else it.tip,
                        )
                    }
                }?.let { go -> ips = (listOf(go) + ips).distinct() }
                linkSecs = secs()
                val f = if (inv.freq > 0) inv.freq else p.legacyFreq
                set(s.gen) { it.copy(freqMhz = f, link = linkLabel(inv.type, f), tip = if (p.legacyFreq > 0) null else tipFor(staFreq(ctx), f)) }
            }
        }
        set(s.gen) { it.copy(step = "Linked. Opening connection…") }

        val (ctrl, ip) = connectCtrl(ips)
        s.keep(ctrl)
        val din = DataInputStream(BufferedInputStream(ctrl.getInputStream()))
        val dout = DataOutputStream(BufferedOutputStream(ctrl.getOutputStream()))
        val streams = Prefs.streams(ctx)
        dout.writeInt(Proto.MAGIC); dout.writeInt(streams); dout.writeUTF(Prefs.deviceName(ctx)); dout.writeBoolean(s.viaQr); dout.flush()
        if (!s.viaQr) set(s.gen) { it.copy(step = "Waiting for ${inv.name.ifBlank { "the receiver" }} to accept…") }
        if (din.readByte().toInt() != 1) throw IOException("${inv.name.ifBlank { "The receiver" }} declined the transfer.")
        val peer = din.readUTF()
        s.dout = dout
        val total = secs()
        val log = String.format(Locale.US, "Connected in %.1f s (link %.1f s, network %.1f s)", total, linkSecs, total - linkSecs)
        set(s.gen) { it.copy(step = null, connectLog = log, connected = true, peer = peer) }

        launch { guard(s) { control(din, s) } }
        repeat(streams) {
            launch {
                guard(s) {
                    val sc = s.keep(openDataRetry(ip))
                    launch { guard(s) { recvLoop(sc, s) } }
                    sendLoop(sc, s)
                }
            }
        }
        addOut(s, items)
        ticker(s)
    }

    // ---------------------------------------------------------------- both directions

    private suspend fun addOut(s: Session, items: List<Picked>) = s.addLock.withLock {
        if (s.ending) return@withLock
        val base = s.outs.size
        val new = items.mapIndexed { i, p ->
            Track(s.ctx, s.seq.getAndIncrement(), base + i, true, p.name, p.size, p.mime, p.group,
                p.uri.takeIf { it != Uri.EMPTY }, p)
        }
        s.outs.addAll(new)
        s.msg(Proto.T_ADD) {
            writeInt(new.size)
            new.forEach { writeUTF(it.name); writeLong(it.size); writeUTF(it.mime); writeUTF(it.group) }
        }
        val have = withTimeoutOrNull(20_000) { s.added.receive() } ?: throw IOException("The other phone stopped responding.")
        new.forEachIndexed { i, t ->
            val skip = have.getOrNull(i)?.toHashSet().orEmpty()
            if (skip.isNotEmpty()) t.resumed = true
            val todo = chunksOf(t.idx, t.size).filter { c ->
                val have = (c.off / Proto.CHUNK).toInt() in skip
                if (have) t.got.addAndGet(c.len.toLong())
                !have
            }
            t.chunksLeft.set(todo.size)
            todo.forEach { s.queue.trySend(it) }
        }
    }

    private class Meta(val name: String, val size: Long, val mime: String, val group: String)

    private fun control(din: DataInputStream, s: Session) {
        while (true) {
            val type = try { din.readByte().toInt() } catch (e: IOException) {
                if (!s.pending) { s.peerGone(); return } else throw e
            }
            when (type) {
                Proto.T_ADD -> {
                    val metas = List(din.readInt()) { Meta(din.readUTF(), din.readLong(), din.readUTF(), din.readUTF()) }
                    val base = s.ins.size
                    val new = metas.mapIndexed { i, m ->
                        // Same name + size stopped half-way earlier? Continue that file.
                        val part = if (m.name == Proto.BENCH_NAME) null else Partials.take(s.ctx, m.name, m.size)
                        val reopened = part?.let { Sink.reopen(s.ctx, it.locator) }
                        val t = Track(s.ctx, s.seq.getAndIncrement(), base + i, false, m.name, m.size, m.mime, m.group,
                            null, null, reopened)
                        if (part != null && reopened != null) {
                            part.chunks.forEach { ci -> t.chunks.set(ci); t.got.addAndGet(chunkLen(m.size, ci).toLong()) }
                            t.resumed = true
                        }
                        t
                    }
                    s.ins.addAll(new)
                    s.msg(Proto.T_ADDED) {
                        writeInt(new.size)
                        new.forEach { t -> val have = synchronized(t.chunks) { t.chunks.indices() }; writeInt(have.size); have.forEach(::writeInt) }
                    }
                    new.filter { it.got.get() >= it.size }.forEach { s.finishIn(it) }
                }
                Proto.T_ADDED -> {
                    val n = din.readInt()
                    s.added.trySend(List(n) { IntArray(din.readInt()) { din.readInt() } })
                }
                Proto.T_FILE_DONE -> {
                    s.outs.getOrNull(din.readInt())?.done = true
                    // Speed test: finish as soon as the receiver has everything.
                    if (s.bench && !s.pending) { s.close(userCancel = false); return }
                }
                Proto.T_SKIP_MINE -> s.skipIn(din.readInt(), tell = false)
                Proto.T_SKIP_YOURS -> s.skipOut(din.readInt(), tell = false)
                Proto.T_BYE -> { s.peerGone(); return }
                else -> throw IOException("Unknown message from the other phone")
            }
        }
    }

    private fun recvLoop(sc: SocketChannel, s: Session) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        var lazyBuf: ByteBuffer? = null
        val crc = CRC32()
        while (true) {
            hdr.clear(); readFully(sc, hdr); hdr.flip()
            val idx = hdr.int
            if (idx < 0) break
            val buf = lazyBuf ?: ByteBuffer.allocateDirect(Proto.CHUNK).also { lazyBuf = it }
            val off = hdr.long; val len = hdr.int; val sum = hdr.int
            val t = s.ins.getOrNull(idx)
            if (t == null || len !in 1..Proto.CHUNK) throw IOException("Bad data from the other phone")
            buf.clear(); buf.limit(len); readFully(sc, buf); buf.flip()
            if (t.skipped) continue // in flight when cancelled: drop it
            crc.reset(); crc.update(buf); buf.rewind()
            if (crc.value.toInt() != sum) throw IOException("Corrupted data (checksum mismatch)")
            try { t.openSink().write(buf, off) } catch (e: IOException) { if (t.skipped) continue else throw e }
            if (t.skipped) { t.sink?.abort(); continue } // cancelled while we were writing
            synchronized(t.chunks) { t.chunks.set((off / Proto.CHUNK).toInt()) }
            if (t.got.addAndGet(len.toLong()) >= t.size) s.finishIn(t)
        }
    }

    private suspend fun sendLoop(sc: SocketChannel, s: Session) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        var lazyBuf: ByteBuffer? = null // most sessions only send one way: don't hold 4 MB per idle stream
        val crc = CRC32()
        for (c in s.queue) {
            val buf = lazyBuf ?: ByteBuffer.allocateDirect(Proto.CHUNK).also { lazyBuf = it }
            val t = s.outs[c.file]
            if (t.skipped) { if (t.chunksLeft.decrementAndGet() <= 0) t.closeSource(); continue }
            buf.clear(); buf.limit(c.len)
            try { t.source().read(buf, c.off) } catch (e: IOException) {
                if (s.ending) throw e
                // File vanished or became unreadable: drop just this file, keep the session going.
                s.skipOut(t.idx, tell = true); t.chunksLeft.decrementAndGet(); continue
            }
            buf.flip()
            crc.reset(); crc.update(buf); buf.rewind()
            hdr.clear(); hdr.putInt(c.file).putLong(c.off).putInt(c.len).putInt(crc.value.toInt()).flip()
            writeFully(sc, hdr, buf) // gathering write: one syscall for header + data
            t.got.addAndGet(c.len.toLong())
            if (t.chunksLeft.decrementAndGet() <= 0) t.closeSource()
        }
        hdr.clear(); hdr.putInt(-1).putLong(0).putInt(0).putInt(0).flip()
        runCatching { writeFully(sc, hdr) }
    }

    /** Short attempts, quick retries: right after the link comes up the address may need a moment. */
    private suspend fun connectCtrl(ips: List<String>): Pair<Socket, String> {
        if (ips.isEmpty()) throw IOException("Receiver's code has no address")
        val end = System.currentTimeMillis() + 20_000
        var i = 0
        while (System.currentTimeMillis() < end) {
            val ip = ips[i++ % ips.size]
            val sock = Socket()
            try {
                runInterruptible { sock.connect(InetSocketAddress(ip, Proto.CTRL_PORT), 800) }
                sock.tcpNoDelay = true
                return sock to ip
            } catch (e: IOException) {
                sock.close(); delay(150)
            }
        }
        throw IOException("Can't reach the receiver. Is its code still on screen?")
    }

    /** A session that was just closed (e.g. "Use 2.4 GHz" restarts receiving) may hold the ports for a moment. */
    private suspend fun <T : Closeable> bindRetry(open: () -> T): T {
        repeat(25) { try { return open() } catch (e: java.net.BindException) { delay(120) } }
        return open()
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

    private fun items(s: Session) = s.all.sortedBy { it.seq }.map { t ->
        FileItem(
            t.idx, t.outgoing, t.name, t.size, t.mime, t.group,
            uri = if (t.outgoing) t.uri else t.sink?.readyUri,
            done = min(t.got.get(), t.size),
            status = when { t.skipped -> FStatus.Skipped; t.done -> FStatus.Done; t.got.get() > 0 -> FStatus.Active; else -> FStatus.Queued },
            resumed = t.resumed,
        )
    }

    private fun CoroutineScope.ticker(s: Session) = launch {
        var lastB = 0L; var lastT = System.nanoTime(); var ema = 0.0; var peak = 0.0
        var activeNs = 0L; var moved = 0L
        while (isActive) {
            // Big batches: refresh less often so the list doesn't stutter on budget phones.
            delay(if (s.ins.size + s.outs.size > 300) 600 else 250)
            val now = System.nanoTime()
            val tracks = s.all
            val b = tracks.sumOf { it.got.get() }
            val dt = now - lastT; val db = max(0L, b - lastB)
            val busy = s.pending
            val moving = busy || db > 0
            if (moving) { activeNs += dt; moved += db }
            val inst = db / 1e6 / (dt / 1e9)
            ema = if (moving) (if (ema == 0.0) inst else ema * 0.75 + inst * 0.25) else 0.0
            peak = max(peak, ema)
            val avg = if (activeNs > 0) moved / 1e6 / (activeNs / 1e9) else 0.0
            val live = tracks.filter { !it.skipped }
            val total = live.sumOf { it.size }
            val done = live.sumOf { min(it.got.get(), it.size) }
            val eta = if (avg > 0.5 && busy) ((total - done) / 1e6 / avg).toLong() else -1
            set(s.gen) {
                it.copy(
                    phase = when { tracks.isEmpty() -> Phase.Connecting; busy -> Phase.Transferring; else -> Phase.Done },
                    bench = s.bench || tracks.any { t -> t.name == Proto.BENCH_NAME },
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
