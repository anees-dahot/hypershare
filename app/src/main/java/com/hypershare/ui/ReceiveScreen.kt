package com.hypershare.ui

import androidx.compose.foundation.selection.toggleable
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.hypershare.data.Prefs
import com.hypershare.transfer.Engine
import com.hypershare.transfer.Phase
import com.hypershare.transfer.UiState

@Composable
fun ReceiveScreen(s: UiState, onBack: () -> Unit, onBand: (only24: Boolean) -> Unit) {
    val ctx = LocalContext.current
    val me = remember { Prefs.deviceName(ctx) }
    val only24 = remember { Prefs.only24(ctx) }

    // Full brightness while the code is on screen: cameras read it faster.
    if (s.qr != null) {
        val window = (ctx as? Activity)?.window
        DisposableEffect(window) {
            val attrs = window?.attributes
            val before = attrs?.screenBrightness
            if (window != null && attrs != null) { attrs.screenBrightness = 1f; window.attributes = attrs }
            onDispose {
                if (window != null && before != null) { val a = window.attributes; a.screenBrightness = before; window.attributes = a }
            }
        }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
    ) {
        TopRow("Receive", onBack)

        Panel(Modifier.fillMaxWidth(), padding = 16.dp) {
            Box(Modifier.fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
                AnimatedContent(
                    s.qr, label = "qr",
                    transitionSpec = { (fadeIn(tween(300)) + scaleIn(tween(300), initialScale = 0.94f)) togetherWith fadeOut(tween(150)) },
                ) { qr ->
                    if (qr == null) QrPlaceholder() else {
                        val bmp = remember(qr) { qrBitmap(qr) }
                        Image(
                            bmp.asImageBitmap(), "Pairing code",
                            // White quiet zone so the code scans in dark mode too.
                            Modifier.fillMaxSize().clip(RoundedCornerShape(14.dp)).background(Color.White).padding(14.dp),
                            filterQuality = FilterQuality.None,
                        )
                    }
                }
            }
            s.code?.let { c ->
                Spacer(Modifier.height(10.dp))
                Text(
                    "Code  " + c.chunked(3).joinToString(" "),
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.align(Alignment.CenterHorizontally),
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                PulseDot()
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        if (s.phase == Phase.Preparing) "Starting Wi-Fi…" else "Ready to receive",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        if (s.discoverable) "Visible nearby as $me" else "Shown as $me",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                s.link?.let { Chip(it.substringBefore(" ·"), warn = s.freqMhz in 1..4999) }
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionLabel("On the other phone")
        Spacer(Modifier.height(8.dp))
        Panel(Modifier.fillMaxWidth()) {
            Step(1, "Open HyperShare and tap Send")
            Step(2, "Pick the files")
            Step(3, when {
                s.discoverable && s.code != null -> "Tap \"$me\" in the Nearby list, scan this QR, or tap Enter code and type ${s.code}"
                s.discoverable -> "Tap \"$me\" in the Nearby list, or scan this code"
                else -> "Scan this code"
            })
        }

        s.tip?.let {
            Spacer(Modifier.height(12.dp))
            TipCard(it)
        }

        // Older / budget phones only have 2.4 GHz Wi-Fi and can't see a 5 GHz group at all.
        if (s.link?.startsWith("Wi-Fi Direct") == true && (only24 || s.freqMhz >= 5000)) {
            Spacer(Modifier.height(8.dp))
            TextButton({ onBand(!only24) }, Modifier.fillMaxWidth()) {
                Text(if (only24) "Using 2.4 GHz for older phones · Switch to 5 GHz" else "Other phone can't find you? Use 2.4 GHz")
            }
        }
        Spacer(Modifier.height(24.dp))
    }

    // A phone found us in its Nearby list (didn't scan the code): ask before letting it in.
    s.request?.let { peer ->
        var always by remember(peer) { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = {},
            icon = { Avatar(peer, 48.dp) },
            title = { Text("$peer wants to send you files") },
            text = {
                Column {
                    Text("Only accept if you know this phone. It found you in its Nearby list.")
                    if (s.requestTrust) Row(
                        Modifier.fillMaxWidth().padding(top = 12.dp).clip(RoundedCornerShape(10.dp))
                            .toggleable(always, role = Role.Checkbox) { always = it },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(always, null)
                        Spacer(Modifier.width(10.dp))
                        Text("Always accept from this phone", style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = { Button({ Engine.respond(true, always) }) { Text("Accept") } },
            dismissButton = { TextButton({ Engine.respond(false) }) { Text("Decline") } },
        )
    }
}

@Composable
fun TopRow(title: String, onBack: () -> Unit, trailing: @Composable (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onBack, Modifier.offset(x = (-12).dp)) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f).offset(x = (-8).dp))
        trailing?.invoke()
    }
}

@Composable
fun TipCard(text: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.primaryContainer) {
        Text(text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

@Composable
private fun Step(n: Int, text: String) {
    Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(24.dp).border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(50)),
            contentAlignment = Alignment.Center,
        ) { Text("$n", style = MaterialTheme.typography.labelMedium) }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun PulseDot() {
    val t = rememberInfiniteTransition(label = "pulse")
    val s by t.animateFloat(1f, 2.4f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "s")
    val a by t.animateFloat(0.45f, 0f, infiniteRepeatable(tween(1400, easing = LinearOutSlowInEasing)), label = "a")
    Box(Modifier.size(12.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(8.dp).scale(s).alpha(a).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.primary))
    }
}

@Composable
private fun QrPlaceholder() {
    val t = rememberInfiniteTransition(label = "ph")
    val a by t.animateFloat(0.35f, 0.7f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "a")
    Box(Modifier.fillMaxSize().padding(12.dp).alpha(a).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant))
}

private fun qrBitmap(text: String): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0))
    val w = m.width; val h = m.height
    val px = IntArray(w * h) { i -> if (m[i % w, i / w]) 0xFF0E1420.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
}
