package com.glasslauncher.tv.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import com.glasslauncher.tv.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Authenticator
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import org.json.JSONObject
import java.security.KeyStore
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Not a data class on purpose: no generated toString(), so tokens can't end up in a log by accident.
 * [lifeMs] is the access token's lifetime from Trakt's expires_in; 0 for tokens stored before 1.9.3-d6.
 */
class TraktTokens(val access: String, val refresh: String, val expiresAt: Long, val lifeMs: Long = 0L) {
    override fun toString() = "TraktTokens(***)"
}

/** A Trakt request that didn't give a usable answer. The message is safe to show: never a token or the client ID. */
class TraktError(message: String) : Exception(message)

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
        val plain = JSONObject().put("a", t.access).put("r", t.refresh).put("e", t.expiresAt).put("l", t.lifeMs).toString().toByteArray()
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
        TraktTokens(o.getString("a"), o.getString("r"), o.getLong("e"), o.optLong("l", 0L))
    }.getOrNull()

    @Synchronized
    fun clear() { sp.edit().remove("blob").commit() }

    var needsSignIn: Boolean
        get() = sp.getBoolean("needsSignIn", false)
        set(v) { sp.edit().putBoolean("needsSignIn", v).apply() }

    /** The Trakt username, for "Signed in as …". Not a secret. */
    var username: String
        get() = sp.getString("username", "").orEmpty()
        set(v) { sp.edit().putString("username", v).apply() }
}

/**
 * Trakt sign-in (device code flow, client ID only, no client secret) and authenticated GETs via OkHttp
 * Interceptor + Authenticator. The client ID comes from BuildConfig (GitHub Actions secret TRAKT_CLIENT_ID);
 * without it Trakt is off.
 */
class Trakt(ctx: Context, private val scope: CoroutineScope, private val foreground: StateFlow<Boolean>) {
    sealed interface State {
        /** App start: the stored sign-in is being read (Keystore decrypt, off the main thread). */
        data object Checking : State
        data object NotConfigured : State
        data object SignedOut : State
        /** Sign-in pressed, waiting for Trakt's code. */
        data object Starting : State
        /** [qrUrl] is what the QR code encodes: Trakt's complete URL (code included) when it sends one, else [url]. */
        data class Waiting(val userCode: String, val url: String, val qrUrl: String, val expiresAt: Long) : State
        data object SignedIn : State
        data class Failed(val message: String) : State
    }

    private val clientId = BuildConfig.TRAKT_CLIENT_ID.trim()
    val configured: Boolean get() = clientId.isNotBlank()
    private val vault = TokenVault(ctx)
    private val authLock = Any()
    private var signInJob: Job? = null
    /** Called after a successful sign-in (Continue Watching loads right away). */
    var onSignedIn: () -> Unit = {}
    /** Called after sign-out and when the sign-in expired (Continue Watching clears its list). */
    var onSignedOut: suspend () -> Unit = {}
    /** Result of the last "Force Trakt refresh now" (Settings → Continue Watching, hold OK on the Trakt row). */
    val refreshCheck = MutableStateFlow("")

    val state = MutableStateFlow<State>(if (configured) State.Checking else State.NotConfigured)
    val signedIn: Boolean get() = state.value == State.SignedIn
    /** Empty until known; signed-in users from before 1.9.3-d5 get it loaded at start. */
    val username = MutableStateFlow("")
    /** Completes once the stored sign-in has been read; Continue Watching waits for it before its first fetch. */
    val ready = kotlinx.coroutines.CompletableDeferred<Unit>()

    // ---- OkHttp Interceptor & Authenticator (HTTPS only, never cached, never logged)

    /** Injects standard Trakt API headers onto every outgoing request. */
    private val headerInterceptor = Interceptor { chain ->
        val req = chain.request().newBuilder()
            .header("Accept", "application/json")
            .header("User-Agent", "Frost-flix/${BuildConfig.VERSION_NAME}")
            .header("trakt-api-version", "2")
            .header("trakt-api-key", clientId)
            .build()
        chain.proceed(req)
    }

