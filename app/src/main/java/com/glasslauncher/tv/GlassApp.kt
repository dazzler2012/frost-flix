package com.glasslauncher.tv

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.os.Bundle
import com.glasslauncher.tv.data.AppRepository
import com.glasslauncher.tv.data.CatalogRepository
import com.glasslauncher.tv.data.Http
import com.glasslauncher.tv.data.IconPacks
import com.glasslauncher.tv.data.IconStore
import com.glasslauncher.tv.data.Settings
import androidx.compose.runtime.mutableLongStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class GlassApp : Application(), coil3.SingletonImageLoader.Factory {
    /**
     * Image caches sized for TV boxes that also have to play video: 15% of the app's memory class for
     * decoded pictures, 256 MB on disk (small boxes such as the Shield have little free storage).
     */
    override fun newImageLoader(context: coil3.PlatformContext): coil3.ImageLoader =
        coil3.ImageLoader.Builder(context)
            .memoryCache { coil3.memory.MemoryCache.Builder().maxSizePercent(context, 0.15).build() }
            .diskCache {
                coil3.disk.DiskCache.Builder()
                    .directory(java.io.File(cacheDir, "images").absolutePath.let { okio.Path.Companion.run { it.toPath() } })
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .build()

    /**
     * True while any Frost-flix activity is started (ON_START .. ON_STOP). Background loops wait on it, so
     * nothing runs while another app (e.g. NuvioTV playing a stream) is in front.
     */
    private val _foreground = MutableStateFlow(false)
    val foreground: StateFlow<Boolean> = _foreground
    private var started = 0

    /** Drops every large cached bitmap: page backdrops, glass copies and Coil's decoded pictures. */
    fun releaseImages() {
        com.glasslauncher.tv.ui.releaseBitmaps()
        runCatching { coil3.SingletonImageLoader.get(this).memoryCache?.clear() }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        // UI_HIDDEN and above: we are in the background; RUNNING_LOW / CRITICAL: the TV is short of memory.
        @Suppress("DEPRECATION")
        if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW ||
            level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) releaseImages()
    }

    /** Bumped when Home is pressed while Glass is already in front: the carousel goes back to its start. */
    val homeRev = androidx.compose.runtime.mutableIntStateOf(0)
    /** Last Left/Right in the carousel: while browsing, slides switch at once and fades are short. */
    @Volatile var navAt = 0L
    fun browsing() = System.currentTimeMillis() - navAt < 1200

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    lateinit var settings: Settings; private set
    lateinit var http: Http; private set
    lateinit var icons: IconStore; private set
    lateinit var packs: IconPacks; private set
    lateinit var apps: AppRepository; private set
    /** Bumped on user input (throttled) so the clock / settings button can auto-hide. */
    val interaction = mutableLongStateOf(0L)
    /** Set from inside the app (e.g. the carousel's "add catalogs" button): opens Settings on that tab. */
    val requestSettings = androidx.compose.runtime.mutableIntStateOf(-1)
    /** Bumped with every request, so asking for the same tab twice (or while the screen was asleep) still works. */
    val requestSettingsRev = androidx.compose.runtime.mutableIntStateOf(0)
    fun touch() {
        val n = System.currentTimeMillis()
        if (n - interaction.longValue > 400) interaction.longValue = n
    }
    lateinit var catalogs: CatalogRepository; private set
    lateinit var weather: com.glasslauncher.tv.data.Weather; private set
    lateinit var trakt: com.glasslauncher.tv.data.Trakt; private set
    lateinit var cw: com.glasslauncher.tv.data.ContinueWatching; private set

    override fun onCreate() {
        super.onCreate()
        // A launcher must never vanish silently: keep the last crash for Settings → About.
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            runCatching {
                java.io.File(filesDir, "last-crash.txt").writeText(
                    java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date()) + "\n" + e.stackTraceToString().take(4000))
            }
            previous?.uncaughtException(t, e)
        }
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(a: Activity) { started++; _foreground.value = true }
            override fun onActivityStopped(a: Activity) {
                started = (started - 1).coerceAtLeast(0)
                if (started == 0) { _foreground.value = false; releaseImages() }
            }
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityDestroyed(a: Activity) {}
        })
        settings = Settings(this)
        if (settings.perfMode == "auto") settings.applyMode("auto")
        com.glasslauncher.tv.ui.GlassCache.res = settings.glassRes
        http = Http(this)
        icons = IconStore(this)
        packs = IconPacks(this)
        apps = AppRepository(this, settings, packs, icons).also { it.start() }
        catalogs = CatalogRepository(this, settings, http).also { it.start(scope, foreground) }
        weather = com.glasslauncher.tv.data.Weather(settings, http, scope, foreground).also { it.start() }
        trakt = com.glasslauncher.tv.data.Trakt(this, scope)
        cw = com.glasslauncher.tv.data.ContinueWatching(this, settings, http, trakt, catalogs, scope, foreground).also { it.start() }
        // Icon files from the drop folder are decoded off the main thread.
        scope.launch(Dispatchers.IO) { runCatching { icons.importDropFolder(apps.apps.value.map { it.pkg }) } }
        // Decode dock artwork once, off the main thread, so the first frames are instant.
        scope.launch { apps.apps.value.forEach { runCatching { apps.artwork(it) } } }
    }
}
