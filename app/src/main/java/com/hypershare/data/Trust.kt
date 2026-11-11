package com.hypershare.data

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A phone this one accepts from without asking. [key] is the secret that phone proves it holds. */
data class TrustedPhone(val key: String, val name: String, val time: Long)

/**
 * "Always accept from this phone."
 *
 * A name can't be trusted: any phone can call itself "Mum's phone". So when the receiver ticks
 * the box it makes a random secret and hands it to the sender over the (encrypted) link. Next
 * time, the sender proves it still holds that secret: it sends HMAC(secret, this session's Wi-Fi
 * name). The Wi-Fi name is random and picked by the receiver for every session, so a proof
 * recorded once is useless later, and the secret itself never travels again.
 *
 * The sender doesn't know which receiver it is talking to before connecting, so it sends one
 * proof per secret it holds. They reveal nothing to a phone that doesn't have the matching secret.
 */
object Trust {
    /** Marks the hello token as a list of proofs. Also tells the receiver this sender can store a secret. */
    const val PREFIX = "t:"
    private const val MAX = 30
    private val KEY = Regex("[0-9a-f]{32}")

    private val _phones = MutableStateFlow<List<TrustedPhone>>(emptyList())
    /** Receiver side: phones we accept without asking, newest first. */
    val phones: StateFlow<List<TrustedPhone>> = _phones

    fun load(ctx: Context) {
        _phones.value = runCatching {
            val a = JSONArray(prefs(ctx).getString("phones", "[]"))
            List(a.length()) { i -> a.getJSONObject(i).let { TrustedPhone(it.getString("key"), it.getString("name"), it.getLong("time")) } }
        }.getOrDefault(emptyList())
    }

    /** Receiver: trust the phone connecting now. Returns the secret to hand it. */
    @Synchronized fun remember(ctx: Context, name: String): String {
        val key = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        load(ctx)
        save(ctx, (listOf(TrustedPhone(key, name, System.currentTimeMillis())) + _phones.value).take(MAX))
        return key
    }

    @Synchronized fun forget(ctx: Context, key: String) {
        load(ctx)
        save(ctx, _phones.value.filter { it.key != key })
    }

    /** Receiver: does [token] prove the sender holds one of our secrets, for this session's [nonce]? */
    fun accepts(ctx: Context, token: String, nonce: String): Boolean {
        if (!token.startsWith(PREFIX)) return false
        val proofs = token.removePrefix(PREFIX).split(',').filter { it.isNotEmpty() }.toSet()
        if (proofs.isEmpty()) return false
        load(ctx)
        return _phones.value.any { proof(it.key, nonce) in proofs }
    }

    /** Sender: a receiver chose to trust us and sent this secret. */
    @Synchronized fun keep(ctx: Context, key: String) {
        if (!KEY.matches(key)) return
        val all = (listOf(key) + keys(ctx)).distinct().take(MAX)
        prefs(ctx).edit { putString("keys", JSONArray(all).toString()) }
    }

    /** Sender: the hello token for a receiver whose session Wi-Fi name is [nonce]. */
    fun token(ctx: Context, nonce: String): String = PREFIX + keys(ctx).joinToString(",") { proof(it, nonce) }

    private fun keys(ctx: Context): List<String> = runCatching {
        val a = JSONArray(prefs(ctx).getString("keys", "[]"))
        List(a.length()) { a.getString(it) }.filter { KEY.matches(it) }
    }.getOrDefault(emptyList())

    private fun proof(key: String, nonce: String): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(), "HmacSHA256")) }
        return mac.doFinal(nonce.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }

    private fun save(ctx: Context, list: List<TrustedPhone>) {
        _phones.value = list
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("key", it.key).put("name", it.name).put("time", it.time)) }
        prefs(ctx).edit { putString("phones", a.toString()) }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("trust", Context.MODE_PRIVATE)
}
