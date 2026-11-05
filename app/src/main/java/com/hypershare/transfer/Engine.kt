package com.hypershare.transfer

import android.content.Context
import android.net.Uri
import com.hypershare.p2p.Invite
import com.hypershare.p2p.P2p
import com.hypershare.service.TransferService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.Channels
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.CRC32

data class UiState(
    val running: Boolean = false,
    val status: String = "Pick Receive on one phone, Send on the other.",
    val error: String? = null,
    val qr: String? = null,
    val freqMhz: Int = 0,
    val total: Long = 0,
    val done: Long = 0,
    val mbps: Double = 0.0,
    val avgMbps: Double = 0.0,
)

/** App-wide transfer state machine. One transfer at a time. */
object Engine {
    val state = MutableStateFlow(UiState())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var p2p: P2p? = null
    /** Sockets/files of the current run; replaced per run so an old job can't close a new one's. */
    @Volatile private var open = CopyOnWriteArrayList<Closeable>()
    @Volatile private var t0 = 0L

    fun startReceive(ctx: Context) = run(ctx, "Creating 5 GHz group…") { receive(it) }

    fun startSend(ctx: Context, uris: List<Uri>, qr: String, bench: Boolean) =
        run(ctx, "Joining receiver…") { send(it, uris, Invite.decode(qr), bench) }

    fun cancel() {
        job?.cancel()
        closeAll()
        state.update { it.copy(running = false, qr = null, status = "Cancelled") }
    }

