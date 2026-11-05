package com.hypershare.ui

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
import com.hypershare.data.APK_MIME
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

@Composable
fun TransferScreen(s: UiState, onSendMore: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val scope = rememberCoroutineScope()
    LaunchedEffect(s.phase) { if (s.phase == Phase.Done) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val sending = s.role == Role.Send
    val title = when (s.phase) {
        Phase.Done -> when { s.bench -> "Speed test done"; s.connected -> "Connected"; sending -> "Sent"; else -> "Received" }
        Phase.Failed -> if (s.error == "Cancelled") "Cancelled" else "Transfer failed"
        Phase.Connecting, Phase.Preparing -> "Connecting"
        else -> if (s.bench) "Testing speed" else if (sending) "Sending" else "Receiving"
    }

    fun install(f: FileItem) {
        val parts = if (f.group.isNotEmpty()) s.files.filter { it.group == f.group && it.status == FStatus.Done }.mapNotNull { it.uri }
        else listOfNotNull(f.uri)
        scope.launch {
            val err = withContext(Dispatchers.IO) { runCatching { Installer.install(ctx, parts) }.exceptionOrNull() }
            if (err != null) Toast.makeText(ctx, "Install failed: ${err.message}", Toast.LENGTH_LONG).show()
        }
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            }

            item { SpeedCard(s) }

            s.error?.takeIf { s.phase == Phase.Failed && it != "Cancelled" }?.let { err ->
                item {
                    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                        Text(err, Modifier.fillMaxWidth().padding(14.dp), color = MaterialTheme.colorScheme.onErrorContainer)
                    }
                }
            }
            s.tip?.let { item { TipCard(it) } }

            if (!s.bench && s.files.isNotEmpty()) {
                item {
                    val n = s.files.count { it.status == FStatus.Done }
                    SectionLabel("Files", Modifier.padding(top = 4.dp)) {
                        Text("$n of ${s.files.size} done", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
                        Spacer(Modifier.height(4.dp))
                        s.files.forEachIndexed { i, f ->
                            ItemRow(f, sending, s.active || s.connected, onSkip = { Engine.skip(f.idx) }, onInstall = { install(f) })
                            if (i < s.files.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                }
                if (!sending && s.files.any { it.status == FStatus.Done }) item {
                    Text(
                        "Saved in Downloads/HyperShare",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        // Bottom actions
        Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            val canAdd = sending && s.connected && !s.bench
            val busy = s.phase == Phase.Transferring || s.phase == Phase.Connecting || s.phase == Phase.Preparing
            if (canAdd) OutlinedButton(onSendMore, Modifier.weight(1f).height(52.dp)) {
                Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Send more")
            }
            if (busy) OutlinedButton({ Engine.cancel() }, Modifier.weight(1f).height(52.dp)) { Text("Cancel") }
            else Button(onClose, Modifier.weight(1f).height(52.dp)) { Text(if (s.connected) "Finish" else "Done") }
        }
    }
}

@Composable
private fun ItemRow(f: FileItem, sending: Boolean, live: Boolean, onSkip: () -> Unit, onInstall: () -> Unit) {
    val ctx = LocalContext.current
    val uri = f.uri
    val pct = if (f.size > 0) f.done.toFloat() / f.size else 1f
    val isApk = f.mime == APK_MIME || f.name.endsWith(".apk", true)
    val pending = f.status == FStatus.Queued || f.status == FStatus.Active
    val primary: RowAction? = when {
        f.status != FStatus.Done || uri == null || sending -> null
        isApk -> RowAction(Icons.Outlined.Add, "Install", onInstall)
        f.mime.startsWith("video/") || f.mime.startsWith("audio/") -> RowAction(Icons.Outlined.Add, "Play") { openFile(ctx, uri, f.mime) }
        else -> RowAction(Icons.Outlined.Add, "Open") { openFile(ctx, uri, f.mime) }
    }
    val icons = when {
        pending && live -> listOf(RowAction(Icons.Outlined.Close, "Cancel file", onSkip))
        f.status == FStatus.Done && uri != null && !sending -> listOf(RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, uri, f.mime) })
        else -> emptyList()
    }
    FileRow(
        name = f.name.substringAfterLast('/'),
        mime = f.mime,
        uri = if (f.status == FStatus.Done || sending) uri else null,
        pkg = f.group.ifEmpty { null },
        meta = buildString {
            when (f.status) {
                FStatus.Done -> append("${fmtBytes(f.size)} · ${extOf(f.name, f.mime)}")
                FStatus.Active -> append("${fmtBytes(f.done)} of ${fmtBytes(f.size)}")
                FStatus.Queued -> append("${fmtBytes(f.size)} · waiting")
                FStatus.Skipped -> append("Cancelled")
            }
            if (f.name.contains('/') && f.status != FStatus.Skipped) append(" · ${f.name.substringBeforeLast('/')}")
        },
        progress = if (f.status == FStatus.Active) pct else null,
        trailing = if (f.status == FStatus.Active) "${(pct * 100).toInt()}%" else null,
        done = f.status == FStatus.Done,
        primary = primary,
        actions = icons,
        dim = f.status == FStatus.Skipped,
        onClick = if (f.status == FStatus.Done && uri != null && !sending && !isApk) ({ openFile(ctx, uri, f.mime) }) else null,
    )
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
        Row {
            Text("${fmtBytes(s.done)} of ${fmtBytes(s.total)}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
    }
}
