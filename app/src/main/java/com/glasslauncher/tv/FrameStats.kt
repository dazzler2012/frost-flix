package com.glasslauncher.tv

import android.app.Activity
import android.os.Handler
import android.os.HandlerThread
import android.view.FrameMetrics
import android.view.Window

/**
 * Frame times of the home screen, for the hidden Frames line (Settings → About, hold OK on the version), to
 * compare builds on the TV without a PC. Counted from Android's FrameMetrics (the same per-frame durations
 * dumpsys gfxinfo uses) while the home screen is shown; reset every time it opens. Nothing is sent anywhere.
 */
object FrameStats {
    // 1 ms buckets up to 999 ms; the last bucket holds everything slower.
    private val hist = IntArray(1000)
    private var frames = 0
    private var janky = 0
    private var worstNs = 0L
    private var firstNs = -1L
    private var firstParts = ""
    private var seenFirst = false
    private var worstParts = ""
    private var worstAtMs = 0L
    private var worstIndex = 0
    private var since = 0L
    private var sinceUptime = 0L
    private var intervalNs = 16_666_667L

    /** False while Settings or another screen covers the home screen: those frames are not counted. */
    @Volatile var counting = false

    private val thread by lazy { HandlerThread("frame-stats").apply { start() } }
    private val listener = Window.OnFrameMetricsAvailableListener { _, m, _ -> add(m) }

    /** Starts counting for [activity] from zero (MainActivity ON_START). */
    fun start(activity: Activity) {
        val hz = runCatching { activity.windowManager.defaultDisplay.refreshRate }.getOrDefault(60f).takeIf { it > 1f } ?: 60f
        synchronized(this) {
            hist.fill(0); frames = 0; janky = 0; worstNs = 0L; firstNs = -1L
            firstParts = ""; seenFirst = false; worstParts = ""; worstAtMs = 0L; worstIndex = 0
            since = System.currentTimeMillis()
            sinceUptime = android.os.SystemClock.uptimeMillis()
            intervalNs = (1_000_000_000L / hz).toLong()
        }
        runCatching { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
    }

    /** Stops counting (MainActivity ON_STOP); the numbers stay for the About page. */
    fun stop(activity: Activity) {
        runCatching { activity.window.removeOnFrameMetricsAvailableListener(listener) }
    }

    private fun ms(m: FrameMetrics, id: Int) = m.getMetric(id) / 1_000_000L

    /**
     * Where a frame's time went: "wait" = the UI thread was busy with something else before the frame could
     * start (a long task between frames), "UI" = input, animations, composition, layout and recording the
     * drawing, "upload" = handing pictures to the GPU (a picture shown for the first time), "GPU" = issuing
     * the drawing commands.
     */
    private fun parts(m: FrameMetrics): String {
        val ui = ms(m, FrameMetrics.INPUT_HANDLING_DURATION) + ms(m, FrameMetrics.ANIMATION_DURATION) +
            ms(m, FrameMetrics.LAYOUT_MEASURE_DURATION) + ms(m, FrameMetrics.DRAW_DURATION)
        return "wait ${ms(m, FrameMetrics.UNKNOWN_DELAY_DURATION)} · UI $ui (layout ${ms(m, FrameMetrics.LAYOUT_MEASURE_DURATION)}) · " +
            "upload ${ms(m, FrameMetrics.SYNC_DURATION)} · GPU ${ms(m, FrameMetrics.COMMAND_ISSUE_DURATION)} ms"
    }

    private fun add(m: FrameMetrics) {
        val ns = m.getMetric(FrameMetrics.TOTAL_DURATION)
        if (ns <= 0) return
        synchronized(this) {
            // The first frame after the screen is built (on every ON_START) is reported on its own, flagged by
            // Android or not, and also while Settings is in front.
            if (!seenFirst || m.getMetric(FrameMetrics.FIRST_DRAW_FRAME) == 1L) {
                seenFirst = true; firstNs = ns; firstParts = parts(m); return
            }
            if (!counting) return
            frames++
            // Janky: the frame took longer than one refresh of the display (missed its vsync), like gfxinfo.
            if (ns > intervalNs) janky++
            if (ns > worstNs) {
                worstNs = ns; worstParts = parts(m); worstIndex = frames
                worstAtMs = android.os.SystemClock.uptimeMillis() - sinceUptime
            }
            hist[(ns / 1_000_000L).toInt().coerceIn(0, hist.size - 1)]++
        }
    }

    private fun percentile(p: Int): Int {
        val target = (frames * p + 99) / 100
        var seen = 0
        for (i in hist.indices) { seen += hist[i]; if (seen >= target) return i + 1 }
        return hist.size
    }

    /**
     * "Frames (home, since 21:04): 802, janky 204 (25.4%) · 50th 10 ms · … · worst 200 ms (frame 12, 3.4 s after
     * opening: wait 0 · UI 180 (layout 20) · upload 12 · GPU 5 ms) · first frame 410 ms (…)".
     */
    fun describe(): String = synchronized(this) {
        if (since == 0L) return "Frames: the home screen has not been shown yet"
        val at = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(since))
        if (frames == 0) return "Frames (home, since $at): none counted yet"
        val pct = String.format(java.util.Locale.US, "%.1f", janky * 100.0 / frames)
        buildString {
            append("Frames (home, since $at): $frames, janky $janky ($pct%)")
            append(" · 50th ${percentile(50)} ms · 90th ${percentile(90)} ms · 95th ${percentile(95)} ms · 99th ${percentile(99)} ms")
            val at = String.format(java.util.Locale.US, "%.1f", worstAtMs / 1000.0)
            append(" · worst ${worstNs / 1_000_000L} ms (frame $worstIndex, $at s after opening: $worstParts)")
            if (firstNs > 0) append(" · first frame ${firstNs / 1_000_000L} ms ($firstParts)")
        }
    }
}
