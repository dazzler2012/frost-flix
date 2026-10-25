package com.glasslauncher.tv

import android.os.Build

/**
 * Gives the native heap's freed pages back to the system. Freeing a bitmap returns its memory to the
 * allocator, which keeps the pages for later; until it releases them they still count in the process's PSS.
 */
object NativeHeap {
    private val loaded = runCatching { System.loadLibrary("heappurge") }.isSuccess

    /**
     * mallopt(M_PURGE) (API 28+). 1: the allocator released its free pages; 0: it doesn't support the option;
     * -1: not called (older Android or the library is missing).
     */
    fun purge(): Int =
        if (!loaded || Build.VERSION.SDK_INT < 28) -1 else runCatching { nativePurge() }.getOrDefault(-1)

    /** Which allocator this process runs on, from the names it gives its memory maps (for the Memory line). */
    val allocator: String by lazy {
        runCatching {
            var name = "unknown"
            java.io.File("/proc/self/maps").useLines { lines ->
                for (l in lines) {
                    if (l.contains("scudo:")) { name = "Scudo"; break }
                    if (l.contains("[anon:libc_malloc]")) name = "jemalloc"
                }
            }
            name
        }.getOrDefault("unknown")
    }

    @JvmStatic private external fun nativePurge(): Int
}
