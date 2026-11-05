package com.hypershare

import android.app.Application
import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HyperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}

/**
 * Saves crashes (and serious non-fatal errors) to a file so the next launch can show them
 * with a Copy/Share button. Nothing leaves the phone unless the user shares it.
 */
object CrashLog {
    private lateinit var app: Context
    private fun file() = File(app.filesDir, "crash.txt")

    fun install(ctx: Context) {
        app = ctx.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            save(e, "Crash on thread ${t.name}")
            prev?.uncaughtException(t, e)
        }
    }

    fun save(e: Throwable, what: String) {
        if (!::app.isInitialized) return
        runCatching {
            file().writeText(buildString {
                appendLine("HyperShare ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
                appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date()))
                appendLine(what)
                appendLine()
                append(Log.getStackTraceString(e))
            })
        }
    }

    fun read(): String? = runCatching { file().takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear() { runCatching { file().delete() } }
}
