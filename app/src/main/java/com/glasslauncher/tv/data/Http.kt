package com.glasslauncher.tv.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

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

    private fun key(url: String) =
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }

    fun text(url: String, ttlMs: Long, bearer: String? = null): String {
        val f = File(dir, key(url) + ".json")
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < ttlMs) {
            runCatching { return f.readText() }
        }
        try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 8000; c.readTimeout = 12000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("User-Agent", USER_AGENT)
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer $bearer")
            try {
                if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
                val body = readCapped(c.inputStream, c.contentLengthLong)
                runCatching { f.writeText(body) }
                return body
            } finally { c.disconnect() }
        } catch (e: Exception) {
            if (f.exists()) runCatching { return f.readText() }
            throw e
        }
    }

    fun json(url: String, ttlMs: Long, bearer: String? = null) = JSONObject(text(url, ttlMs, bearer))
    fun jsonArray(url: String, ttlMs: Long = 0) = JSONArray(text(url, ttlMs))

    /** Never cached and never falls back: used for the local Nuvio add-on server. */
    fun liveArray(url: String, timeoutMs: Int = 2500): JSONArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("HTTP ${c.responseCode}")
            return JSONArray(readCapped(c.inputStream, c.contentLengthLong))
        } finally { c.disconnect() }
    }
}
