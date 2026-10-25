package com.glasslauncher.tv.data

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

data class AppEntry(val pkg: String, val label: String, val component: ComponentName, val leanback: Boolean)

data class Art(val bmp: ImageBitmap, val transparent: Boolean, val light: Boolean = false, val onLight: ImageBitmap = bmp)

class AppRepository(private val ctx: Context, private val settings: Settings, private val packs: IconPacks, private val icons: IconStore) {
    private val pm: PackageManager = ctx.packageManager
    private val _apps = MutableStateFlow<List<AppEntry>>(emptyList())
    val apps: StateFlow<List<AppEntry>> = _apps

    private val preferred = listOf(
        "com.nuvio.tv", "space.nuvio.nativelegacy", "com.nuvioreshaped.tv", "com.apple.atve.androidtv.appletv",
        "com.google.android.youtube.tv", "com.google.android.apps.youtube.unplugged",
        "com.netflix.ninja", "com.disney.disneyplus", "com.amazon.amazonvideo.livingroom",
        "com.google.android.videos", "com.android.vending",
    )

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) { reload() }
    }

    fun start() {
        reload()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addDataScheme("package")
        }
        runCatching { ctx.registerReceiver(receiver, f) }
    }

    fun reload() {
        val found = linkedMapOf<String, AppEntry>()
        fun scan(category: String, leanback: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val ai = ri.activityInfo ?: continue
                if (ai.packageName == ctx.packageName) continue
                if (found.containsKey(ai.packageName)) continue
                val label = runCatching { ri.loadLabel(pm).toString() }.getOrDefault("").ifBlank { ai.packageName.substringAfterLast('.') }
                found[ai.packageName] = AppEntry(ai.packageName, label, ComponentName(ai.packageName, ai.name), leanback)
            }
        }
        scan(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        scan(Intent.CATEGORY_LAUNCHER, false)
        _apps.value = found.values.toList()
    }

    /** Apps in the user's saved order, hidden ones removed unless [includeHidden]. */
    fun ordered(includeHidden: Boolean = false): List<AppEntry> {
        val hidden = settings.hiddenApps.toSet()
        val saved = settings.dockOrder
        val all = _apps.value.filter { it.leanback || settings.showMobileApps || it.pkg in saved }
        val byPkg = all.associateBy { it.pkg }
        val result = mutableListOf<AppEntry>()
        saved.forEach { p -> byPkg[p]?.let(result::add) }
        val rest = all.filter { a -> result.none { it.pkg == a.pkg } }
        val rank = { a: AppEntry -> preferred.indexOf(a.pkg).let { if (it < 0) Int.MAX_VALUE else it } }
        result += rest.sortedWith(compareBy<AppEntry> { rank(it) }.thenBy { it.label.lowercase() })
        return if (includeHidden) result else result.filter { it.pkg !in hidden }
    }

    fun move(pkg: String, delta: Int) {
        val order = ordered(includeHidden = true).map { it.pkg }.toMutableList()
        val i = order.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, order.lastIndex)
        order.removeAt(i); order.add(j, pkg)
        settings.dockOrder = order
    }

    /** Moves an app by [delta] positions among the apps that are visible in the dock. */
    fun moveVisible(pkg: String, delta: Int) {
        val full = ordered(includeHidden = true).map { it.pkg }.toMutableList()
        val vis = ordered(includeHidden = false).map { it.pkg }
        val i = vis.indexOf(pkg)
        if (i < 0) return
        val j = (i + delta).coerceIn(0, vis.lastIndex)
        if (j == i) return
        val target = vis[j]
        full.remove(pkg)
        val ti = full.indexOf(target)
        full.add(if (delta > 0) ti + 1 else ti, pkg)
        settings.dockOrder = full
    }

    fun toggleHidden(pkg: String) {
        val h = settings.hiddenApps.toMutableList()
        if (!h.remove(pkg)) h.add(pkg)
        settings.hiddenApps = h
    }

    fun launchIntent(e: AppEntry): Intent? =
        (pm.getLeanbackLaunchIntentForPackage(e.pkg) ?: pm.getLaunchIntentForPackage(e.pkg))
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    // Decoded dock artwork, in KB. Counts both copies (a transparent icon may also have a darkened one); emptied
    // by [releaseArt] when Frost-flix goes to the background, and decoded again on return. Icons are decoded at
    // the size a focused tile draws them (~0.1-0.3 MB each at 1080p), so 24 MB holds far more than one dock.
    private val cache = object : LruCache<String, Art>(24 * 1024) {
        override fun sizeOf(key: String, value: Art): Int {
            val main = value.bmp.asAndroidBitmap().allocationByteCount
            val second = if (value.onLight !== value.bmp) value.onLight.asAndroidBitmap().allocationByteCount else 0
            return ((main + second) / 1024).coerceAtLeast(1)
        }
    }

    /** Drops every decoded icon (app in the background). Icons on screen are never recycled here. */
    fun releaseArt() = cache.evictAll()

    /** Width in pixels of the screen the dock is laid out on; the home screen sets it from its own size. */
    @Volatile var screenWidthPx: Int = ctx.resources.displayMetrics.let { maxOf(it.widthPixels, it.heightPixels) }

    /**
     * A dock tile in pixels, as DockMetrics in HomeScreen lays it out: 136/960 of the screen width × "Dock size",
     * 1.6:1, and 8/960 of the screen width of padding around an icon that is fitted (not cropped). All × 1.14,
     * the largest scale of a focused tile, so the focused icon is never enlarged. Folder and Settings icons
     * are drawn smaller than this.
     */
    private class TileBox(val w: Int, val h: Int, val pad: Int)
    private fun tileBox(): TileBox {
        val sw = screenWidthPx.coerceAtLeast(320).toFloat()
        val w = sw * 136f / 960f * settings.dockScale * 1.14f
        return TileBox(kotlin.math.ceil(w).toInt(), kotlin.math.ceil(w / 1.6f).toInt(), (sw * 8f / 960f * 1.14f).toInt())
    }

    /**
     * Pixel size an [iw]×[ih] picture is drawn at on a tile: a wide opaque banner fills the tile (Crop), anything
     * else is fitted inside the padding (Fit). Same rules as IconArt / FocusColorArt in HomeScreen.
     */
    private fun drawnSize(iw: Int, ih: Int, fill: Boolean, b: TileBox): Pair<Int, Int> {
        val sc = if (fill) maxOf(b.w / iw.toFloat(), b.h / ih.toFloat())
        else minOf((b.w - 2 * b.pad) / iw.toFloat(), (b.h - 2 * b.pad) / ih.toFloat())
        return kotlin.math.ceil(iw * sc).toInt().coerceAtLeast(1) to kotlin.math.ceil(ih * sc).toInt().coerceAtLeast(1)
    }

    private fun cacheKey(e: AppEntry, b: TileBox = tileBox()) = "${settings.iconPack}|${icons.rev.intValue}|${b.w}x${b.h}|${e.pkg}"

    /** Already-decoded artwork, available instantly (no flicker when the dock recomposes). */
    fun cachedArtwork(e: AppEntry): Art? = cache.get(cacheKey(e))

    /** Custom icon, else the icon pack's, else the app's banner, else its icon. */
    suspend fun artwork(e: AppEntry): Art? {
        val box = tileBox()
        val key = cacheKey(e, box)
        cache.get(key)?.let { return it }
        return withContext(iconDispatcher) {
            runCatching {
                val custom = icons.fileFor(e.pkg)
                var transparent: Boolean
                var bmp: Bitmap
                if (custom.exists()) {
                    bmp = decodeFile(custom, box) ?: error("bad custom icon")
                    transparent = isTransparent(bmp)
                    val (tw, th) = drawnSize(bmp.width, bmp.height, isWide(bmp.width, bmp.height) && !transparent, box)
                    // Only ever made smaller: a small custom icon is enlarged when drawn, as before.
                    if (tw < bmp.width) bmp = shrink(bmp, tw, th).also { if (it !== bmp) bmp.recycle() }
                } else {
                    val pack = settings.iconPack
                    val d: Drawable = (if (pack.isNotEmpty()) packs.drawableFor(pack, e.component) else null)
                        ?: pm.getActivityInfo(e.component, 0).let { ai -> hiRes(ai) ?: ai.loadBanner(pm) ?: ai.loadIcon(pm) }
                    val iw = d.intrinsicWidth.takeIf { it > 0 } ?: 256
                    val ih = d.intrinsicHeight.takeIf { it > 0 } ?: 256
                    val wide = isWide(iw, ih)
                    // A wide picture is first drawn at the (larger) fill size; if it turns out to be see-through
                    // it is fitted instead, so it is drawn again at that size.
                    val (tw, th) = drawnSize(iw, ih, wide, box)
                    bmp = d.render(iw, tw, th)
                    transparent = isTransparent(bmp)
                    if (wide && transparent) {
                        val (fw, fh) = drawnSize(iw, ih, false, box)
                        bmp.recycle()
                        bmp = d.render(iw, fw, fh)
                    }
                }
                val light = isLight(bmp)
                // A transparent icon's darkened copy is only kept when darkening changed something.
                val dark = if (transparent) darkenWhites(bmp) else null
                if (!transparent && isOpaque(bmp)) bmp = to565(bmp).also { bmp.recycle() }
                val main = com.glasslauncher.tv.ui.BitmapTracker.track(bmp.asImageBitmap(), "icons")
                Art(main, transparent, light, dark?.let { com.glasslauncher.tv.ui.BitmapTracker.track(it.asImageBitmap(), "icons") } ?: main)
                    .also { cache.put(key, it) }
            }.getOrNull()
        }
    }

    // Icons are made two at a time: on return to the home screen all of them are made again, and with every core
    // busy decoding them the first frames of the screen were late.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val iconDispatcher = Dispatchers.Default.limitedParallelism(2)

    /** Same test as the dock: wider than 1.3:1 is a banner. */
    private fun isWide(w: Int, h: Int) = w > h * 1.3f

    /** A custom icon file, decoded with inSampleSize to the smallest power-of-two step not below its drawn size. */
    private fun decodeFile(f: java.io.File, box: TileBox): Bitmap? {
        val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(f.absolutePath, o)
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        // Fill size: the larger of the two possible drawn sizes, so sampling never goes below either.
        val (tw, th) = drawnSize(o.outWidth, o.outHeight, isWide(o.outWidth, o.outHeight), box)
        var sample = 1
        while (o.outWidth / (sample * 2) >= tw && o.outHeight / (sample * 2) >= th) sample *= 2
        return android.graphics.BitmapFactory.decodeFile(f.absolutePath, android.graphics.BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        })
    }

    /** True when every pixel is fully opaque: the icon can be stored as RGB_565 (half the memory). */
    private fun isOpaque(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return true
        val w = b.width; val row = IntArray(w)
        for (y in 0 until b.height) {
            b.getPixels(row, 0, w, 0, y, w, 1)
            for (p in row) if ((p ushr 24) != 255) return false
        }
        return true
    }

    /** RGB_565 copy, dithered so gradients in banners don't band. */
    private fun to565(src: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(src, 0f, 0f, android.graphics.Paint(android.graphics.Paint.DITHER_FLAG))
        return out
    }

    /**
     * The app's banner (or icon) at the highest density it ships: loadBanner() picks the TV's own density
     * (xhdpi, 320×180) and the tile then scales it up, which made icons soft.
     */
    private fun hiRes(ai: android.content.pm.ActivityInfo): Drawable? = runCatching {
        val res = pm.getResourcesForApplication(ai.applicationInfo)
        val banner = if (ai.banner != 0) ai.banner else ai.applicationInfo.banner
        val icon = if (ai.icon != 0) ai.icon else ai.applicationInfo.icon
        val id = if (banner != 0) banner else icon
        if (id == 0) null else res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXHIGH, null)
    }.getOrNull()

    /** White / light-grey parts (lettering) turn near-black; coloured parts keep their colour. Null: nothing to darken. */
    private fun darkenWhites(src: Bitmap): Bitmap? {
        val b = src.copy(Bitmap.Config.ARGB_8888, true)
        val w = b.width; val h = b.height
        val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
        var changed = false
        for (i in px.indices) {
            val p = px[i]; val a = p ushr 24
            if (a == 0) continue
            val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
            val mx = maxOf(r, g, bl); val mn = minOf(r, g, bl)
            if (mx - mn < 48 && mn > 150) {
                val v = 0x1C + (255 - mn) / 6
                px[i] = (a shl 24) or (v shl 16) or (v shl 8) or (v + 2).coerceAtMost(255)
                changed = true
            }
        }
        if (!changed) { b.recycle(); return null }
        b.setPixels(px, 0, w, 0, 0, w, h)
        return b
    }

    /** True when the visible part of the icon is (near) white: it needs darkening on a light plate. */
    private fun isLight(b: Bitmap): Boolean {
        var n = 0; var light = 0
        val stepX = maxOf(1, b.width / 24); val stepY = maxOf(1, b.height / 14)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) {
                val p = b.getPixel(x, y)
                if ((p ushr 24) > 160) {
                    n++
                    val r = (p shr 16) and 255; val g = (p shr 8) and 255; val bl = p and 255
                    if (minOf(r, g, bl) > 200 && maxOf(r, g, bl) - minOf(r, g, bl) < 40) light++
                }
                x += stepX
            }
            y += stepY
        }
        return n > 0 && light * 100 / n >= 70
    }

    /** True when a good part of the image is see-through (glyph icons), false for full banners. */
    private fun isTransparent(b: Bitmap): Boolean {
        if (!b.hasAlpha()) return false
        var clear = 0; var total = 0
        val stepX = maxOf(1, b.width / 16); val stepY = maxOf(1, b.height / 10)
        var y = 0
        while (y < b.height) {
            var x = 0
            while (x < b.width) { total++; if ((b.getPixel(x, y) ushr 24) < 40) clear++; x += stepX }
            y += stepY
        }
        return total > 0 && clear * 100 / total >= 35
    }

    /**
     * Draws the drawable at [tw]×[th]. A picture with more pixels than that ([iw] wide) is drawn at a power-of-two
     * multiple first and halved down: one big bilinear step would alias lettering in banners.
     */
    private fun Drawable.render(iw: Int, tw: Int, th: Int): Bitmap {
        var k = 1
        while (tw * k * 2 <= iw && k < 8) k *= 2
        val b = toBitmap(tw * k, th * k)
        return if (k == 1) b else shrink(b, tw, th).also { if (it !== b) b.recycle() }
    }

    /**
     * Scales [src] down to [tw]×[th] in halving steps (each one an even 2×2 average), then one last step.
     * All on the CPU: software bitmaps and a software Canvas, nothing touches the GPU.
     */
    private fun shrink(src: Bitmap, tw: Int, th: Int): Bitmap {
        var b = src
        while (b.width / 2 >= tw && b.height / 2 >= th) {
            val n = scaled(b, b.width / 2, b.height / 2)
            if (b !== src) b.recycle()
            b = n
        }
        if (b.width != tw || b.height != th) {
            val n = scaled(b, tw, th)
            if (b !== src) b.recycle()
            b = n
        }
        return b
    }

    /** Bilinear scale into a new software ARGB_8888 bitmap (what createScaledBitmap with filtering does). */
    private fun scaled(src: Bitmap, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(src, null, android.graphics.Rect(0, 0, w, h), android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
        return out
    }

    private fun Drawable.toBitmap(w: Int, h: Int): Bitmap {
        // A software Canvas can't draw a hardware bitmap: give such a drawable a software copy first.
        val hw = (this as? android.graphics.drawable.BitmapDrawable)?.bitmap
            ?.takeIf { it.config == Bitmap.Config.HARDWARE }
        val d = hw?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it.copy(Bitmap.Config.ARGB_8888, false)) } ?: this
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        d.setBounds(0, 0, w, h); d.draw(c)
        return bmp
    }
}
