package com.hypershare.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build

/** Installs one app from one or more APK parts (base + splits) with PackageInstaller. Blocking: call off the main thread. */
object Installer {
    const val EXTRA_SELF_UPDATE = "self_update"

    fun install(ctx: Context, parts: List<Uri>, selfUpdate: Boolean = false) {
        val pi = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            if (selfUpdate) setAppPackageName(ctx.packageName)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = pi.createSession(params)
        try { write(ctx, pi, id, parts, selfUpdate) } catch (e: Throwable) {
            runCatching { pi.abandonSession(id) } // don't leave a half-written session (and its copy of the APK) behind
            throw e
        }
    }

    private fun write(ctx: Context, pi: PackageInstaller, id: Int, parts: List<Uri>, selfUpdate: Boolean) {
        pi.openSession(id).use { s ->
            parts.forEachIndexed { i, uri ->
                val input = ctx.contentResolver.openInputStream(uri) ?: error("Can't read APK")
                input.use { inp ->
                    s.openWrite("part$i.apk", 0, -1).use { o -> inp.copyTo(o, 256 * 1024); s.fsync(o) }
                }
            }
            val cb = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, InstallReceiver::class.java).putExtra(EXTRA_SELF_UPDATE, selfUpdate),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
            )
            s.commit(cb.intentSender)
        }
    }
}
