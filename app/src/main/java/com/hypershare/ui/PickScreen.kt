package com.hypershare.ui

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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.CreateNewFolder
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Movie
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Search
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
    fun hasMedia() = mediaPerms().any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED } ||
        (Build.VERSION.SDK_INT >= 34 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED)
    var granted by remember { mutableStateOf(hasMedia()) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted = hasMedia() }
    LaunchedEffect(Unit) { if (!granted) permLauncher.launch(mediaPerms()) }

    val apps by produceState<List<Entity>?>(null) { value = MediaRepo.apps(ctx) }
    val photos by produceState<List<Entity>?>(null, granted) { value = if (granted) MediaRepo.media(ctx, MediaKind.Photos) else emptyList() }
    val videos by produceState<List<Entity>?>(null, granted) { value = if (granted) MediaRepo.media(ctx, MediaKind.Videos) else emptyList() }
    val music by produceState<List<Entity>?>(null, granted) { value = if (granted) MediaRepo.media(ctx, MediaKind.Music) else emptyList() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            MediaRepo.documents(ctx, uris).forEach { e -> if (docs.none { it.key == e.key }) docs.add(0, e); selected[e.key] = e }
        }
    }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { tree ->
        if (tree != null) scope.launch {
            val e = MediaRepo.folder(ctx, tree)
            if (docs.none { it.key == e.key }) docs.add(0, e); selected[e.key] = e
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
                1 -> if (!granted) NeedAccess { permLauncher.launch(mediaPerms()) } else Tools(photos, selected) { MediaGrid(it, selected, 3, ::toggle) }
                2 -> if (!granted) NeedAccess { permLauncher.launch(mediaPerms()) } else Tools(videos, selected) { MediaGrid(it, selected, 3, ::toggle) }
                3 -> if (!granted) NeedAccess { permLauncher.launch(mediaPerms()) } else Tools(music, selected) { MusicList(it, selected, ::toggle) }
                else -> FilesTab(docs, selected, ::toggle, onFiles = { filePicker.launch(arrayOf("*/*")) }, onFolder = { folderPicker.launch(null) })
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
private fun Empty(text: String) = Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
    Text(text, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
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

private enum class Sort(val label: String) { Newest("Newest"), Oldest("Oldest"), Largest("Largest"), Name("Name") }

/** Count, sort menu and Select all above a media tab. */
@Composable
private fun Tools(list: List<Entity>?, selected: SnapshotStateMap<String, Entity>, content: @Composable (List<Entity>?) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(Sort.Newest) }
    var menu by remember { mutableStateOf(false) }
    val sorted = remember(list, sort) {
        list?.let {
            when (sort) {
                Sort.Newest -> it
                Sort.Oldest -> it.reversed()
                Sort.Largest -> it.sortedByDescending { e -> e.size }
                Sort.Name -> it.sortedBy { e -> e.label.lowercase() }
            }
        }
    }
    Column {
        if (!list.isNullOrEmpty()) Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${list.size} items", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            Box {
                TextButton({ menu = true }) { Text(sort.label) }
                DropdownMenu(menu, { menu = false }) {
                    Sort.entries.forEach { o -> DropdownMenuItem(text = { Text(o.label) }, onClick = { sort = o; menu = false }) }
                }
            }
            val allOn = list.all { selected.containsKey(it.key) }
            TextButton({ if (allOn) list.forEach { selected.remove(it.key) } else list.forEach { selected[it.key] = it } }) {
                Text(if (allOn) "Clear" else "Select all")
            }
        }
        Box(Modifier.weight(1f)) { content(sorted) }
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
                Text(e.label, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fmtBytes(e.size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private val gridThumbs = LruCache<String, Bitmap>(150)

@Composable
private fun MediaGrid(list: List<Entity>?, selected: Map<String, Entity>, cols: Int, toggle: (Entity) -> Unit) {
    if (list == null) return Loading()
    if (list.isEmpty()) return Empty("Nothing here yet.")
    val ctx = LocalContext.current
    LazyVerticalGrid(
        GridCells.Fixed(cols), Modifier.fillMaxSize(), contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp), verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(list, key = { it.key }) { e ->
            val on = selected.containsKey(e.key)
            val inset by animateDpAsState(if (on) 10.dp else 0.dp, spring(stiffness = 700f), label = "inset")
            val bmp by produceState(gridThumbs.get(e.key), e.key) {
                if (value != null) return@produceState
                value = withContext(Dispatchers.IO) {
                    e.thumb?.let { thumbOf(ctx, it, e.mime, 256) }
                }?.also { gridThumbs.put(e.key, it) }
            }
            Box(
                Modifier.aspectRatio(1f)
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { toggle(e) },
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
private fun MusicList(list: List<Entity>?, selected: Map<String, Entity>, toggle: (Entity) -> Unit) {
    if (list == null) return Loading()
    if (list.isEmpty()) return Empty("No music found.")
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
                    Text(e.label.substringBeforeLast('.'), maxLines = 1, overflow = TextOverflow.Ellipsis)
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
    onFiles: () -> Unit, onFolder: () -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton(Icons.Outlined.FileOpen, "Browse files", Modifier.weight(1f), onFiles)
                BigButton(Icons.Outlined.CreateNewFolder, "Send a folder", Modifier.weight(1f), onFolder)
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
