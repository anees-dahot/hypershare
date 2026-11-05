package com.hypershare.transfer

import android.content.Context
import android.net.Uri
import com.hypershare.data.History
import com.hypershare.data.HistoryItem
import com.hypershare.data.Prefs
import com.hypershare.p2p.Invite
import com.hypershare.p2p.P2p
import com.hypershare.p2p.staFreq
import com.hypershare.service.TransferService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.zip.CRC32
import kotlin.math.max

enum class Role { Send, Receive }
enum class Phase { Idle, Preparing, Waiting, Connecting, Transferring, Done, Failed }
enum class FStatus { Queued, Active, Done }

data class FileItem(
    val name: String,
    val size: Long,
    val mime: String,
    val uri: Uri?,
    val done: Long = 0,
    val status: FStatus = FStatus.Queued,
)

data class UiState(
    val phase: Phase = Phase.Idle,
    val role: Role? = null,
    val bench: Boolean = false,
    val error: String? = null,
    val qr: String? = null,
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
) {
    val active get() = phase in setOf(Phase.Preparing, Phase.Waiting, Phase.Connecting, Phase.Transferring)
}

/** App-wide transfer state machine. One transfer at a time. */
object Engine {
    const val CHART_POINTS = 120

    val state = MutableStateFlow(UiState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var open = CopyOnWriteArrayList<Closeable>()
    @Volatile private var p2p: P2p? = null
    @Volatile private var startNs = 0L

    fun startReceive(ctx: Context) = run(ctx, Role.Receive, false) { receive(it) }

    fun startSend(ctx: Context, uris: List<Uri>, qr: String, bench: Boolean) {
        val inv = try { Invite.decode(qr) } catch (e: Exception) {
            state.value = UiState(phase = Phase.Failed, role = Role.Send, error = e.message); return
        }
        run(ctx, Role.Send, bench) { send(it, uris, inv, bench) }
    }

    fun cancel() {
        job?.cancel()
        open.forEach { runCatching { it.close() } }
        if (state.value.active) state.update { it.copy(phase = Phase.Failed, qr = null, error = "Cancelled") }
    }

    /** Back to the home screen. */
    fun reset() { cancel(); state.value = UiState() }

    private fun run(ctx: Context, role: Role, bench: Boolean, block: suspend CoroutineScope.(Context) -> Unit) {
        cancel()
        val app = ctx.applicationContext
        state.value = UiState(phase = Phase.Preparing, role = role, bench = bench)
        TransferService.start(app)
        val mine = CopyOnWriteArrayList<Closeable>().also { open = it }
        job = scope.launch {
            var p: P2p? = null
            try {
                p = P2p(app).also { p2p = it }
                coroutineScope { block(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // A cancelled job fails with socket-closed errors; the user already moved on, so stay quiet.
                if (isActive) state.update { it.copy(phase = Phase.Failed, qr = null, error = friendly(e)) }
            } finally {
                mine.forEach { runCatching { it.close() } }
                withContext(NonCancellable) { p?.removeGroup(); runCatching { p?.close() } }
                TransferService.stop(app)
            }
        }
    }

    private fun friendly(e: Throwable) = when (e) {
        is EOFException -> "Connection lost. The other phone cancelled or went out of range."
        else -> e.message ?: e.toString()
    }

    private fun <T : Closeable> T.tracked(): T = also { open += it }

    private fun tipFor(sta: Int, grp: Int): String? = when {
        grp in 1..4999 -> "Linked on 2.4 GHz. Speed tops out near 15 MB/s on this band."
        sta > 0 && grp > 0 && sta != grp ->
            "This phone is also connected to a Wi-Fi network on another channel, so its radio " +
                "switches back and forth. For steady top speed, disconnect from Wi-Fi networks " +
                "(keep Wi-Fi itself on) or connect both phones to the same 5 GHz router."
        else -> null
    }

    /** Per-file and total byte counters, updated by all streams. */
    private class Progress(val sizes: List<Long>) {
        val per = AtomicLongArray(sizes.size)
        val all = AtomicLong()
        /** Returns true when this chunk completed its file. */
        fun add(i: Int, n: Long): Boolean { all.addAndGet(n); return per.addAndGet(i, n) == sizes[i] }
    }

    // ---------------------------------------------------------------- receiver

    private suspend fun CoroutineScope.receive(ctx: Context) {
        val sta = staFreq(ctx)
        val (inv, group) = p2p!!.createGroup(sta)
        state.update {
            it.copy(phase = Phase.Waiting, qr = inv.encode(), freqMhz = group.frequency, tip = tipFor(sta, group.frequency))
        }

        val server = ServerSocketChannel.open().tracked()
        server.socket().reuseAddress = true
        server.bind(InetSocketAddress(Proto.PORT))

        // 1) control connection: manifest
        val ctrl = server.accept().tracked().also { tune(it) }
        val din = DataInputStream(Channels.newInputStream(ctrl).buffered())
        val dout = DataOutputStream(Channels.newOutputStream(ctrl))
        if (din.readInt() != Proto.MAGIC) throw IOException("The other phone runs a different HyperShare version. Update both.")
        val streams = din.readInt().coerceIn(1, 16)
        val metas = List(din.readInt()) { Triple(din.readUTF(), din.readLong(), din.readUTF()) }
        val bench = metas.size == 1 && metas[0].first == Proto.BENCH_NAME
        val sinks = metas.map { Sink.open(ctx, it.first, it.third).also { s -> open += Closeable { s.abort() } } }
        val total = metas.sumOf { it.second }
        state.update {
            it.copy(
                phase = Phase.Transferring, qr = null, bench = bench, total = total,
                files = metas.map { m -> FileItem(m.first, m.second, m.third, null) },
            )
        }
        val prog = Progress(metas.map { it.second })
        metas.forEachIndexed { i, m -> if (m.second == 0L) sinks[i].finish() }
        dout.writeByte(1); dout.flush()

        // 2) data connections, all streams write into the files in parallel
        val uriOf = { i: Int -> sinks[i].readyUri }
        val ticker = ticker(prog, total, uriOf)
        try {
            List(streams) {
                val sc = server.accept().tracked().also { tune(it) }
                launch { recvLoop(sc, sinks, prog) }
            }.joinAll()
            if (prog.all.get() != total) throw IOException("Transfer incomplete")
        } finally {
            ticker.cancel()
        }
        dout.writeByte(1); dout.flush()
        complete(prog, uriOf)
        if (!bench) History.add(ctx, state.value.files.map { it.toHistory(Role.Receive) })
    }

    private fun recvLoop(sc: SocketChannel, sinks: List<Sink>, prog: Progress) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        val buf = ByteBuffer.allocateDirect(Proto.CHUNK)
        val crc = CRC32()
        while (true) {
            hdr.clear(); readFully(sc, hdr); hdr.flip()
            val idx = hdr.int
            if (idx < 0) break
            val off = hdr.long; val len = hdr.int; val sum = hdr.int
            if (idx >= sinks.size || len !in 1..Proto.CHUNK) throw IOException("Bad data from sender")
            buf.clear(); buf.limit(len); readFully(sc, buf); buf.flip()
            crc.reset(); crc.update(buf); buf.rewind()
            if (crc.value.toInt() != sum) throw IOException("Corrupted data (checksum mismatch)")
            sinks[idx].write(buf, off)
            if (prog.add(idx, len.toLong())) sinks[idx].finish()
        }
    }

    // ---------------------------------------------------------------- sender

    private suspend fun CoroutineScope.send(ctx: Context, uris: List<Uri>, inv: Invite, bench: Boolean) {
        val sources = if (bench) listOf(Source.bench()) else uris.map { Source.open(ctx, it) }
        sources.forEach { s -> open += Closeable { s.close() } }
        val total = sources.sumOf { it.size }
        state.update {
            it.copy(
                phase = Phase.Connecting, total = total,
                files = sources.mapIndexed { i, s -> FileItem(s.name, s.size, s.mime, if (bench) null else uris[i]) },
            )
        }

        p2p!!.join(inv)
        val ctrl = connectRetry()
        state.update { it.copy(freqMhz = inv.freq, tip = tipFor(staFreq(ctx), inv.freq)) }

        val streams = Prefs.streams(ctx)
        val din = DataInputStream(Channels.newInputStream(ctrl))
        val dout = DataOutputStream(Channels.newOutputStream(ctrl).buffered())
        dout.writeInt(Proto.MAGIC)
        dout.writeInt(streams)
        dout.writeInt(sources.size)
        sources.forEach { dout.writeUTF(it.name); dout.writeLong(it.size); dout.writeUTF(it.mime) }
        dout.flush()
        if (din.readByte().toInt() != 1) throw IOException("Receiver rejected the transfer")

        val prog = Progress(sources.map { it.size })
        val chunks = chunksOf(sources.map { it.size })
        val next = AtomicInteger()
        state.update { it.copy(phase = Phase.Transferring) }
        val uriOf = { i: Int -> if (bench) null else uris[i] }
        val ticker = ticker(prog, total, uriOf)
        try {
            List(streams) {
                launch { sendLoop(connectRetry(), sources, chunks, next, prog) }
            }.joinAll()
            if (din.readByte().toInt() != 1) throw IOException("Receiver reported an error")
        } finally {
            ticker.cancel()
        }
        complete(prog, uriOf)
        if (!bench) History.add(ctx, state.value.files.map { it.toHistory(Role.Send) })
    }

    private fun sendLoop(sc: SocketChannel, src: List<Source>, chunks: List<Chunk>, next: AtomicInteger, prog: Progress) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        val buf = ByteBuffer.allocateDirect(Proto.CHUNK)
        val crc = CRC32()
        while (true) {
            val i = next.getAndIncrement()
            if (i >= chunks.size) break
            val c = chunks[i]
            buf.clear(); buf.limit(c.len); src[c.file].read(buf, c.off); buf.flip()
            crc.reset(); crc.update(buf); buf.rewind()
            hdr.clear(); hdr.putInt(c.file).putLong(c.off).putInt(c.len).putInt(crc.value.toInt()).flip()
            writeFully(sc, hdr, buf) // gathering write: one syscall for header + data
            prog.add(c.file, c.len.toLong())
        }
        hdr.clear(); hdr.putInt(-1).putLong(0).putInt(0).putInt(0).flip()
        writeFully(sc, hdr)
    }

    /** Group formation takes a few seconds after connect() succeeds, so retry. */
    private suspend fun connectRetry(): SocketChannel {
        repeat(60) {
            try { return openClient().tracked() } catch (e: IOException) { delay(500) }
        }
        throw IOException("Can't reach the receiver. Is its code still on screen?")
    }

    // ---------------------------------------------------------------- progress

    private fun filesNow(base: List<FileItem>, prog: Progress, uriOf: (Int) -> Uri?) =
        base.mapIndexed { i, f ->
            val d = prog.per.get(i)
            f.copy(
                done = d,
                uri = uriOf(i) ?: f.uri,
                status = when { d >= f.size -> FStatus.Done; d > 0 -> FStatus.Active; else -> FStatus.Queued },
            )
        }

    private fun CoroutineScope.ticker(prog: Progress, total: Long, uriOf: (Int) -> Uri?): Job {
        val t0 = System.nanoTime().also { startNs = it }
        return launch {
            var lastB = 0L; var lastT = t0; var ema = 0.0; var peak = 0.0
            while (isActive) {
                delay(250)
                val now = System.nanoTime(); val b = prog.all.get()
                val inst = (b - lastB) / 1e6 / ((now - lastT) / 1e9)
                ema = if (ema == 0.0) inst else ema * 0.75 + inst * 0.25
                peak = max(peak, ema)
                val secs = (now - t0) / 1e9
                val avg = b / 1e6 / secs
                val eta = if (avg > 0.5) ((total - b) / 1e6 / avg).toLong() else -1
                state.update {
                    it.copy(
                        done = b, mbps = ema, avgMbps = avg, peakMbps = peak, etaSec = eta,
                        elapsedMs = (secs * 1000).toLong(),
                        samples = (it.samples + ema.toFloat()).takeLast(CHART_POINTS),
                        files = filesNow(it.files, prog, uriOf),
                    )
                }
                lastB = b; lastT = now
            }
        }
    }

    private fun complete(prog: Progress, uriOf: (Int) -> Uri?) {
        val secs = (System.nanoTime() - startNs) / 1e9
        val b = prog.all.get()
        state.update {
            it.copy(
                phase = Phase.Done, done = b, mbps = 0.0, etaSec = 0,
                avgMbps = b / 1e6 / secs, elapsedMs = (secs * 1000).toLong(),
                files = filesNow(it.files, prog, uriOf),
            )
        }
    }

    private fun FileItem.toHistory(role: Role) =
        HistoryItem(role.name, name, size, mime, uri?.toString(), System.currentTimeMillis())
}
