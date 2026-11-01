package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/** Sent with every request Frost-flix makes. */
val USER_AGENT = "Frost-flix/${com.glasslauncher.tv.BuildConfig.VERSION_NAME}"

/** Largest API reply read into memory (add-on catalogs, TMDB, Trakt, weather). */
const val MAX_REPLY_BYTES = 5 * 1024 * 1024

/** Thrown when a reply is larger than [MAX_REPLY_BYTES]; nothing past the limit is read. */
class ReplyTooLarge : IOException("Reply is larger than ${MAX_REPLY_BYTES / (1024 * 1024)} MB")

/** Reads [input] as UTF-8 text, failing with [ReplyTooLarge] instead of filling memory. */
fun readCapped(input: InputStream, declaredLength: Long = -1L, max: Int = MAX_REPLY_BYTES): String {
    if (declaredLength > max) throw ReplyTooLarge()
    val out = java.io.ByteArrayOutputStream()
    val buf = ByteArray(16 * 1024)
    input.use {
        while (true) {
            val n = it.read(buf)
            if (n < 0) break
            if (out.size() + n > max) throw ReplyTooLarge()
            out.write(buf, 0, n)
        }
    }
    return out.toString("UTF-8")
}

/** Small JSON-over-HTTP helper with an on-disk cache that also serves as an offline fallback. */
class Http(ctx: Context) {
    private val dir = File(ctx.cacheDir, "api-cache").apply { mkdirs() }

    /**
     * One client for every request made here, so its pooled connections can be closed when Frost-flix goes
     * to the background ([closeConnections]). Like the HttpURLConnection used before: redirects are followed,
     * but never between https and http.
     */
    private val clientLazy = lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(12, TimeUnit.SECONDS)
            .followSslRedirects(false)
            .build()
    }
    private val client: OkHttpClient by clientLazy

    /**
     * True while Frost-flix is in the background (set by GlassApp). Requests then use only the disk cache
     * (even when old) and never the network, so work cut short by going to the background stops quickly.
     */
    @Volatile var inBackground = false

    /**
     * Frost-flix went to the background: cancels the calls still running and closes the pooled connections.
     * I/O: call off the main thread.
     */
    fun closeConnections() {
        if (!clientLazy.isInitialized()) return
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
    }

    private fun key(url: String) =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun text(url: String, ttlMs: Long, bearer: String? = null): String {
        val f = File(dir, key(url) + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs) {
            runCatching { return f.readText() }
        }
        try {
            if (inBackground) throw IOException("Frost-flix is in the background")
            val req = Request.Builder().url(url)
                .header("Accept", "application/json")
                .header("User-Agent", USER_AGENT)
                .apply { if (bearer != null) header("Authorization", "Bearer $bearer") }
                .build()
            client.newCall(req).execute().use { r ->
                if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
                val b = r.body ?: throw IOException("empty reply")
                val body = readCapped(b.byteStream(), b.contentLength())
                runCatching { f.writeText(body) }
                return body
            }
        } catch (e: Exception) {
            if (f.exists()) runCatching { return f.readText() }
            throw e
        }
    }

    fun json(url: String, ttlMs: Long, bearer: String? = null) = JSONObject(text(url, ttlMs, bearer))
    fun jsonArray(url: String, ttlMs: Long = 0) = JSONArray(text(url, ttlMs))

    /** Never cached and never falls back: used for the local Nuvio add-on server. */
    fun liveArray(url: String, timeoutMs: Int = 2500): JSONArray {
        // Same pool as [client], shorter timeouts.
        val c = client.newBuilder()
            .connectTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .readTimeout(timeoutMs.toLong(), TimeUnit.MILLISECONDS)
            .build()
        c.newCall(Request.Builder().url(url).header("User-Agent", USER_AGENT).build()).execute().use { r ->
            if (!r.isSuccessful) throw IllegalStateException("HTTP ${r.code}")
            val b = r.body ?: throw IOException("empty reply")
            return JSONArray(readCapped(b.byteStream(), b.contentLength()))
        }
    }
}
