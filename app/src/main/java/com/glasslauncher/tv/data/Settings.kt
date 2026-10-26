package com.glasslauncher.tv.data

import android.content.Context
import androidx.compose.runtime.mutableIntStateOf

/**
 * Every launcher option lives here. Reads touch a Compose state counter, so any composable that
 * reads a property recomposes automatically when a setter bumps it.
 */
class Settings(private val ctx: Context) {
    private val sp = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)
    private val rev = mutableIntStateOf(0)

    private fun touch() { rev.intValue = rev.intValue + 1 }
    private fun b(k: String, d: Boolean): Boolean { rev.intValue; return sp.getBoolean(k, d) }
    private fun i(k: String, d: Int): Int { rev.intValue; return sp.getInt(k, d) }
    private fun f(k: String, d: Float): Float { rev.intValue; return sp.getFloat(k, d) }
    private fun s(k: String, d: String): String { rev.intValue; return sp.getString(k, d) ?: d }
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply(); touch() }
    private fun putI(k: String, v: Int) { sp.edit().putInt(k, v).apply(); touch() }
    private fun putF(k: String, v: Float) { sp.edit().putFloat(k, v).apply(); touch() }
    private fun putS(k: String, v: String) { sp.edit().putString(k, v).apply(); touch() }
    private fun list(k: String): List<String> =
        s(k, "").split('\n').map { it.trim() }.filter { it.isNotEmpty() }
    private fun putList(k: String, v: List<String>) = putS(k, v.joinToString("\n"))

    /** True while [applyMode] writes a preset; any other write to a preset value is the user's own choice. */
    private var applyingMode = false
    /**
     * For the values a performance mode sets: a change made by the user switches the mode to "custom",
     * so Auto (applied again at every app start) never overwrites it.
     */
    private fun userTuned() { if (!applyingMode && perfMode != "custom") putS("perfMode", "custom") }

    // ---- Hero / carousel
    var heroAutoplay: Boolean get() = b("heroAutoplay", true); set(v) = putB("heroAutoplay", v)
    /** auto | performance | balanced | quality | custom */
    var perfMode: String get() = s("perfMode", "auto"); set(v) = putS("perfMode", v)

    /**
     * What this TV can carry, from its memory and GPU: 2 GB / GLES 2 boxes get "performance",
     * 3-4 GB boxes (Shield TV Pro, Google TV Streamer) "balanced", bigger ones "quality".
     * The Android version is not a reason to drop to "performance": below Android 13 the glass
     * refraction simply stays off (it needs AGSL), the rest of "balanced" works the same.
     */
    fun deviceTier(): String {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val mi = android.app.ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        val gb = mi.totalMem / (1024.0 * 1024 * 1024)
        val gles = am.deviceConfigurationInfo.reqGlEsVersion
        val cores = Runtime.getRuntime().availableProcessors()
        return when {
            am.isLowRamDevice || gb < 2.6 || gles < 0x30000 -> "performance"
            gb < 5.0 || cores < 6 -> "balanced"
            else -> "quality"
        }
    }
    /** The tier Auto picked, for display. */
    val autoTier: String by lazy { deviceTier() }
    /** performance | balanced | quality | custom: what is in effect, with Auto resolved. */
    fun effectiveMode(): String = perfMode.let { if (it == "auto") autoTier else it }

    /** Glass copies of the backdrop: 480 px (light, default) or 720 px (crisper glass, more memory). */
    var glassRes: Int get() = i("glassRes", 480); set(v) { putI("glassRes", if (v >= 720) 720 else 480); userTuned() }

    /** Which picture a title uses: main | second | best (highest resolution) | addon (the add-on's own). */
    var artChoice: String get() = s("artChoice", "main"); set(v) = putS("artChoice", v)
    /** Per-title picture picked with a long press on the carousel: "key\turl" lines. */
    fun artPick(key: String): String? = artPicks()[key]
    // The carousel asks for every slide it prepares on every recomposition: parse the stored lines once per change.
    private var picksRaw: String? = null
    private var picksMap: Map<String, String> = emptyMap()
    private fun artPicks(): Map<String, String> {
        val raw = s("artPicks", "")
        if (raw != picksRaw) {
            picksMap = raw.split('\n').map { it.trim() }.filter { '\t' in it }
                .associate { it.substringBefore('\t') to it.substringAfter('\t') }
            picksRaw = raw
        }
        return picksMap
    }
    fun setArtPick(key: String, url: String?) {
        val rest = list("artPicks").filterNot { it.startsWith("$key\t") }
        putList("artPicks", if (url == null) rest else (rest + "$key\t$url").takeLast(300))
    }
    fun clearArtPicks() = putList("artPicks", emptyList())
    /** max = original pictures (4K source, sharpest) | high = 1280 px pictures (faster, lighter) */
    var imageQuality: String get() = s("imageQuality", "max"); set(v) { putS("imageQuality", v); userTuned() }
    /** How many slides ahead are prepared (pictures, logos, glass). */
    var prefetchDepth: Int get() = i("prefetchDepth", 4); set(v) { putI("prefetchDepth", v.coerceIn(1, 8)); userTuned() }

    fun applyMode(m: String) {
        applyingMode = true
        try { applyPreset(if (m == "auto") deviceTier() else m) } finally { applyingMode = false }
        perfMode = m
    }

    private fun applyPreset(tier: String) {
        when (tier) {
            "performance" -> { imageQuality = "high"; prefetchDepth = 2; transitionMs = 250; refraction = 0; heroGallery = false; glassRes = 480 }
            // Balanced (Auto on 3-4 GB boxes such as the Shield TV Pro): 3 slides prepared ahead and a 350 ms
            // crossfade (were 4 and 450 ms) keep less decoding and fewer animated frames on 4-core boxes.
            "balanced" -> { imageQuality = "max"; prefetchDepth = 3; transitionMs = 350; refraction = 6; heroGallery = true; galleryShots = 2; glassRes = 480 }
            "quality" -> { imageQuality = "max"; prefetchDepth = 6; transitionMs = 700; refraction = 14; heroGallery = true; galleryShots = 3; glassRes = 720 }
        }
    }

    /** Apple TV style: each title shows a few of its backdrops before the carousel moves on. */
    var heroGallery: Boolean get() = b("heroGallery", true); set(v) { putB("heroGallery", v); userTuned() }
    var galleryShots: Int get() = i("galleryShots", 3); set(v) { putI("galleryShots", v.coerceIn(2, 6)); userTuned() }
    var heroIntervalSec: Int get() = i("heroIntervalSec", 8); set(v) = putI("heroIntervalSec", v.coerceIn(3, 60))
    /** logo | text | hidden */
    var titleMode: String get() = s("titleMode", "logo"); set(v) = putS("titleMode", v)
    var titleScale: Float get() = f("titleScale", 1.0f); set(v) = putF("titleScale", v.coerceIn(0.5f, 2.2f))
    var showMeta: Boolean get() = b("showMeta", true); set(v) = putB("showMeta", v)
    var showGenres: Boolean get() = b("showGenres", true); set(v) = putB("showGenres", v)
    var showSynopsis: Boolean get() = b("showSynopsis", true); set(v) = putB("showSynopsis", v)
    /** Synopsis appears only while the carousel is selected. */
    var synopsisOnFocus: Boolean get() = b("synopsisOnFocus", false); set(v) = putB("synopsisOnFocus", v)
    /** Back on the home screen starts the screensaver (like Apple TV). */
    var backSaver: Boolean get() = b("backSaver", true); set(v) = putB("backSaver", v)
    var synopsisScale: Float get() = f("synopsisScale", 1.0f); set(v) = putF("synopsisScale", v.coerceIn(0.6f, 2.0f))
    var synopsisLines: Int get() = i("synopsisLines", 3); set(v) = putI("synopsisLines", v.coerceIn(1, 8))
    /** window | progress | hidden */
    var dotsMode: String get() = s("dotsMode", "window"); set(v) = putS("dotsMode", v)
    var dotsMax: Int get() = i("dotsMax", 10); set(v) = putI("dotsMax", v.coerceIn(3, 20))
    var perCatalog: Int get() = i("perCatalog", 20); set(v) = putI("perCatalog", v.coerceIn(5, 500))
    /** 0 = unlimited */
    var maxItems: Int get() = i("maxItems", 0); set(v) = putI("maxItems", v.coerceAtLeast(0))
    var shuffle: Boolean get() = b("shuffle", false); set(v) = putB("shuffle", v)
    /** en | ar */
    var descLang: String get() = s("descLang", "en"); set(v) = putS("descLang", v)
    var tmdbToken: String get() = s("tmdbToken", ""); set(v) = putS("tmdbToken", v.trim())

    var transitionMs: Int get() = i("transitionMs", 700); set(v) { putI("transitionMs", v.coerceIn(100, 2000)); userTuned() }
    var dotsAutoHide: Boolean get() = b("dotsAutoHide", true); set(v) = putB("dotsAutoHide", v)

    // ---- Top Shelf (the backdrop follows the selected app in the dock, like Apple TV)
    var topShelf: Boolean get() = b("topShelf", true); set(v) = putB("topShelf", v)
    /** Use the rows apps publish for the Android TV home screen (needs READ_TV_LISTINGS, asked when switched on). */
    var topShelfAppRows: Boolean get() = b("topShelfAppRows", false); set(v) = putB("topShelfAppRows", v)

    // ---- Clock / dock look
    var topAutoHide: Boolean get() = b("topAutoHide", false); set(v) = putB("topAutoHide", v)
    var topHideSec: Int get() = i("topHideSec", 5); set(v) = putI("topHideSec", v.coerceIn(2, 30))
    var cornerRadius: Int get() = i("cornerRadius", 14); set(v) = putI("cornerRadius", v.coerceIn(0, 40))
    /** Index of the Apps folder among the dock apps; -1 = after the last app. */
    var folderPos: Int get() = i("folderPos", -1); set(v) = putI("folderPos", v)
    /** 0.6 .. 1.0 (1.0 = full width) */
    var dockScale: Float get() = f("dockScale", 1.0f); set(v) = putF("dockScale", v.coerceIn(0.6f, 1.0f))
    /** Background of the dock and the Apps folder: liquid | clear | amoled | bow */
    var dockStyle: String get() = s("dockStyle", "liquid"); set(v) = putS("dockStyle", v)
    /** Background of settings and the icon picker: liquid | clear | amoled */
    var panelStyle: String get() = s("panelStyle", "liquid"); set(v) = putS("panelStyle", v)
    /** liquid | clear | amoled | bow (black on white) */
    var tileStyle: String get() = s("tileStyle", "liquid"); set(v) = putS("tileStyle", v)
    /** How far the glass rim bends the background, dp. */
    var refraction: Int get() = i("refraction", 14); set(v) { putI("refraction", v.coerceIn(0, 40)); userTuned() }
    var metaScale: Float get() = f("metaScale", 1.0f); set(v) = putF("metaScale", v.coerceIn(0.6f, 2.0f))
    var genresScale: Float get() = f("genresScale", 1.0f); set(v) = putF("genresScale", v.coerceIn(0.6f, 2.0f))
    /** Opaque dark plate behind every icon, so transparent pack icons don't show the poster through. */
    var tileBacking: Boolean get() = b("tileBacking", true); set(v) = putB("tileBacking", v)
    /** package name of the selected icon pack, "" = none */
    var iconPack: String get() = s("iconPack", ""); set(v) = putS("iconPack", v)
    /** Weather in the top bar: off | temp | condition | city | all */
    var weatherMode: String get() = s("weatherMode", "off"); set(v) = putS("weatherMode", v)
    /** Empty until the user types a city; no weather is fetched without one. */
    var weatherCity: String get() = s("weatherCity", ""); set(v) = putS("weatherCity", v.trim())
    var weatherF: Boolean get() = b("weatherF", false); set(v) = putB("weatherF", v)
    var showDate: Boolean get() = b("showDate", true); set(v) = putB("showDate", v)
    /** tvOS look: icons are white until selected, then show their colours on a light plate. */
    var focusColor: Boolean get() = b("focusColor", false); set(v) = putB("focusColor", v)
    /** The selected icon rises a little, like tvOS. */
    /** Plate behind the selected icon in "Colour on focus": light | dark */
    var focusPlate: String get() = s("focusPlate", "light"); set(v) = putS("focusPlate", v)
    /** Clock: left (Apple TV) | right (next to the settings button, weather under it) */
    var clockPos: String get() = s("clockPos", "left"); set(v) = putS("clockPos", v)
    /** Pressing further at the edge of the dock nudges the icon, like tvOS. */
    var edgeBounce: Boolean get() = b("edgeBounce", true); set(v) = putB("edgeBounce", v)
    var tileLift: Boolean get() = b("tileLift", true); set(v) = putB("tileLift", v)
    var showClock: Boolean get() = b("showClock", true); set(v) = putB("showClock", v)
    var clock24h: Boolean get() = b("clock24h", false); set(v) = putB("clock24h", v)
    var blurRadius: Int get() = i("blurRadius", 36); set(v) = putI("blurRadius", v.coerceIn(0, 80))
    var glassTint: Int get() = i("glassTint", 6); set(v) = putI("glassTint", v.coerceIn(0, 40))
    var showMobileApps: Boolean get() = b("showMobileApps", false); set(v) = putB("showMobileApps", v)
    var dockOrder: List<String> get() = list("dockOrder"); set(v) = putList("dockOrder", v)
    var hiddenApps: List<String> get() = list("hiddenApps"); set(v) = putList("hiddenApps", v)

    // ---- Continue Watching (from Trakt)
    /** off | first (at the start of the carousel) | mixed (among the catalogs) */
    var continueMode: String get() = s("continueMode", "first"); set(v) = putS("continueMode", v)
    /** How many titles in progress open the carousel (0 = all); the rest mix in with the catalogs. */
    var cwLead: Int get() = i("cwLead", 1); set(v) = putI("cwLead", v)
    var cwProgress: Boolean get() = b("cwProgress", true); set(v) = putB("cwProgress", v)
    /** The Trakt Watchlist row under the app grid (only while signed in to Trakt). */
    var watchlistRow: Boolean get() = b("watchlistRow", true); set(v) = putB("watchlistRow", v)

    // ---- Nuvio
    /** auto | nuviotv | legacy | reshaped. "auto" opens titles in whichever Nuvio is installed, NuvioTV first. */
    var nuvioTarget: String get() = s("nuvioTarget", "auto"); set(v) = putS("nuvioTarget", v)

    // ---- Screensaver
    var idleMinutes: Int get() = i("idleMinutes", 0); set(v) = putI("idleMinutes", v.coerceAtLeast(0))
    /** The user's own video links, played by the system screensaver (GlassDreamService). */
    var aerialUrls: List<String> get() = list("aerialUrls"); set(v) = putList("aerialUrls", v)
    var saverClock: Boolean get() = b("saverClock", true); set(v) = putB("saverClock", v)

    /**
     * Writes checked values from a backup (Settings → Backup → Import) in one go: Boolean, Int, Float or String
     * (lists already joined one per line). Keys and ranges are checked by Backup before this is called.
     */
    fun importValues(values: Map<String, Any>) {
        val e = sp.edit()
        for ((k, v) in values) when (v) {
            is Boolean -> e.putBoolean(k, v)
            is Int -> e.putInt(k, v)
            is Float -> e.putFloat(k, v)
            is String -> e.putString(k, v)
        }
        e.commit()
        touch()
        if ("interceptHome" in values) {
            com.glasslauncher.tv.HomeKeyService.setIntercept(interceptHome)
            (ctx.applicationContext as? com.glasslauncher.tv.GlassApp)?.checkHomeService()
        }
    }

    // ---- System
    var interceptHome: Boolean get() = b("interceptHome", false); set(v) { putB("interceptHome", v); com.glasslauncher.tv.HomeKeyService.setIntercept(v); (ctx.applicationContext as? com.glasslauncher.tv.GlassApp)?.checkHomeService() }
}
