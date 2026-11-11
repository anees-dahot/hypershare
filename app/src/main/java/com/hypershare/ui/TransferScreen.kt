package com.hypershare.ui

import androidx.compose.material.icons.outlined.ContentCopy
import com.hypershare.data.TEXT_GROUP
import com.hypershare.data.textTitle
import com.hypershare.data.Prefs
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import com.hypershare.transfer.Engine
import com.hypershare.transfer.FStatus
import com.hypershare.transfer.FileItem
import com.hypershare.transfer.Phase
import com.hypershare.transfer.Role
import com.hypershare.transfer.UiState
import com.hypershare.update.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/** One visible row: a single file, or all APK parts of one app shown together. */
private data class Row(val key: String, val parts: List<FileItem>) {
    val first get() = parts.first()
    val outgoing get() = first.outgoing
    val size get() = parts.sumOf { it.size }
    val done get() = parts.sumOf { it.done }
    val status get() = when {
        parts.all { it.status == FStatus.Skipped } -> FStatus.Skipped
        parts.all { it.status == FStatus.Done || it.status == FStatus.Skipped } -> FStatus.Done
        parts.any { it.status == FStatus.Active || it.status == FStatus.Done } -> FStatus.Active
        else -> FStatus.Queued
    }
    val label get() = if (parts.size == 1) first.name.substringAfterLast('/')
    else parts.minBy { it.name.length }.name.removeSuffix(".apk")
}

private fun rows(files: List<FileItem>): List<Row> {
    val out = LinkedHashMap<String, MutableList<FileItem>>()
    files.forEach { f ->
        // APK parts of one app share a row; text snippets share a group marker but are separate items.
        val k = if (f.group.isNotEmpty() && f.group != TEXT_GROUP) "${f.outgoing}:g:${f.group}" else "${f.outgoing}:${f.idx}"
        out.getOrPut(k) { mutableListOf() } += f
    }
    return out.map { (k, v) -> Row(k, v) }
}

