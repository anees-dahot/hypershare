package com.hypershare.ui

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Picked(val uri: Uri, val name: String, val size: Long, val mime: String)

private fun describe(ctx: Context, uri: Uri): Picked {
    var name = "file"; var size = 0L
    ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use {
        if (it.moveToFirst()) { name = it.getString(0) ?: name; if (!it.isNull(1)) size = it.getLong(1) }
    }
    return Picked(uri, name, size, ctx.contentResolver.getType(uri) ?: "application/octet-stream")
}

/** Shows the picked files before pairing; lets the user drop some or add more. */
@Composable
fun ReviewScreen(uris: List<Uri>, onRemove: (Uri) -> Unit, onAddMore: () -> Unit, onScan: () -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val files by produceState(emptyList<Picked>(), uris) {
        value = withContext(Dispatchers.IO) { uris.map { describe(ctx, it) } }
    }
    val total = files.sumOf { it.size }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            TopRow("Send", onBack) {
                TextButton(onAddMore) {
                    Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp)); Text("Add")
                }
            }
            Text(
                "${uris.size} file${if (uris.size == 1) "" else "s"} · ${fmtBytes(total)}",
                style = MaterialTheme.typography.headlineSmall,
            )
            Spacer(Modifier.height(16.dp))
        }
        // Box takes the free space so the button stays at the bottom; the panel only wraps its rows.
        Box(Modifier.weight(1f).padding(horizontal = 20.dp)) {
        Panel(Modifier.fillMaxWidth(), padding = 0.dp) {
            LazyColumn(contentPadding = PaddingValues(vertical = 6.dp)) {
                items(files, key = { it.uri.toString() }) { f ->
                    Box(Modifier.animateItem()) {
                        FileRow(
                            name = f.name, mime = f.mime, uri = f.uri,
                            meta = "${fmtBytes(f.size)} · ${extOf(f.name, f.mime)}",
                            progress = null, done = false,
                            actions = listOf(RowAction(Icons.Outlined.Close, "Remove") { onRemove(f.uri) }),
                        )
                    }
                }
            }
        }
        }
        Button(
            onScan,
            Modifier.fillMaxWidth().padding(20.dp).height(56.dp),
            enabled = uris.isNotEmpty(),
        ) {
            Icon(Icons.Outlined.QrCodeScanner, null); Spacer(Modifier.width(10.dp))
            Text("Scan receiver's code", style = MaterialTheme.typography.titleMedium)
        }
    }
}
