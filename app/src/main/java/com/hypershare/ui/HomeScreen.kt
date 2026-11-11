package com.hypershare.ui

import androidx.compose.material.icons.outlined.ContentCopy
import com.hypershare.data.textTitle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.material.icons.outlined.History
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
    var viewing by remember { mutableStateOf<String?>(null) }
    viewing?.let { TextDialog(it) { viewing = null } }
    val scope = rememberCoroutineScope()
    LazyColumn(
        Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("HyperShare", style = MaterialTheme.typography.headlineMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Visible as $name",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalIconButton(onSettings, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.Outlined.Settings, "Settings")
                }
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
                HorizontalDivider(Modifier.padding(start = 70.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
                Panel(Modifier.fillMaxWidth(), padding = 8.dp) {
                    EmptyState(Icons.Outlined.History, "No transfers yet", "Files you send or receive will show up here.")
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
                        val text = h.text
                        if (text != null) FileRow(
                            name = textTitle(text), mime = h.mime, uri = null,
                            meta = "${if (sent) "Sent" else "Received"} · ${if (linkIn(text) != null) "Link" else "Text"} · ${fmtAgo(h.time)}",
                            progress = null, done = false,
                            actions = listOf(RowAction(Icons.Outlined.ContentCopy, "Copy") { copyText(ctx, text) }),
                            thumbName = h.name,
                            onClick = { viewing = text },
                        ) else FileRow(
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
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
            Spacer(Modifier.width(14.dp))
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
    val fg = if (filled) Color.White else c.onSurface
    Surface(
        onClick = onClick, interactionSource = src,
        modifier = modifier.pressable(src),
        shape = MaterialTheme.shapes.extraLarge,
        color = if (filled) Color.Transparent else c.surface,
        border = if (filled) null else BorderStroke(1.dp, c.outlineVariant),
        shadowElevation = if (filled) 6.dp else 0.dp,
    ) {
        Column(
            Modifier.fillMaxWidth().then(if (filled) Modifier.background(BrandGradient) else Modifier).padding(20.dp).heightIn(min = 136.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Box(
                Modifier.size(48.dp).clip(RoundedCornerShape(15.dp))
                    .background(if (filled) Color.White.copy(alpha = 0.16f) else c.primaryContainer),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = if (filled) fg else c.primary) }
            Spacer(Modifier.height(22.dp))
            Text(title, style = MaterialTheme.typography.titleLarge, color = fg)
            Spacer(Modifier.height(2.dp))
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
