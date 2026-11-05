package com.hypershare.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.provider.OpenableColumns
import java.io.EOFException
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.SocketChannel

object Proto {
    const val PORT = 8988
    /** Wi-Fi Direct group owner always has this address on Android. */
    const val GO_IP = "192.168.49.1"
    const val MAGIC = 0x48535832 // "HSX2"
    const val CHUNK = 4 * 1024 * 1024
    /** Chunk header: fileIdx Int, offset Long, len Int, crc Int. fileIdx -1 = end of stream. */
    const val HDR = 20
    const val BENCH_NAME = "__bench__"
    const val BENCH_SIZE = 2L * 1024 * 1024 * 1024
}

data class Chunk(val file: Int, val off: Long, val len: Int)

fun chunksOf(sizes: List<Long>): List<Chunk> = buildList {
    sizes.forEachIndexed { i, size ->
        var off = 0L
        while (off < size) {
            val len = minOf(Proto.CHUNK.toLong(), size - off).toInt()
            add(Chunk(i, off, len)); off += len
        }
    }
}

/**
 * Only disable Nagle. Do NOT set SO_SNDBUF/SO_RCVBUF: on Linux that pins the buffer size and
 * turns off TCP autotuning, which made throughput jumpy in v0.1.
 */
fun tune(sc: SocketChannel) { sc.socket().tcpNoDelay = true }

fun openClient(): SocketChannel {
    val sc = SocketChannel.open()
    try {
        tune(sc)
        sc.socket().connect(InetSocketAddress(Proto.GO_IP, Proto.PORT), 2000)
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
class Source private constructor(
    val name: String, val size: Long, val mime: String,
    private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?,
) {
    /** Fill [b] (position..limit) from file offset [pos]. Thread-safe (positional read). */
    fun read(b: ByteBuffer, pos: Long) {
        if (ch == null) { b.position(b.limit()); return }
        var p = pos
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n < 0) throw EOFException("$name got shorter while sending")
            p += n
        }
    }

    fun close() { runCatching { ch?.close(); pfd?.close() } }

    companion object {
        fun bench() = Source(Proto.BENCH_NAME, Proto.BENCH_SIZE, "application/octet-stream", null, null)

        fun open(ctx: Context, uri: Uri): Source {
            val cr = ctx.contentResolver
            var name = "file"; var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0) ?: name
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
            val mime = cr.getType(uri) ?: "application/octet-stream"
            val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Can't open $name")
            if (size < 0) size = pfd.statSize
            return Source(name, size, mime, pfd, FileInputStream(pfd.fileDescriptor).channel)
        }
    }
}

/** A file being received into Downloads/HyperShare via MediaStore. Bench sink discards. */
class Sink private constructor(
    private val ctx: Context, private val uri: Uri?,
    private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?,
) {
    @Volatile private var finished = false

    /** Content URI once the file is complete and visible to other apps. */
    val readyUri: Uri? get() = if (finished) uri else null

    /** Thread-safe positional write. */
    fun write(b: ByteBuffer, pos: Long) {
        if (ch == null) return
        var p = pos
        while (b.hasRemaining()) p += ch.write(b, p)
    }

    @Synchronized fun finish() {
        if (finished) return
        ch?.close(); pfd?.close()
        uri?.let { ctx.contentResolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
        finished = true
    }

    /** Delete a half-written file. Finished files are kept. */
    @Synchronized fun abort() {
        if (finished) return
        runCatching { ch?.close(); pfd?.close() }
        uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }

    companion object {
        fun open(ctx: Context, name: String, mime: String): Sink {
            if (name == Proto.BENCH_NAME) return Sink(ctx, null, null, null)
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, File(name).name.ifBlank { "file" })
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/HyperShare")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Can't create $name")
            val pfd = cr.openFileDescriptor(uri, "rw") ?: throw IOException("Can't write $name")
            return Sink(ctx, uri, pfd, FileOutputStream(pfd.fileDescriptor).channel)
        }
    }
}
