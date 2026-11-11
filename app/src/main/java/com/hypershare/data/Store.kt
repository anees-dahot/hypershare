package com.hypershare.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** A file that stopped half-way; the next transfer of the same name+size continues it. */
data class Partial(val name: String, val size: Long, val mime: String, val locator: String, val chunks: List<Int>)

object Partials {
    private fun file(ctx: Context) = File(ctx.filesDir, "partials.json")

    @Synchronized fun put(ctx: Context, p: Partial) {
        val all = load(ctx).filter { !(it.name == p.name && it.size == p.size) } + p
        // Forgotten partials would otherwise stay on disk as half files nobody can resume.
        all.dropLast(MAX).forEach { old ->
            runCatching {
                if (old.locator.startsWith("content://")) ctx.contentResolver.delete(Uri.parse(old.locator), null, null)
                else File(old.locator).delete()
            }
        }
        save(ctx, all.takeLast(MAX))
    }

    private const val MAX = 50

    /** Removes and returns a matching partial, if any. */
    @Synchronized fun take(ctx: Context, name: String, size: Long): Partial? {
        val all = load(ctx)
        val hit = all.firstOrNull { it.name == name && it.size == size } ?: return null
        save(ctx, all - hit)
        return hit
    }

    private fun load(ctx: Context): List<Partial> = runCatching {
        val a = JSONArray(file(ctx).readText())
        List(a.length()) { i ->
            val o = a.getJSONObject(i)
            val c = o.getJSONArray("chunks")
            Partial(o.getString("name"), o.getLong("size"), o.getString("mime"), o.getString("loc"), List(c.length()) { c.getInt(it) })
        }
    }.getOrDefault(emptyList())

    private fun save(ctx: Context, list: List<Partial>) {
        val a = JSONArray()
        list.forEach { p ->
            a.put(JSONObject().put("name", p.name).put("size", p.size).put("mime", p.mime).put("loc", p.locator)
                .put("chunks", JSONArray(p.chunks)))
        }
        runCatching { file(ctx).writeText(a.toString()) }
    }
}

/** A finished file in Downloads/HyperShare. */
data class Received(val uri: Uri, val name: String, val size: Long, val mime: String, val time: Long, val folder: String, val file: File? = null)

object Inbox {
    suspend fun list(ctx: Context): List<Received> = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT >= 29) fromMediaStore(ctx) else fromDisk(ctx)
    }

    /** Downloads/HyperShare, plus Pictures/ and Movies/HyperShare when "save to gallery" is (or was) on. */
    @androidx.annotation.RequiresApi(29)
    private fun fromMediaStore(ctx: Context): List<Received> = (
        query(ctx, MediaStore.Downloads.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_DOWNLOADS) +
            query(ctx, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_PICTURES) +
            query(ctx, MediaStore.Video.Media.EXTERNAL_CONTENT_URI, Environment.DIRECTORY_MOVIES)
        ).sortedByDescending { it.time }

    @androidx.annotation.RequiresApi(29)
    private fun query(ctx: Context, coll: Uri, root: String): List<Received> {
        val out = ArrayList<Received>()
        val proj = arrayOf(
            MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.RELATIVE_PATH,
        )
        runCatching {
            ctx.contentResolver.query(
                coll, proj, "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND ${MediaStore.MediaColumns.IS_PENDING} = 0",
                arrayOf("$root/HyperShare/%"), "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val rel = c.getString(5) ?: ""
                    out += Received(
                        ContentUris.withAppendedId(coll, c.getLong(0)), c.getString(1) ?: "file", c.getLong(2),
                        c.getString(3) ?: "application/octet-stream", c.getLong(4) * 1000,
                        rel.removePrefix("$root/HyperShare").trim('/'),
                    )
                }
            }
        }
        return out
    }

    @Suppress("DEPRECATION")
    private fun fromDisk(ctx: Context): List<Received> =
        listOf(Environment.DIRECTORY_DOWNLOADS, Environment.DIRECTORY_PICTURES, Environment.DIRECTORY_MOVIES).flatMap { dir ->
            val root = File(Environment.getExternalStoragePublicDirectory(dir), "HyperShare")
            root.walkTopDown().filter { it.isFile }.map { f ->
                val mime = android.webkit.MimeTypeMap.getSingleton()
                    .getMimeTypeFromExtension(f.extension.lowercase()) ?: "application/octet-stream"
                Received(
                    androidx.core.content.FileProvider.getUriForFile(ctx, "${ctx.packageName}.files", f),
                    f.name, f.length(), mime, f.lastModified(), f.parentFile?.relativeTo(root)?.path ?: "", f,
                )
            }.toList()
        }.sortedByDescending { it.time }

    fun delete(ctx: Context, r: Received): Boolean =
        runCatching { r.file?.delete() ?: (ctx.contentResolver.delete(r.uri, null, null) > 0) }.getOrDefault(false)
}
