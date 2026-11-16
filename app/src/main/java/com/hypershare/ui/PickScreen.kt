package com.hypershare.ui

import androidx.compose.material.icons.outlined.EditNote
import androidx.compose.material.icons.outlined.ContentPaste
import com.hypershare.data.textEntity
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hypershare.data.Entity
import com.hypershare.data.MediaKind
import com.hypershare.data.MediaRepo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val TABS = listOf("Apps", "Photos", "Videos", "Music", "Files")

/**
 * Built-in chooser: apps, photos, videos, music, plus files/folders via the system picker.
 * [selected] is owned by the activity so it survives screen switches.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PickScreen(
    selected: SnapshotStateMap<String, Entity>,
    docs: SnapshotStateList<Entity>,
    adding: Boolean,
    onBack: () -> Unit,
    onSend: () -> Unit,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    // Text snippets (a note, a link, the clipboard) join the selection like any picked file.
    fun addText(t: String) {
        if (t.isBlank()) return
        val e = textEntity(t)
        if (docs.none { it.key == e.key }) docs.add(0, e)
        selected[e.key] = e
    }
    fun paste() {
        val clip = runCatching {
            ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip
                ?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
        }.getOrNull()
        if (clip.isNullOrBlank()) Toast.makeText(ctx, "Clipboard is empty", Toast.LENGTH_SHORT).show() else addText(clip)
    }
    var writing by rememberSaveable { mutableStateOf(false) }
    if (writing) {
        var draft by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("Send text") },
            text = {
                OutlinedTextField(
                    draft, { draft = it }, Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 280.dp),
                    placeholder = { Text("A note, a link, an address…") },
                )
            },
            confirmButton = { Button({ addText(draft); writing = false }, enabled = draft.isNotBlank()) { Text("Add") } },
            dismissButton = { TextButton({ writing = false }) { Text("Cancel") } },
        )
    }

    fun hasMedia() = mediaPerms().any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    var granted by remember { mutableStateOf(hasMedia()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasMedia() }
    // Ask once: without this a denied prompt comes back on every rotation.
    var asked by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(Unit) { if (!granted && !asked) { asked = true; permLauncher.launch(mediaPerms()) } }

    val apps by produceState<List<Entity>?>(null) { value = runCatching { MediaRepo.apps(ctx) }.getOrDefault(emptyList()) }
    val photos by produceState<List<Entity>?>(null, granted) { value = if (granted) MediaRepo.media(ctx, MediaKind.Photos) else emptyList() }
    val videos by produceState<List<Entity>?>(null, granted) { value = if (granted) MediaRepo.media(ctx, MediaKind.Videos) else emptyList() }
    val music by produceState<List<Entity>?>(null, granted) { value = if (granted) MediaRepo.media(ctx, MediaKind.Music) else emptyList() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            // A provider (cloud drive, another app) can fail or deny the query: say so instead of crashing.
            val picked = try { MediaRepo.documents(ctx, uris) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Toast.makeText(ctx, "Couldn't read those files", Toast.LENGTH_SHORT).show(); return@launch
            }
            picked.forEach { e ->
                if (docs.none { it.key == e.key }) docs.add(0, e)
                selected[e.key] = e
            }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val e = try { MediaRepo.folder(ctx, tree) } catch (e: CancellationException) { throw e } catch (e: Exception) {
                Toast.makeText(ctx, "Couldn't read that folder", Toast.LENGTH_SHORT).show(); return@launch
            }
            if (e.parts.isEmpty()) { Toast.makeText(ctx, "That folder is empty", Toast.LENGTH_SHORT).show(); return@launch }
            if (docs.none { it.key == e.key }) docs.add(0, e)
            selected[e.key] = e
        }
    }

    val pager = rememberPagerState(initialPage = 1) { TABS.size }
    var showSelection by remember { mutableStateOf(false) }
    fun toggle(e: Entity) { if (selected.containsKey(e.key)) selected.remove(e.key) else selected[e.key] = e }

    Column(Modifier.fillMaxSize().systemBarsPadding()) {
        Column(Modifier.padding(horizontal = 20.dp)) {
            TopRow(if (adding) "Add files" else "Choose files", onBack)
        }
        PrimaryTabRow(
            selectedTabIndex = pager.currentPage,
            containerColor = MaterialTheme.colorScheme.background,
            divider = { HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) },
        ) {
            TABS.forEachIndexed { i, t ->
                Tab(
                    selected = pager.currentPage == i,
                    onClick = { scope.launch { pager.animateScrollToPage(i) } },
                    text = { Text(t, maxLines = 1) },
                    selectedContentColor = MaterialTheme.colorScheme.primary,
                    unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        HorizontalPager(pager, Modifier.weight(1f), beyondViewportPageCount = 1) { page ->
            when (page) {
                0 -> AppsTab(apps, selected, ::toggle)
                1 -> if (!granted) NeedAccess { permLauncher.launch(mediaPerms()) } else MediaTab(MediaKind.Photos, photos, selected, pager.currentPage == 1, ::toggle)
                2 -> if (!granted) NeedAccess { permLauncher.launch(mediaPerms()) } else MediaTab(MediaKind.Videos, videos, selected, pager.currentPage == 2, ::toggle)
                3 -> if (!granted) NeedAccess { permLauncher.launch(mediaPerms()) } else MediaTab(MediaKind.Music, music, selected, pager.currentPage == 3, ::toggle)
                else -> FilesTab(docs, selected, ::toggle, onFiles = { filePicker.launch(arrayOf("*/*")) }, onFolder = { folderPicker.launch(null) }, onText = { writing = true }, onPaste = ::paste)
            }
        }
        SelectionBar(selected, adding, onOpen = { showSelection = true }, onSend = onSend)
    }

    if (showSelection) ModalBottomSheet({ showSelection = false }, containerColor = MaterialTheme.colorScheme.surface) {
        Text(
            "Selected", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, bottom = 8.dp),
        )
        LazyColumn(Modifier.heightIn(max = 480.dp)) {
            items(selected.values.toList(), key = { it.key }) { e ->
                FileRow(
                    name = e.label, mime = e.mime, uri = e.thumb, pkg = e.pkg,
                    meta = metaOf(e), progress = null, done = false,
                    actions = listOf(RowAction(Icons.Outlined.Close, "Remove") { selected.remove(e.key) }),
                )
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

private fun metaOf(e: Entity) = buildString {
    append(fmtBytes(e.size))
    if (e.parts.size > 1) append(" · ${e.parts.size} files")
    if (e.durationMs > 0) append(" · ${fmtClock(e.durationMs)}")
}

fun fmtClock(ms: Long): String {
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}

@Composable
private fun SelectionBar(selected: Map<String, Entity>, adding: Boolean, onOpen: () -> Unit, onSend: () -> Unit) {
    AnimatedVisibility(selected.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Surface(
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        ) {
            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f).clip(RoundedCornerShape(10.dp)).clickable(onClick = onOpen).padding(6.dp)) {
                    val n = selected.size
                    Text("$n selected", style = MaterialTheme.typography.titleMedium)
                    Text(
                        fmtBytes(selected.values.sumOf { it.size }) + " · tap to review",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Button(onSend, Modifier.height(48.dp), contentPadding = PaddingValues(horizontal = 28.dp)) {
                    Text(if (adding) "Add" else "Send", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
}

@Composable
private fun Empty(text: String) = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    EmptyState(Icons.Outlined.SearchOff, text)
}

@Composable
private fun NeedAccess(onAllow: () -> Unit) = Column(
    Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally,
) {
    Text("Allow access to show your photos, videos and music.", textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(16.dp))
    Button(onAllow) { Text("Allow access") }
}

/** Blue check in the corner; outlined circle when not selected. */
@Composable
private fun CheckMark(on: Boolean, onDark: Boolean) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier.size(22.dp).clip(RoundedCornerShape(50))
            .background(if (on) c.primary else if (onDark) Color.Black.copy(alpha = 0.25f) else Color.Transparent)
            .border(1.5.dp, if (on) c.primary else if (onDark) Color.White else c.outline, RoundedCornerShape(50)),
        contentAlignment = Alignment.Center,
    ) { if (on) Icon(Icons.Rounded.Check, null, Modifier.size(15.dp), tint = c.onPrimary) }
}

private enum class Sort(val label: String) { Newest("Newest"), Oldest("Oldest"), Largest("Largest"), Longest("Longest"), Name("Name A–Z") }

private fun List<Entity>.ordered(sort: Sort) = when (sort) {
    Sort.Newest -> this // the query already returns newest first
    Sort.Oldest -> asReversed()
    Sort.Largest -> sortedByDescending { it.size }
    Sort.Longest -> sortedByDescending { it.durationMs }
    Sort.Name -> sortedBy { it.label.lowercase() }
}

/** A storage folder holding media of one kind, as in a video player's folder list. */
private class Folder(val path: String, val name: String, val items: List<Entity>, val size: Long)

/** "1 video", "24 videos". */
private fun counted(n: Int, plural: String) = "$n ${if (n == 1) plural.dropLast(1) else plural}"

private fun folderName(path: String) = path.substringAfterLast('/').ifBlank { "Internal storage" }

private fun foldersOf(list: List<Entity>): List<Folder> =
    list.groupBy { it.folder }
        .map { (path, items) -> Folder(path, folderName(path), items, items.sumOf { it.size }) }
        .sortedBy { it.name.lowercase() }

/**
 * One media tab: search by name, browse everything or folder by folder, sort, select all.
 * [active] = this is the page on screen (neighbouring pages stay composed and must not grab Back).
 */
@Composable
private fun MediaTab(kind: MediaKind, list: List<Entity>?, selected: SnapshotStateMap<String, Entity>, active: Boolean, toggle: (Entity) -> Unit) {
    if (list == null) return Loading()
    val noun = when (kind) { MediaKind.Photos -> "photos"; MediaKind.Videos -> "videos"; MediaKind.Music -> "songs" }
    if (list.isEmpty()) return Empty("No $noun found.")
    val ctx = LocalContext.current
    val prefs = remember { ctx.getSharedPreferences("picker", 0) }
    var q by rememberSaveable { mutableStateOf("") }
    var sort by rememberSaveable { mutableStateOf(Sort.Newest) }
    // Videos open on folders, like a video player; the choice is remembered per tab.
    var byFolder by remember { mutableStateOf(prefs.getBoolean("folders_$kind", kind == MediaKind.Videos)) }
    var open by rememberSaveable { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    val folders = remember(list) { foldersOf(list) }
    val query = q.trim()
    val folder = if (byFolder && query.isEmpty()) folders.firstOrNull { it.path == open } else null
    val listing = byFolder && query.isEmpty() && folder == null // the folder list itself is on screen
    val shown = remember(list, folder, query, sort) {
        when {
            // Search always looks everywhere, whatever view is on.
            query.isNotEmpty() -> list.filter { it.label.contains(query, true) || folderName(it.folder).contains(query, true) }
            folder != null -> folder.items
            else -> list
        }.ordered(sort)
    }
    BackHandler(active && (query.isNotEmpty() || folder != null)) { if (query.isNotEmpty()) q = "" else open = null }

    Column {
        OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp), singleLine = true,
            placeholder = { Text("Search ${counted(list.size, noun)}") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            trailingIcon = { if (q.isNotEmpty()) IconButton({ q = "" }) { Icon(Icons.Outlined.Close, "Clear search") } },
            shape = RoundedCornerShape(14.dp),
        )
        Row(Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(start = 12.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            when {
                query.isNotEmpty() -> Text(
                    "${shown.size} found", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                folder != null -> {
                    IconButton({ open = null }) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "All folders") }
                    Column(Modifier.weight(1f)) {
                        Text(folder.name, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${counted(folder.items.size, noun)} · ${fmtBytes(folder.size)}",
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
                else -> Row(Modifier.weight(1f).padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    fun view(f: Boolean) { byFolder = f; open = null; prefs.edit().putBoolean("folders_$kind", f).apply() }
                    FilterChip(!byFolder, { view(false) }, { Text("All") })
                    FilterChip(byFolder, { view(true) }, { Text("Folders (${folders.size})") })
                }
            }
            if (!listing) {
                Box {
                    TextButton({ menu = true }) { Text(sort.label) }
                    DropdownMenu(menu, { menu = false }) {
                        Sort.entries.filter { it != Sort.Longest || kind != MediaKind.Photos }
                            .forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { sort = o; menu = false }) }
                    }
                }
                val allOn = shown.isNotEmpty() && shown.all { selected.containsKey(it.key) }
                TextButton({ if (allOn) shown.forEach { selected.remove(it.key) } else shown.forEach { selected[it.key] = it } }, enabled = shown.isNotEmpty()) {
                    Text(if (allOn) "Clear" else "Select all")
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when {
                listing -> FolderList(kind, noun, folders, selected) { open = it.path }
                shown.isEmpty() -> Empty("Nothing matches \"$query\".")
                kind == MediaKind.Photos -> MediaGrid(shown, selected, 3, toggle)
                kind == MediaKind.Videos -> VideoList(shown, selected, showFolder = folder == null, toggle)
                else -> MusicList(shown, selected, toggle)
            }
        }
    }
}

/** Thumbnail from the shared cache, decoded off the main thread; null until ready (or undecodable). */
@Composable
private fun rememberThumb(e: Entity): Bitmap? {
    val ctx = LocalContext.current
    val bmp by produceState(gridThumbs.get(e.key), e.key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            e.thumb?.let { thumbOf(ctx, it, e.mime, 256) }
        }?.also { gridThumbs.put(e.key, it) }
    }
    return bmp
}

/** Folders with a cover, a count, and a check that takes the whole folder in one tap. */
@Composable
private fun FolderList(kind: MediaKind, noun: String, folders: List<Folder>, selected: SnapshotStateMap<String, Entity>, onOpen: (Folder) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(folders, key = { it.path }) { f ->
            val picked = f.items.count { selected.containsKey(it.key) }
            val all = picked == f.items.size
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(f) }.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(60.dp).clip(RoundedCornerShape(16.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Folder, null, tint = MaterialTheme.colorScheme.primary)
                    if (kind != MediaKind.Music) rememberThumb(f.items.first())?.let {
                        Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${counted(f.items.size, noun)} · ${fmtBytes(f.size)}",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                    )
                    if (picked > 0) Text("$picked selected", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    // Two folders can share a name (WhatsApp Video in two places): show where it is.
                    else if (f.path.contains('/')) Text(
                        f.path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).clickable {
                        if (all) f.items.forEach { selected.remove(it.key) } else f.items.forEach { selected[it.key] = it }
                    },
                    contentAlignment = Alignment.Center,
                ) { CheckMark(all, false) }
            }
        }
    }
}

/** Videos as rows: you pick a video by its name, not by a tiny square of its first frame. */
@Composable
private fun VideoList(list: List<Entity>, selected: Map<String, Entity>, showFolder: Boolean, toggle: (Entity) -> Unit) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 4.dp)) {
        items(list, key = { it.key }) { e ->
            val on = selected.containsKey(e.key)
            val bg by animateFloatAsState(if (on) 0.5f else 0f, label = "bg")
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = bg))
                    .clickable { toggle(e) }.padding(start = 16.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.width(116.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Outlined.Movie, null, tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                    rememberThumb(e)?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                    if (e.durationMs > 0) Text(
                        fmtClock(e.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp)
                            .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.6f))
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label, style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), maxLines = 4, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(2.dp))
                    Text(
                        listOfNotNull(
                            fmtBytes(e.size), if (showFolder) folderName(e.folder) else null, if (e.date > 0) fmtAgo(e.date) else null,
                        ).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton({ e.thumb?.let { openFile(ctx, it, e.mime) } }) {
                    Icon(Icons.Outlined.PlayArrow, "Play to check", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CheckMark(on, false)
                Spacer(Modifier.width(16.dp))
            }
        }
    }
}

