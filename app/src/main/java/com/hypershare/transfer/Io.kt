package com.hypershare.transfer

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.hypershare.data.Picked
import com.hypershare.data.Prefs
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
    const val MAGIC = 0x48535835 // "HSX5"
    const val CHUNK = 4 * 1024 * 1024
    /** Chunk header: fileIdx Int, offset Long, len Int, crc Int. fileIdx -1 = end of stream. */
    const val HDR = 20
    const val BENCH_NAME = "__bench__"
    const val BENCH_SIZE = 2L * 1024 * 1024 * 1024

    // Control messages (either direction unless noted), framed as type byte + payload.
    // Both phones can send; indexes are per direction (the sender's outgoing list).
    const val T_ADD = 1        // count, then (name, size, mime, group) each
    const val T_ADDED = 2      // reply: count, then per file the chunk indexes already received (resume)
    const val T_FILE_DONE = 3  // receiver -> sender: idx fully written
    const val T_SKIP_MINE = 4  // "I cancelled my outgoing file idx"
    const val T_BYE = 5        // session over
    const val T_SKIP_YOURS = 6 // "stop sending me your file idx"
    const val T_TRUST = 7      // receiver -> sender: "I'll accept you without asking from now on", + secret.
                               // Only sent to senders whose hello shows they understand it.

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
class Source private constructor(
    private val pfd: ParcelFileDescriptor?, private val ch: FileChannel?,
    /** A text snippet: sent straight from memory. */
    private val bytes: ByteArray? = null,
) {
    /** Fill [b] (position..limit) from file offset [pos]. Thread-safe (positional read). */
    fun read(b: ByteBuffer, pos: Long) {
        if (bytes != null) {
            if (pos < 0 || pos + b.remaining() > bytes.size) throw EOFException("Text is shorter than announced")
            b.put(bytes, pos.toInt(), b.remaining()); return
        }
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
            p.text?.let { return Source(null, null, it.toByteArray()) }
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
    /** A text snippet: kept in memory and shown, never written to storage. */
    private val mem: ByteArray? = null,
) {
    @Volatile private var finished = false
    @Volatile private var aborted = false

    /** Where the partial file lives (content URI or path), for resuming later. */
    val locator: String? get() = uri?.toString() ?: file?.path

    /** Close without finishing: keeps the partial file for a later resume. */
    @Synchronized fun closeKeep() {
        if (finished || aborted) return
        aborted = true // no more writes in this session
        runCatching { ch?.close(); pfd?.close() }
    }

    /** Content URI once the file is complete and visible to other apps. */
    val readyUri: Uri? get() = if (!finished) null else uri ?: file?.let {
        androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", it)
    }

    /** The received text snippet, once complete. */
    val text: String? get() = if (finished && mem != null) decoded ?: String(mem).also { decoded = it } else null
    @Volatile private var decoded: String? = null

    /** Thread-safe positional write. */
    fun write(b: ByteBuffer, pos: Long) {
        if (mem != null) {
            if (aborted) throw IOException("File closed")
            if (pos < 0 || pos + b.remaining() > mem.size) throw IOException("Bad data from the other phone")
            b.get(mem, pos.toInt(), b.remaining()); return // streams write disjoint ranges
        }
        if (ch == null) return // speed test: discard
        // Must not look like success: the caller would mark the chunk as saved, and a later
        // resume would skip data that never reached the file.
        if (aborted) throw IOException("File closed")
        var p = pos
        while (b.hasRemaining()) p += ch.write(b, p)
    }

    @Synchronized fun finish() {
        if (finished || aborted) return
        ch?.close(); pfd?.close()
        uri?.let { ctx.contentResolver.update(it, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) }
        file?.let { runCatching { android.media.MediaScannerConnection.scanFile(ctx, arrayOf(it.path), null, null) } }
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
        /** In-memory sink for a text snippet of [size] bytes. */
        fun memory(ctx: Context, size: Int) = Sink(ctx, null, null, null, null, ByteArray(size))

        /**
         * With "save to gallery" on, loose photos and videos go to Pictures/ or Movies/HyperShare,
         * where gallery apps look. Files sent inside a folder stay together in Downloads.
         */
        private fun galleryDir(ctx: Context, mime: String, dirs: String): String? = when {
            dirs.isNotEmpty() || !Prefs.gallery(ctx) -> null
            mime.startsWith("image/") -> Environment.DIRECTORY_PICTURES
            mime.startsWith("video/") -> Environment.DIRECTORY_MOVIES
            else -> null
        }

        fun open(ctx: Context, name: String, mime: String): Sink {
            if (name == Proto.BENCH_NAME) return Sink(ctx, null, null, null)
            val parts = name.split('/').map { it.trim() }.filter { it.isNotEmpty() && it != "." && it != ".." }
            val file = parts.lastOrNull() ?: "file"
            val dirs = parts.dropLast(1).joinToString("/")
            val media = galleryDir(ctx, mime, dirs)
            if (android.os.Build.VERSION.SDK_INT < 29) return openLegacy(ctx, file, dirs, media ?: Environment.DIRECTORY_DOWNLOADS)
            val cr = ctx.contentResolver
            fun insert(coll: Uri, root: String, n: String) = runCatching {
                cr.insert(coll, ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, n)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, root + "/HyperShare" + if (dirs.isNotEmpty()) "/$dirs" else "")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                })
            }.getOrNull()
            val downloads = MediaStore.Downloads.EXTERNAL_CONTENT_URI
            // Gallery first; it refuses types it doesn't know (then Downloads takes the file).
            // MediaStore renames duplicates itself, but gives up (throws) after ~32 files with one
            // name. Then use a name of our own, so one clash doesn't fail the whole transfer.
            val uri = media?.let {
                insert(if (it == Environment.DIRECTORY_PICTURES) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Video.Media.EXTERNAL_CONTENT_URI, it, file)
            } ?: insert(downloads, Environment.DIRECTORY_DOWNLOADS, file)
                ?: insert(downloads, Environment.DIRECTORY_DOWNLOADS, uniqueName(file))
                ?: throw IOException("Can't create $file")
            val pfd = try { cr.openFileDescriptor(uri, "rw") } catch (e: Exception) { null }
            if (pfd == null) {
                runCatching { cr.delete(uri, null, null) } // don't leave an empty pending entry behind
                throw IOException("Can't write $file")
            }
            return Sink(ctx, uri, pfd, FileOutputStream(pfd.fileDescriptor).channel)
        }

        private fun uniqueName(name: String): String {
            val tag = java.lang.Long.toString(System.currentTimeMillis(), 36)
            val ext = name.substringAfterLast('.', "")
            return if (ext.isEmpty() || ext.length > 8) "$name $tag" else "${name.substringBeforeLast('.')} $tag.$ext"
        }
    
        /** Reopen a partial file kept by [closeKeep]; null if it's gone. */
        fun reopen(ctx: Context, locator: String): Sink? = runCatching {
            if (locator.startsWith("content://")) {
                val uri = Uri.parse(locator)
                val pfd = ctx.contentResolver.openFileDescriptor(uri, "rw") ?: return null
                Sink(ctx, uri, pfd, FileOutputStream(pfd.fileDescriptor).channel)
            } else {
                val f = java.io.File(locator)
                if (!f.exists()) return null
                Sink(ctx, null, null, java.io.RandomAccessFile(f, "rw").channel, f)
            }
        }.getOrNull()

        @Suppress("DEPRECATION")
        private fun openLegacy(ctx: Context, name: String, dirs: String, publicDir: String): Sink {
            val root = java.io.File(Environment.getExternalStoragePublicDirectory(publicDir), "HyperShare")
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
