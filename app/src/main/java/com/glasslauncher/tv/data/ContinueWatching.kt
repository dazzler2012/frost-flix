package com.glasslauncher.tv.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * One title in progress. season/episode are 0 for movies. percent is -1 for "up next" (the next episode
 * of a show you are partway through), otherwise the playback position Trakt has (0..100).
 */
data class CwEntry(
    val type: String, val id: String, val name: String, val year: String,
    val season: Int, val episode: Int, val percent: Int, val at: Long,
) {
    val key: String get() = "$type:$id"
}

/**
 * Continue Watching from Trakt: in-progress movies and episodes (/sync/playback) plus the next episode of
 * shows you are partway through (/sync/progress/watched). Also the Trakt watchlist (/sync/watchlist) for the
 * Watchlist row, fetched with the same timing. Results are cached in app-private storage and fetched on app
 * start and at most every 30 minutes, only while Frost-flix is in front. Pictures come from your add-ons and TMDB.
 */
class ContinueWatching(
    ctx: Context,
    private val settings: Settings,
    private val http: Http,
    private val trakt: Trakt,
    private val catalogs: CatalogRepository,
    private val scope: CoroutineScope,
    private val foreground: StateFlow<Boolean>,
) {
    private val sp = ctx.getSharedPreferences("glass_cw_trakt", Context.MODE_PRIVATE)
    private val fetchLock = Mutex()
    val entries = MutableStateFlow(load())
    val items = MutableStateFlow<List<HeroItem>>(emptyList())
    /** The Trakt watchlist (movies and shows, newest first), fetched and cached together with Continue Watching. */
    val watchEntries = MutableStateFlow(load("watchlist"))
    val watchItems = MutableStateFlow<List<HeroItem>>(emptyList())
    val lastFetch = MutableStateFlow(sp.getLong("fetchedAt", 0L))
    /** Why the last update failed, for Settings → Continue Watching; empty after a successful update. */
    val lastError = MutableStateFlow("")
    /** Failed updates in a row, and when the next automatic try is due (see [BACKOFF_MIN]). Guarded by [fetchLock]. */
    private var failures = 0
    private var nextTry = 0L

    fun start() {
        trakt.onSignedIn = { scope.launch(Dispatchers.IO) { fetch(force = true) } }
        trakt.onSignedOut = { clear() }
        scope.launch(Dispatchers.IO) {
            rebuildItems()
            trakt.ready.await()
            while (true) {
                foreground.first { it }
                runCatching { fetch(force = false) }
                delay(60_000)
            }
        }
    }

    /**
     * Fetches from Trakt when the cache is 30 minutes old or more ([force]: right after sign-in, or OK on the
     * Trakt row). After a failure the next automatic try waits 1, 2, 5, 15, then every 30 minutes.
     */
    suspend fun fetch(force: Boolean) = fetchLock.withLock {
        val wantCw = settings.continueMode != "off"
        val wantWatchlist = settings.watchlistRow
        if (!trakt.signedIn || (!wantCw && !wantWatchlist)) return@withLock
        val now = System.currentTimeMillis()
        // The watchlist was never fetched (first start after updating from a version without it): don't wait
        // for the 30 minutes, which still count from the last Continue Watching update.
        val firstWatchlist = wantWatchlist && !sp.contains("watchlist")
        if (!force) {
            if (failures > 0 && now < nextTry) return@withLock
            if (failures == 0 && !firstWatchlist && now - lastFetch.value < INTERVAL_MS) return@withLock
        }
        // Each list is kept when its own request fails; a failure of either one starts the backoff.
        var error: Exception? = null
        var cw: List<CwEntry>? = null
        var watch: List<CwEntry>? = null
        if (wantCw) try { cw = fetchContinue() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = e }
        if (wantWatchlist) try { watch = fetchWatchlist() } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { error = error ?: e }
        // Signed out (or the sign-in expired) while this was loading: don't bring the lists back.
        if (!trakt.signedIn) return@withLock
        cw?.let { entries.value = it; save(it) }
        watch?.let { watchEntries.value = it; save(it, "watchlist") }
        if (error != null) {
            failed(error)
        } else {
            failures = 0; nextTry = 0L; lastError.value = ""
            lastFetch.value = System.currentTimeMillis()
            sp.edit().putLong("fetchedAt", lastFetch.value).apply()
        }
        if (cw != null || watch != null) rebuildItems()
    }

    /** /sync/watchlist: movies and shows, newest first, at most [MAX_WATCHLIST]. */
    private suspend fun fetchWatchlist(): List<CwEntry> {
        val list = JSONArray(trakt.fetch("/sync/watchlist"))
        val out = mutableListOf<CwEntry>()
        for (i in 0 until list.length()) {
            val o = list.optJSONObject(i) ?: continue
            val at = time(o.optString("listed_at"))
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.let { m -> entry("movie", m, 0, 0, -1, at)?.let(out::add) }
                "show" -> o.optJSONObject("show")?.let { m -> entry("series", m, 0, 0, -1, at)?.let(out::add) }
            }
        }
        return out.sortedByDescending { it.at }.distinctBy { it.key }.take(MAX_WATCHLIST)
    }

    /** /sync/playback plus the next episodes from /sync/progress/watched. */
    private suspend fun fetchContinue(): List<CwEntry> {
        val playback = JSONArray(trakt.fetch("/sync/playback"))
        val watched = runCatching { JSONArray(trakt.fetch("/sync/progress/watched?hide_completed=true&limit=100")) }.getOrNull()

        val out = mutableListOf<CwEntry>()
        for (i in 0 until playback.length()) {
            val o = playback.optJSONObject(i) ?: continue
            val percent = o.optDouble("progress", 0.0).toInt()
            if (percent < 1 || percent > 95) continue
            val at = time(o.optString("paused_at"))
            when (o.optString("type")) {
                "movie" -> o.optJSONObject("movie")?.let { m -> entry("movie", m, 0, 0, percent, at)?.let(out::add) }
                "episode" -> {
                    val show = o.optJSONObject("show") ?: continue
                    val ep = o.optJSONObject("episode") ?: continue
                    entry("series", show, ep.optInt("season"), ep.optInt("number"), percent, at)?.let(out::add)
                }
            }
        }
        // One slide per title: the most recently paused episode wins.
        val paused = out.sortedByDescending { it.at }.distinctBy { it.key }.toMutableList()
        val keys = paused.map { it.key }.toHashSet()
        val next = mutableListOf<CwEntry>()
        if (watched != null) for (i in 0 until watched.length()) {
            val o = watched.optJSONObject(i) ?: continue
            val show = o.optJSONObject("show") ?: continue
            val p = o.optJSONObject("progress") ?: continue
            if (p.optInt("completed") <= 0) continue
            val ep = p.optJSONObject("next_episode") ?: continue
            val e = entry("series", show, ep.optInt("season"), ep.optInt("number"), -1, time(p.optString("last_watched_at"))) ?: continue
            if (e.key !in keys) next += e
        }
        return (paused + next.sortedByDescending { it.at }.take(MAX_NEXT)).sortedByDescending { it.at }.take(MAX_ITEMS)
    }

    private fun entry(type: String, media: JSONObject, s: Int, e: Int, percent: Int, at: Long): CwEntry? {
        val ids = media.optJSONObject("ids") ?: return null
        val imdb = ids.optString("imdb").takeIf { it.startsWith("tt") }
        val tmdb = ids.optInt("tmdb", 0).takeIf { it > 0 }
        val id = imdb ?: tmdb?.let { "tmdb:$it" } ?: return null
        val year = media.optInt("year", 0).takeIf { it > 0 }?.toString().orEmpty()
        return CwEntry(type, id, media.optString("title"), year, s, e, percent, at)
    }

    private fun time(s: String) = runCatching { Instant.parse(s).toEpochMilli() }.getOrDefault(0L)

    private fun failed(e: Exception) {
        failures++
        val waitMin = BACKOFF_MIN[(failures - 1).coerceAtMost(BACKOFF_MIN.size - 1)]
        nextTry = System.currentTimeMillis() + waitMin * 60_000L
        val why = when (e) {
            is TraktError -> e.message.orEmpty()
            is org.json.JSONException -> "Trakt's reply is not a list"
            else -> e.javaClass.simpleName
        }
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        lastError.value = "Couldn't update at $at: $why · next try in $waitMin min"
    }

    /** Clears the cached list (sign out, sign-in expired). Waits for a running update, so it can't come back. */
    suspend fun clear() = fetchLock.withLock {
        failures = 0; nextTry = 0L; lastError.value = ""
        entries.value = emptyList(); items.value = emptyList(); save(emptyList())
        watchEntries.value = emptyList(); watchItems.value = emptyList(); save(emptyList(), "watchlist")
        lastFetch.value = 0L; sp.edit().putLong("fetchedAt", 0L).apply()
    }

    // ---- Artwork: your add-ons' meta resource (not Cinemeta), then TMDB through CatalogRepository.enrich

    private class MetaAddon(val base: String, val types: Set<String>, val prefixes: List<String>)

    private fun metaAddons(): List<MetaAddon> = catalogs.addons.mapNotNull { url ->
        if (runCatching { java.net.URI(url).host.orEmpty() }.getOrDefault("").contains("cinemeta", true)) return@mapNotNull null
        val m = runCatching { http.json(url, 24L * 3600 * 1000) }.getOrNull() ?: return@mapNotNull null
        fun strings(a: JSONArray?) = (0 until (a?.length() ?: 0)).map { a!!.optString(it) }.filter { it.isNotBlank() }
        val res = m.optJSONArray("resources") ?: return@mapNotNull null
        var found: MetaAddon? = null
        for (i in 0 until res.length()) {
            val r = res.opt(i)
            val base = url.removeSuffix("/manifest.json").trimEnd('/')
            if (r == "meta") found = MetaAddon(base, strings(m.optJSONArray("types")).toSet(), strings(m.optJSONArray("idPrefixes")))
            else if (r is JSONObject && r.optString("name") == "meta") {
                val types = strings(r.optJSONArray("types")).ifEmpty { strings(m.optJSONArray("types")) }
                val prefixes = strings(r.optJSONArray("idPrefixes")).ifEmpty { strings(m.optJSONArray("idPrefixes")) }
                found = MetaAddon(base, types.toSet(), prefixes)
            }
        }
        found
    }

    private fun meta(addons: List<MetaAddon>, e: CwEntry): HeroItem? {
        for (a in addons) {
            if (a.types.isNotEmpty() && e.type !in a.types) continue
            if (a.prefixes.isNotEmpty() && a.prefixes.none { e.id.startsWith(it) }) continue
            val m = runCatching {
                http.json("${a.base}/meta/${catalogs.enc(e.type)}/${catalogs.enc(e.id)}.json", 7L * 24 * 3600 * 1000).optJSONObject("meta")
            }.getOrNull() ?: continue
            catalogs.parseMeta(m, e.type)?.let { return it }
        }
        return null
    }

    private fun rebuildItems() {
        val list = entries.value
        val watch = watchEntries.value
        val addons = if (list.isEmpty() && watch.isEmpty()) emptyList() else metaAddons()
        watchItems.value = watch.map { e ->
            val base = meta(addons, e) ?: HeroItem(
                key = e.key, id = e.id, type = e.type, name = e.name, poster = "", background = "", logo = "",
                overview = "", rating = "", year = e.year, genres = emptyList(), platform = "",
            )
            base.copy(key = e.key, id = e.id, type = e.type, name = base.name.ifBlank { e.name })
        }
        items.value = list.map { e ->
            val sub = if (e.type == "series") "S${e.season} · E${e.episode}" else ""
            val progress = if (e.percent < 0) 0f else e.percent / 100f
            val base = meta(addons, e) ?: HeroItem(
                key = e.key, id = e.id, type = e.type, name = e.name, poster = "", background = "", logo = "",
                overview = "", rating = "", year = e.year, genres = emptyList(), platform = "",
            )
            base.copy(key = e.key, id = e.id, type = e.type, name = base.name.ifBlank { e.name }, progress = progress, sub = sub)
        }
    }

    private fun load(key: String = "entries"): List<CwEntry> = runCatching {
        val a = JSONArray(sp.getString(key, "[]"))
        (0 until a.length()).map { i ->
            val o = a.getJSONObject(i)
            CwEntry(o.getString("t"), o.getString("id"), o.optString("n"), o.optString("y"), o.optInt("s"), o.optInt("e"), o.optInt("p"), o.optLong("at"))
        }
    }.getOrDefault(emptyList())

    private fun save(l: List<CwEntry>, key: String = "entries") {
        val a = JSONArray()
        l.forEach { a.put(JSONObject().put("t", it.type).put("id", it.id).put("n", it.name).put("y", it.year).put("s", it.season).put("e", it.episode).put("p", it.percent).put("at", it.at)) }
        sp.edit().putString(key, a.toString()).apply()
    }

    companion object {
        private const val INTERVAL_MS = 30L * 60 * 1000
        private const val MAX_NEXT = 10
        private const val MAX_ITEMS = 20
        private const val MAX_WATCHLIST = 30
        /** Minutes to wait after the 1st, 2nd, 3rd, 4th and every later failed update in a row. */
        private val BACKOFF_MIN = longArrayOf(1, 2, 5, 15, 30)
    }
}
