package com.glasslauncher.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.glasslauncher.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.HttpsURLConnection

/** Not a data class on purpose: no generated toString(), so tokens can't end up in a log by accident. */
class TraktTokens(val access: String, val refresh: String, val expiresAt: Long) {
    override fun toString() = "TraktTokens(***)"
}

/**
 * Trakt tokens in app-private SharedPreferences, encrypted with an AES-GCM key that lives in the
 * Android Keystore (the key never leaves it). Nothing here is ever logged.
 */
private class TokenVault(ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass_trakt_auth", Context.MODE_PRIVATE)
    private val alias = "glassio_trakt_tokens"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
        )
        return gen.generateKey()
    }

    @Synchronized
    fun save(t: TraktTokens): Boolean = runCatching {
        val plain = JSONObject().put("a", t.access).put("r", t.refresh).put("e", t.expiresAt).toString().toByteArray()
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val blob = Base64.encodeToString(c.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(c.doFinal(plain), Base64.NO_WRAP)
        // commit(), not apply(): a single-use refresh token must be on disk before the old one is forgotten.
        sp.edit().putString("blob", blob).commit()
    }.getOrDefault(false)

    @Synchronized
    fun load(): TraktTokens? = runCatching {
        val blob = sp.getString("blob", null) ?: return null
        val (iv, ct) = blob.split(":").let { Base64.decode(it[0], Base64.NO_WRAP) to Base64.decode(it[1], Base64.NO_WRAP) }
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv)) }
        val o = JSONObject(String(c.doFinal(ct)))
        TraktTokens(o.getString("a"), o.getString("r"), o.getLong("e"))
    }.getOrNull()

    @Synchronized
    fun clear() { sp.edit().remove("blob").commit() }

    var needsSignIn: Boolean
        get() = sp.getBoolean("needsSignIn", false)
        set(v) { sp.edit().putBoolean("needsSignIn", v).apply() }
}

/**
 * Trakt sign-in (device code flow, client ID only, no client secret) and authenticated GETs.
 * The client ID comes from BuildConfig (GitHub Actions secret TRAKT_CLIENT_ID); without it Trakt is off.
 */
class Trakt(ctx: Context, private val scope: CoroutineScope) {
    sealed interface State {
        data object NotConfigured : State
        data object SignedOut : State
        data class Waiting(val userCode: String, val url: String, val expiresAt: Long) : State
        data object SignedIn : State
        data class Failed(val message: String) : State
    }

    private val clientId = BuildConfig.TRAKT_CLIENT_ID.trim()
    val configured: Boolean get() = clientId.isNotBlank()
    private val vault = TokenVault(ctx)
    private val lock = Mutex()
    private var signInJob: Job? = null
    /** Called after a successful sign-in (Continue Watching loads right away). */
    var onSignedIn: () -> Unit = {}

    val state = MutableStateFlow<State>(
        when {
            !configured -> State.NotConfigured
            vault.load() != null -> State.SignedIn
            vault.needsSignIn -> State.Failed(SIGN_IN_AGAIN)
            else -> State.SignedOut
        }
    )
    val signedIn: Boolean get() = state.value == State.SignedIn

    // ---- HTTP (api.trakt.tv, and auth.trakt.tv for /oauth/*; HTTPS only, never cached, never logged)

    private class Reply(val code: Int, val body: String)

