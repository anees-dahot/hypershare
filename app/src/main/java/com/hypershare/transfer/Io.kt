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
    const val MAGIC = 0x48535831 // "HSX1"
    const val CHUNK = 4 * 1024 * 1024
    const val STREAMS = 4
    const val SOCK_BUF = 4 * 1024 * 1024
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

fun tune(sc: SocketChannel) = sc.socket().apply {
    tcpNoDelay = true
    sendBufferSize = Proto.SOCK_BUF
    receiveBufferSize = Proto.SOCK_BUF
}

fun openClient(): SocketChannel {
    val sc = SocketChannel.open()
    try {
        tune(sc) // before connect so the TCP window scale is negotiated big
        sc.socket().connect(InetSocketAddress(Proto.GO_IP, Proto.PORT), 2000)
        return sc
    } catch (e: IOException) {
        sc.close(); throw e
    }
}

fun readFully(sc: SocketChannel, b: ByteBuffer) {
    while (b.hasRemaining()) if (sc.read(b) < 0) throw EOFException("Peer closed connection")
}

fun writeFully(sc: SocketChannel, vararg bs: ByteBuffer) {
    while (bs.any { it.hasRemaining() }) sc.write(bs)
}

/** A file being sent. Bench source sends a zeroed buffer without touching storage. */
class Source private constructor(
    val name: String, val size: Long,
    private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?,
) {
    /** Fill [b] (position..limit) from file offset [pos]. Thread-safe (positional read). */
    fun read(b: ByteBuffer, pos: Long) {
        if (ch == null) { b.position(b.limit()); return }
        var p = pos
        while (b.hasRemaining()) {
            val n = ch.read(b, p)
            if (n < 0) throw EOFException("$name shrank while sending")
            p += n
        }
    }

    fun close() { ch?.close(); pfd?.close() }

    companion object {
        fun bench() = Source(Proto.BENCH_NAME, Proto.BENCH_SIZE, null, null)

        fun open(ctx: Context, uri: Uri): Source {
            val cr = ctx.contentResolver
            var name = "file"; var size = -1L
            cr.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) {
                    name = it.getString(0) ?: name
                    if (!it.isNull(1)) size = it.getLong(1)
                }
            }
            val pfd = cr.openFileDescriptor(uri, "r") ?: throw IOException("Can't open $name")
            if (size < 0) size = pfd.statSize
            return Source(name, size, pfd, FileInputStream(pfd.fileDescriptor).channel)
        }
    }
}

/** A file being received into Downloads/HyperShare via MediaStore. Bench sink discards. */
class Sink private constructor(
    private val ctx: Context, private val uri: Uri?,
    private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?,
) {
    /** Thread-safe positional write. */
    fun write(b: ByteBuffer, pos: Long) {
        if (ch == null) return
        var p = pos
        while (b.hasRemaining()) p += ch.write(b, p)
    }

    fun finish() {
        ch?.close(); pfd?.close()
        uri ?: return
        ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
    }

    fun abort() {
        runCatching { ch?.close(); pfd?.close() }
        uri?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
    }

    companion object {
        fun open(ctx: Context, name: String): Sink {
            if (name == Proto.BENCH_NAME) return Sink(ctx, null, null, null)
            val cr = ctx.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, File(name).name.ifBlank { "file" })
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
