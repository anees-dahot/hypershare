package com.hypershare.ui

import com.hypershare.data.Trust
import com.hypershare.data.textEntity
import androidx.compose.ui.Alignment
import androidx.compose.foundation.rememberScrollState
import android.Manifest
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.hypershare.BuildConfig
import com.hypershare.data.Entity
import com.hypershare.data.History
import com.hypershare.data.MediaKind
import com.hypershare.data.MediaRepo
import com.hypershare.data.Picked
import com.hypershare.data.Prefs
import com.hypershare.transfer.Engine
import com.hypershare.transfer.Phase
import com.hypershare.transfer.Role
import com.hypershare.update.Updater
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.launch

private enum class Screen { Welcome, Home, Inbox, Pick, Choose, Receive, Transfer }

class MainActivity : ComponentActivity() {
    /** Picker selection; lives here so it survives screen switches. */
    private val selected = mutableStateMapOf<String, Entity>()
    private val docs = mutableStateListOf<Entity>()
    private var picking by mutableStateOf(false)
    private var adding by mutableStateOf(false)
    private var choosing by mutableStateOf(false)
    private var inbox by mutableStateOf(false)
    private var onboarded by mutableStateOf(true)
    private var pendingItems: List<Picked> = emptyList()
    private var pendingBench by mutableStateOf(false)