    private fun run(ctx: Context, status: String, block: suspend CoroutineScope.(Context) -> Unit) {
        cancel()
        val app = ctx.applicationContext
        state.value = UiState(running = true, status = status)
        TransferService.start(app)
        val mine = CopyOnWriteArrayList<Closeable>().also { open = it }
        job = scope.launch {
            val p = P2p(app).also { p2p = it }
            try {
                coroutineScope { block(app) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                state.update { it.copy(running = false, qr = null, error = e.message ?: e.toString(), status = "Failed") }
            } finally {
                mine.forEach { runCatching { it.close() } }
                withContext(NonCancellable) { p.removeGroup(); p.close() }
                TransferService.stop(app)
            }
        }
    }

    private fun <T : Closeable> T.tracked(): T = also { open += it }
    private fun closeAll() { open.forEach { runCatching { it.close() } }; open.clear() }

    // ---------------------------------------------------------------- receiver

    private suspend fun CoroutineScope.receive(ctx: Context) {
        val (inv, group) = p2p!!.createGroup()
        state.update {
            it.copy(qr = inv.encode(), freqMhz = group.frequency, status = "Scan this QR on the sending phone")
        }

        val server = ServerSocketChannel.open().tracked()
        server.socket().reuseAddress = true
        server.socket().receiveBufferSize = Proto.SOCK_BUF // inherited by accepted sockets
        server.bind(InetSocketAddress(Proto.PORT))

        // 1) control connection: manifest
        val ctrl = server.accept().tracked().also { tune(it) }
        val din = DataInputStream(Channels.newInputStream(ctrl).buffered())
        val dout = DataOutputStream(Channels.newOutputStream(ctrl))
        if (din.readInt() != Proto.MAGIC) throw IOException("Not a HyperShare sender")
        val streams = din.readInt()
        val names = List(din.readInt()) { din.readUTF() to din.readLong() }
        val total = names.sumOf { it.second }
        val sinks = names.map { Sink.open(ctx, it.first) }
        state.update { it.copy(qr = null, total = total, status = "Receiving ${names.size} file(s)…") }
        dout.writeByte(1); dout.flush()

        // 2) data connections, all streams write into the same files in parallel
        val got = AtomicLong()
        val ticker = ticker(got, total)
        try {
            val workers = List(streams) {
                val sc = server.accept().tracked().also { tune(it) }
                launch { recvLoop(sc, sinks, got) }
            }
            workers.joinAll()
            if (got.get() != total) throw IOException("Incomplete: ${got.get()} of $total bytes")
            sinks.forEach { it.finish() }
        } catch (e: Throwable) {
            sinks.forEach { it.abort() }
            throw e
        } finally {
            ticker.cancel()
        }
        dout.writeByte(1); dout.flush()
        finish(got.get(), total, "Saved to Downloads/HyperShare")
    }

    private fun recvLoop(sc: SocketChannel, sinks: List<Sink>, got: AtomicLong) {
        val hdr = ByteBuffer.allocate(Proto.HDR)
        val buf = ByteBuffer.allocateDirect(Proto.CHUNK)
        val crc = CRC32()
        while (true) {
            hdr.clear(); readFully(sc, hdr); hdr.flip()
            val idx = hdr.int
            if (idx < 0) break
            val off = hdr.long; val len = hdr.int; val sum = hdr.int
            if (idx >= sinks.size || len !in 1..Proto.CHUNK) throw IOException("Bad chunk header")
            buf.clear(); buf.limit(len); readFully(sc, buf); buf.flip()
            crc.reset(); crc.update(buf); buf.rewind()
            if (crc.value.toInt() != sum) throw IOException("Corrupt chunk (CRC mismatch)")
            sinks[idx].write(buf, off)
            got.addAndGet(len.toLong())
        }
    }

    // ---------------------------------------------------------------- sender

    private suspend fun CoroutineScope.send(ctx: Context, uris: List<Uri>, inv: Invite, bench: Boolean) {
        val sources = (if (bench) listOf(Source.bench()) else uris.map { Source.open(ctx, it) })
            .onEach { s -> open += Closeable { s.close() } }

        p2p!!.join(inv)
        state.update { it.copy(status = "Connecting…") }
        val ctrl = connectRetry()
        p2p!!.groupInfo()?.let { g -> state.update { it.copy(freqMhz = g.frequency) } }

        val din = DataInputStream(Channels.newInputStream(ctrl))
        val dout = DataOutputStream(Channels.newOutputStream(ctrl).buffered())
        dout.writeInt(Proto.MAGIC)
        dout.writeInt(Proto.STREAMS)
        dout.writeInt(sources.size)
        sources.forEach { dout.writeUTF(it.name); dout.writeLong(it.size) }
        dout.flush()
        if (din.readByte().toInt() != 1) throw IOException("Receiver rejected")

        val total = sources.sumOf { it.size }
        val chunks = chunksOf(sources.map { it.size })
        val next = AtomicInteger()
        val sent = AtomicLong()
        state.update { it.copy(total = total, status = if (bench) "Speed test…" else "Sending ${sources.size} file(s)…") }
        val ticker = ticker(sent, total)
        try {
            List(Proto.STREAMS) {
                launch {
                    val sc = connectRetry()
                    sendLoop(sc, sources, chunks, next, sent)
                }
            }.joinAll()
            if (din.readByte().toInt() != 1) throw IOException("Receiver reported an error")
        } finally {
            ticker.cancel()
        }
        finish(sent.get(), total, "Sent")
    }

    private fun sendLoop(sc: SocketChannel, src: List<Source>, chunks: List<Chunk>, next: AtomicInteger, sent: AtomicLong) {
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
            sent.addAndGet(c.len.toLong())
        }
        hdr.clear(); hdr.putInt(-1).putLong(0).putInt(0).putInt(0).flip()
        writeFully(sc, hdr)
    }

    /** Group formation takes a few seconds after connect() succeeds, so retry. */
    private suspend fun connectRetry(): SocketChannel {
        repeat(60) {
            try { return openClient().tracked() } catch (e: IOException) { delay(500) }
        }
        throw IOException("Can't reach receiver. Same QR? Wi-Fi on?")
    }

    // ---------------------------------------------------------------- progress

    private fun CoroutineScope.ticker(counter: AtomicLong, total: Long): Job {
        t0 = System.nanoTime()
        return launch { tick(counter, total) }
    }

    private suspend fun CoroutineScope.tick(counter: AtomicLong, total: Long) {
        var lastB = 0L; var lastT = t0
        while (isActive) {
            delay(300)
            val now = System.nanoTime(); val b = counter.get()
            val inst = (b - lastB) / 1e6 / ((now - lastT) / 1e9)
            val avg = b / 1e6 / ((now - t0) / 1e9)
            state.update { it.copy(done = b, total = total, mbps = inst, avgMbps = avg) }
            lastB = b; lastT = now
        }
    }

    private fun finish(bytes: Long, total: Long, what: String) {
        val avg = bytes / 1e6 / ((System.nanoTime() - t0) / 1e9)
        state.update {
            it.copy(
                running = false, done = bytes, total = total, mbps = 0.0,
                status = "$what %.0f MB · avg %.1f MB/s".format(bytes / 1e6, avg),
            )
        }
    }
}
