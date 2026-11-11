package com.hypershare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import com.hypershare.transfer.Engine
import com.hypershare.transfer.FStatus
import com.hypershare.transfer.Phase
import com.hypershare.transfer.UiState
import com.hypershare.ui.MainActivity
import com.hypershare.ui.fmtBytes
import com.hypershare.ui.fmtDuration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Keeps the process alive and the radio awake while a transfer runs, and shows live progress
 * with a Cancel button in the notification.
 *
 * Lifecycle rule: nobody stops this service from outside. Android kills the app if a service
 * started with startForegroundService() is stopped before it calls startForeground()
 * (ForegroundServiceDidNotStartInTimeException). So it always goes foreground first, then
 * stops itself once [Engine.needService] turns false.
 */
class TransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watcher: Job? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var perfLock: WifiManager.WifiLock? = null
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH, "Transfers", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Finished transfers", NotificationManager.IMPORTANCE_DEFAULT))
        val n = build(Engine.state.value)
        if (Build.VERSION.SDK_INT >= 29) startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        else startForeground(ID, n)

        if (intent?.action == ACTION_CANCEL) Engine.cancel()

        if (wakeLock == null) {
            // Disables Wi-Fi power save: big win for sustained throughput.
            // LOW_LATENCY only exists on Android 10+; passing it on 8–9 throws.
            if (Build.VERSION.SDK_INT >= 29) wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "hypershare")
                .apply { setReferenceCounted(false); acquire() }
            @Suppress("DEPRECATION")
            perfLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hypershare-perf")
                .apply { setReferenceCounted(false); acquire() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hypershare:transfer")
                .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        }
        if (watcher == null) watcher = scope.launch {
            val progress = launch { Engine.state.sample(800).collect { nm.notify(ID, build(it)) } }
            Engine.needService.first { !it }
            // Stop updating first: a late sample would re-post the progress notification after the
            // service is gone, leaving it stuck in the shade.
            progress.cancelAndJoin()
            summary(nm, Engine.state.value)
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun openApp() = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun build(s: UiState): Notification {
        val incoming = s.files.count { !it.outgoing }; val outgoing = s.files.size - incoming
        val title = when (s.phase) {
            Phase.Waiting -> "Waiting for a sender"
            Phase.Preparing, Phase.Connecting -> "Connecting" + (s.peer?.let { " to $it" } ?: "")
            Phase.Transferring -> when {
                s.bench -> "Testing speed"
                incoming > 0 && outgoing == 0 -> "Receiving ${files(incoming)}"
                outgoing > 0 && incoming == 0 -> "Sending ${files(outgoing)}"
                else -> "Transferring ${files(s.files.size)}"
            } + (s.peer?.let { " · $it" } ?: "")
            Phase.Done -> "Connected" + (s.peer?.let { " to $it" } ?: "")
            else -> "HyperShare"
        }
        val pct = if (s.total > 0) (s.done * 100 / s.total).toInt() else 0
        val text = if (s.phase == Phase.Transferring)
            String.format(Locale.US, "%d%% · %.1f MB/s · %s left", pct, s.mbps, fmtDuration(s.etaSec))
        else s.step ?: "Tap to open"
        return Notification.Builder(this, CH)
            .setSmallIcon(if (outgoing >= incoming) android.R.drawable.stat_sys_upload else android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp())
            .apply { if (s.phase == Phase.Transferring) setProgress(100, pct, false) }
            .addAction(Notification.Action.Builder(null, "Cancel",
                PendingIntent.getService(this, 1, Intent(this, TransferService::class.java).setAction(ACTION_CANCEL),
                    PendingIntent.FLAG_IMMUTABLE)).build())
            .build()
    }

    /** One-off "done" notification after the session, so it's visible if the app was in the background. */
    private fun summary(nm: NotificationManager, s: UiState) {
        val got = s.files.filter { !it.outgoing && it.status == FStatus.Done }
        val sent = s.files.filter { it.outgoing && it.status == FStatus.Done }
        if (s.bench || (got.isEmpty() && sent.isEmpty())) return
        val title = buildList {
            if (got.isNotEmpty()) add("Received ${files(got.size)}")
            if (sent.isNotEmpty()) add("Sent ${files(sent.size)}")
        }.joinToString(" · ")
        val bytes = (got + sent).sumOf { it.size }
        nm.notify(ID_DONE, Notification.Builder(this, CH_DONE)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(title)
            .setContentText(String.format(Locale.US, "%s at %.1f MB/s average", fmtBytes(bytes), s.avgMbps))
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .build())
    }

    private fun files(n: Int) = if (n == 1) "1 file" else "$n files"

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.release()
        perfLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val CH = "transfers"
        private const val CH_DONE = "transfers_done"
        private const val ID = 1
        private const val ID_DONE = 2
        private const val ACTION_CANCEL = "com.hypershare.CANCEL"

        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, TransferService::class.java)) }
                .onFailure { com.hypershare.CrashLog.save(it, "Couldn't start transfer service (transfer continues)") }
        }
    }
}
