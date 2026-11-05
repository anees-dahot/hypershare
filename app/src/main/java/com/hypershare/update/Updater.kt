package com.hypershare.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import com.hypershare.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val apkUrl: String, val size: Long, val notes: String)

data class UpdateUi(
    val checking: Boolean = false,
    val available: Release? = null,
    /** 0..1 while downloading, -1 otherwise. */
    val progress: Float = -1f,
    val installing: Boolean = false,
    val upToDate: Boolean = false,
    val error: String? = null,
)

/** Checks GitHub Releases for a newer APK and installs it with PackageInstaller. */
object Updater {
    private const val REPO = "anees-dahot/hypershare"

    private val _ui = MutableStateFlow(UpdateUi())
    val ui: StateFlow<UpdateUi> = _ui

    suspend fun check() {
        if (_ui.value.checking || _ui.value.progress >= 0) return
        _ui.update { it.copy(checking = true, error = null, upToDate = false) }
        val rel = runCatching { withContext(Dispatchers.IO) { latest() } }
        _ui.update { u ->
            rel.fold(
                onSuccess = { r ->
                    if (r != null && newer(r.version, BuildConfig.VERSION_NAME)) u.copy(checking = false, available = r)
                    else u.copy(checking = false, available = null, upToDate = true)
                },
                onFailure = { u.copy(checking = false, error = "Couldn't check for updates") },
            )
        }
    }

    suspend fun install(ctx: Context) {
        val rel = _ui.value.available ?: return
        if (_ui.value.progress >= 0) return
        _ui.update { it.copy(progress = 0f, error = null) }
        try {
            val apk = withContext(Dispatchers.IO) { download(ctx, rel) }
            _ui.update { it.copy(progress = -1f, installing = true) }
            withContext(Dispatchers.IO) { commit(ctx, apk) }
        } catch (e: Exception) {
            _ui.update { it.copy(progress = -1f, installing = false, error = "Update failed: ${e.message}") }
        }
    }

    internal fun installFinished(error: String?) {
        _ui.update { it.copy(installing = false, error = error) }
    }

    private fun latest(): Release? {
        val c = URL("https://api.github.com/repos/$REPO/releases/latest").openConnection() as HttpURLConnection
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "HyperShare-Android")
        c.connectTimeout = 8000; c.readTimeout = 8000
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val j = JSONObject(c.inputStream.bufferedReader().readText())
            val assets = j.getJSONArray("assets")
            for (i in 0 until assets.length()) {
                val a = assets.getJSONObject(i)
                if (a.getString("name").endsWith(".apk")) {
                    return Release(
                        j.getString("tag_name").removePrefix("v"),
                        a.getString("browser_download_url"),
                        a.getLong("size"),
                        j.optString("body"),
                    )
                }
            }
            return null
        } finally { c.disconnect() }
    }

    private fun download(ctx: Context, rel: Release): File {
        val out = File(ctx.cacheDir, "update.apk")
        var url = URL(rel.apkUrl)
        // GitHub redirects to its CDN; follow manually so https->https hops are explicit.
        var c: HttpURLConnection
        var hops = 0
        while (true) {
            c = url.openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", "HyperShare-Android")
            c.connectTimeout = 10000; c.readTimeout = 20000
            val code = c.responseCode
            if (code in 300..399 && hops++ < 5) { url = URL(url, c.getHeaderField("Location")); c.disconnect(); continue }
            if (code != 200) throw IOException("HTTP $code")
            break
        }
        val total = c.contentLengthLong.takeIf { it > 0 } ?: rel.size
        c.inputStream.use { input ->
            out.outputStream().use { o ->
                val buf = ByteArray(64 * 1024)
                var got = 0L; var lastPct = -1
                while (true) {
                    val n = input.read(buf); if (n < 0) break
                    o.write(buf, 0, n); got += n
                    val pct = (got * 100 / total).toInt()
                    if (pct != lastPct) { lastPct = pct; _ui.update { it.copy(progress = got.toFloat() / total) } }
                }
            }
        }
        c.disconnect()
        return out
    }

    private fun commit(ctx: Context, apk: File) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        pi.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { o ->
                apk.inputStream().use { it.copyTo(o) }
                s.fsync(o)
            }
            val cb = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(cb.intentSender)
        }
    }

    fun newer(a: String, b: String): Boolean {
        val x = a.split('.', '-').mapNotNull { it.toIntOrNull() }
        val y = b.split('.', '-').mapNotNull { it.toIntOrNull() }
        for (i in 0 until maxOf(x.size, y.size)) {
            val d = x.getOrElse(i) { 0 } - y.getOrElse(i) { 0 }
            if (d != 0) return d > 0
        }
        return false
    }
}

/** Receives PackageInstaller results; shows the system confirm dialog when needed. */
class InstallReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(ctx::startActivity)
            }
            PackageInstaller.STATUS_SUCCESS -> Updater.installFinished(null)
            else -> Updater.installFinished(
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Install cancelled"
            )
        }
    }
}
