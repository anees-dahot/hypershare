package com.hypershare.ui

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

private enum class Screen { Home, Pick, Receive, Transfer }

class MainActivity : ComponentActivity() {
    /** Picker selection; lives here so it survives screen switches. */
    private val selected = mutableStateMapOf<String, Entity>()
    private val docs = mutableStateListOf<Entity>()
    private var picking by mutableStateOf(false)
    private var adding by mutableStateOf(false)
    private var pendingItems: List<Picked> = emptyList()
    private var pendingBench = false

    private val perms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {}

    private val scanner = registerForActivityResult(ScanContract()) { r ->
        r.contents?.let {
            Engine.startSend(this, pendingItems, it, pendingBench)
            picking = false
            selected.clear()
        }
    }

    private fun sendSelected() {
        val items = selected.values.flatMap { it.parts }
        if (adding) {
            Engine.addFiles(items)
            selected.clear(); picking = false; adding = false
        } else {
            pendingItems = items; pendingBench = false; scan()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        History.load(this)
        Prefs.load(this)
        perms.launch(buildList {
            add(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) {
                add(Manifest.permission.NEARBY_WIFI_DEVICES)
                add(Manifest.permission.POST_NOTIFICATIONS)
            } else add(Manifest.permission.ACCESS_FINE_LOCATION)
        }.toTypedArray())
        lifecycleScope.launch { Updater.check() }
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
        var settings by remember { mutableStateOf(false) }

        val screen = when {
            picking -> Screen.Pick
            s.phase == Phase.Idle -> Screen.Home
            s.role == Role.Receive && (s.phase == Phase.Preparing || s.phase == Phase.Waiting) -> Screen.Receive
            else -> Screen.Transfer
        }

        // Screen off throttles Wi-Fi; keep it on while anything is in flight.
        LaunchedEffect(s.active) {
            if (s.active) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }

        BackHandler(screen != Screen.Home) {
            when (screen) {
                Screen.Pick -> { picking = false; adding = false }
                Screen.Receive -> Engine.reset()
                Screen.Transfer -> if (s.active) moveTaskToBack(true) else Engine.reset()
                Screen.Home -> Unit
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
                Screen.Home -> HomeScreen(
                    history, update,
                    onSend = { adding = false; picking = true },
                    onReceive = { Engine.startReceive(this@MainActivity) },
                    onBench = { pendingBench = true; scan() },
                    onSettings = { settings = true },
                )
                Screen.Pick -> PickScreen(
                    selected, docs, adding,
                    onBack = { picking = false; adding = false },
                    onSend = { sendSelected() },
                )
                Screen.Receive -> ReceiveScreen(s, onBack = { Engine.reset() })
                Screen.Transfer -> TransferScreen(
                    s,
                    onSendMore = { adding = true; selected.clear(); picking = true },
                    onClose = { Engine.reset() },
                )
            }
        }

        if (settings) SettingsSheet(onDismiss = { settings = false })
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (BuildConfig.DEBUG) debugHook(intent)
    }

    /**
     * Debug builds only: drive a transfer over the existing network without Wi-Fi Direct, for emulator tests.
     *   am start -n com.hypershare/.ui.MainActivity --ez debug_receive_lan true
     *   am start -n com.hypershare/.ui.MainActivity --es debug_send_qr 'HSX3|L|-|-|0|0|10.0.2.2' [--ez debug_bench true]
     */
    private fun debugHook(i: android.content.Intent) {
        if (i.getBooleanExtra("debug_receive_lan", false)) { picking = false; Engine.startReceive(this, lan = true); return }
        val qr = i.getStringExtra("debug_send_qr") ?: return
        val bench = i.getBooleanExtra("debug_bench", false)
        lifecycleScope.launch {
            val items = if (bench) emptyList() else
                (MediaRepo.media(this@MainActivity, MediaKind.Photos) + MediaRepo.media(this@MainActivity, MediaKind.Videos)).flatMap { it.parts }
            picking = false
            Engine.startSend(this@MainActivity, items, qr, bench)
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
    val link by Prefs.linkFlow.collectAsStateWithLifecycle()
    val update by Updater.ui.collectAsStateWithLifecycle()

    ModalBottomSheet(onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(20.dp))

            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text(
                "Set on the receiving phone. If Wi-Fi Direct is slow or dips, try Hotspot: many phones run it on a wider 5 GHz channel. The sender confirms joining once.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            val links = listOf(Prefs.LINK_DIRECT to "Wi-Fi Direct", Prefs.LINK_HOTSPOT to "Hotspot")
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                links.forEachIndexed { i, (k, label) ->
                    SegmentedButton(
                        selected = link == k, onClick = { Prefs.setLink(ctx, k) },
                        shape = SegmentedButtonDefaults.itemShape(i, links.size),
                    ) { Text(label) }
                }
            }
            Spacer(Modifier.height(20.dp))

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
