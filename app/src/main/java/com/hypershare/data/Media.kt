package com.hypershare.data

import android.content.ContentUris
import android.content.Context
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

const val APK_MIME = "application/vnd.android.package-archive"
const val DIR_MIME = "inode/directory"

/** One file on the wire. [name] may contain folders ("Trip/day1/a.jpg"). [group] ties APK parts of one app together. */
data class Picked(val uri: Uri, val name: String, val size: Long, val mime: String, val group: String = "")

/** One selectable thing in the picker: a photo, an app (several APK parts), a folder (many files). */
data class Entity(
    val key: String,
    val label: String,
    val size: Long,
    val mime: String,
    val thumb: Uri?,
    val pkg: String? = null,
    val durationMs: Long = 0,
    val parts: List<Picked>,
)

enum class MediaKind { Photos, Videos, Music }

object MediaRepo {
    suspend fun apps(ctx: Context): List<Entity> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        pm.getInstalledApplications(0)
            .filter {
                val user = it.flags and ApplicationInfo.FLAG_SYSTEM == 0 || it.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0
                user && it.packageName != ctx.packageName && pm.getLaunchIntentForPackage(it.packageName) != null
            }
            .map { ai ->
                val label = pm.getApplicationLabel(ai).toString()
                val clean = label.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().ifEmpty { ai.packageName }
                val files = listOf(ai.sourceDir) + (ai.splitSourceDirs?.toList() ?: emptyList())
                val parts = files.mapIndexed { i, path ->
                    val f = File(path)
                    Picked(
                        Uri.fromFile(f),
                        if (i == 0) "$clean.apk" else "$clean.${f.nameWithoutExtension}.apk",
                        f.length(), APK_MIME, ai.packageName,
                    )
                }
                Entity("app:${ai.packageName}", label, parts.sumOf { it.size }, APK_MIME, null, ai.packageName, 0, parts)
            }
            .sortedBy { it.label.lowercase() }
    }

    suspend fun media(ctx: Context, kind: MediaKind): List<Entity> = withContext(Dispatchers.IO) {
        val vol = "external" // MediaStore.VOLUME_EXTERNAL, valid on all versions
        val coll = when (kind) {
            MediaKind.Photos -> MediaStore.Images.Media.getContentUri(vol)
            MediaKind.Videos -> MediaStore.Video.Media.getContentUri(vol)
            MediaKind.Music -> MediaStore.Audio.Media.getContentUri(vol)
        }
        val hasDur = kind != MediaKind.Photos
        val proj = buildList {
            add(MediaStore.MediaColumns._ID); add(MediaStore.MediaColumns.DISPLAY_NAME)
            add(MediaStore.MediaColumns.SIZE); add(MediaStore.MediaColumns.MIME_TYPE)
            if (hasDur) add(MediaStore.MediaColumns.DURATION)
        }.toTypedArray()
        val out = ArrayList<Entity>()
        runCatching {
            ctx.contentResolver.query(coll, proj, null, null, "${MediaStore.MediaColumns.DATE_MODIFIED} DESC")?.use { c ->
                while (c.moveToNext() && out.size < 3000) {
                    val uri = ContentUris.withAppendedId(coll, c.getLong(0))
                    val name = c.getString(1) ?: continue
                    val size = c.getLong(2)
                    if (size <= 0) continue
                    val mime = c.getString(3) ?: "application/octet-stream"
                    val dur = if (hasDur) c.getLong(4) else 0
                    out += Entity(uri.toString(), name, size, mime, uri, null, dur, listOf(Picked(uri, name, size, mime)))
                }
            }
        }
        out
    }

    /** Files chosen with the system picker. */
    suspend fun documents(ctx: Context, uris: List<Uri>): List<Entity> = withContext(Dispatchers.IO) {
        uris.map { uri ->
            var name = "file"; var size = 0L
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
                if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
            }
            val mime = ctx.contentResolver.getType(uri) ?: "application/octet-stream"
            Entity(uri.toString(), name, size, mime, uri, null, 0, listOf(Picked(uri, name, size, mime)))
        }
    }

    /** A whole folder (system folder picker), sent with its sub-folder structure. */
    suspend fun folder(ctx: Context, tree: Uri): Entity = withContext(Dispatchers.IO) {
        val cr = ctx.contentResolver
        val rootId = DocumentsContract.getTreeDocumentId(tree)
        var rootName = "Folder"
        cr.query(DocumentsContract.buildDocumentUriUsingTree(tree, rootId), arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            ?.use { if (it.moveToFirst()) rootName = it.getString(0) ?: rootName }
        val parts = ArrayList<Picked>()
        fun walk(docId: String, prefix: String) {
            val kids = DocumentsContract.buildChildDocumentsUriUsingTree(tree, docId)
            cr.query(kids, arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE,
            ), null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getString(0); val name = c.getString(1) ?: continue; val mime = c.getString(2) ?: ""
                    if (mime == DocumentsContract.Document.MIME_TYPE_DIR) walk(id, "$prefix$name/")
                    else parts += Picked(DocumentsContract.buildDocumentUriUsingTree(tree, id), "$prefix$name", c.getLong(3), mime.ifEmpty { "application/octet-stream" })
                }
            }
        }
        walk(rootId, "$rootName/")
        Entity("dir:$tree", rootName, parts.sumOf { it.size }, DIR_MIME, null, null, 0, parts)
    }
}
