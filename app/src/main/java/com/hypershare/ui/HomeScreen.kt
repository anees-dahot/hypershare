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
import androidx.compose.material.icons.rounded.ArrowOutward
import androidx.compose.material.icons.rounded.SouthWest
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.ui.text.style.TextOverflow
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp, bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                LogoMark(34.dp)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("HyperShare", style = MaterialTheme.typography.titleLarge)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.tertiary))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "Visible as $name",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                FilledTonalIconButton(onSettings, colors = IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.surface)) {
                    Icon(Icons.Outlined.Settings, "Settings")
                }
            }
        }

        item {
            AnimatedVisibility(update.available != null, Modifier.padding(bottom = 8.dp), enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                UpdateBanner(update) { scope.launch { Updater.install(ctx) } }
            }
        }

        item {
            Row(Modifier.padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionTile(Icons.Rounded.ArrowOutward, "Send", "Apps, photos,\nvideos, files", true, Modifier.weight(1f), onSend)
                ActionTile(Icons.Rounded.SouthWest, "Receive", "Show your QR\nor code", false, Modifier.weight(1f), onReceive)
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LinkTile(Icons.Outlined.Inventory2, "Received", "Open, install, share", Modifier.weight(1f), onInbox)
                LinkTile(Icons.Outlined.Speed, "Speed test", "No files saved", Modifier.weight(1f), onBench)
            }
        }

        item {
            SectionLabel("Recent", Modifier.padding(top = 14.dp, bottom = 6.dp)) {
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
            val recent = history.take(8)
            // One lazy item per row (keyed) so rows animate and recycle; corners rounded on the ends only.
            itemsIndexed(recent, key = { i, h -> "${h.time}:${h.name}:$i" }) { i, h ->
                val shape = RoundedCornerShape(
                    topStart = if (i == 0) 22.dp else 4.dp, topEnd = if (i == 0) 22.dp else 4.dp,
                    bottomStart = if (i == recent.lastIndex) 22.dp else 4.dp, bottomEnd = if (i == recent.lastIndex) 22.dp else 4.dp,
                )
                Surface(Modifier.fillMaxWidth().animateItem(), shape = shape, color = MaterialTheme.colorScheme.surface) {
                    val uri = h.uri?.let(Uri::parse)
                    val sent = h.role == "Send"
                    val text = h.text
                    val dir = if (sent) "Sent" else "Received"
                    if (text != null) FileRow(
                        name = textTitle(text), mime = h.mime, uri = null,
                        meta = "$dir · ${if (linkIn(text) != null) "Link" else "Text"} · ${fmtAgo(h.time)}",
                        progress = null, done = false,
                        actions = listOf(RowAction(Icons.Outlined.ContentCopy, "Copy") { copyText(ctx, text) }),
                        thumbName = h.name,
                        onClick = { viewing = text },
                    ) else FileRow(
                        name = h.name, mime = h.mime, uri = uri,
                        meta = "$dir · ${fmtBytes(h.size)} · ${fmtAgo(h.time)}",
                        progress = null, done = false,
                        actions = if (uri != null) listOf(RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, uri, h.mime) }) else emptyList(),
                        onClick = uri?.let { { openFile(ctx, it, h.mime) } },
                    )
                }
            }
        }
    }
}

/** App mark: volt square, ink bolt-arrow. */
@Composable
fun LogoMark(size: androidx.compose.ui.unit.Dp) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(Volt),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.ArrowOutward, null, Modifier.size(size * 0.62f), tint = Ink) }
}

@Composable
private fun LinkTile(icon: ImageVector, title: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    val src = remember { MutableInteractionSource() }
    Surface(
        onClick = onClick, interactionSource = src, modifier = modifier.pressable(src),
        shape = RoundedCornerShape(22.dp), color = MaterialTheme.colorScheme.surface,
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.onSurface) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
        }
    }
}

/** Big home tile. Send = volt on ink text; Receive = inverse (ink in light, paper in dark). */
@Composable
private fun ActionTile(icon: ImageVector, title: String, sub: String, filled: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    // Receive: solid ink in light mode; raised grey in dark so only Send glows.
    val bg = if (filled) Volt else if (dark) c.surfaceContainerHighest else Ink
    val fg = if (filled) Ink else Color.White
    Surface(
        onClick = onClick, interactionSource = src,
        modifier = modifier.pressable(src),
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
    ) {
        Column(Modifier.fillMaxWidth().padding(18.dp).heightIn(min = 168.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Text(title, style = MaterialTheme.typography.headlineMedium, color = fg, modifier = Modifier.weight(1f))
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(50)).background(if (filled) Ink else Volt),
                    contentAlignment = Alignment.Center,
                ) { Icon(icon, null, Modifier.size(22.dp), tint = if (filled) Volt else Ink) }
            }
            Spacer(Modifier.weight(1f))
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = fg.copy(alpha = 0.72f))
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