    /**
     * Injects stored Bearer credentials onto api.trakt.tv calls. A final 401 is passed back to the caller
     * as a failed request; it never clears tokens. Only the token endpoint (see [refreshLocked]) signs out.
     */
    private val authInterceptor = Interceptor { chain ->
        val incoming = chain.request()
        val authed = if (incoming.header("Authorization") != null) {
            incoming
        } else {
            val token = synchronized(authLock) { vault.load()?.access }
            if (token.isNullOrBlank()) incoming
            else incoming.newBuilder().header("Authorization", "Bearer $token").build()
        }
        chain.proceed(authed)
    }

    /**
     * Handles HTTP 401 challenges on api.trakt.tv:
     * - Gives up (returns null) if a retry already failed, so there is no retry loop. Tokens are kept.
     * - Attempts at most one synchronized token refresh via [authClient]. Only a 401 / invalid_grant from the
     *   token endpoint signs the user out (done inside [refreshLocked]). Network errors and timeouts keep the
     *   tokens and fail just this request.
     */
    private val tokenAuthenticator = Authenticator { _: Route?, response: Response ->
        if (responseCount(response) >= 2) {
            return@Authenticator null
        }
        synchronized(authLock) {
            val stored = vault.load()
            if (stored == null || stored.refresh.isBlank()) {
                return@synchronized null
            }
            val usedBearer = response.request.header("Authorization")?.removePrefix("Bearer ")?.trim()
            if (!usedBearer.isNullOrEmpty() && stored.access != usedBearer) {
                return@synchronized response.request.newBuilder()
                    .header("Authorization", "Bearer ${stored.access}")
                    .build()
            }
            when (val r = refreshLocked(stored)) {
                is Refresh.Ok -> response.request.newBuilder()
                    .header("Authorization", "Bearer ${r.tokens.access}")
                    .build()
                // Expired: refreshLocked already signed out (token endpoint said 401 / invalid_grant).
                // Failed: network error, timeout or other server reply; keep the tokens, fail this request.
                is Refresh.Expired, is Refresh.Failed -> null
            }
        }
    }

