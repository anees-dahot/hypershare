package com.hypershare.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CameraAlt
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PermMedia
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.hypershare.data.Prefs

private data class Need(val icon: ImageVector, val title: String, val why: String, val perms: Array<String>)

fun wifiPerms(): Array<String> = if (Build.VERSION.SDK_INT >= 33) arrayOf(Manifest.permission.NEARBY_WIFI_DEVICES)
else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)

fun mediaPerms(): Array<String> = when {
    Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
    Build.VERSION.SDK_INT >= 29 -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
}

/** First run: what the app does, the phone's name, and why each permission is needed. */
@Composable
fun WelcomeScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(Prefs.deviceName(ctx)) }
    var tick by remember { mutableIntStateOf(0) } // re-check grants after each request
    fun granted(p: Array<String>) = p.any { ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { tick++ }

    val needs = buildList {
        add(Need(Icons.Outlined.Wifi, if (Build.VERSION.SDK_INT >= 33) "Nearby devices" else "Location",
            if (Build.VERSION.SDK_INT >= 33) "Find and connect to the other phone over Wi-Fi Direct."
            else "Android needs it to use Wi-Fi Direct. HyperShare never reads your location.", wifiPerms()))
        add(Need(Icons.Outlined.CameraAlt, "Camera", "Scan the other phone's pairing code.", arrayOf(Manifest.permission.CAMERA)))
        add(Need(Icons.Outlined.PermMedia, if (Build.VERSION.SDK_INT >= 29) "Photos, videos & music" else "Storage",
            "Show your files in the picker" + (if (Build.VERSION.SDK_INT < 29) " and save what you receive." else "."), mediaPerms()))
        if (Build.VERSION.SDK_INT >= 33) add(Need(Icons.Outlined.Notifications, "Notifications",
            "Show progress while you use other apps.", arrayOf(Manifest.permission.POST_NOTIFICATIONS)))
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp),
    ) {
        Spacer(Modifier.height(32.dp))
        Box(
            Modifier.size(72.dp).clip(RoundedCornerShape(22.dp)).background(BrandGradient),
            contentAlignment = Alignment.Center,
        ) { Text("H", style = MaterialTheme.typography.headlineLarge, color = androidx.compose.ui.graphics.Color.White) }
        Spacer(Modifier.height(20.dp))
        Text("Welcome to HyperShare", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(6.dp))
        Text(
            "Send files phone to phone over a direct Wi-Fi link. No internet, no account, nothing uploaded.",
            style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(28.dp))
        SectionLabel("This phone's name")
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            name, { name = it.take(30) }, Modifier.fillMaxWidth(), singleLine = true,
            supportingText = { Text("Other phones see this name.") },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        )

        Spacer(Modifier.height(20.dp))
        val allowed = remember(tick) { needs.count { granted(it.perms) } }
        SectionLabel("Permissions") {
            Chip("$allowed of ${needs.size} allowed")
        }
        Spacer(Modifier.height(8.dp))
        Panel(Modifier.fillMaxWidth(), padding = 4.dp) {
            needs.forEachIndexed { i, n ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 66.dp), color = MaterialTheme.colorScheme.outlineVariant)
                key(tick) {
                    val ok = granted(n.perms)
                    Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.primaryContainer),
                            contentAlignment = Alignment.Center,
                        ) { Icon(n.icon, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary) }
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(n.title, style = MaterialTheme.typography.titleMedium)
                            Text(n.why, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(Modifier.width(8.dp))
                        AnimatedContent(ok, label = "perm") { on ->
                            if (on) DoneBadge(26.dp)
                            else FilledTonalButton({ ask.launch(n.perms) }, contentPadding = PaddingValues(horizontal = 14.dp)) { Text("Allow") }
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            "You can skip these now; HyperShare asks again when it needs one.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(24.dp))
        Button(
            { Prefs.setDeviceName(ctx, name); Prefs.setOnboarded(ctx); onDone() },
            Modifier.fillMaxWidth().height(54.dp),
        ) { Text("Get started", style = MaterialTheme.typography.titleMedium) }
        Spacer(Modifier.height(20.dp))
    }
}
