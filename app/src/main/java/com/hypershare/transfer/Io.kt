package com.hypershare.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.hypershare.data.Picked
import java.io.EOFException
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel

object Proto {
    const val DATA_PORT = 8988
    const val CTRL_PORT = 8989
    const val MAGIC = 0x48535833 // "HSX3"
    const val CHUNK = 4 * 1024 * 1024
    /** Chunk header: fileIdx Int, offset Long, len Int, crc Int. fileIdx -1 = end of stream. */
    const val HDR = 20
    const val BENCH_NAME = "__bench__"
    const val BENCH_SIZE = 2L * 1024 * 1024 * 1024

    // Control messages (either direction unless noted), framed as type byte + payload.
    const val T_ADD = 1        // S->R: count, then (name, size, mime, group) each
    const val T_ADDED = 2      // R->S: new file count
    const val T_FILE_DONE = 3  // R->S: idx fully written
    const val T_SKIP = 4       // idx cancelled by either side
    const val T_BYE = 5        // session over

    fun benchItem() = Picked(Uri.EMPTY, BENCH_NAME, BENCH_SIZE, "application/octet-stream")
}

data class Chunk(val file: Int, val off: Long, val len: Int)

fun chunksOf(file: Int, size: Long): List<Chunk> = buildList {
    var off = 0L
    while (off < size) {
        val len = minOf(Proto.CHUNK.toLong(), size - off).toInt()
        add(Chunk(file, off, len)); off += len
    }
}

/**
 * Only disable Nagle. Do NOT set SO_SNDBUF/SO_RCVBUF: on Linux that pins the buffer size and
 * turns off TCP autotuning, which made throughput jumpy in v0.1.
 */
fun tune(sc: SocketChannel) { sc.socket().tcpNoDelay = true }

fun openData(ip: String): SocketChannel {
    val sc = SocketChannel.open()
    try {
        tune(sc)
        sc.socket().connect(InetSocketAddress(ip, Proto.DATA_PORT), 3000)
        return sc
    } catch (e: IOException) {
        sc.close(); throw e
    }
}

fun readFully(sc: SocketChannel, b: ByteBuffer) {
    while (b.hasRemaining()) if (sc.read(b) < 0) throw EOFException("Connection lost")
}

fun writeFully(sc: SocketChannel, vararg bs: ByteBuffer) {
    while (bs.any { it.hasRemaining() }) sc.write(bs)
}

/** A file being sent. Bench source sends a zeroed buffer without touching storage. */
class Source private constructor(private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?) {
    /** Fill [b] (position..limit) from file offset [pos]. Thread-safe (positional read). */
    fun read(b: ByteBuffer, pos: Long) {
        if (ch == null) { b.position(b.limit()); return }
        var p = pos
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n < 0) throw EOFException("A file got shorter while sending")
            p += n
        }
    }

    fun close() { runCatching { ch?.close(); pfd?.close() } }

    companion object {
        fun open(ctx: Context, p: Picked): Source {
            if (p.name == Proto.BENCH_NAME) return Source(null, null)
            val pfd = ctx.contentResolver.openFileDescriptor(p.uri, "r") ?: throw IOException("Can't open ${p.name}")
            return Source(pfd, FileInputStream(pfd.fileDescriptor).channel)
        }
    }
}

/** A file being received into Downloads/HyperShare (keeping sub-folders) via MediaStore. Bench sink discards. */
class Sink private constructor(
    private val ctx: Context, private val uri: Uri?,
    private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?,
    /** Android 8–9 only: the file written directly in Download/HyperShare. */
    private val file: java.io.File? = null,
) {
    @Volatile private var finished = false
    @Volatile private var aborted = false

    /** Content URI once the file is complete and visible to other apps. */
    val readyUri: Uri? get() = if (!finished) null else uri ?: file?.let {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it)
    }

    /** Thread-safe positional write. */
    fun write(b: ByteBuffer, pos: Long) {
        if (ch == null || aborted) return
        var p = pos
        while (b.hasRemaining()) p += ch.write(b, p)
    }

    @Synchronized fun finish() {
        if (finished || aborted) return
        ch?.close(); pfd?.close()
        uri?.let { ctx.contentResolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
        file?.let { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(it.path), null, null) }
        finished = true
    }

    /** Delete a half-written file. Finished files are kept. */
    @Synchronized fun abort() {
        if (finished || aborted) return
        aborted = true
        runCatching { ch?.close(); pfd?.close() }
        uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
        file?.let { runCatching { it.delete() } }
    }

    companion object {
        fun open(ctx: Context, name: String, mime: String): Sink {
            if (name == Proto.BENCH_NAME) return Sink(ctx, null, null, null)
            val parts = name.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
            val file = parts.lastOrNull() ?: "file"
            val dirs = parts.dropLast(1).joinToString("/")
            if (android.os.Build.VERSION.SDK_INT < 29) return openLegacy(ctx, file, dirs)
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, file)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/HyperShare" + if (dirs.isNotEmpty()) "/$dirs" else "")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val cr = ctx.contentResolver
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: throw IOException("Can't create $file")
            val pfd = cr.openFileDescriptor(uri, "rw") ?: throw IOException("Can't write $file")
            return Sink(ctx, uri, pfd, FileOutputStream(pfd.fileDescriptor).channel)
        }
    
        @Suppress("DEPRECATION")
        private fun openLegacy(ctx: Context, name: String, dirs: String): Sink {
            val root = java.io.File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "HyperShare")
            val dir = if (dirs.isEmpty()) root else java.io.File(root, dirs)
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Can't create ${dir.path}. Allow storage access.")
            val stem = name.substringBeforeLast('.', name); val ext = name.substringAfterLast('.', "")
            var f = java.io.File(dir, name); var n = 1
            while (f.exists()) { f = java.io.File(dir, if (ext.isEmpty()) "$stem ($n)" else "$stem ($n).$ext"); n++ }
            val raf = java.io.RandomAccessFile(f, "rw")
            return Sink(ctx, null, null, raf.channel, f)
        }
    }
}