    /** Unauthenticated client for auth.trakt.tv (/oauth/...); has no Authenticator so refresh can never recurse. */
    private val authClientLazy = lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .followRedirects(false)
            .followSslRedirects(false)
            .addInterceptor(headerInterceptor)
            .build()
    }
    private val authClient: OkHttpClient by authClientLazy

    /** Authenticated client for api.trakt.tv; pairs [authInterceptor] with [tokenAuthenticator]. */
    private val apiClient: OkHttpClient by lazy {
        authClient.newBuilder()
            .addInterceptor(authInterceptor)
            .authenticator(tokenAuthenticator)
            .build()
    }

    init {
        // The Keystore decrypt and the prefs read happen here, not on the main thread during app start.
        scope.launch(Dispatchers.IO) {
            if (configured) {
                username.value = vault.username
                val s = when {
                    vault.load() != null -> State.SignedIn
                    vault.needsSignIn -> State.Failed(SIGN_IN_AGAIN)
                    else -> State.SignedOut
                }
                // Only if nothing (e.g. a sign-in started meanwhile) changed the state in the meantime.
                state.compareAndSet(State.Checking, s)
            }
            ready.complete(Unit)
            // Network: only once Frost-flix is in front.
            if (signedIn && username.value.isBlank()) { foreground.first { it }; loadUsername() }
        }
    }

    /**
     * Frost-flix went to the background: closes the idle pooled connections of both Trakt clients (they share
     * one pool). Calls still running are not cancelled, so a token refresh is never cut off halfway (Trakt's
     * refresh token is single-use). I/O: call off the main thread.
     */
    fun closeIdleConnections() {
        if (authClientLazy.isInitialized()) authClient.connectionPool.evictAll()
    }

    private fun responseCount(response: Response): Int {
        var count = 1
        var prior = response.priorResponse
        while (prior != null) {
            count++
            prior = prior.priorResponse
        }
        return count
    }


    /** Must hold [authLock]. Explicitly wipes stored tokens and marks the session expired. */
    private fun expireSessionLocked() {
        vault.clear()
        vault.needsSignIn = true
        vault.username = ""
        username.value = ""
        val current = state.value
        if (current !is State.Starting && current !is State.Waiting) {
            state.value = State.Failed(SIGN_IN_AGAIN)
        }
        // Not inline: the caller may hold Continue Watching's lock, which clearing the list needs.
        scope.launch { onSignedOut() }
    }

    /** GET /users/settings on api.trakt.tv → user.username. Best effort: on failure the page just says "Signed in to Trakt". */
    private fun loadUsername(explicitBearer: String? = null) {
        val r = runCatching { executeApiGet("/users/settings", explicitBearer) }.getOrNull() ?: return
        if (r.code != 200) return
        val name = runCatching { JSONObject(r.body).optJSONObject("user")?.optString("username").orEmpty() }.getOrDefault("").trim().take(60)
        if (name.isNotBlank()) { vault.username = name; username.value = name }
    }

    /**
     * Trakt's complete activation URL (code already in it) if the reply has one, else null.
     * Only accepted when it is https on the same host as verification_url and contains the code.
     */
    private fun completeUrl(o: JSONObject, url: String, userCode: String): String? {
        val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return null
        return listOf("verification_url_complete", "verification_uri_complete")
            .map { o.optString(it).trim() }
            .firstOrNull { u ->
                u.startsWith("https://") && u.contains(userCode) &&
                    runCatching { java.net.URI(u).host }.getOrNull().equals(host, ignoreCase = true)
            }
    }

    private class Reply(val code: Int, val body: String)

    private fun executeOAuthPost(path: String, payload: JSONObject): Reply {
        val req = Request.Builder()
            .url(AUTH + path)
            .post(payload.toString().toRequestBody(JSON_MEDIA))
            .build()
        authClient.newCall(req).execute().use { res -> return readReply(res) }
    }

    private fun executeApiGet(path: String, explicitBearer: String? = null): Reply {
        val builder = Request.Builder().url(API + path).get()
        if (!explicitBearer.isNullOrBlank()) builder.header("Authorization", "Bearer $explicitBearer")
        apiClient.newCall(builder.build()).execute().use { res -> return readReply(res) }
    }

    private fun readReply(res: Response): Reply {
        val code = res.code
        val body = res.body
        val text = if (body == null) ""
        else if (code in 200..299) readCapped(body.byteStream(), body.contentLength())
        else runCatching { readCapped(body.byteStream(), max = 64 * 1024) }.getOrNull().orEmpty()
        return Reply(code, text)
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
        TraktTokens(access, refresh, expiresAt, life * 1000)
    }.getOrNull()

    // ---- Device code sign-in

    fun startSignIn() {
        if (!configured) return
        signInJob?.cancel()
        state.value = State.Starting
        signInJob = scope.launch(Dispatchers.IO) { deviceFlow() }
    }

    fun cancelSignIn() {
        signInJob?.cancel(); signInJob = null
        if (state.value is State.Waiting || state.value == State.Starting) state.value = State.SignedOut
    }

    private suspend fun deviceFlow() {
        val start = runCatching { executeOAuthPost("/oauth/device/code", JSONObject().put("client_id", clientId)) }
        currentCoroutineContext().ensureActive()
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
        state.value = State.Waiting(userCode, url, completeUrl(o, url, userCode) ?: url, deadline)
        while (System.currentTimeMillis() + interval * 1000 < deadline) {
            delay(interval * 1000)
            val reply = runCatching {
                executeOAuthPost("/oauth/device/token", JSONObject().put("code", device).put("client_id", clientId))
            }.getOrNull()
            currentCoroutineContext().ensureActive()
            val r = reply ?: continue // network hiccup: try again at the next interval
            when (r.code) {
                200 -> {
                    val t = parseTokens(r.body)
                    currentCoroutineContext().ensureActive()
                    val saved = t != null && synchronized(authLock) { vault.save(t) }
                    if (t == null || !saved) {
                        state.value = State.Failed("Couldn't store the Trakt sign-in"); return
                    }
                    vault.needsSignIn = false
                    loadUsername(t.access)
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

    private sealed interface Refresh {
        class Ok(val tokens: TraktTokens) : Refresh
        class Failed(val reason: String, val keep: TraktTokens?) : Refresh
        data object Expired : Refresh
    }

    /**
     * Must hold [authLock]. Sends client_id, refresh_token, grant_type and redirect_uri to auth.trakt.tv.
     * Replaces both stored tokens on success; on 401 or invalid_grant explicitly clears stored tokens.
     */
    private fun refreshLocked(t: TraktTokens): Refresh {
        val valid = t.takeIf { System.currentTimeMillis() < it.expiresAt }
        val r = try {
            executeOAuthPost("/oauth/token", JSONObject()
                .put("client_id", clientId)
                .put("refresh_token", t.refresh)
                .put("redirect_uri", BuildConfig.TRAKT_REDIRECT_URI)
                .put("grant_type", "refresh_token"))
        } catch (e: Exception) {
            return Refresh.Failed("can't reach auth.trakt.tv (${e.javaClass.simpleName})", valid)
        }
        if (r.code == 200) {
            val n = parseTokens(r.body) ?: return Refresh.Failed("HTTP 200 · reply has no tokens", valid)
            if (!vault.save(n)) return Refresh.Failed("couldn't store the new tokens", valid)
            return Refresh.Ok(n)
        }
        val error = runCatching { JSONObject(r.body).optString("error") }.getOrDefault("")
        if (r.code == 401 || error == "invalid_grant") {
            expireSessionLocked()
            return Refresh.Expired
        }
        return Refresh.Failed(describe(r), valid)
    }

    /** Authenticated GET on api.trakt.tv; returns the body, or throws [TraktError] with a safe reason. */
    suspend fun fetch(path: String): String = withContext(Dispatchers.IO) {
        if (!configured) throw TraktError("Trakt not configured")
        if (!signedIn) throw TraktError("not signed in to Trakt")
        val r = try {
            executeApiGet(path)
        } catch (e: ReplyTooLarge) {
            throw TraktError(e.message ?: "reply too large")
        } catch (e: Exception) {
            throw TraktError("can't reach api.trakt.tv (${e.javaClass.simpleName})")
        }
        if (r.code == 401) throw TraktError(SIGN_IN_AGAIN)
        if (r.code !in 200..299) throw TraktError(describe(r))
        r.body
    }

    /** Like [fetch], or null on any failure. */
    suspend fun get(path: String): String? = runCatching { fetch(path) }.getOrNull()

    /**
     * Debug: refreshes the token now, whatever its age, and returns what happened (also kept in [refreshCheck]).
     * Never shows a token; only the outcome, Trakt's error text and the new expiry.
     */
    suspend fun forceRefresh(): String = withContext(Dispatchers.IO) {
        val msg = if (!signedIn) "Not signed in to Trakt" else synchronized(authLock) {
            val t = vault.load() ?: return@synchronized "Refresh failed: the stored sign-in can't be read"
            val fmt = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault())
            when (val r = refreshLocked(t)) {
                is Refresh.Ok -> {
                    val life = r.tokens.lifeMs / 3_600_000.0
                    "Refresh OK · new token valid until ${fmt.format(java.util.Date(r.tokens.expiresAt))}" +
                        " (lifetime ${if (life >= 48) "${(life / 24).toInt()} days" else "${life.toInt()} hours"})"
                }
                is Refresh.Failed -> "Refresh failed: ${r.reason}" +
                    (r.keep?.let { " · the current token still works until ${fmt.format(java.util.Date(it.expiresAt))}" } ?: "")
                Refresh.Expired -> "Refresh failed: $SIGN_IN_AGAIN"
            }
        }
        refreshCheck.value = msg
        msg
    }

    suspend fun signOut() = withContext(Dispatchers.IO) {
        cancelSignIn()
        val t = synchronized(authLock) {
            vault.load().also { vault.clear(); vault.needsSignIn = false; vault.username = "" }
        }
        runCatching { apiClient.dispatcher.cancelAll() }
        username.value = ""
        refreshCheck.value = ""
        state.value = if (configured) State.SignedOut else State.NotConfigured
        onSignedOut()
        // Best effort: also end the session on Trakt's side.
        if (t != null) runCatching { executeOAuthPost("/oauth/revoke", JSONObject().put("token", t.access).put("client_id", clientId)) }
    }

    companion object {
        private const val API = "https://api.trakt.tv"
        private const val AUTH = "https://auth.trakt.tv"
        private const val STEP_CODE = "Step 1 of 2 (get code) failed: "
        private const val STEP_TOKEN = "Step 2 of 2 (get token) failed: "
        private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()
        const val SIGN_IN_AGAIN = "Trakt sign-in expired. Sign in again."
    }
}
