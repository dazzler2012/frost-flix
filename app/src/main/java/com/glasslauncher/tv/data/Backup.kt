package com.glasslauncher.tv.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Settings → Backup: one JSON file in Download/Frost-flix/ with the launcher's settings.
 *
 * Never exported: the Trakt sign-in (its own encrypted storage, not read here) and the TMDB token (not in the
 * allowlist below). Add-on links, the catalogs chosen from them and video links can hold private keys, so
 * they are exported only when you tick "Include add-on and video links".
 *
 * Import runs only when you choose a file in Settings, shows every change first and applies nothing until you
 * confirm. Nothing is ever imported on its own (no file is read at startup). The file is validated: only the
 * known settings below are read, each with its type and allowed range; unknown fields are ignored; a bad
 * file gives a message, never a crash.
 */
class Backup(
    private val ctx: Context,
    private val settings: Settings,
    private val catalogs: CatalogRepository,
    private val icons: IconStore,
) {
    private sealed class Spec(val key: String, val label: String) {
        class B(key: String, label: String) : Spec(key, label)
        class I(key: String, label: String, val min: Int, val max: Int) : Spec(key, label)
        class F(key: String, label: String, val min: Float, val max: Float) : Spec(key, label)
        class S(key: String, label: String, val allowed: Set<String>? = null, val maxLen: Int = 200) : Spec(key, label)
        /** A list of strings (stored one per line), e.g. the dock order. */
        class L(key: String, label: String, val maxItems: Int = 500, val maxLen: Int = 300) : Spec(key, label)
    }

    private val styles = setOf("liquid", "water", "water_still", "clear", "amoled", "bow")

    /** Every setting a backup carries, with the ranges the settings screen allows. The TMDB token is not here. */
    private val specs: List<Spec> = listOf(
        // Look and layout
        Spec.S("perfMode", "Performance mode", setOf("auto", "performance", "balanced", "quality", "custom")),
        Spec.I("glassRes", "Glass detail", 480, 720),
        Spec.S("imageQuality", "Picture quality", setOf("high", "max")),
        Spec.I("prefetchDepth", "Prepare ahead", 1, 8),
        Spec.I("transitionMs", "Slide transition", 100, 2000),
        Spec.I("refraction", "Glass refraction", 0, 40),
        Spec.B("heroGallery", "Backdrop gallery"),
        Spec.I("galleryShots", "Pictures per title", 2, 6),
        Spec.I("cornerRadius", "Corner radius", 0, 40),
        Spec.I("blurRadius", "Glass blur", 0, 80),
        Spec.I("glassTint", "Glass brightness", 0, 40),
        Spec.F("dockScale", "Dock size", 0.6f, 1.0f),
        Spec.S("dockStyle", "Dock background", styles),
        Spec.S("panelStyle", "Settings background", setOf("liquid", "clear", "amoled")),
        Spec.S("tileStyle", "Icon style", styles),
        Spec.B("tileBacking", "Icon backing"),
        Spec.B("focusColor", "Colour on focus"),
        Spec.S("focusPlate", "Selected icon plate", setOf("light", "dark")),
        Spec.B("edgeBounce", "Bounce at the edges"),
        Spec.B("tileLift", "Lift selected icon"),
        Spec.B("showMobileApps", "Show phone apps too"),
        Spec.I("folderPos", "Apps folder position", -1, 500),
        Spec.L("dockOrder", "Dock order"),
        Spec.L("hiddenApps", "Hidden apps"),
        Spec.S("iconPack", "Icon pack", maxLen = 200),
        // Clock and top bar
        Spec.B("showClock", "Show clock"),
        Spec.B("clock24h", "24-hour clock"),
        Spec.B("showDate", "Show date"),
        Spec.S("clockPos", "Clock position", setOf("left", "right")),
        Spec.B("topAutoHide", "Auto-hide clock and settings button"),
        Spec.I("topHideSec", "Shown at start for", 2, 30),
        Spec.S("weatherMode", "Weather", setOf("off", "temp", "condition", "city", "all")),
        Spec.S("weatherCity", "Weather city", maxLen = 100),
        Spec.B("weatherF", "Fahrenheit"),
        // Carousel and catalogs
        Spec.B("heroAutoplay", "Auto-play"),
        Spec.I("heroIntervalSec", "Slide time", 3, 60),
        Spec.S("titleMode", "Title style", setOf("logo", "text", "hidden")),
        Spec.F("titleScale", "Logo / title size", 0.5f, 2.2f),
        Spec.B("showMeta", "Rating, year, platform"),
        Spec.F("metaScale", "Rating, year, platform size", 0.6f, 2.0f),
        Spec.B("showGenres", "Genres line"),
        Spec.F("genresScale", "Genres size", 0.6f, 2.0f),
        Spec.B("showSynopsis", "Synopsis"),
        Spec.B("synopsisOnFocus", "Synopsis only when selected"),
        Spec.F("synopsisScale", "Synopsis size", 0.6f, 2.0f),
        Spec.I("synopsisLines", "Synopsis lines", 1, 8),
        Spec.S("dotsMode", "Dots style", setOf("window", "progress", "hidden")),
        Spec.I("dotsMax", "Dots per group", 3, 20),
        Spec.B("dotsAutoHide", "Dots auto-hide"),
        Spec.I("perCatalog", "Titles per catalog", 5, 500),
        Spec.I("maxItems", "Total titles", 0, 100_000),
        Spec.B("shuffle", "Shuffle all catalogs"),
        Spec.S("descLang", "Synopsis language", setOf("en", "ar")),
        Spec.S("artChoice", "Artwork", setOf("main", "second", "best", "addon")),
        Spec.L("artPicks", "Pictures picked per title", maxItems = 300, maxLen = 1000),
        // Continue Watching, Watchlist, Top Shelf, Nuvio
        Spec.S("continueMode", "Show Continue Watching", setOf("off", "first", "mixed")),
        Spec.I("cwLead", "Slides at the start", 0, 5),
        Spec.B("cwProgress", "Progress bar"),
        Spec.B("watchlistRow", "Watchlist row"),
        Spec.B("topShelf", "Top Shelf"),
        Spec.B("topShelfAppRows", "Top Shelf: use apps' own rows"),
        Spec.S("nuvioTarget", "Open titles in", setOf("auto", "nuviotv", "legacy", "reshaped")),
        // Screensaver and system
        Spec.I("idleMinutes", "Screensaver start after", 0, 30),
        Spec.B("saverClock", "Screensaver clock"),
        Spec.B("backSaver", "Back starts the screensaver"),
        Spec.B("interceptHome", "Home button opens Frost-flix"),
    )
    /** Video links: may hold private keys, exported only with the opt-in (like add-on links). */
    private val videoLinks = Spec.L("aerialUrls", "Video links", maxItems = 100, maxLen = 2000)

    private val sp get() = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE)

    // ---- Export

    /** Writes the backup and returns where it went. Blocking work runs on IO. */
    suspend fun export(includeLinks: Boolean): String = withContext(Dispatchers.IO) {
        val p = sp
        val values = JSONObject()
        for (spec in specs + if (includeLinks) listOf(videoLinks) else emptyList()) {
            if (!p.contains(spec.key)) continue
            when (spec) {
                is Spec.B -> values.put(spec.key, p.getBoolean(spec.key, false))
                is Spec.I -> values.put(spec.key, p.getInt(spec.key, 0))
                is Spec.F -> values.put(spec.key, p.getFloat(spec.key, 0f).toDouble())
                is Spec.S -> values.put(spec.key, p.getString(spec.key, "").orEmpty())
                is Spec.L -> values.put(spec.key, JSONArray(lines(p.getString(spec.key, "").orEmpty())))
            }
        }
        val root = JSONObject()
            .put("app", APP).put("format", FORMAT)
            .put("version", runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty())
            .put("exported", java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", java.util.Locale.US).format(java.util.Date()))
            .put("settings", values)
        if (includeLinks) {
            root.put("addons", JSONArray(catalogs.addons.sorted()))
            root.put("manualAddons", JSONArray(catalogs.manualAddons.sorted()))
            // Catalog keys contain their add-on's link, so they travel with the links only.
            root.put("selectedCatalogs", JSONArray(catalogs.selectedKeys.value.sorted()))
        }
        val iconObj = JSONObject()
        icons.customIcons().forEach { (pkg, f) ->
            if (f.length() in 1..MAX_ICON_BYTES) iconObj.put(pkg, android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP))
        }
        root.put("icons", iconObj)
        write(fileName(), root.toString(2).toByteArray())
    }

    private fun fileName(): String {
        val day = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val time = java.text.SimpleDateFormat("HHmm", java.util.Locale.US).format(java.util.Date())
        val taken = list().map { it.name }.toSet()
        return listOf("frost-flix-settings-$day.json", "frost-flix-settings-$day-$time.json").firstOrNull { it !in taken }
            ?: "frost-flix-settings-$day-$time-${System.currentTimeMillis() % 1000}.json"
    }

    /**
     * Download/Frost-flix/ through MediaStore (Android 10+, no storage permission needed for the app's own files).
     * Android 8/9 would need a storage permission for Download, so there the file goes to the app's own folder
     * on the shared storage instead (Android/data/tv.darren.glassio/files/Frost-flix).
     */
    private fun write(name: String, bytes: ByteArray): String {
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/json")
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/" + FOLDER + "/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: error("Could not create the file in Download")
            try {
                r.openOutputStream(uri)?.use { it.write(bytes) } ?: error("Could not write the file")
                r.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                runCatching { r.delete(uri, null, null) }
                throw e
            }
            return "Download/$FOLDER/$name"
        }
        val dir = File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FOLDER).apply { mkdirs() }
        File(dir, name).writeBytes(bytes)
        return File(dir, name).absolutePath
    }

    // ---- Import

    class BackupFile(val name: String, val uri: Uri, val modified: Long)

    /** The backup files Frost-flix can read, newest first. Blocking: call off the main thread. */
    fun list(): List<BackupFile> {
        val out = mutableListOf<BackupFile>()
        if (Build.VERSION.SDK_INT >= 29) runCatching {
            val proj = arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME, MediaStore.MediaColumns.DATE_MODIFIED, MediaStore.MediaColumns.RELATIVE_PATH)
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, proj, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1).orEmpty()
                    val path = c.getString(3).orEmpty()
                    if (!path.trimEnd('/').endsWith(FOLDER) || !name.endsWith(".json")) continue
                    out += BackupFile(name, android.content.ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, c.getLong(0)), c.getLong(2) * 1000)
                }
            }
        }
        File(ctx.getExternalFilesDir(null) ?: ctx.filesDir, FOLDER).listFiles()?.filter { it.name.endsWith(".json") }?.forEach {
            out += BackupFile(it.name, Uri.fromFile(it), it.lastModified())
        }
        return out.sortedByDescending { it.modified }
    }

    /** What a file would change, ready to be applied with [apply]. */
    class Plan(
        val file: String,
        /** One line per change: "Dock size: 100% → 80%". */
        val lines: List<String>,
        internal val values: Map<String, Any>,
        internal val addons: Set<String>?, internal val manual: Set<String>?, internal val selected: Set<String>?,
        internal val icons: Map<String, ByteArray>,
        /** Fields that were not used (unknown, wrong type or out of range). */
        val skipped: Int,
    )

    /** Reads and checks [f]. Throws with a readable message for a file that is not a Frost-flix backup. */
    fun plan(f: BackupFile): Plan {
        val bytes = ctx.contentResolver.openInputStream(f.uri)?.use { input ->
            val buf = java.io.ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) break
                buf.write(chunk, 0, n)
                require(buf.size() <= MAX_FILE_BYTES) { "The file is larger than ${MAX_FILE_BYTES / (1024 * 1024)} MB" }
            }
            buf.toByteArray()
        } ?: error("Could not open the file")
        val root = try { JSONObject(String(bytes, Charsets.UTF_8)) } catch (e: org.json.JSONException) { throw IllegalArgumentException("Not a valid JSON file") }
        require(root.optString("app") == APP) { "Not a Frost-flix backup" }
        val format = root.optInt("format", -1)
        require(format == FORMAT) { "Unknown backup format ($format)" }

        var skipped = 0
        val p = sp
        val values = LinkedHashMap<String, Any>()
        val lines = mutableListOf<String>()
        val s = root.optJSONObject("settings") ?: JSONObject()
        val known = (specs + videoLinks).associateBy { it.key }
        for (k in s.keys()) {
            val spec = known[k]
            val v = spec?.let { read(it, s.opt(k)) }
            if (spec == null || v == null) { skipped++; continue }
            val old: Any? = if (!p.contains(k)) null else when (spec) {
                is Spec.B -> p.getBoolean(k, false)
                is Spec.I -> p.getInt(k, 0)
                is Spec.F -> p.getFloat(k, 0f)
                is Spec.S -> p.getString(k, "")
                is Spec.L -> lines(p.getString(k, "").orEmpty())
            }
            if (old == v) continue
            values[k] = v
            lines += "${spec.label}: ${show(spec, old)} → ${show(spec, v)}"
        }
        fun set(name: String, maxLen: Int): Set<String>? = root.optJSONArray(name)?.let { a ->
            (0 until a.length()).mapNotNull { a.opt(it) as? String }.filter { it.length in 1..maxLen }.take(500).toSet()
        }
        val addons = set("addons", 2000)?.filter { it.startsWith("https://") }?.toSet()
        val manual = set("manualAddons", 2000)?.filter { it.startsWith("https://") }?.toSet()
        val selected = set("selectedCatalogs", 4000)
        if (addons != null && addons != catalogs.addons) {
            val added = (addons - catalogs.addons).size; val removed = (catalogs.addons - addons).size
            lines += "Add-on links: $added added, $removed removed (${addons.size} in the file)"
        }
        if (selected != null && selected != catalogs.selectedKeys.value) lines += "Selected catalogs: ${selected.size} (now ${catalogs.selectedKeys.value.size})"
        val iconMap = LinkedHashMap<String, ByteArray>()
        root.optJSONObject("icons")?.let { o ->
            for (pkg in o.keys()) {
                val b64 = o.opt(pkg) as? String
                if (!PKG.matches(pkg) || b64 == null || b64.length > MAX_ICON_BYTES * 4 / 3 + 8) { skipped++; continue }
                val data = runCatching { android.util.Base64.decode(b64, android.util.Base64.DEFAULT) }.getOrNull()
                // Only real pictures: the bounds must decode.
                val ok = data != null && android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    .also { android.graphics.BitmapFactory.decodeByteArray(data, 0, data.size, it) }.outWidth > 0
                if (!ok) { skipped++; continue }
                val cur = icons.fileFor(pkg).takeIf { it.exists() }?.readBytes()
                if (cur != null && cur.contentEquals(data)) continue
                iconMap[pkg] = data!!
            }
        }
        if (iconMap.isNotEmpty()) lines += "Custom icons: ${iconMap.size} (${iconMap.keys.take(4).joinToString(", ")}${if (iconMap.size > 4) ", …" else ""})"
        if (root.optJSONArray("addons") == null) lines += "Add-on links are not in this file: yours stay as they are"
        return Plan(f.name, lines, values,
            addons?.takeIf { it != catalogs.addons }, manual, selected?.takeIf { it != catalogs.selectedKeys.value }, iconMap, skipped)
    }

    /** Applies a confirmed [plan]. Blocking: call off the main thread. */
    fun apply(plan: Plan) {
        settings.importValues(plan.values.mapValues { (k, v) ->
            if (v is List<*>) v.joinToString("\n") else if (k == "topShelfAppRows" && v == true && !permissionOk()) false else v
        })
        if (plan.addons != null || plan.selected != null) catalogs.importLinks(plan.addons, plan.manual, plan.selected)
        plan.icons.forEach { (pkg, data) -> runCatching { icons.setFromBytes(pkg, data) } }
    }

    private fun permissionOk() = androidx.core.content.ContextCompat.checkSelfPermission(ctx, TopShelf.READ_TV_LISTINGS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

    /** The value of [raw] for [spec], or null when it has the wrong type or is out of range. */
    private fun read(spec: Spec, raw: Any?): Any? = when (spec) {
        is Spec.B -> raw as? Boolean
        is Spec.I -> (raw as? Number)?.takeIf { it.toDouble() == Math.floor(it.toDouble()) }?.toInt()?.takeIf { it in spec.min..spec.max }
        is Spec.F -> (raw as? Number)?.toFloat()?.takeIf { !it.isNaN() && it >= spec.min - 0.001f && it <= spec.max + 0.001f }?.coerceIn(spec.min, spec.max)
        is Spec.S -> (raw as? String)?.takeIf { it.length <= spec.maxLen && !it.contains('\n') && (spec.allowed == null || it in spec.allowed) }
        is Spec.L -> (raw as? JSONArray)?.let { a ->
            val l = (0 until a.length()).map { a.opt(it) as? String ?: return@let null }
            l.takeIf { it.size <= spec.maxItems && it.all { x -> x.length <= spec.maxLen && !x.contains('\n') } }
        }
    }

    private fun show(spec: Spec, v: Any?): String = when {
        v == null -> "default"
        spec is Spec.B -> if (v == true) "On" else "Off"
        spec is Spec.F -> "${Math.round((v as Float) * 100)}%"
        spec is Spec.L -> "${(v as List<*>).size} entries"
        spec is Spec.S && (v as String).isEmpty() -> "none"
        else -> v.toString()
    }

    private fun lines(s: String) = s.split('\n').map { it.trim() }.filter { it.isNotEmpty() }

    companion object {
        private const val APP = "Frost-flix"
        private const val FORMAT = 1
        const val FOLDER = "Frost-flix"
        private const val MAX_FILE_BYTES = 20 * 1024 * 1024
        private const val MAX_ICON_BYTES = 2L * 1024 * 1024
        private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