    private fun call(method: String, path: String, body: JSONObject? = null, bearer: String? = null): Reply {
        // Trakt's openapi.json: every /oauth/* request (device code, device token, token refresh, revoke) goes to auth.trakt.tv.
        val host = if (path.startsWith("/oauth/")) AUTH else API
        val c = URL(host + path).openConnection() as HttpsURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Frost-flix/${BuildConfig.VERSION_NAME}")
        c.setRequestProperty("trakt-api-version", "2")
        c.setRequestProperty("trakt-api-key", clientId)
        if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
        try {
            if (body != null) { c.doOutput = true; c.outputStream.use { it.write(body.toString().toByteArray()) } }
            val code = c.responseCode
            val text = runCatching { (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull().orEmpty()
            return Reply(code, text)
        } finally { c.disconnect() }
    }

    /**
     * "HTTP 401 · invalid_client: client not found" for the sign-in page. Only Trakt's error fields are shown,
     * never the raw body, so the client ID, device code and tokens can't appear on screen.
     */
    private fun describe(r: Reply): String {
        val o = runCatching { JSONObject(r.body) }.getOrNull()
        val err = o?.optString("error").orEmpty().take(60)
        val desc = o?.optString("error_description").orEmpty().take(160)
        val text = listOf(err, desc).filter { it.isNotBlank() }.joinToString(": ")
            .ifBlank { if (r.body.isBlank()) "no error text" else "no JSON error text" }
            .let { if (clientId.isNotEmpty()) it.replace(clientId, "***") else it }
        val hint = if (r.code == 401 && err == "invalid_client") " (Trakt doesn't know this build's Client ID; check the TRAKT_CLIENT_ID secret)" else ""
        return "HTTP ${r.code} · $text$hint"
    }

    private fun parseTokens(body: String): TraktTokens? = runCatching {
        val o = JSONObject(body)
        val access = o.optString("access_token"); val refresh = o.optString("refresh_token")
        if (access.isBlank() || refresh.isBlank()) return null
        val life = o.optLong("expires_in", 7L * 24 * 3600)
        val created = o.optLong("created_at", 0L)
        val expiresAt = if (created > 0) (created + life) * 1000 else System.currentTimeMillis() + life * 1000
        TraktTokens(access, refresh, expiresAt)
    }.getOrNull()

    // ---- Device code sign-in

    fun startSignIn() {
        if (!configured) return
        signInJob?.cancel()
        signInJob = scope.launch(Dispatchers.IO) { deviceFlow() }
    }

    fun cancelSignIn() {
        signInJob?.cancel(); signInJob = null
        if (state.value is State.Waiting) state.value = State.SignedOut
    }

    private suspend fun deviceFlow() {
        val start = runCatching { call("POST", "/oauth/device/code", JSONObject().put("client_id", clientId)) }
        val s = start.getOrNull()
        if (s == null || s.code != 200) {
            state.value = State.Failed(STEP_CODE + (if (s == null) "can't reach auth.trakt.tv (${start.exceptionOrNull()?.javaClass?.simpleName})" else describe(s)))
            return
        }
        val o = runCatching { JSONObject(s.body) }.getOrNull() ?: run { state.value = State.Failed(STEP_CODE + "HTTP 200 · reply is not JSON"); return }
        val device = o.optString("device_code")
        val userCode = o.optString("user_code")
        val url = o.optString("verification_url")
        if (device.isBlank() || userCode.isBlank() || url.isBlank()) {
            state.value = State.Failed(STEP_CODE + "HTTP 200 · reply is missing device_code, user_code or verification_url"); return
        }
        val deadline = System.currentTimeMillis() + o.optLong("expires_in", 600) * 1000
        var interval = o.optLong("interval", 5).coerceAtLeast(1)
        state.value = State.Waiting(userCode, url, deadline)
        while (System.currentTimeMillis() + interval * 1000 < deadline) {
            delay(interval * 1000)
            val r = runCatching { call("POST", "/oauth/device/token", JSONObject().put("code", device).put("client_id", clientId)) }.getOrNull()
                ?: continue // network hiccup: try again at the next interval
            when (r.code) {
                200 -> {
                    val t = parseTokens(r.body)
                    if (t == null || !lock.withLock { vault.save(t) }) { state.value = State.Failed("Couldn't store the Trakt sign-in"); return }
                    vault.needsSignIn = false
                    state.value = State.SignedIn
                    onSignedIn()
                    return
                }
                400 -> Unit // still waiting for the user
                429 -> interval += 5 // "slow down"
                404 -> { state.value = State.Failed(STEP_TOKEN + "HTTP 404 · Trakt didn't recognise the code. Try again."); return }
                409 -> { state.value = State.Failed(STEP_TOKEN + "HTTP 409 · That code was already used. Try again."); return }
                410 -> { state.value = State.Failed(STEP_TOKEN + "HTTP 410 · The code expired. Try again."); return }
                418 -> { state.value = State.Failed(STEP_TOKEN + "HTTP 418 · Sign-in was declined on Trakt."); return }
                in 500..599 -> Unit // Trakt hiccup: try again at the next interval
                else -> { state.value = State.Failed(STEP_TOKEN + describe(r)); return }
            }
        }
        state.value = State.Failed(STEP_TOKEN + "the code expired before it was entered. Try again.")
    }

    // ---- Tokens

    /** Must hold [lock]. Replaces both stored tokens on success; on invalid_grant asks to sign in again. */
    private fun refreshLocked(t: TraktTokens): TraktTokens? {
        val r = runCatching {
            call("POST", "/oauth/token", JSONObject()
                .put("client_id", clientId)
                .put("refresh_token", t.refresh)
                .put("redirect_uri", BuildConfig.TRAKT_REDIRECT_URI)
                .put("grant_type", "refresh_token"))
        }.getOrNull() ?: return t.takeIf { System.currentTimeMillis() < it.expiresAt } // offline: keep using a still-valid token
        if (r.code == 200) {
            val n = parseTokens(r.body) ?: return null
            if (!vault.save(n)) return null
            return n
        }
        val error = runCatching { JSONObject(r.body).optString("error") }.getOrDefault("")
        if (error == "invalid_grant") {
            vault.clear(); vault.needsSignIn = true
            state.value = State.Failed(SIGN_IN_AGAIN)
            return null
        }
        return t.takeIf { System.currentTimeMillis() < it.expiresAt }
    }

    /** A valid access token, refreshed when it has less than a day left. */
    private suspend fun accessToken(force: Boolean = false, used: String? = null): String? = lock.withLock {
        val t = vault.load() ?: return@withLock null
        // Another caller may already have refreshed while we waited for the lock.
        if (force && used != null && t.access != used) return@withLock t.access
        if (force || System.currentTimeMillis() > t.expiresAt - REFRESH_EARLY_MS) refreshLocked(t)?.access else t.access
    }

    /** Authenticated GET; returns the body, or null on failure. One refresh-and-retry on 401. */
    suspend fun get(path: String): String? = withContext(Dispatchers.IO) {
        if (!configured || !signedIn) return@withContext null
        val token = accessToken() ?: return@withContext null
        var r = runCatching { call("GET", path, bearer = token) }.getOrNull() ?: return@withContext null
        if (r.code == 401) {
            val again = accessToken(force = true, used = token) ?: return@withContext null
            r = runCatching { call("GET", path, bearer = again) }.getOrNull() ?: return@withContext null
        }
        if (r.code in 200..299) r.body else null
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        cancelSignIn()
        val t = lock.withLock { vault.load().also { vault.clear(); vault.needsSignIn = false } }
        state.value = if (configured) State.SignedOut else State.NotConfigured
        // Best effort: also end the session on Trakt's side.
        if (t != null) runCatching { call("POST", "/oauth/revoke", JSONObject().put("token", t.access).put("client_id", clientId)) }
    }

    companion object {
        private const val API = "https://api.trakt.tv"
        private const val AUTH = "https://auth.trakt.tv"
        private const val STEP_CODE = "Step 1 of 2 (get code) failed: "
        private const val STEP_TOKEN = "Step 2 of 2 (get token) failed: "
        private const val REFRESH_EARLY_MS = 24L * 3600 * 1000
        const val SIGN_IN_AGAIN = "Trakt sign-in expired. Sign in again."
    }
}
