package com.hypershare.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.OpenInNew
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
import com.hypershare.transfer.Phase
import com.hypershare.transfer.Role
import com.hypershare.transfer.UiState
import java.util.Locale

@Composable
fun TransferScreen(s: UiState, onCancel: () -> Unit, onClose: () -> Unit) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(s.phase) { if (s.phase == Phase.Done) haptics.performHapticFeedback(HapticFeedbackType.LongPress) }

    val sending = s.role == Role.Send
    val title = when (s.phase) {
        Phase.Done -> if (s.bench) "Speed test done" else if (sending) "Sent" else "Received"
        Phase.Failed -> if (s.error == "Cancelled") "Cancelled" else "Transfer failed"
        Phase.Connecting, Phase.Preparing -> "Connecting"
        else -> if (s.bench) "Testing speed" else if (sending) "Sending" else "Receiving"
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    AnimatedContent(title, label = "title", transitionSpec = {
                        (fadeIn(tween(250)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(120))
                    }) { Text(it, style = MaterialTheme.typography.headlineSmall) }
                    Spacer(Modifier.width(10.dp))
                    if (s.phase == Phase.Done) DoneBadge(26.dp)
                    Spacer(Modifier.weight(1f))
                    bandLabel(s.freqMhz)?.let { Chip(it.substringBefore(" · ch").substringBefore(" · "), warn = s.freqMhz < 5000) }
                }
            }

            item { SpeedPanel(s) }

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
                        Text("$n / ${s.files.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
                        Spacer(Modifier.height(6.dp))
                        s.files.forEachIndexed { i, f ->
                            val done = f.status == FStatus.Done
                            val pct = if (f.size > 0) f.done.toFloat() / f.size else 1f
                            val uri = f.uri
                            FileRow(
                                name = f.name, mime = f.mime, uri = if (done || sending) uri else null,
                                meta = when (f.status) {
                                    FStatus.Done -> "${fmtBytes(f.size)} · ${extOf(f.name, f.mime)}"
                                    FStatus.Active -> "${fmtBytes(f.done)} of ${fmtBytes(f.size)}"
                                    FStatus.Queued -> "${fmtBytes(f.size)} · waiting"
                                },
                                progress = if (f.status == FStatus.Active) pct else null,
                                done = done,
                                trailing = if (f.status == FStatus.Active) "${(pct * 100).toInt()}%" else null,
                                actions = if (done && uri != null && s.phase == Phase.Done) listOf(
                                    RowAction(Icons.Outlined.OpenInNew, "Open") { openFile(ctx, uri, f.mime) },
                                    RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, uri, f.mime) },
                                ) else emptyList(),
                                onClick = if (done && uri != null) ({ openFile(ctx, uri, f.mime) }) else null,
                            )
                            if (i < s.files.lastIndex) HorizontalDivider(
                                Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                if (!sending && s.phase == Phase.Done) item {
                    Text(
                        "Saved in Downloads/HyperShare",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                }
            }
        }

        Box(Modifier.fillMaxWidth().padding(20.dp)) {
            if (s.active) {
                OutlinedButton(onCancel, Modifier.fillMaxWidth().height(52.dp)) { Text("Cancel") }
            } else {
                Button(onClose, Modifier.fillMaxWidth().height(52.dp)) { Text("Done") }
            }
        }
    }
}

@Composable
private fun SpeedPanel(s: UiState) {
    val shown by animateFloatAsState(s.mbps.toFloat(), tween(450), label = "speed")
    val progress = if (s.total > 0) s.done.toFloat() / s.total else 0f
    val p by animateFloatAsState(progress, tween(300), label = "p")
    val finished = s.phase == Phase.Done

    Panel(Modifier.fillMaxWidth(), padding = 20.dp) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                String.format(Locale.US, "%.1f", if (finished) s.avgMbps.toFloat() else shown),
                style = MaterialTheme.typography.displayMedium,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                if (finished) "MB/s average" else "MB/s",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        SpeedChart(s.samples, Engine.CHART_POINTS, Modifier.fillMaxWidth().height(88.dp))
        Spacer(Modifier.height(16.dp))

        AnimatedVisibility(s.phase == Phase.Connecting || s.phase == Phase.Preparing) {
            LinearProgressIndicator(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)))
        }
        if (s.total > 0 && s.phase != Phase.Connecting) {
            LinearProgressIndicator(
                progress = { p }, Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)),
                trackColor = MaterialTheme.colorScheme.surfaceVariant, drawStopIndicator = {}, gapSize = 0.dp,
            )
            Spacer(Modifier.height(8.dp))
            Row {
                Text(
                    "${fmtBytes(s.done)} of ${fmtBytes(s.total)}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                )
                Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Spacer(Modifier.height(14.dp))
        Row {
            Stat("Average", String.format(Locale.US, "%.1f MB/s", s.avgMbps), Modifier.weight(1f))
            Stat("Peak", String.format(Locale.US, "%.1f MB/s", s.peakMbps), Modifier.weight(1f))
            if (finished) Stat("Time", fmtDuration(s.elapsedMs / 1000), Modifier.weight(1f))
            else Stat("Remaining", fmtDuration(s.etaSec), Modifier.weight(1f))
        }
    }
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(2.dp))
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Numeric))
    }
}