    // Ask for permissions right before the step that needs them, then carry on either way.
    private var afterPerms: (() -> Unit)? = null
    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        afterPerms?.invoke(); afterPerms = null
    }

    private fun withPerms(list: Array<String>, then: () -> Unit) {
        val missing = list.filter { checkSelfPermission(it) != android.content.pm.PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) then() else { afterPerms = then; perms.launch(missing.toTypedArray()) }
    }

    /** Wi-Fi (+ notifications, + storage on Android 8–9) before any transfer. */
    private fun transferPerms() = buildList {
        addAll(wifiPerms())
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT < 29) add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }.toTypedArray()

    /**
     * Android 8–12 silently fail to find the other phone when
     * Location is off. Check first and say so, instead of a long, mysterious wait.
     */
    private var locationPrompt by mutableStateOf<(() -> Unit)?>(null)

    private fun locationOn(): Boolean {
        val lm = getSystemService(android.location.LocationManager::class.java) ?: return true
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    }

    private fun withLocation(then: () -> Unit) {
        if (Build.VERSION.SDK_INT < 33 && !locationOn()) locationPrompt = then else then()
    }

    private val scanner = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let { start(it, viaQr = true) }
    }

    private fun start(code: String, viaQr: Boolean) {
        withPerms(transferPerms()) { withLocation {
            Engine.startSend(this, pendingItems, code, pendingBench, viaQr)
            picking = false; choosing = false
            selected.clear()
        } }
    }

    private fun receive() = withPerms(transferPerms()) {
        withLocation { Engine.startReceive(this) }
    }

    /** Picker "Send"/"Add": add to the open session, or go choose a receiver. */
    private fun sendSelected() {
        val items = selected.values.flatMap { it.parts }
        if (adding) {
            Engine.addFiles(items)
            selected.clear(); picking = false; adding = false
        } else {
            pendingItems = items; pendingBench = false
            withPerms(wifiPerms()) { picking = false; choosing = true }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        History.load(this); Trust.load(this)
        Prefs.load(this)
        onboarded = Prefs.onboarded(this)
        lifecycleScope.launch { Updater.check() }
        handleShare(intent)
        if (BuildConfig.DEBUG) debugHook(intent)

        setContent {
            HyperTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { App() }
            }
        }
    }

    @Composable
    private fun App() {
        val s by Engine.state.collectAsStateWithLifecycle()
        val history by History.items.collectAsStateWithLifecycle()
        val update by Updater.ui.collectAsStateWithLifecycle()
        val name by Prefs.nameFlow.collectAsStateWithLifecycle()
        var settings by remember { mutableStateOf(false) }
        var crash by remember { mutableStateOf(com.hypershare.CrashLog.read()) }

        val screen = when {
            !onboarded -> Screen.Welcome
            picking -> Screen.Pick
            s.phase != Phase.Idle && s.role == Role.Receive && (s.phase == Phase.Preparing || s.phase == Phase.Waiting) -> Screen.Receive
            s.phase != Phase.Idle -> Screen.Transfer
            choosing -> Screen.Choose
            inbox -> Screen.Inbox
            else -> Screen.Home
        }

        // Screen off throttles Wi-Fi; keep it on while anything is in flight.
        LaunchedEffect(s.active) {
            if (s.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        BackHandler(screen != Screen.Home && screen != Screen.Welcome) {
            when (screen) {
                Screen.Pick -> { picking = false; adding = false }
                Screen.Choose -> { choosing = false; if (!pendingBench) picking = true }
                Screen.Inbox -> inbox = false
                Screen.Receive -> Engine.reset()
                Screen.Transfer -> if (s.active || s.connected) moveTaskToBack(true) else Engine.reset()
                else -> Unit
            }
        }

        AnimatedContent(
            screen, label = "screen",
            transitionSpec = {
                val forward = targetState.ordinal > initialState.ordinal
                val dir = if (forward) 1 else -1
                (fadeIn(tween(240, 60)) + slideInHorizontally(tween(300)) { dir * it / 10 }) togetherWith
                    (fadeOut(tween(140)) + slideOutHorizontally(tween(300)) { -dir * it / 14 })
            },
        ) { sc ->
            when (sc) {
                Screen.Welcome -> WelcomeScreen { onboarded = true; Prefs.load(this@MainActivity) }
                Screen.Home -> HomeScreen(
                    history, update, name,
                    onSend = { adding = false; picking = true },
                    onReceive = { receive() },
                    onBench = { pendingItems = emptyList(); pendingBench = true; withPerms(wifiPerms()) { choosing = true } },
                    onInbox = { inbox = true },
                    onSettings = { settings = true },
                )
                Screen.Inbox -> InboxScreen(onBack = { inbox = false })
                Screen.Pick -> PickScreen(
                    selected, docs, adding,
                    onBack = { picking = false; adding = false },
                    onSend = { sendSelected() },
                )
                Screen.Choose -> ChooseScreen(
                    count = selected.size.takeIf { it > 0 } ?: pendingItems.size,
                    bytes = pendingItems.sumOf { it.size },
                    bench = pendingBench,
                    onPeer = { p -> start(p.invite, viaQr = false) },
                    onCode = { inv -> start(inv, viaQr = false) },
                    onScan = { scan() },
                    onBack = { choosing = false; if (!pendingBench) picking = true },
                )
                Screen.Receive -> ReceiveScreen(s, onBack = { Engine.reset() }, onBand = { only24 ->
                    Prefs.setOnly24(this@MainActivity, only24); Engine.reset(); receive()
                })
                Screen.Transfer -> TransferScreen(
                    s,
                    onSendMore = { adding = true; selected.clear(); picking = true },
                    onRetry = {
                        // Same files again; the receiver continues partly received ones.
                        pendingItems = Engine.retryItems(); pendingBench = false
                        Engine.reset(); choosing = true
                    },
                    onClose = { Engine.reset() },
                )
            }
        }

        if (settings) SettingsSheet(onDismiss = { settings = false })
        locationPrompt?.let { then ->
            AlertDialog(
                onDismissRequest = { locationPrompt = null },
                title = { Text("Turn on Location") },
                text = { Text("Android needs Location switched on to find the other phone over Wi-Fi. HyperShare doesn't read or store your location.") },
                confirmButton = {
                    Button({
                        locationPrompt = null
                        runCatching { startActivity(android.content.Intent(android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    }) { Text("Open settings") }
                },
                dismissButton = { TextButton({ locationPrompt = null; then() }) { Text("Try anyway") } },
            )
        }
        crash?.let { report -> CrashDialog(report) { com.hypershare.CrashLog.clear(); crash = null } }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        handleShare(intent)
        if (BuildConfig.DEBUG) debugHook(intent)
    }

    /** "Share → HyperShare" from any app: jump straight to choosing a receiver. */
    private fun handleShare(i: android.content.Intent?) {
        if (i == null || (i.action != android.content.Intent.ACTION_SEND && i.action != android.content.Intent.ACTION_SEND_MULTIPLE)) return
        @Suppress("DEPRECATION")
        val uris: List<android.net.Uri> = when (i.action) {
            android.content.Intent.ACTION_SEND -> listOfNotNull(i.getParcelableExtra(android.content.Intent.EXTRA_STREAM))
            else -> i.getParcelableArrayListExtra<android.net.Uri>(android.content.Intent.EXTRA_STREAM).orEmpty()
        }
        // A link or note shared from a browser or notes app has no file: it goes as a text snippet.
        val text = if (uris.isEmpty()) i.getCharSequenceExtra(android.content.Intent.EXTRA_TEXT)?.toString()?.takeIf { it.isNotBlank() } else null
        if (uris.isEmpty() && text == null) return
        i.action = null // handle once
        lifecycleScope.launch {
            val ents = if (text != null) listOf(textEntity(text))
                else runCatching { MediaRepo.documents(this@MainActivity, uris) }.getOrDefault(emptyList())
            if (ents.isEmpty()) {
                android.widget.Toast.makeText(this@MainActivity, "Couldn't read what was shared", android.widget.Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (Engine.state.value.connected) { Engine.addFiles(ents.flatMap { it.parts }); return@launch }
            selected.clear(); ents.forEach { selected[it.key] = it }
            pendingItems = ents.flatMap { it.parts }; pendingBench = false
            picking = false; inbox = false
            withPerms(wifiPerms()) { choosing = true }
        }
    }

    /**
     * Debug builds only: drive a transfer over the existing network without Wi-Fi Direct, for emulator tests.
     *   am start -n com.hypershare/.ui.MainActivity --ez debug_receive_lan true
     *   am start -n com.hypershare/.ui.MainActivity --es debug_send_qr 'HSX5|L|-|-|0|10.0.2.2|Emu|token' [--ez debug_bench true]
     *     [--es debug_text 'hello'] sends a text snippet instead of all photos and videos;
     *     [--ez debug_nearby true] connects like a phone picked from the Nearby list (no QR token).
     */
    private fun debugHook(i: android.content.Intent) {
        if (i.getBooleanExtra("debug_fgs_race", false)) {
            // Recreates v0.4.0's bug: start the foreground service, then stop it before it can go foreground.
            com.hypershare.service.TransferService.start(this)
            stopService(android.content.Intent(this, com.hypershare.service.TransferService::class.java)); return
        }
        if (i.getBooleanExtra("debug_crash", false)) { Thread { throw IllegalStateException("Test crash from debug hook") }.start(); return }
        if (i.getBooleanExtra("debug_receive_lan", false)) { picking = false; Engine.startReceive(this, lan = true); return }
        val qr = i.getStringExtra("debug_send_qr") ?: return
        val bench = i.getBooleanExtra("debug_bench", false)
        lifecycleScope.launch {
            val text = i.getStringExtra("debug_text")
            val items = when {
                bench -> emptyList()
                text != null -> textEntity(text).parts
                else -> (MediaRepo.media(this@MainActivity, MediaKind.Photos) + MediaRepo.media(this@MainActivity, MediaKind.Videos)).flatMap { it.parts }
            }
            picking = false
            Engine.startSend(this@MainActivity, items, qr, bench, viaQr = !i.getBooleanExtra("debug_nearby", false))
        }
    }

    private fun scan() = scanner.launch(
        ScanOptions()
            .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
            .setPrompt("Scan the code on the receiving phone")
            .setBeepEnabled(false)
            .setOrientationLocked(false)
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val streams by Prefs.streamsFlow.collectAsStateWithLifecycle()
    val update by Updater.ui.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            var devName by remember { mutableStateOf(Prefs.deviceName(ctx)) }
            Text("This phone's name", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                devName, { devName = it.take(30); Prefs.setDeviceName(ctx, devName) }, Modifier.fillMaxWidth(), singleLine = true,
                supportingText = { Text("Shown to the other phone and in its Nearby list.") },
            )
            Spacer(Modifier.height(16.dp))

            Text("Parallel streams", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set on the sending phone. 4 suits most phones; try 2 if speed swings a lot, 8 on fast flagships.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                Prefs.STREAM_CHOICES.forEachIndexed { i, n ->
                    SegmentedButton(
                        selected = streams == n,
                        onClick = { Prefs.setStreams(ctx, n) },
                        shape = SegmentedButtonDefaults.itemShape(i, Prefs.STREAM_CHOICES.size),
                    ) { Text("$n") }
                }
            }

            Spacer(Modifier.height(20.dp))
            var gallery by remember { mutableStateOf(Prefs.gallery(ctx)) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Save photos and videos to gallery", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Received photos and videos show up in your gallery's HyperShare album. Off: everything stays in Downloads/HyperShare.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Switch(gallery, { gallery = it; Prefs.setGallery(ctx, it) })
            }

            val trusted by Trust.phones.collectAsStateWithLifecycle()
            if (trusted.isNotEmpty()) {
                Spacer(Modifier.height(20.dp))
                Text("Always accepted phones", style = MaterialTheme.typography.titleMedium)
                Text(
                    "These can send to you from their Nearby list without asking first.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                trusted.forEach { t ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(t.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, maxLines = 1)
                        TextButton({ Trust.forget(ctx, t.key) }) { Text("Remove") }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(Modifier.height(16.dp))

            Row {
                Column(Modifier.weight(1f)) {
                    Text("Version ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            update.checking -> "Checking…"
                            update.available != null -> "Version ${update.available!!.version} available"
                            update.error != null -> update.error!!
                            update.upToDate -> "You're up to date"
                            else -> "Updates install from GitHub"
                        },
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (update.available != null) Button({ scope.launch { Updater.install(ctx) } }) { Text("Update") }
                else OutlinedButton({ scope.launch { Updater.check() } }, enabled = !update.checking) { Text("Check") }
            }

            Spacer(Modifier.height(16.dp))
            TextButton({ History.clear(ctx) }, contentPadding = PaddingValues(0.dp)) { Text("Clear recent transfers") }
        }
    }
}

/** Shown once after a crash or failed transfer: lets the user copy or share the details. */
@Composable
private fun CrashDialog(report: String, onDone: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val crashed = report.contains("Crash on thread")
    AlertDialog(
        onDismissRequest = onDone,
        title = { Text(if (crashed) "HyperShare closed unexpectedly" else "Last transfer failed") },
        text = {
            Column {
                Text(
                    "Send this report to the developer so it can be fixed. It contains your phone model and the error, nothing else.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Spacer(Modifier.height(12.dp))
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    Text(
                        report.lines().take(14).joinToString("\n"),
                        Modifier.padding(10.dp).heightIn(max = 220.dp)
                            .verticalScroll(androidx.compose.foundation.rememberScrollState()),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace),
                    )
                }
            }
        },
        confirmButton = {
            Button({
                ctx.startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(android.content.Intent.EXTRA_SUBJECT, "HyperShare crash report")
                        .putExtra(android.content.Intent.EXTRA_TEXT, report), "Share report",
                ))
                onDone()
            }) { Text("Share") }
        },
        dismissButton = {
            TextButton({
                val cm = ctx.getSystemService(android.content.ClipboardManager::class.java)
                cm.setPrimaryClip(android.content.ClipData.newPlainText("HyperShare report", report))
                android.widget.Toast.makeText(ctx, "Report copied", android.widget.Toast.LENGTH_SHORT).show()
                onDone()
            }) { Text("Copy") }
        },
    )
}
