package com.hypershare.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.util.LruCache
import android.util.Size
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

// ------------------------------------------------------------------ formatting

fun fmtBytes(b: Long): String = when {
    b < 1_000 -> "$b B"
    b < 1_000_000 -> String.format(Locale.US, "%.0f KB", b / 1e3)
    b < 1_000_000_000 -> String.format(Locale.US, "%.1f MB", b / 1e6)
    else -> String.format(Locale.US, "%.2f GB", b / 1e9)
}

fun fmtDuration(sec: Long): String = when {
    sec < 0 -> "--"
    sec < 60 -> "${sec}s"
    sec < 3600 -> "${sec / 60}m ${sec % 60}s"
    else -> "${sec / 3600}h ${(sec % 3600) / 60}m"
}

fun fmtAgo(t: Long): String {
    val s = (System.currentTimeMillis() - t) / 1000
    return when {
        s < 60 -> "just now"
        s < 3600 -> "${s / 60} min ago"
        s < 86400 -> "${s / 3600} h ago"
        else -> "${s / 86400} d ago"
    }
}

fun extOf(name: String, mime: String): String {
    val e = name.substringAfterLast('.', "").uppercase(Locale.US)
    return if (e.isNotEmpty() && e.length <= 4) e else mime.substringAfter('/').take(4).uppercase(Locale.US)
}

private fun iconFor(mime: String, name: String): ImageVector = when {
    mime == com.hypershare.data.DIR_MIME -> Icons.Outlined.Folder
    mime.startsWith("image/") -> Icons.Outlined.Image
    mime.startsWith("video/") -> Icons.Outlined.Movie
    mime.startsWith("audio/") -> Icons.Outlined.MusicNote
    name.endsWith(".apk", true) -> Icons.Outlined.Android
    mime.contains("zip") || mime.contains("compressed") || mime.contains("x-7z") || mime.contains("rar") -> Icons.Outlined.FolderZip
    mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") || mime.contains("sheet") -> Icons.Outlined.Description
    else -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

// ------------------------------------------------------------------ actions

fun openFile(ctx: Context, uri: Uri, mime: String) = launch(ctx) {
    Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
}

fun shareFile(ctx: Context, uri: Uri, mime: String) = launch(ctx) {
    Intent.createChooser(
        Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),
        null,
    )
}

private fun launch(ctx: Context, intent: () -> Intent) {
    try { ctx.startActivity(intent()) } catch (e: ActivityNotFoundException) {
        Toast.makeText(ctx, "No app can open this file", Toast.LENGTH_SHORT).show()
    } catch (e: SecurityException) {
        Toast.makeText(ctx, "File is no longer available", Toast.LENGTH_SHORT).show()
    }
}

// ------------------------------------------------------------------ thumbnails

private val thumbCache = LruCache<String, Bitmap>(80)

