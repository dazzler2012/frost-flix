package com.glasslauncher.tv

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
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
import com.glasslauncher.tv.ui.Screen
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf(Screen.Home)
    @Volatile private var lastInput = System.currentTimeMillis()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        hideBars()
        val app = application as GlassApp
        setContent {
            // A launcher never exits on Back; it just returns to its home view.
            BackHandler(enabled = true) { screen = Screen.Home }
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
            com.glasslauncher.tv.ui.GlassType { HomeScreen(app, screen) { screen = it } }
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
            (application as GlassApp).homeRev.intValue++
        }
        lastInput = System.currentTimeMillis()
    }

    override fun onUserInteraction() {
        lastInput = System.currentTimeMillis()
        (application as GlassApp).touch()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            screen = if (screen == Screen.Settings) Screen.Home else Screen.Settings
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
