package com.hypershare.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

data class HistoryItem(
    val role: String, // "Send" / "Receive"
    val name: String,
    val size: Long,
    val mime: String,
    val uri: String?,
    val time: Long,
)

/** Last 50 transferred files, stored as JSON in SharedPreferences. */
object History {
    private const val MAX = 50
    private val _items = MutableStateFlow<List<HistoryItem>>(emptyList())
    val items: StateFlow<List<HistoryItem>> = _items

    fun load(ctx: Context) {
        val raw = prefs(ctx).getString("items", null) ?: return
        _items.value = runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i ->
                val o = a.getJSONObject(i)
                HistoryItem(
                    o.getString("role"), o.getString("name"), o.getLong("size"), o.getString("mime"),
                    o.optString("uri").ifEmpty { null }, o.getLong("time"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, new: List<HistoryItem>) {
        _items.value = (new.reversed() + _items.value).take(MAX)
        save(ctx)
    }

    fun clear(ctx: Context) { _items.value = emptyList(); save(ctx) }

    private fun save(ctx: Context) {
        val a = JSONArray()
        _items.value.forEach {
            a.put(JSONObject().put("role", it.role).put("name", it.name).put("size", it.size)
                .put("mime", it.mime).put("uri", it.uri ?: "").put("time", it.time))
        }
        prefs(ctx).edit().putString("items", a.toString()).apply()
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("history", Context.MODE_PRIVATE)
}

object Prefs {
    val STREAM_CHOICES = listOf(1, 2, 4, 8)
    private val _streams = MutableStateFlow(4)
    val streamsFlow: StateFlow<Int> = _streams


    fun load(ctx: Context) {
        _streams.value = prefs(ctx).getInt("streams", 4)
        _name.value = deviceName(ctx)
    }
    private val _name = MutableStateFlow("")
    val nameFlow: StateFlow<String> = _name

    /** Name other phones see, e.g. "vivo V60 Lite". */
    fun deviceName(ctx: Context): String = prefs(ctx).getString("name", null)?.takeIf { it.isNotBlank() } ?: defaultName()
    fun setDeviceName(ctx: Context, v: String) { prefs(ctx).edit().putString("name", v.trim().take(30)).apply(); _name.value = deviceName(ctx) }
    private fun defaultName(): String {
        val maker = android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }
        val model = android.os.Build.MODEL
        return (if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model").take(30)
    }

    /** Receiver: make the Wi-Fi Direct group on 2.4 GHz so 2.4 GHz-only phones can join. */
    fun only24(ctx: Context) = prefs(ctx).getBoolean("only24", false)
    fun setOnly24(ctx: Context, v: Boolean) = prefs(ctx).edit().putBoolean("only24", v).apply()

    fun onboarded(ctx: Context) = prefs(ctx).getBoolean("onboarded", false)
    fun setOnboarded(ctx: Context) = prefs(ctx).edit().putBoolean("onboarded", true).apply()

    fun streams(ctx: Context) = prefs(ctx).getInt("streams", 4)
    fun setStreams(ctx: Context, n: Int) { prefs(ctx).edit().putInt("streams", n).apply(); _streams.value = n }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)
}
