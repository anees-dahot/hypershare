package com.hypershare.ui

import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Inventory2
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
    name: String,
    onSend: () -> Unit,
    onReceive: () -> Unit,
    onBench: () -> Unit,
    onInbox: () -> Unit,
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
                        "This phone: $name",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onSettings) { Icon(Icons.Outlined.Settings, "Settings") }
            }
        }

        item {
            AnimatedVisibility(update.available != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                UpdateBanner(update) { scope.launch { Updater.install(ctx) } }
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ActionTile(Icons.Rounded.ArrowUpward, "Send", "Apps, photos, files", true, Modifier.weight(1f), onSend)
                ActionTile(Icons.Rounded.ArrowDownward, "Receive", "Show your code", false, Modifier.weight(1f), onReceive)
            }
        }

        item {
            Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
                LinkRow(Icons.Outlined.Inventory2, "Received files", "Search, open, install, share", onInbox)
                HorizontalDivider(Modifier.padding(start = 56.dp), color = MaterialTheme.colorScheme.outlineVariant)
                LinkRow(Icons.Outlined.Speed, "Test link speed", "2 GB of test data, nothing saved", onBench)
            }
        }

        item {
            SectionLabel("Recent", Modifier.padding(top = 8.dp)) {
                if (history.isNotEmpty()) TextButton(onInbox, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("See all") }
            }
        }
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
                val recent = history.take(8)
                Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Spacer(Modifier.height(6.dp))
                    recent.forEachIndexed { i, h ->
                        val uri = h.uri?.let(Uri::parse)
                        val sent = h.role == "Send"
                        FileRow(
                            name = h.name, mime = h.mime, uri = uri,
                            meta = "${if (sent) "Sent" else "Received"} · ${fmtBytes(h.size)} · ${fmtAgo(h.time)}",
                            progress = null, done = false,
                            actions = if (uri != null) listOf(RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, uri, h.mime) }) else emptyList(),
                            onClick = uri?.let { { openFile(ctx, it, h.mime) } },
                        )
                        if (i < recent.lastIndex) HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
    }
}

@Composable
private fun LinkRow(icon: ImageVector, title: String, sub: String, onClick: () -> Unit) {
    Surface(onClick = onClick, color = Color.Transparent) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ActionTile(icon: ImageVector, title: String, sub: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val fg = if (filled) c.onPrimary else c.onSurface
    Surface(
        onClick = onClick, interactionSource = src,
        modifier = modifier.pressable(src),
        shape = RoundedCornerShape(22.dp),
        color = if (filled) c.primary else c.surface,
        border = if (filled) null else BorderStroke(1.dp, c.outlineVariant),
    ) {
        Column(Modifier.padding(18.dp).heightIn(min = 120.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Box(
                Modifier.size(44.dp).clip(RoundedCornerShape(13.dp))
                    .background(if (filled) Color.White.copy(alpha = 0.16f) else c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (filled) fg else c.primary) }
            Spacer(Modifier.height(18.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = fg.copy(alpha = if (filled) 0.82f else 0.62f))
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
