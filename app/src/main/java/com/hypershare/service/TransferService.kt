package com.hypershare.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.IBinder
import android.os.PowerManager
import com.hypershare.transfer.Engine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Keeps the process alive and the radio awake while a transfer runs.
 * Transfer logic lives in Engine; this only owns the foreground notification + locks.
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
        val n = Notification.Builder(this, CH)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("HyperShare transfer active")
            .setOngoing(true)
            .build()
        if (android.os.Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else startForeground(1, n)

        if (wakeLock == null) {
            // Disables Wi-Fi power save: big win for sustained throughput.
            // LOW_LATENCY only exists on Android 10+; passing it on 8–9 throws.
            if (android.os.Build.VERSION.SDK_INT >= 29) wifiLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "hypershare")
                .apply { setReferenceCounted(false); acquire() }
            // Older Android honours HIGH_PERF (no-op on 14+, where LOW_LATENCY covers it).
            @Suppress("DEPRECATION")
            perfLock = getSystemService(WifiManager::class.java)
                .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "hypershare-perf")
                .apply { setReferenceCounted(false); acquire() }
            wakeLock = getSystemService(PowerManager::class.java)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "hypershare:transfer")
                .apply { setReferenceCounted(false); acquire(3 * 60 * 60 * 1000L) }
        }
        if (watcher == null) watcher = scope.launch {
            Engine.needService.first { !it }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        wifiLock?.release()
        perfLock?.release()
        wakeLock?.release()
        super.onDestroy()
    }

    companion object {
        private const val CH = "transfers"
        fun start(ctx: Context) {
            runCatching { ctx.startForegroundService(Intent(ctx, TransferService::class.java)) }
                .onFailure { com.hypershare.CrashLog.save(it, "Couldn't start transfer service (transfer continues)") }
        }
    }
}
