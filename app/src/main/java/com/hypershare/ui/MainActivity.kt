package com.hypershare.ui

import android.Manifest
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import com.hypershare.transfer.Engine
import com.hypershare.transfer.UiState
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private var pendingUris: List<Uri> = emptyList()
    private var pendingBench = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val scanner = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { Engine.startSend(this, pendingUris, it, pendingBench) }
    }

    private val picker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { pendingUris = uris; pendingBench = false; scan() }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        perms.launch(buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray())

        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    val s by Engine.state.collectAsStateWithLifecycle()
                    Screen(
                        s,
                        onReceive = { Engine.startReceive(this) },
                        onSend = { picker.launch(arrayOf("*/*")) },
                        onBench = { pendingUris = emptyList(); pendingBench = true; scan() },
                        onCancel = { Engine.cancel() },
                    )
                }
            }
        }
    }

    private fun scan() = scanner.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the QR on the receiving phone")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    )
}

@Composable
private fun Screen(s: UiState, onReceive: () -> Unit, onSend: () -> Unit, onBench: () -> Unit, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("HyperShare", style = MaterialTheme.typography.headlineMedium)

        if (!s.running) {
            Button(onReceive, Modifier.fillMaxWidth()) { Text("Receive") }
            Button(onSend, Modifier.fillMaxWidth()) { Text("Send files") }
            OutlinedButton(onBench, Modifier.fillMaxWidth()) { Text("Speed test (2 GB, no storage)") }
        } else {
            OutlinedButton(onCancel, Modifier.fillMaxWidth()) { Text("Cancel") }
        }

        Text(s.status)
        s.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        if (s.freqMhz > 0) {
            val slow = s.freqMhz < 5000
            Text(
                "Link: ${s.freqMhz} MHz " + if (slow) "(2.4 GHz: expect ≤15 MB/s)" else "(5 GHz)",
                color = if (slow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
            )
        }

        s.qr?.let { qr ->
            val bmp = remember(qr) { qrBitmap(qr) }
            Image(bmp.asImageBitmap(), "Pairing QR", Modifier.size(280.dp))
        }

        if (s.total > 0) {
            LinearProgressIndicator(
                progress = { (s.done.toDouble() / s.total).toFloat() },
                Modifier.fillMaxWidth(),
            )
            Text("%.0f / %.0f MB".format(s.done / 1e6, s.total / 1e6))
            if (s.running) Text(
                "%.1f MB/s now · %.1f MB/s avg".format(s.mbps, s.avgMbps),
                style = MaterialTheme.typography.titleLarge,
            )
        }
    }
}

private fun qrBitmap(text: String, size: Int = 600): Bitmap {
    val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
    val px = IntArray(size * size) { i -> if (m[i % size, i / size]) Color.BLACK else Color.WHITE }
    return Bitmap.createBitmap(px, size, size, Bitmap.Config.ARGB_8888)
}
