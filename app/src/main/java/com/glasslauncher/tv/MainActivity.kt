package com.glasslauncher.tv

import android.content.ComponentCallbacks2
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.glasslauncher.tv.saver.ScreensaverActivity
import com.glasslauncher.tv.ui.HomeScreen
import com.glasslauncher.tv.ui.ImageCaches
import com.glasslauncher.tv.ui.LocalImageCaches
import com.glasslauncher.tv.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    @Volatile private var lastInput = System.currentTimeMillis()
    /** Reference to the active composition's [ImageCaches] for [onTrimMemory]; managed via [DisposableEffect]. */
    private var images: ImageCaches? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Only one MainActivity may exist. A second one can appear when Frost-flix is started both as the
        // system Home activity and as a normal app (launcher row, Home button service): Android keeps those
        // in separate tasks. The older one is closed; the new one is the one in front.
        current?.get()?.let { old ->
            if (old !== this && !old.isFinishing && !old.isDestroyed) {
                android.util.Log.i("FrostHome", "closing the older MainActivity")
                old.finish()
            }
        }
        current = java.lang.ref.WeakReference(this)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        val app = application as GlassApp
        setContent {
            val caches = remember { ImageCaches(app.settings.glassRes) }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner, caches) {
                images = caches
                val observer = LifecycleEventObserver { _, event ->
                    when (event) {
                        Lifecycle.Event.ON_START -> {
                            FrameStats.counting = screen == Screen.Home
                            FrameStats.start(this@MainActivity)
                        }
                        Lifecycle.Event.ON_STOP -> {
                            FrameStats.counting = false
                            FrameStats.stop(this@MainActivity)
                            caches.stop()
                        }
                        else -> Unit
                    }
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                    FrameStats.counting = screen == Screen.Home
                    FrameStats.start(this@MainActivity)
                }
                onDispose {
                    lifecycleOwner.lifecycle.removeObserver(observer)
                    FrameStats.counting = false
                    FrameStats.stop(this@MainActivity)
                    caches.stop()
                    if (images === caches) images = null
                }
            }
            // A launcher never exits on Back; it just returns to its home view.
            BackHandler(enabled = true) { screen = Screen.Home }
            // Frames are counted for the home screen only (not while Settings covers it).
            LaunchedEffect(Unit) {
                androidx.compose.runtime.snapshotFlow { screen }.collect {
                    FrameStats.counting = it == Screen.Home && lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
                }
            }
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

    override fun onDestroy() {
        super.onDestroy()
        images = null
        if (current?.get() === this) current = null
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // In the background (UI_HIDDEN and above) or short of memory: drop the glass copies. What is on screen
        // stays referenced; HomeScreen drops its backdrop state on ON_STOP.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) images?.clear()
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
            // Also when Home is pressed from another app: start on the first slide.
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
        /** The live MainActivity (see onCreate). Weak, so it never keeps a destroyed one alive. */
        var current: java.lang.ref.WeakReference<MainActivity>? = null
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
