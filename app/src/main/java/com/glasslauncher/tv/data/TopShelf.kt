package com.glasslauncher.tv.data

import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import androidx.compose.ui.graphics.asAndroidBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Top Shelf: the pictures the backdrop shows while an app tile in the dock is selected, like Apple TV.
 *
 * In this order, the first that gives a picture wins:
 * 1. "Top Shelf: use apps' own rows" (off by default, needs READ_TV_LISTINGS): the preview programs the app
 *    publishes for the Android TV home screen. Only pictures that stay on the TV or come from TMDB are used
 *    (content:// and android.resource:// links, https://image.tmdb.org), so no new host is ever contacted.
 * 2. NuvioTV / Nuvio / Stremio / Kodi / Plex: backdrops of your Continue Watching and Watchlist titles.
 * 3. Every other app (and the cases above when they have nothing): a soft gradient in the main colour of the
 *    app's icon with its banner, drawn once off the main thread and kept as a file in the app's cache.
 *
 * Every picture is returned as a link the home screen loads, bakes and turns into glass like a carousel backdrop.
 */
class TopShelf(
    private val ctx: Context,
    private val settings: Settings,
    private val apps: AppRepository,
    private val icons: IconStore,
    private val cw: ContinueWatching,
    private val catalogs: CatalogRepository,
) {
    /** Titles from the Trakt watchlist (set once the Watchlist row exists; empty until then). */
    @Volatile var watchlist: () -> List<HeroItem> = { emptyList() }

    fun permissionGranted(): Boolean =
        androidx.core.content.ContextCompat.checkSelfPermission(ctx, READ_TV_LISTINGS) == PackageManager.PERMISSION_GRANTED

    /** Pictures for [e], never empty (the gradient is the last resort). Call off the main thread. */
    suspend fun pictures(e: AppEntry): List<String> = withContext(Dispatchers.IO) {
        if (settings.topShelfAppRows && permissionGranted()) appRows(e.pkg).takeIf { it.isNotEmpty() }?.let { return@withContext it }
        if (e.pkg in MEDIA_APPS) mediaArt().takeIf { it.isNotEmpty() }?.let { return@withContext it }
        listOf(gradient(e))
    }

    // ---- 1. The app's own preview programs

    private class Cached(val at: Long, val urls: List<String>)
    private val rows = HashMap<String, Cached>()

    /** One app's preview program pictures that pass [usable], at most [MAX_PICTURES], cached for 10 minutes. */
    private fun appRows(pkg: String): List<String> {
        synchronized(rows) { rows[pkg]?.takeIf { System.currentTimeMillis() - it.at < ROWS_TTL_MS }?.let { return it.urls } }
        val urls = previewPrograms().filter { it.pkg == pkg }.flatMap { listOf(it.poster, it.thumb) }
            .filter { usable(it) }.distinct().take(MAX_PICTURES)
        synchronized(rows) { rows[pkg] = Cached(System.currentTimeMillis(), urls) }
        return urls
    }

    private class Program(val pkg: String, val poster: String, val thumb: String)

    /**
     * Every preview program Android lets Frost-flix read. Without ACCESS_ALL_EPG_DATA (system apps only) the TV
     * provider allows no selection and returns the caller's own rows plus, with READ_TV_LISTINGS, every
     * "searchable" row of other apps; the package filter is applied here.
     */
    private fun previewPrograms(): List<Program> = runCatching {
        val out = mutableListOf<Program>()
        ctx.contentResolver.query(PREVIEW_PROGRAMS, arrayOf("package_name", "poster_art_uri", "thumbnail_uri"), null, null, null)?.use { c ->
            val p = c.getColumnIndex("package_name"); val a = c.getColumnIndex("poster_art_uri"); val t = c.getColumnIndex("thumbnail_uri")
            while (c.moveToNext() && out.size < 2000) {
                fun str(i: Int) = if (i >= 0 && !c.isNull(i)) c.getString(i).orEmpty() else ""
                out += Program(str(p), str(a), str(t))
            }
        }
        out
    }.getOrDefault(emptyList())

    /** Pictures that need no new network host: on the TV itself, or on TMDB's image server. */
    private fun usable(u: String): Boolean {
        if (u.isBlank()) return false
        val uri = runCatching { Uri.parse(u) }.getOrNull() ?: return false
        return when (uri.scheme?.lowercase()) {
            "content", "android.resource" -> true
            "https" -> uri.host.equals("image.tmdb.org", true)
            else -> false
        }
    }

    /**
     * For Settings: which apps publish preview programs Frost-flix can read, with how many pictures it could use.
     * "com.plexapp.android: 12 programs, 8 usable pictures".
     */
    suspend fun describeAppRows(): String = withContext(Dispatchers.IO) {
        if (!permissionGranted()) return@withContext "Permission not granted: Top Shelf uses the fallback for every app"
        synchronized(rows) { rows.clear() }
        val all = previewPrograms().filter { it.pkg != ctx.packageName }
        if (all.isEmpty()) return@withContext "No app publishes rows Frost-flix can read: Top Shelf uses the fallback for every app"
        all.groupBy { it.pkg }.entries.sortedBy { it.key }.joinToString("\n") { (pkg, list) ->
            val label = apps.apps.value.firstOrNull { it.pkg == pkg }?.label ?: pkg
            val ok = list.flatMap { listOf(it.poster, it.thumb) }.filter { usable(it) }.distinct().size
            "$label: ${list.size} programs, $ok usable pictures"
        }
    }

    // ---- 2. Media apps: Continue Watching and Watchlist

    private fun mediaArt(): List<String> {
        val enriched = catalogs.enrichedItems.value
        return (cw.items.value + watchlist()).map { enriched[it.key]?.background?.takeIf { b -> b.isNotBlank() } ?: it.background }
            .filter { it.isNotBlank() }.distinct().take(MAX_PICTURES)
    }

    // ---- 3. Gradient from the icon's colour, with the banner

    private val dir by lazy { File(ctx.cacheDir, "topshelf").apply { mkdirs() } }

    /** The gradient picture of [e] as a file link; drawn once per icon (pack, custom icon) and kept. */
    private suspend fun gradient(e: AppEntry): String {
        val art = runCatching { apps.artwork(e) }.getOrNull()
        val name = "${e.pkg}_${settings.iconPack.hashCode()}_${art?.let { it.bmp.width * 10000 + it.bmp.height } ?: 0}_${icons.fileFor(e.pkg).lastModified()}.jpg"
        val f = File(dir, name)
        if (!f.exists()) runCatching {
            // Old pictures of this app (another icon) go.
            dir.listFiles()?.filter { it.name.startsWith(e.pkg + "_") && it.name != name }?.forEach { it.delete() }
            val bmp = drawGradient(art?.bmp?.asAndroidBitmap(), art?.transparent == true)
            val tmp = File(dir, "$name.tmp")
            tmp.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bmp.recycle()
            tmp.renameTo(f)
        }
        return Uri.fromFile(f).toString()
    }

    private fun drawGradient(icon: Bitmap?, transparent: Boolean): Bitmap {
        val w = 1280; val h = 720
        val base = icon?.let { mainColour(it) } ?: 0xFF2A2350.toInt()
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.DITHER_FLAG or Paint.ANTI_ALIAS_FLAG)
        // Dark at the left (where the carousel text would sit) to a soft light of the colour at the right.
        p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(shade(base, 0.18f), shade(base, 0.42f), shade(base, 0.62f)), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = RadialGradient(w * 0.70f, h * 0.36f, w * 0.45f, intArrayOf(withAlpha(lighten(base, 0.25f), 0x88), 0x00000000), null, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        if (icon != null) {
            // The banner (or icon) in the upper right, above the dock.
            val maxW = w * 0.30f; val maxH = h * 0.26f
            val sc = minOf(maxW / icon.width, maxH / icon.height)
            val bw = icon.width * sc; val bh = icon.height * sc
            val cx = w * 0.70f; val cy = h * 0.36f
            val dst = RectF(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
            val ip = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
            if (!transparent) {
                // Opaque banners get rounded corners and a soft shadow, like a tile.
                val r = bh * 0.10f
                val sh = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66000000; setShadowLayer(bh * 0.12f, 0f, bh * 0.05f, 0x99000000.toInt()) }
                c.drawRoundRect(dst, r, r, sh)
                c.save()
                c.clipPath(android.graphics.Path().apply { addRoundRect(dst, r, r, android.graphics.Path.Direction.CW) })
                c.drawBitmap(icon, null, dst, ip)
                c.restore()
            } else c.drawBitmap(icon, null, dst, ip)
        }
        return out
    }

    /** The icon's main colour: the average of its coloured pixels (not near-white, near-black or see-through). */
    private fun mainColour(b: Bitmap): Int {
        var r = 0L; var g = 0L; var bl = 0L; var n = 0L
        var ar = 0L; var ag = 0L; var ab = 0L; var an = 0L
        val stepX = maxOf(1, b.width / 40); val stepY = maxOf(1, b.height / 24)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val px = b.getPixel(x, y)
                if ((px ushr 24) > 160) {
                    val pr = (px shr 16) and 255; val pg = (px shr 8) and 255; val pb = px and 255
                    ar += pr; ag += pg; ab += pb; an++
                    val mx = maxOf(pr, pg, pb); val mn = minOf(pr, pg, pb)
                    if (mx - mn > 40 && mx > 50) { r += pr; g += pg; bl += pb; n++ }
                }
                x += stepX
            }
            y += stepY
        }
        return when {
            n > 0 -> (0xFF shl 24) or ((r / n).toInt() shl 16) or ((g / n).toInt() shl 8) or (bl / n).toInt()
            an > 0 -> (0xFF shl 24) or ((ar / an).toInt() shl 16) or ((ag / an).toInt() shl 8) or (ab / an).toInt()
            else -> 0xFF2A2350.toInt()
        }
    }

    private fun shade(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 255) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun lighten(c: Int, f: Float): Int {
        fun ch(s: Int) = (((c shr s) and 255) + (255 - ((c shr s) and 255)) * f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun withAlpha(c: Int, a: Int) = (a shl 24) or (c and 0xFFFFFF)

    companion object {
        const val READ_TV_LISTINGS = "android.permission.READ_TV_LISTINGS"
        private val PREVIEW_PROGRAMS: Uri = Uri.parse("content://android.media.tv/preview_program")
        private const val ROWS_TTL_MS = 10L * 60 * 1000
        private const val MAX_PICTURES = 6

        /** Apps whose Top Shelf shows your Continue Watching and Watchlist. */
        val MEDIA_APPS = setOf(
            NuvioLaunch.NUVIOTV_PKG, NuvioLaunch.NUVIOTV_PLAY_PKG, NuvioLaunch.LEGACY_PKG, NuvioLaunch.RESHAPED_PKG,
            "com.stremio.one", // Stremio
            "org.xbmc.kodi", // Kodi
            "com.plexapp.android", // Plex
        )
    }
}
