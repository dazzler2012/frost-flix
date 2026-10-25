package com.glasslauncher.tv

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import com.glasslauncher.tv.saver.ScreensaverActivity
import com.glasslauncher.tv.ui.HomeScreen
import com.glasslauncher.tv.ui.ImageCaches
import com.glasslauncher.tv.ui.LocalImageCaches
import com.glasslauncher.tv.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    @Volatile private var lastInput = System.currentTimeMillis()
    /** The home screen's bitmaps (backdrops, glass copies). Exists only between ON_START and ON_STOP. */
    private var images: ImageCaches? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
    }

    /**
     * The screen is built on every ON_START and thrown away on every ON_STOP. While another app (NuvioTV
     * playing a stream) is in front, nothing of it stays: no composition, no remembered pictures, no render
     * nodes still pointing at bitmaps. Coming back shows the same slide again, its pictures reloaded from
     * the disk cache.
     */
    override fun onStart() {
        super.onStart()
        if (images == null) show()
        FrameStats.start(this)
    }

    override fun onStop() {
        super.onStop()
        FrameStats.counting = false
        FrameStats.stop(this)
        // Removing the ComposeView from the window disposes its composition (the default strategy).
        findViewById<android.view.ViewGroup>(android.R.id.content)?.removeAllViews()
        // Also cancels the preloads and glass copies still running for it (downloads included).
        images?.stop()
        images = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // The TV is short of memory while we are in front: drop the caches; what is on screen stays referenced.
        @Suppress("DEPRECATION")
        if (level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) images?.clear()
    }

    private fun show() {
        val app = application as GlassApp
        val caches = ImageCaches(app.settings.glassRes).also { images = it }
        setContent {
            // A launcher never exits on Back; it just returns to its home view.
            BackHandler(enabled = true) { screen = Screen.Home }
            // Frames are counted for the home screen only (not while Settings covers it).
            LaunchedEffect(Unit) { androidx.compose.runtime.snapshotFlow { screen }.collect { FrameStats.counting = it == Screen.Home } }
            LaunchedEffect(Unit) {
                while (true) {
                    delay(10_000)
                    val minutes = app.settings.idleMinutes
                    val idle = System.currentTimeMillis() - lastInput
                    if (minutes > 0 && idle > minutes * 60_000L && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                        lastInput = System.currentTimeMillis()
                        startActivity(Intent(this@MainActivity, ScreensaverActivity::class.java))
                    }
                }
            }
            CompositionLocalProvider(LocalImageCaches provides caches) {
                com.glasslauncher.tv.ui.GlassType { HomeScreen(app, screen) { screen = it } }
            }
        }
    }

    private fun hideBars() {
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    override fun onResume() {
        super.onResume()
        lastInput = System.currentTimeMillis()
        hideBars()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // Home pressed while we are already in front: go back to the plain home view.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) {
            screen = Screen.Home
            // Also when the screen is rebuilt after this (Home pressed in another app): start on the first slide.
            (application as GlassApp).heroIndex = 0
            (application as GlassApp).homeRev.intValue++
        }
        lastInput = System.currentTimeMillis()
    }

    override fun onUserInteraction() {
        lastInput = System.currentTimeMillis()
        (application as GlassApp).touch()
    }

    /** When the last D-pad key event was let through (uptime ms). */
    private var lastDpad = 0L

    /**
     * Holding a D-pad key repeats it about 20 times a second. Each step moves focus or changes the slide and
     * starts work (recomposition, picture loads); when the UI thread falls behind, the repeats queue up and the
     * screen keeps moving after the key is released. Repeats are let through at most every [REPEAT_MS], and a
     * repeat that waited more than [STALE_MS] in the queue is dropped. The first press always goes through.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val dpad = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> true
            else -> false
        }
        if (dpad && event.action == KeyEvent.ACTION_DOWN) {
            val now = android.os.SystemClock.uptimeMillis()
            if (event.repeatCount > 0 && (now - event.eventTime > STALE_MS || now - lastDpad < REPEAT_MS)) return true
            lastDpad = now
        }
        return super.dispatchKeyEvent(event)
    }

    private companion object {
        const val REPEAT_MS = 100L
        const val STALE_MS = 120L
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            screen = if (screen == Screen.Settings) Screen.Home else Screen.Settings
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
