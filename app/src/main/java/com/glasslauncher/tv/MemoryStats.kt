package com.glasslauncher.tv

import android.os.Debug
import com.glasslauncher.tv.ui.BitmapTracker

/**
 * Numbers for the hidden Memory line (Settings → About, hold OK on the version), to compare builds on a TV.
 * Nothing here is sent anywhere.
 */
object MemoryStats {
    private fun mb(bytes: Long) = String.format(java.util.Locale.US, "%.1f", bytes / (1024.0 * 1024.0))

    /**
     * Native heap in use (Debug.getNativeHeapAllocatedSize: bitmap pixels live there on Android 8+) and the
     * native heap's total size (in use + freed but not yet given back), Java heap, the bitmaps Frost-flix
     * tracks, Coil's memory cache and, with [pss], the process's total PSS (reads /proc, ~50-200 ms: call
     * off the main thread).
     */
    fun read(app: GlassApp, pss: Boolean): String {
        val rt = Runtime.getRuntime()
        val bitmaps = BitmapTracker.snapshot()
        val bmpCount = bitmaps.values.sumOf { it.first }
        val bmpBytes = bitmaps.values.sumOf { it.second }
        val kinds = bitmaps.entries.joinToString(", ") { (k, v) -> "$k ${v.first} / ${mb(v.second)} MB" }
        val coil = runCatching {
            coil3.SingletonImageLoader.get(app).memoryCache?.let { "${mb(it.size)} of ${mb(it.maxSize)} MB" }
        }.getOrNull() ?: "off"
        return buildString {
            append("Native ${mb(Debug.getNativeHeapAllocatedSize())} MB in use, heap ${mb(Debug.getNativeHeapSize())} MB")
            append(" · Java ${mb(rt.totalMemory() - rt.freeMemory())} of ${mb(rt.maxMemory())} MB")
            if (pss) append(" · PSS ${mb(Debug.getPss() * 1024)} MB")
            append(" · tracked bitmaps $bmpCount / ${mb(bmpBytes)} MB")
            if (kinds.isNotEmpty()) append(" ($kinds)")
            append(" · Coil memory $coil")
        }
    }

    /**
     * Frees what was just unreferenced. Bitmap pixels are only freed when their Java object is collected,
     * and an idle app in the background may not collect for a long time on its own (its Java heap is small).
     */
    fun collect() {
        Runtime.getRuntime().gc()
        System.runFinalization()
        Runtime.getRuntime().gc()
    }

    /**
     * The dumpsys meminfo summary from Debug.getMemoryInfo, which also asks the memtrack HAL for the process's
     * GPU memory: "graphics" (EGL + GL mtrack), "private other" (holds "Other mtrack", the GPU driver's
     * textures and buffers on the Shield) and total PSS including both. Debug.getPss() leaves memtrack out.
     * Reads /proc and the HAL (~100-300 ms): call off the main thread.
     */
    fun gpu(): String {
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        fun kb(key: String) = mi.getMemoryStat(key)?.toLongOrNull()?.let { mb(it * 1024) } ?: "?"
        return "graphics ${kb("summary.graphics")} MB, private other ${kb("summary.private-other")} MB, total PSS ${kb("summary.total-pss")} MB"
    }

    /**
     * Frees the GPU memory the render thread still caches for us. When a window stops, Android's renderer keeps
     * textures used in the last 30 s, and TRIM_MEMORY_UI_HIDDEN only cuts its cache to half its limit (the limit
     * is 48 bytes per pixel of the display mode: ~199 MB of that half at 4K). Destroying a renderer purges every
     * cached resource not used in the last 30 s (CanvasContext::destroy → trimStaleResources, in the
     * Android 11 source). The renderer made here never draws; it only shares the render thread's GPU context.
     * Call on the main thread, more than 30 s after the screen was last drawn. Android 10+ (HardwareRenderer).
     */
    fun releaseGpu(): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 29) return false
        return runCatching { android.graphics.HardwareRenderer().destroy() }.isSuccess
    }
}
