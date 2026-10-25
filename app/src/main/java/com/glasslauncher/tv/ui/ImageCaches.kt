package com.glasslauncher.tv.ui

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher

/** The three small copies of a backdrop that the glass refracts: blurred (setting), sharp, heavy (folder / veil). */
data class GlassImages(val blurred: ImageBitmap, val sharp: ImageBitmap, val heavy: ImageBitmap)

/** A TMDB image at another size ("w780", "w1280"); other URLs are returned unchanged. */
fun sized(url: String, size: String) = url.replace("/t/p/original/", "/t/p/$size/")

/**
 * Counts the bitmaps Frost-flix makes itself (baked backdrops, glass copies, dock icons, QR codes), for the
 * hidden Memory line in Settings → About. Weak keys: a tracked bitmap is never kept alive by being tracked,
 * and it drops out of the count once the garbage collector has freed it.
 */
object BitmapTracker {
    private val live = java.util.WeakHashMap<Bitmap, String>()

    fun track(b: Bitmap, kind: String): Bitmap { synchronized(live) { live[b] = kind }; return b }
    fun track(b: ImageBitmap, kind: String): ImageBitmap { track(b.asAndroidBitmap(), kind); return b }

    /** kind → (count, bytes) of the tracked bitmaps that still exist. */
    fun snapshot(): Map<String, Pair<Int, Long>> = synchronized(live) {
        val out = sortedMapOf<String, Pair<Int, Long>>()
        for ((b, kind) in live) {
            val bytes = runCatching { if (b.isRecycled) 0L else b.allocationByteCount.toLong() }.getOrDefault(0L)
            val (n, sum) = out[kind] ?: (0 to 0L)
            out[kind] = (n + 1) to (sum + bytes)
        }
        out
    }
}

/** The one [ImageCaches] of the home screen that is on screen; provided by MainActivity. */
val LocalImageCaches = staticCompositionLocalOf<ImageCaches> { error("No ImageCaches: provided by MainActivity") }

/**
 * Every large bitmap the home screen keeps between frames: glass copies of backdrops, the baked full-screen
 * backdrops and their half-size copies. One instance per started MainActivity: it is created on ON_START and
 * dropped on ON_STOP together with the whole screen, so nothing here outlives the visible home screen.
 * (No static field and no app-scope object holds these bitmaps.)
 */
class ImageCaches(glassRes: Int) {
    /** Width of the glass copies (Settings → Performance → Glass detail). */
    @Volatile var res: Int = glassRes

    // ---- Glass copies: three 480×270 (or 720×405) bitmaps per backdrop, about 1.5 MB at 480 px.
    // Limited by memory (about 36 MB), not by count: bigger glass copies keep fewer slides instead of more memory.
    private val glass = object : LruCache<String, GlassImages>(36 * 1024 * 1024) {
        override fun sizeOf(key: String, v: GlassImages): Int =
            v.blurred.width * v.blurred.height * 4 + v.sharp.width * v.sharp.height * 4 + v.heavy.width * v.heavy.height * 4
    }
    private val pending = HashMap<String, Deferred<GlassImages?>>()

    fun peekGlass(url: String, blur: Int): GlassImages? = glass.get("$blur|$res|$url")

    /**
     * Glass copies of [url], prepared ahead of time so a slide, its backdrop and the glass in front of it all
     * change in the same frame. The work runs in its own scope: a caller that goes away (you pressed Right
     * again) only stops waiting, it never cancels a job other callers are waiting for.
     */
    suspend fun glass(ctx: Context, url: String, blur: Int): GlassImages? {
        val r = res
        val key = "$blur|$r|$url"
        glass.get(key)?.let { return it }
        val job = synchronized(pending) {
            pending.getOrPut(key) {
                blurScope.async {
                    try {
                        val req = ImageRequest.Builder(ctx.applicationContext).data(sized(url, "w780"))
                            .size(r, r * 9 / 16).allowHardware(false).build()
                        val src = (SingletonImageLoader.get(ctx.applicationContext).execute(req) as? SuccessResult)?.image?.toBitmap()
                        src?.let {
                            val sh = if (it.isMutable) it.copy(Bitmap.Config.ARGB_8888, false) else it
                            sh.prepareToDraw()
                            GlassImages(
                                BitmapTracker.track(blurredBackdrop(it, blur), "glass"),
                                BitmapTracker.track(sh.asImageBitmap(), "glass"),
                                BitmapTracker.track(blurredBackdrop(it, 80), "glass"),
                            )
                        }?.also { glass.put(key, it) }
                    } catch (e: Exception) {
                        null
                    } finally {
                        synchronized(pending) { pending.remove(key) }
                    }
                }
            }
        }
        return job.await()
    }

    // ---- Baked backdrops: the picture at screen size (max 1920×1080) with the scrims painted in, ~8 MB each.
    /** Most baked backdrops kept: 4, or 2 in "performance" mode (set by HomeScreen). */
    @Volatile var maxBaked = 4
    private val baked = object : LinkedHashMap<String, Pair<Int, ImageBitmap>>(8, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Pair<Int, ImageBitmap>>?) = size > maxBaked
    }
    @Synchronized fun getBaked(url: String, src: ImageBitmap): ImageBitmap? =
        baked[url]?.takeIf { it.first == src.width * 10000 + src.height }?.second
    @Synchronized fun putBaked(url: String, src: ImageBitmap, b: ImageBitmap) { baked[url] = (src.width * 10000 + src.height) to b }

    // ---- Half-size copies of baked backdrops, for the clear glass. Weak keys: they go with their baked picture.
    private val half = java.util.WeakHashMap<ImageBitmap, ImageBitmap>()
    fun peekHalf(b: ImageBitmap?): ImageBitmap? = b?.let { synchronized(half) { half[it] } }
    fun putHalf(b: ImageBitmap, h: ImageBitmap) { synchronized(half) { half[b] = h } }

    /** Drops everything (screen stopped, or the TV is low on memory). Bitmaps on screen are never recycled here. */
    fun clear() {
        glass.evictAll()
        synchronized(this) { baked.clear() }
        synchronized(half) { half.clear() }
    }

    /** "glass 9 · baked 2 · half 2", for the Memory line. */
    fun describe(): String = "glass ${glass.size() / (1024 * 1024)} MB · baked ${synchronized(this) { baked.size }} · half ${synchronized(half) { half.size }}"

    private companion object {
        // One low-priority worker: blurring never competes with drawing the screen. Holds no bitmaps.
        val worker: java.util.concurrent.ExecutorService = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread({ android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND); r.run() }, "glass-blur").apply { isDaemon = true }
        }
        val blurScope = CoroutineScope(SupervisorJob() + worker.asCoroutineDispatcher())
    }
}