@Composable
fun TransferScreen(s: UiState, onSendMore: () -> Unit, onRetry: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.phase) { if (s.phase == Phase.Done) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val all = remember(s.files) { rows(s.files) }
    val incoming = all.filter { !it.outgoing }
    val outgoing = all.filter { it.outgoing }
    val title = when (s.phase) {
        Phase.Done -> when { s.bench -> "Speed test done"; s.connected -> "Connected"; outgoing.isNotEmpty() && incoming.isEmpty() -> "Sent"; else -> "Received" }
        Phase.Failed -> if (s.error == "Cancelled") "Cancelled" else "Transfer failed"
        Phase.Connecting, Phase.Preparing, Phase.Waiting -> "Connecting"
        else -> when {
            s.bench -> "Testing speed"
            incoming.any { it.status != FStatus.Done } && outgoing.any { it.status != FStatus.Done } -> "Transferring"
            incoming.any { it.status != FStatus.Done } -> "Receiving"
            else -> "Sending"
        }
    }

    fun install(r: Row) {
        val uris = r.parts.filter { it.status == FStatus.Done }.mapNotNull { it.uri }
        scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { Installer.install(ctx, uris) }.exceptionOrNull() }
            if (err != null) Toast.makeText(ctx, "Install failed: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(title, label = "title", transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(120))
                    }) { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    Spacer(Modifier.width(10.dp))
                    if (s.phase == Phase.Done && !s.connected) DoneBadge(24.dp)
                    Spacer(Modifier.weight(1f))
                    s.link?.let { Chip(it, warn = s.freqMhz in 1..4999) }
                }
                s.peer?.let {
                    Text(
                        (if (s.connected) "with " else "") + it,
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            item { SpeedCard(s); Spacer(Modifier.height(12.dp)) }

            s.error?.takeIf { s.phase == Phase.Failed && it != "Cancelled" }?.let { err ->
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(err, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
            s.tip?.let { item { TipCard(it); Spacer(Modifier.height(12.dp)) } }

            if (!s.bench) {
                section("Receiving", incoming, s, sending = false, onInstall = ::install)
                section("Sending", outgoing, s, sending = true, onInstall = ::install)
            }
            val saved = incoming.filter { it.status == FStatus.Done && it.first.group != TEXT_GROUP }
            if (!s.bench && saved.isNotEmpty()) item {
                // Same rule as Sink.open: loose photos and videos go to the gallery when that setting is on.
                val inGallery = if (!Prefs.gallery(ctx)) 0 else saved.count { r ->
                    r.parts.size == 1 && !r.first.name.contains('/') && (r.first.mime.startsWith("image/") || r.first.mime.startsWith("video/"))
                }
                Text(
                    when (inGallery) {
                        0 -> "Saved in Downloads/HyperShare"
                        saved.size -> "Saved to your gallery (HyperShare album)"
                        else -> "Photos and videos are in your gallery (HyperShare album), other files in Downloads/HyperShare"
                    },
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp),
                )
            }
        }

        // Bottom actions
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val busy = s.phase == Phase.Transferring || s.phase == Phase.Connecting || s.phase == Phase.Preparing
            if (s.connected && !s.bench) OutlinedButton(onSendMore, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Send more")
            }
            when {
                busy -> OutlinedButton({ Engine.cancel() }, Modifier.weight(1f).height(52.dp)) { Text("Cancel") }
                s.phase == Phase.Failed && s.canRetry -> {
                    OutlinedButton(onClose, Modifier.weight(1f).height(52.dp)) { Text("Close") }
                    Button(onRetry, Modifier.weight(1f).height(52.dp)) {
                        Icon(Icons.Outlined.Refresh, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Try again")
                    }
                }
                else -> Button(onClose, Modifier.weight(1f).height(52.dp)) { Text(if (s.connected) "Finish" else "Done") }
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.section(
    title: String, list: List<Row>, s: UiState, sending: Boolean, onInstall: (Row) -> Unit,
) {
    if (list.isEmpty()) return
    item(key = "h:$title") {
        val n = list.count { it.status == FStatus.Done }
        SectionLabel(title, Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            Text("$n of ${list.size} done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    items(list, key = { it.key }) { r ->
        val first = r == list.first(); val last = r == list.last()
        val shape = RoundedCornerShape(
            topStart = if (first) 16.dp else 0.dp, topEnd = if (first) 16.dp else 0.dp,
            bottomStart = if (last) 16.dp else 0.dp, bottomEnd = if (last) 16.dp else 0.dp,
        )
        Surface(Modifier.fillMaxWidth().animateItem(), shape = shape, color = MaterialTheme.colorScheme.surface) {
            Column {
                if (first) Spacer(Modifier.height(4.dp))
                ItemRow(r, sending, s.active || s.connected, onInstall)
                if (!last) HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
                else Spacer(Modifier.height(4.dp))
            }
        }
    }
    item(key = "s:$title") { Spacer(Modifier.height(14.dp)) }
}

@Composable
private fun ItemRow(r: Row, sending: Boolean, live: Boolean, onInstall: (Row) -> Unit) {
    val ctx = LocalContext.current
    val f = r.first
    val uri = f.uri
    val pct = if (r.size > 0) r.done.toFloat() / r.size else 1f
    val pending = r.status == FStatus.Queued || r.status == FStatus.Active
    val done = r.status == FStatus.Done
    val isText = f.group == TEXT_GROUP
    val text = f.text
    var viewing by remember { mutableStateOf(false) }
    val primary = if (isText || !done || uri == null || sending) null else primaryAction(ctx, uri, f.mime, f.name) { onInstall(r) }
    val icons = when {
        pending && live -> listOf(RowAction(Icons.Outlined.Close, "Cancel file") { r.parts.forEach { Engine.skip(it) } })
        isText && text != null -> listOf(RowAction(Icons.Outlined.ContentCopy, "Copy") { copyText(ctx, text) })
        done && uri != null && !sending && r.parts.size == 1 -> listOf(RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, uri, f.mime) })
        else -> emptyList()
    }
    FileRow(
        name = if (isText) text?.let(::textTitle) ?: "Text" else r.label,
        mime = f.mime,
        uri = if (done || sending) uri else null,
        pkg = if (isText) null else f.group.ifEmpty { null },
        meta = buildString {
            when (r.status) {
                FStatus.Done -> append(if (isText) "${if (text?.let(::linkIn) != null) "Link" else "Text"} · ${fmtBytes(r.size)}" else "${fmtBytes(r.size)} · ${extOf(f.name, f.mime)}")
                FStatus.Active -> append("${fmtBytes(r.done)} of ${fmtBytes(r.size)}")
                FStatus.Queued -> append("${fmtBytes(r.size)} · waiting")
                FStatus.Skipped -> append("Cancelled")
            }
            if (r.parts.size > 1) append(" · ${r.parts.size} parts")
            if (r.parts.any { it.resumed } && r.status != FStatus.Skipped) append(" · resumed")
            if (f.name.contains('/') && r.status != FStatus.Skipped) append(" · ${f.name.substringBeforeLast('/')}")
        },
        progress = if (r.status == FStatus.Active) pct else null,
        trailing = if (r.status == FStatus.Active) "${(pct * 100).toInt()}%" else null,
        done = done,
        primary = primary,
        actions = icons,
        dim = r.status == FStatus.Skipped,
        thumbName = f.name,
        onClick = when {
            isText && text != null -> ({ viewing = true })
            done && uri != null && !sending && kindOf(f.mime, f.name) != Kind.Apps -> ({ openFile(ctx, uri, f.mime) })
            else -> null
        },
    )
    if (viewing && text != null) TextDialog(text) { viewing = false }
}

/** Compact live stats: big number + avg/peak on one line, short chart, one progress line. */
@Composable
private fun SpeedCard(s: UiState) {
    val shown by animateFloatAsState(s.mbps.toFloat(), tween(450), label = "speed")
    val progress = if (s.total > 0) s.done.toFloat() / s.total else 0f
    val p by animateFloatAsState(progress, tween(300), label = "p")
    val idle = s.phase != Phase.Transferring

    Panel(Modifier.fillMaxWidth(), padding = 16.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.US, "%.1f", if (idle) s.avgMbps.toFloat() else shown),
                style = MaterialTheme.typography.headlineLarge.merge(Numeric),
            )
            Spacer(Modifier.width(4.dp))
            Text(
                if (idle) "MB/s avg" else "MB/s", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
            )
            Spacer(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    String.format(Locale.US, "avg %.1f · peak %.1f", s.avgMbps, s.peakMbps),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    if (idle) fmtDuration(s.elapsedMs / 1000) else "${fmtDuration(s.etaSec)} left",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        SpeedChart(s.samples, Engine.CHART_POINTS, Modifier.fillMaxWidth().height(44.dp))
        Spacer(Modifier.height(10.dp))
        if (s.phase == Phase.Connecting || s.phase == Phase.Preparing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        } else {
            LinearProgressIndicator(
                progress = { p }, Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
        Spacer(Modifier.height(6.dp))
        if (s.phase == Phase.Connecting || s.phase == Phase.Preparing) {
            Text(s.step ?: "Connecting…", style = MaterialTheme.typography.bodySmall)
        } else Row {
            Text("${fmtBytes(s.done)} of ${fmtBytes(s.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        s.connectLog?.let {
            Spacer(Modifier.height(4.dp))
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