/** Thumbnail for a content URI; Android 10+ API, with decoding fallbacks for 8–9. */
fun thumbOf(ctx: Context, uri: Uri, mime: String, px: Int): Bitmap? = runCatching {
    if (android.os.Build.VERSION.SDK_INT >= 29) return@runCatching ctx.contentResolver.loadThumbnail(uri, Size(px, px), null)
    when {
        mime.startsWith("video/") -> android.media.MediaMetadataRetriever().run {
            try { setDataSource(ctx, uri); getFrameAtTime(1_000_000)?.let { scaled(it, px) } } finally { release() }
        }
        mime.startsWith("image/") -> {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= px && o.outHeight / (sample * 2) >= px) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                android.graphics.BitmapFactory.decodeStream(it, null, android.graphics.BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
        else -> null
    }
}.getOrNull()

private fun scaled(b: Bitmap, px: Int): Bitmap {
    val k = px.toFloat() / maxOf(b.width, b.height)
    return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
}

@Composable
fun FileThumb(uri: Uri?, mime: String, name: String, size: Dp = 48.dp) {
    val ctx = LocalContext.current
    val key = uri?.toString()
    val bmp by produceState(key?.let { thumbCache.get(it) }, key) {
        if (key == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            thumbOf(ctx, uri, mime, 192)
        }?.also { thumbCache.put(key, it) }
    }
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(size).clip(shape).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(bmp, transitionSpec = { fadeIn(tween(220)) togetherWith fadeOut(tween(120)) }, label = "thumb") { b ->
            if (b != null) {
                Image(b.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(iconFor(mime, name), null, Modifier.size(size * 0.42f), tint = MaterialTheme.colorScheme.primary)
                    if (size >= 44.dp) Text(
                        extOf(name, mime), fontSize = 9.sp, letterSpacing = 0.4.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------ surfaces

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) { Column(Modifier.padding(padding), content = content) }
}

/** Gentle press-down scale used on the big tiles. */
@Composable
fun Modifier.pressable(source: MutableInteractionSource): Modifier {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed) 0.975f else 1f, spring(stiffness = 900f), label = "press")
    return scale(s)
}

@Composable
fun Chip(text: String, warn: Boolean = false) {
    val c = MaterialTheme.colorScheme
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = if (warn) c.error else c.primary,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (warn) c.errorContainer else c.primaryContainer)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

fun bandLabel(freq: Int) = when {
    freq <= 0 -> null
    freq < 5000 -> "2.4 GHz · $freq MHz"
    freq < 5925 -> "5 GHz · ch ${(freq - 5000) / 5} · $freq MHz"
    else -> "6 GHz · ch ${(freq - 5950) / 5} · $freq MHz"
}

// ------------------------------------------------------------------ file row

data class RowAction(val icon: ImageVector, val label: String, val onClick: () -> Unit)

/** App icon when [pkg] is installed here, else the file thumbnail. */
@Composable
fun ItemThumb(uri: Uri?, mime: String, name: String, pkg: String?, size: Dp = 48.dp) {
    val ctx = LocalContext.current
    val icon by produceState(pkg?.let { appIconCache.get(it) }, pkg) {
        if (pkg == null || value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { ctx.packageManager.getApplicationIcon(pkg).toBitmap(144, 144) }.getOrNull()
        }?.also { appIconCache.put(pkg, it) }
    }
    val b = icon
    if (b != null) Image(b.asImageBitmap(), null, Modifier.size(size))
    else FileThumb(uri, mime, name, size)
}

private val appIconCache = LruCache<String, Bitmap>(200)

@Composable
fun FileRow(
    name: String,
    mime: String,
    uri: Uri?,
    meta: String,
    progress: Float?, // null = no bar
    done: Boolean,
    actions: List<RowAction>,
    trailing: String? = null,
    pkg: String? = null,
    primary: RowAction? = null,
    dim: Boolean = false,
    /** File name the icon and type badge are based on, when [name] is a title rather than a file name (text snippets). */
    thumbName: String = name,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp)
            .then(if (dim) Modifier.alpha(0.5f) else Modifier),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemThumb(uri, mime, thumbName, pkg)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Spacer(Modifier.height(2.dp))
            Text(meta, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (progress != null) {
                Spacer(Modifier.height(6.dp))
                val p by animateFloatAsState(progress, tween(250), label = "fp")
                LinearProgressIndicator(
                    progress = { p }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                    trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp,
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        if (trailing != null) {
            Text(trailing, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(4.dp))
        }
        if (primary != null) {
            FilledTonalButton(
                primary.onClick, Modifier.height(34.dp),
                contentPadding = PaddingValues(horizontal = 14.dp),
            ) { Text(primary.label, style = MaterialTheme.typography.labelLarge) }
            Spacer(Modifier.width(2.dp))
        }
        actions.forEach { a ->
            IconButton(a.onClick, Modifier.size(40.dp)) {
                Icon(a.icon, a.label, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
            }
        }
        if (actions.isEmpty() && primary == null && done) { DoneBadge(); Spacer(Modifier.width(8.dp)) }
    }
}

@Composable
fun DoneBadge(size: Dp = 22.dp) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    androidx.compose.animation.AnimatedVisibility(shown, enter = scaleIn(spring(dampingRatio = 0.5f, stiffness = 600f))) {
        Box(
            Modifier.size(size).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(size * 0.65f)) }
    }
}

// ------------------------------------------------------------------ speed chart

/** Scrolling line of recent speed samples; fixed window so it fills from the right. */
@Composable
fun SpeedChart(samples: List<Float>, window: Int, modifier: Modifier = Modifier) {
    val line = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val target = ((samples.maxOrNull() ?: 0f) * 1.2f).coerceAtLeast(10f)
    val maxY by animateFloatAsState(target, tween(600), label = "maxY")
    Canvas(modifier) {
        val h = size.height; val w = size.width
        for (i in 1..3) {
            val y = h * i / 4f
            drawLine(grid, Offset(0f, y), Offset(w, y), strokeWidth = 1f)
        }
        if (samples.size < 2) return@Canvas
        val step = w / (window - 1)
        val x0 = w - (samples.size - 1) * step
        val path = Path()
        samples.forEachIndexed { i, v ->
            val x = x0 + i * step
            val y = h - (v / maxY).coerceIn(0f, 1f) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        val fill = Path().apply { addPath(path); lineTo(w, h); lineTo(x0, h); close() }
        drawPath(fill, line.copy(alpha = 0.09f))
        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        val last = samples.last()
        drawCircle(line, 3.5.dp.toPx(), Offset(w, h - (last / maxY).coerceIn(0f, 1f) * h))
    }
}

@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text.uppercase(Locale.US), style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, letterSpacing = 0.8.sp, modifier = Modifier.weight(1f),
        )
        trailing?.invoke()
    }
}

