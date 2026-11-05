package com.hypershare.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hypershare.data.HistoryItem
import com.hypershare.update.UpdateUi
import com.hypershare.update.Updater
import kotlinx.coroutines.launch

@Composable
fun HomeScreen(
    history: List<HistoryItem>,
    update: UpdateUi,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBench: () -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HyperShare", style = MaterialTheme.typography.headlineSmall)
                    Text(
                        "Direct Wi-Fi transfer, no internet needed",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
            }
        }

        item {
            AnimatedVisibility(
                update.available != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut(),
            ) {
                UpdateBanner(update) { scope.launch { Updater.install(ctx) } }
            }
        }

        item {
            ActionTile(
                Icons.Rounded.ArrowUpward, "Send", "Pick files, then scan the receiver's code",
                filled = true, onClick = onSend,
            )
        }
        item {
            ActionTile(
                Icons.Rounded.ArrowDownward, "Receive", "Show a code for the other phone to scan",
                filled = false, onClick = onReceive,
            )
        }
        item {
            TextButton(onBench, Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Speed, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text("Test link speed")
            }
        }

        item { SectionLabel("Recent", Modifier.padding(top = 8.dp)) }
        if (history.isEmpty()) {
            item {
                Panel(Modifier.fillMaxWidth(), padding = 20.dp) {
                    Text("No transfers yet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Files you send or receive will show up here.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            item {
                Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Spacer(Modifier.height(6.dp))
                    history.take(20).forEachIndexed { i, h ->
                        val uri = h.uri?.let(Uri::parse)
                        val sent = h.role == "Send"
                        FileRow(
                            name = h.name, mime = h.mime, uri = uri,
                            meta = "${if (sent) "Sent" else "Received"} · ${fmtBytes(h.size)} · ${fmtAgo(h.time)}",
                            progress = null, done = false,
                            actions = if (uri != null) listOf(RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, uri, h.mime) }) else emptyList(),
                            onClick = uri?.let { { openFile(ctx, it, h.mime) } },
                        )
                        if (i < minOf(history.size, 20) - 1) HorizontalDivider(
                            Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant,
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, sub: String, filled: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val bg = if (filled) c.primary else c.surface
    val fg = if (filled) c.onPrimary else c.onSurface
    Surface(
        onClick = onClick,
        interactionSource = src,
        modifier = Modifier.fillMaxWidth().pressable(src),
        shape = RoundedCornerShape(20.dp),
        color = bg,
        border = if (filled) null else BorderStroke(1.dp, c.outlineVariant),
    ) {
        Row(Modifier.padding(20.dp).heightIn(min = 72.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(14.dp))
                    .background(if (filled) Color.White.copy(alpha = 0.16f) else c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (filled) fg else c.primary) }
            Spacer(Modifier.width(16.dp))
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = if (filled) 0.82f else 0.62f))
            }
        }
    }
}

@Composable
private fun UpdateBanner(u: UpdateUi, onUpdate: () -> Unit) {
    val rel = u.available ?: return
    Panel(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Version ${rel.version} is available", style = MaterialTheme.typography.titleMedium)
                Text(
                    when {
                        u.installing -> "Installing…"
                        u.progress >= 0 -> "Downloading ${(u.progress * 100).toInt()}%"
                        u.error != null -> u.error
                        else -> fmtBytes(rel.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (u.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (u.progress < 0 && !u.installing) Button(onUpdate) { Text("Update") }
        }
        if (u.progress >= 0) {
            Spacer(Modifier.height(10.dp))
            LinearProgressIndicator(
                progress = { u.progress }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)),
                drawStopIndicator = {}, gapSize = 0.dp,
            )
        }
    }
}
