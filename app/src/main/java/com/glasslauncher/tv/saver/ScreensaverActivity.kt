package com.glasslauncher.tv.saver

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.annotation.OptIn
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil3.compose.AsyncImage
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.ui.clockText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.material3.Text

/** Full-screen backdrop slideshow. Any key press or touch dismisses it. */
class ScreensaverActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        val app = application as GlassApp
        setContent { com.glasslauncher.tv.ui.GlassType { SaverContent(app) } }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) finish()
        return true
    }
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean { finish(); return true }
    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean { if (ev.actionMasked != MotionEvent.ACTION_HOVER_EXIT) finish(); return true }
}

@Composable
private fun SaverContent(app: GlassApp) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Slideshow(app)
        if (app.settings.saverClock) {
            val t by produceState("") { while (true) { value = clockText(app.settings.clock24h); delay(15_000) } }
            Text(
                t, color = Color.White.copy(alpha = 0.9f), fontSize = 34.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(end = 56.dp, top = 36.dp),
            )
        }
    }
}

/** Slow Ken Burns over the catalog backdrops, with each title's logo. */
@Composable
private fun Slideshow(app: GlassApp) {
    val raw by app.catalogs.items.collectAsState()
    val enriched by app.catalogs.enrichedItems.collectAsState()
    val items = remember(raw) { raw.filter { it.background.isNotBlank() } }
    var index by remember { mutableIntStateOf(0) }
    LaunchedEffect(items.size) { while (items.size > 1) { delay(12_000); index++ } }
    val item = items.takeIf { it.isNotEmpty() }?.let { val r = it[index.mod(it.size)]; enriched[r.key] ?: r }
    val url = item?.background
    val t = rememberInfiniteTransition(label = "kb")
    val scale by t.animateFloat(1.0f, 1.14f, infiniteRepeatable(tween(12_000, easing = LinearEasing), RepeatMode.Reverse), label = "s")
    Crossfade(targetState = url, animationSpec = tween(1600), label = "show") { u ->
        Box(Modifier.fillMaxSize().clipToBounds()) {
            if (u != null) AsyncImage(
                model = u, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = scale; scaleY = scale }.alpha(0.95f),
            )
        }
    }
    // Soft shade so the logo reads on bright frames.
    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Brush.verticalGradient(
        0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
    Crossfade(targetState = item, animationSpec = tween(1600), label = "logo") { it ->
        Box(Modifier.fillMaxSize().padding(start = 64.dp, bottom = 52.dp), contentAlignment = Alignment.BottomStart) {
            if (it != null) {
                if (it.logo.isNotBlank()) AsyncImage(
                    model = it.logo, contentDescription = it.name, contentScale = ContentScale.Fit,
                    alignment = Alignment.BottomStart,
                    modifier = Modifier.size(380.dp, 150.dp),
                ) else Text(it.name, color = Color.White, fontSize = 40.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
        }
    }
}
