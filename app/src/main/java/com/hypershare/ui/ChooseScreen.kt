package com.hypershare.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhoneAndroid
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.hypershare.p2p.Nearby
import com.hypershare.p2p.NearbyPeer

/**
 * Step 2 of sending: pick who gets the files. Receivers in Wi-Fi Direct mode show up here by name;
 * scanning their code still works for everything else.
 */
@Composable
fun ChooseScreen(
    count: Int,
    bytes: Long,
    bench: Boolean,
    onPeer: (NearbyPeer) -> Unit,
    onScan: () -> Unit,
    onBack: () -> Unit,
) {
    val ctx = LocalContext.current
    val nearby = remember { Nearby(ctx) }
    val peers by nearby.peers.collectAsState()
    LaunchedEffect(Unit) { runCatching { nearby.run() } }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            TopRow(if (bench) "Test link speed" else "Send to", onBack)
            Text(
                if (bench) "2 GB of test data, nothing is saved" else "$count item${if (count == 1) "" else "s"} · ${fmtBytes(bytes)}",
                style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel("Nearby") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PulseDot(); Spacer(Modifier.width(8.dp))
                    Text("Searching", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Spacer(Modifier.height(8.dp))
        }

        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (peers.isEmpty()) item {
                Panel(Modifier.fillMaxWidth(), padding = 20.dp) {
                    Radar(Modifier.align(Alignment.CenterHorizontally).padding(vertical = 8.dp))
                    Spacer(Modifier.height(12.dp))
                    Text("Looking for phones…", style = MaterialTheme.typography.titleMedium, modifier = Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "On the other phone, open HyperShare and tap Receive. It appears here within a few seconds. " +
                            "If it doesn't (for example in Hotspot mode), scan its code instead.",
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
            items(peers, key = { it.address }) { p ->
                AnimatedVisibility(true, enter = fadeIn() + expandVertically()) {
                    Surface(
                        onClick = { onPeer(p) }, modifier = Modifier.fillMaxWidth(),
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Avatar(p.name, 48.dp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(p.name, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "Wi-Fi Direct" + when { p.freq <= 0 -> ""; p.freq < 5000 -> " · 2.4 GHz"; else -> " · 5 GHz" },
                                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Button({ onPeer(p) }, Modifier.height(36.dp), contentPadding = PaddingValues(horizontal = 18.dp)) {
                                Text(if (bench) "Test" else "Send")
                            }
                        }
                    }
                }
            }
        }

        OutlinedButton(onScan, Modifier.fillMaxWidth().padding(20.dp).height(54.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp))
            Text("Scan a code instead", style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Expanding rings around a phone icon while discovery runs. */
@Composable
private fun Radar(modifier: Modifier = Modifier) {
    val c = MaterialTheme.colorScheme.primary
    val t = rememberInfiniteTransition(label = "radar")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = LinearEasing)), label = "p")
    Box(modifier.size(120.dp), contentAlignment = Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.matchParentSize()) {
            for (i in 0 until 3) {
                val f = (p + i / 3f) % 1f
                drawCircle(c.copy(alpha = (1f - f) * 0.35f), radius = size.minDimension / 2f * (0.3f + 0.7f * f),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx()))
            }
        }
        Box(
            Modifier.size(44.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Outlined.PhoneAndroid, null, tint = c) }
    }
}
