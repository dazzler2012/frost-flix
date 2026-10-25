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
}