// ------------------------------------------------------------------ shared bits for v0.5 screens

/** Rough file kind, for filters and the right action button. */
enum class Kind(val label: String) { All("All"), Photos("Photos"), Videos("Videos"), Music("Music"), Apps("Apps"), Docs("Docs"), Other("Other") }

fun kindOf(mime: String, name: String): Kind = when {
    mime.startsWith("image/") -> Kind.Photos
    mime.startsWith("video/") -> Kind.Videos
    mime.startsWith("audio/") -> Kind.Music
    mime == com.hypershare.data.APK_MIME || name.endsWith(".apk", true) -> Kind.Apps
    mime.startsWith("text/") || mime.contains("pdf") || mime.contains("document") || mime.contains("sheet") ||
        mime.contains("presentation") || mime.contains("msword") -> Kind.Docs
    else -> Kind.Other
}

/** "Install" for APKs, "Play" for media, "Open" otherwise. */
fun primaryAction(ctx: Context, uri: Uri, mime: String, name: String, install: () -> Unit): RowAction = when (kindOf(mime, name)) {
    Kind.Apps -> RowAction(Icons.Outlined.InstallMobile, "Install", install)
    Kind.Videos, Kind.Music -> RowAction(Icons.Outlined.PlayArrow, "Play") { openFile(ctx, uri, mime) }
    else -> RowAction(Icons.Outlined.OpenInNew, "Open") { openFile(ctx, uri, mime) }
}

/** Round initial for a phone name. */
@Composable
fun Avatar(name: String, size: Dp = 44.dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            name.trim().take(1).uppercase().ifEmpty { "?" },
            style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
        )
    }
}

/** Centered icon + title + body, used for every empty list. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String? = null, modifier: Modifier = Modifier, action: @Composable (() -> Unit)? = null) {
    Column(modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary) }
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (body != null) {
            Spacer(Modifier.height(6.dp))
            Text(
                body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
        }
        if (action != null) { Spacer(Modifier.height(16.dp)); action() }
    }
}

// ------------------------------------------------------------------ text snippets

fun copyText(ctx: Context, text: String) {
    val cm = ctx.getSystemService(android.content.ClipboardManager::class.java) ?: return
    // A very long text can exceed what the system clipboard takes.
    val ok = runCatching { cm.setPrimaryClip(android.content.ClipData.newPlainText("HyperShare", text)) }.isSuccess
    // Android 13+ shows its own "copied" confirmation.
    if (!ok) Toast.makeText(ctx, "Too long to copy", Toast.LENGTH_SHORT).show()
    else if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(ctx, "Copied", Toast.LENGTH_SHORT).show()
}

/** The link, when a snippet is nothing but one web link (then it gets an "Open link" button). */
fun linkIn(text: String): Uri? {
    val t = text.trim()
    if (t.length > 2000 || t.any { it.isWhitespace() }) return null
    if (!t.startsWith("http://", true) && !t.startsWith("https://", true)) return null
    return runCatching { Uri.parse(t) }.getOrNull()?.takeIf { !it.host.isNullOrEmpty() }
}

fun openLink(ctx: Context, uri: Uri) = launch(ctx) { Intent(Intent.ACTION_VIEW, uri) }

/** A snippet in full: selectable, with Copy, and Open when it is a link. */
@Composable
fun TextDialog(text: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val link = remember(text) { linkIn(text) }
    // Laying out hundreds of kilobytes of text would freeze the dialog; Copy still takes all of it.
    val shown = remember(text) { if (text.length > 20_000) text.take(20_000) + "…" else text }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (link != null) "Link" else "Text") },
        text = {
            SelectionContainer {
                Text(shown, Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()), style = MaterialTheme.typography.bodyMedium)
            }
        },
        confirmButton = { Button({ copyText(ctx, text); onDismiss() }) { Text("Copy") } },
        dismissButton = {
            if (link != null) TextButton({ openLink(ctx, link); onDismiss() }) { Text("Open link") }
            else TextButton(onDismiss) { Text("Close") }
        },
    )
}
