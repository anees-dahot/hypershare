package com.hypershare.ui

import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.hypershare.data.Inbox
import com.hypershare.data.Received
import com.hypershare.update.Installer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Everything received so far (Downloads/HyperShare): search, filter by kind, open/install/share/delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InboxScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var reload by remember { mutableIntStateOf(0) }
    val all by produceState<List<Received>?>(null, reload) { value = Inbox.list(ctx) }
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.All) }
    var confirm by remember { mutableStateOf<Received?>(null) }

    val shown = all.orEmpty().filter {
        (kind == Kind.All || kindOf(it.mime, it.name) == kind) &&
            (query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) || it.folder.contains(query.trim(), ignoreCase = true))
    }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            TopRow("Received files", onBack)
            OutlinedTextField(
                query, { query = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("Search by name or folder") },
                leadingIcon = { Icon(Icons.Outlined.Search, null) },
                shape = RoundedCornerShape(14.dp),
            )
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Kind.entries.forEach { k ->
                val n = if (k == Kind.All) all.orEmpty().size else all.orEmpty().count { kindOf(it.mime, it.name) == k }
                if (k == Kind.All || n > 0) FilterChip(
                    selected = kind == k, onClick = { kind = k },
                    label = { Text(if (k == Kind.All) "All $n" else "${k.label} $n") },
                )
            }
        }
        when {
            all == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp) }
            shown.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    if (all!!.isEmpty()) "Nothing received yet.\nFiles you receive are saved in Downloads/HyperShare." else "No files match.",
                    textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            else -> LazyColumn(contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 24.dp)) {
                item {
                    Text(
                        "${shown.size} file${if (shown.size == 1) "" else "s"} · ${fmtBytes(shown.sumOf { it.size })}",
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 8.dp),
                    )
                }
                items(shown, key = { it.uri.toString() }) { r ->
                    Box(Modifier.animateItem()) {
                        FileRow(
                            name = r.name, mime = r.mime, uri = r.uri,
                            meta = buildString {
                                append("${fmtBytes(r.size)} · ${fmtAgo(r.time)}")
                                if (r.folder.isNotEmpty()) append(" · ${r.folder}")
                            },
                            progress = null, done = false,
                            primary = primaryAction(ctx, r.uri, r.mime, r.name) {
                                scope.launch {
                                    val err = withContext(Dispatchers.IO) { runCatching { Installer.install(ctx, listOf(r.uri)) }.exceptionOrNull() }
                                    if (err != null) Toast.makeText(ctx, "Install failed: ${err.message}", Toast.LENGTH_LONG).show()
                                }
                            },
                            actions = listOf(
                                RowAction(Icons.Outlined.Share, "Share") { shareFile(ctx, r.uri, r.mime) },
                                RowAction(Icons.Outlined.Delete, "Delete") { confirm = r },
                            ),
                            onClick = { openFile(ctx, r.uri, r.mime) },
                        )
                    }
                    HorizontalDivider(Modifier.padding(start = 76.dp), color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    confirm?.let { r ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Delete ${r.name}?") },
            text = { Text("This removes the file from your phone. It can't be undone.") },
            confirmButton = {
                Button({
                    val ok = Inbox.delete(ctx, r)
                    if (!ok) Toast.makeText(ctx, "Couldn't delete this file", Toast.LENGTH_SHORT).show()
                    confirm = null; reload++
                }, colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)) { Text("Delete") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Keep") } },
        )
    }
}