@Composable
private fun AppsTab(apps: List<Entity>?, selected: Map<String, Entity>, toggle: (Entity) -> Unit) {
    var q by rememberSaveable { mutableStateOf("") }
    val shown = remember(apps, q) { apps?.filter { q.isBlank() || it.label.contains(q.trim(), ignoreCase = true) } }
    Column {
        if (!apps.isNullOrEmpty()) OutlinedTextField(
            q, { q = it }, Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), singleLine = true,
            placeholder = { Text("Search ${apps.size} apps") },
            leadingIcon = { Icon(Icons.Outlined.Search, null) },
            shape = RoundedCornerShape(14.dp),
        )
        Box(Modifier.weight(1f)) { AppsGrid(shown, selected, toggle) }
    }
}

@Composable
private fun AppsGrid(apps: List<Entity>?, selected: Map<String, Entity>, toggle: (Entity) -> Unit) {
    if (apps == null) return Loading()
    if (apps.isEmpty()) return Empty("No matching apps.")
    LazyVerticalGrid(GridCells.Fixed(4), Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp)) {
        items(apps, key = { it.key }) { e ->
            val on = selected.containsKey(e.key)
            val bg by animateFloatAsState(if (on) 1f else 0f, label = "bg")
            Column(
                Modifier.padding(4.dp).clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = bg))
                    .clickable { toggle(e) }.padding(vertical = 10.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    ItemThumb(null, e.mime, e.label, e.pkg, 46.dp)
                    Box(Modifier.align(Alignment.TopEnd).offset(6.dp, (-6).dp)) { if (on) CheckMark(true, false) }
                }
                Spacer(Modifier.height(6.dp))
                Text(e.label, style = MaterialTheme.typography.labelSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(fmtBytes(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Sized in bytes, not entries: 150 thumbnails at 256 px would pin ~38 MB on a low-RAM phone. */
private val gridThumbs = object : LruCache<String, Bitmap>(16 * 1024 * 1024) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/** Tap selects; press and hold opens the photo or video to check it first. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MediaGrid(list: List<Entity>, selected: Map<String, Entity>, cols: Int, toggle: (Entity) -> Unit) {
    val ctx = LocalContext.current
    LazyVerticalGrid(
        GridCells.Fixed(cols), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(list, key = { it.key }) { e ->
            val on = selected.containsKey(e.key)
            val inset by animateDpAsState(if (on) 10.dp else 0.dp, spring(stiffness = 700f), label = "inset")
            val bmp = rememberThumb(e)
            Box(
                Modifier.aspectRatio(1f)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .combinedClickable(onLongClick = { e.thumb?.let { openFile(ctx, it, e.mime) } }) { toggle(e) },
            ) {
                Box(Modifier.fillMaxSize().padding(inset).clip(RoundedCornerShape(if (on) 8.dp else 0.dp)), contentAlignment = Alignment.Center) {
                    // Shown until (or instead of, if the phone can't decode it) the thumbnail.
                    Icon(
                        if (e.mime.startsWith("video/")) Icons.Outlined.Movie else Icons.Outlined.Image, null,
                        tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.5f),
                    )
                    bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                if (e.durationMs > 0) Text(
                    fmtClock(e.durationMs), color = Color.White, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.align(Alignment.BottomStart).padding(inset + 6.dp)
                        .clip(RoundedCornerShape(4.dp)).background(Color.Black.copy(alpha = 0.55f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
                Box(Modifier.align(Alignment.TopEnd).padding(inset + 6.dp)) { CheckMark(on, true) }
            }
        }
    }
}

@Composable
private fun MusicList(list: List<Entity>, selected: Map<String, Entity>, toggle: (Entity) -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 8.dp)) {
        items(list, key = { it.key }) { e ->
            val on = selected.containsKey(e.key)
            Row(
                Modifier.fillMaxWidth().clickable { toggle(e) }.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(44.dp).clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Outlined.MusicNote, null, tint = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(e.label.substringBeforeLast('.'), style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Medium), maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Text(metaOf(e), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                CheckMark(on, false)
            }
        }
    }
}

@Composable
private fun FilesTab(
    docs: List<Entity>, selected: Map<String, Entity>, toggle: (Entity) -> Unit,
    onFiles: () -> Unit, onFolder: () -> Unit, onText: () -> Unit, onPaste: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(Icons.Outlined.FileOpen, "Browse files", Modifier.weight(1f), onFiles)
                BigButton(Icons.Outlined.CreateNewFolder, "Send a folder", Modifier.weight(1f), onFolder)
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(Icons.Outlined.EditNote, "Write text", Modifier.weight(1f), onText)
                BigButton(Icons.Outlined.ContentPaste, "Paste clipboard", Modifier.weight(1f), onPaste)
            }
        }
        if (docs.isNotEmpty()) {
            item { SectionLabel("Picked", Modifier.padding(top = 10.dp)) }
            items(docs, key = { it.key }) { e ->
                val on = selected.containsKey(e.key)
                Panel(Modifier.fillMaxWidth().clickable { toggle(e) }, padding = 0.dp) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.weight(1f)) {
                            FileRow(
                                name = e.label, mime = e.mime, uri = e.thumb, meta = metaOf(e),
                                progress = null, done = false, actions = emptyList(),
                                thumbName = e.parts.singleOrNull()?.takeIf { it.text != null }?.name ?: e.label,
                            )
                        }
                        CheckMark(on, false)
                        Spacer(Modifier.width(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun BigButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick, modifier = modifier.height(96.dp), shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

typealias SnapshotStateList<T> = androidx.compose.runtime.snapshots.SnapshotStateList<T>
