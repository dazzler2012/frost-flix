package com.glasslauncher.tv.ui

import android.content.Context
import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import com.glasslauncher.tv.data.Trakt
import com.glasslauncher.tv.saver.ScreensaverActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private sealed interface SRow
private class Toggle(val title: String, val desc: String, val get: () -> Boolean, val set: (Boolean) -> Unit) : SRow
private class Adjust(val title: String, val desc: String, val value: () -> String, val step: (Int) -> Unit) : SRow
private class Act(val title: String, val desc: String, val value: String = "", val onLong: (() -> Unit)? = null, val onClick: () -> Unit) : SRow
private class Note(val text: String) : SRow
private class AppItem(val entry: AppEntry) : SRow
/** The Trakt sign-in block: one button that stays the same row (and keeps focus) in every state. */
private class TraktBox(val state: Trakt.State, val username: String, val signedInInfo: String, val onLong: (() -> Unit)? = null, val onClick: () -> Unit) : SRow

private enum class Kind { Rows, About }
private class Category(val title: String, val kind: Kind = Kind.Rows, val rows: @Composable () -> List<SRow> = { emptyList() })

private fun <T> cycle(list: List<T>, cur: T, d: Int): T {
    val i = list.indexOf(cur).let { if (it < 0) 0 else it }
    return list[(i + d).mod(list.size)]
}
private fun styleName(v: String) = when (v) { "water" -> "Glass like glass"; "water_still" -> "Glass like glass (still)"; "clear" -> "Clear (matte)"; "amoled" -> "AMOLED black"; "bow" -> "Black on white"; else -> "Liquid glass" }
private fun hostOf(u: String) = runCatching { java.net.URI(u).host }.getOrNull() ?: "add-on"
private fun pct(f: Float) = "${(f * 100).roundToInt()}%"
private fun tenth(f: Float, d: Int) = ((f * 10).roundToInt() + d) / 10f

/** Google Play page of [pkg], or a Play search for [query]; falls back to the web page. */
private fun openPlay(ctx: Context, pkg: String?, query: String?) {
    val q = android.net.Uri.encode(query.orEmpty())
    val market = if (pkg != null) "market://details?id=$pkg" else "market://search?q=$q&c=apps"
    val web = if (pkg != null) "https://play.google.com/store/apps/details?id=$pkg" else "https://play.google.com/store/search?q=$q&c=apps"
    for (u in listOf(market, web)) {
        try { ctx.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(u)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return }
        catch (_: Exception) {}
    }
    toast(ctx, "Google Play is not available on this device")
}

private fun openSystem(ctx: Context, action: String) {
    try { ctx.startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    catch (_: Exception) { toast(ctx, "This screen is not available on this device") }
}

@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun SettingsScreen(app: GlassApp, initialCat: Int, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val st = app.settings
    val scope = rememberCoroutineScope()
    val catalogs by app.catalogs.catalogs.collectAsState()
    val selected by app.catalogs.selectedKeys.collectAsState()
    val addons by app.catalogs.addonList.collectAsState()
    val status by app.catalogs.status.collectAsState()
    val allApps by app.apps.apps.collectAsState()
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    var cat by remember { mutableIntStateOf(initialCat) }
    var shownCat by remember { mutableIntStateOf(initialCat) }
    LaunchedEffect(cat) { if (cat != shownCat) { kotlinx.coroutines.delay(120); shownCat = cat } }
    val packList = remember { listOf("") + app.packs.installed().map { it.pkg } }
    val packLabels = remember { app.packs.installed().associate { it.pkg to it.label } }
    var rightFocused by remember { mutableStateOf(false) }
    val firstCat = remember { FocusRequester() }
    // One requester per category: leaving the right pane goes back to the category it belongs to,
    // instead of whichever category happens to sit next to the row (that switched the page).
    val catFocus = remember { List(16) { FocusRequester() } }

    fun io(block: suspend () -> String) = scope.launch(Dispatchers.IO) {
        toast(ctx, runCatching { block() }.getOrElse { it.message ?: "Failed" })
    }

    val refractionWorks = android.os.Build.VERSION.SDK_INT >= 33
    val isTv = remember { ctx.packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_LEANBACK) }
    val categories = listOf(
        Category("Performance") {
            // Changing any value below (or the same value on another page) switches Mode to Custom: see Settings.userTuned().
            listOf(
                Adjust("Mode", when (st.perfMode) {
                    "auto" -> "Picked for this TV from its memory and graphics chip"
                    "performance" -> "Smoothest: lighter pictures, quick fades, flat glass"
                    "quality" -> "Sharpest: original pictures, slow fades, full glass"
                    "balanced" -> "Sharp pictures, smooth navigation"
                    else -> "Your own mix of the settings below"
                }, {
                    fun n(m: String) = when (m) { "performance" -> "Performance"; "quality" -> "Quality"; "balanced" -> "Balanced"; else -> "Custom" }
                    if (st.perfMode == "auto") "Auto (${n(st.autoTier)})" else n(st.perfMode)
                }, { st.applyMode(cycle(listOf("auto", "performance", "balanced", "quality"), st.perfMode.takeIf { m -> m != "custom" } ?: "auto", it)) }),
                Adjust("Glass detail", "Resolution of the picture the glass bends (higher = crisper glass, more memory)", {
                    when (st.glassRes) { 720 -> "Sharp (720 px)"; else -> "Standard (480 px)" }
                }, { st.glassRes = cycle(listOf(480, 720), st.glassRes, it) }),
                Adjust("Picture quality", "Carousel backdrops", { if (st.imageQuality == "high") "High (1280 px)" else "Maximum (original)" },
                    { st.imageQuality = cycle(listOf("high", "max"), st.imageQuality, it) }),
                Adjust("Prepare ahead", "Slides loaded before you get to them", { "${st.prefetchDepth}" }, { st.prefetchDepth += it }),
                Adjust("Slide transition", "", { "${st.transitionMs} ms" }, { st.transitionMs += it * 50 }),
                // Refraction needs AGSL (Android 13+); below that the glass is always flat, so the row is hidden.
                Adjust("Glass refraction", "0 = flat glass (lightest)", { "${st.refraction}" }, { st.refraction += it * 2 }).takeIf { refractionWorks },
                Toggle("Backdrop gallery", "Several pictures per title", { st.heroGallery }, { st.heroGallery = it }),
            ).filterNotNull()
        },
        Category("Carousel") {
            listOf(
                Toggle("Auto-play", "Slides change on their own", { st.heroAutoplay }, { st.heroAutoplay = it }),
                Adjust("Slide time", "Seconds before the next title", { "${st.heroIntervalSec} s" }, { st.heroIntervalSec += it }),
                Adjust("Transition speed", "Fade between slides (lower = faster)", { "${st.transitionMs} ms" }, { st.transitionMs += it * 100 }),
                Toggle("Dots auto-hide", "Show them only when the carousel is selected", { st.dotsAutoHide }, { st.dotsAutoHide = it }),
                Adjust("Title style", "Logo image, plain text, or nothing", {
                    when (st.titleMode) { "logo" -> "Logo"; "text" -> "Text"; else -> "Hidden" }
                }, { st.titleMode = cycle(listOf("logo", "text", "hidden"), st.titleMode, it) }),
                Adjust("Logo / title size", "Logos are evened out so wide and tall ones look the same size", { pct(st.titleScale) }, { st.titleScale = tenth(st.titleScale, it) }),
                Adjust("Rating, year, platform size", "", { pct(st.metaScale) }, { st.metaScale = tenth(st.metaScale, it) }),
                Adjust("Genres size", "", { pct(st.genresScale) }, { st.genresScale = tenth(st.genresScale, it) }),
                Toggle("Rating, year, platform", "The chips under the title", { st.showMeta }, { st.showMeta = it }),
                Toggle("Genres line", "", { st.showGenres }, { st.showGenres = it }),
                Toggle("Synopsis", "", { st.showSynopsis }, { st.showSynopsis = it }),
                Toggle("Synopsis only when the carousel is selected", "Hidden while you browse the apps", { st.synopsisOnFocus }, { st.synopsisOnFocus = it }),
                Adjust("Synopsis size", "", { pct(st.synopsisScale) }, { st.synopsisScale = tenth(st.synopsisScale, it) }),
                Adjust("Synopsis lines", "", { "${st.synopsisLines}" }, { st.synopsisLines += it }),
                Adjust("Dots style", "", {
                    when (st.dotsMode) { "window" -> "Dots"; "progress" -> "Progress bar"; else -> "Hidden" }
                }, { st.dotsMode = cycle(listOf("window", "progress", "hidden"), st.dotsMode, it) }),
                Adjust("Dots per group", "", { "${st.dotsMax}" }, { st.dotsMax += it }),
                Adjust("Titles per catalog", "Reload to apply", { "${st.perCatalog}" }, { st.perCatalog += it * 5 }),
                Adjust("Total titles", "0 = every title, endless loop", { if (st.maxItems == 0) "Unlimited" else "${st.maxItems}" }, { st.maxItems = (st.maxItems + it * 10).coerceAtLeast(0) }),
                Toggle("Backdrop gallery", "Like Apple TV: each title shows several of its pictures (needs the TMDB token)", { st.heroGallery }, { st.heroGallery = it }),
                Adjust("Pictures per title", "", { "${st.galleryShots}" }, { st.galleryShots = (st.galleryShots + it).coerceIn(2, 6) }),
                Toggle("Shuffle all catalogs", "Random order across every catalog, a new mix each time the list loads", { st.shuffle }, { st.shuffle = it }),
                Adjust("Synopsis language", "Needs a TMDB token", { if (st.descLang == "ar") "Arabic" else "English" }, {
                    st.descLang = cycle(listOf("en", "ar"), st.descLang, it); app.catalogs.clearEnrichment()
                }),
                Adjust("Artwork", "Which picture each title uses", {
                    when (st.artChoice) { "second" -> "Second picture"; "best" -> "Highest resolution"; "addon" -> "Add-on picture"; else -> "Main (TMDB)" }
                }, { st.artChoice = cycle(listOf("main", "second", "best", "addon"), st.artChoice, it) }),
                Note("One title: on the carousel hold OK (or press Menu) to switch to its next picture. Frost-flix remembers it."),
                Act("Reset pictures picked per title", "", "") { st.clearArtPicks(); toast(ctx, "Every title is back to the Artwork setting") },
                Act("Reload carousel now", "") { io { app.catalogs.refresh(); app.catalogs.status.value } },
            )
        },
        Category("Clock & glass") {
            listOf(
                Toggle("Show clock", "", { st.showClock }, { st.showClock = it }),
                Toggle("24-hour clock", "", { st.clock24h }, { st.clock24h = it }),
                Toggle("Show date", "Under the clock, like Apple TV", { st.showDate }, { st.showDate = it }),
                Adjust("Clock position", "", { if (st.clockPos == "right") "Right, by Settings" else "Left, like Apple TV" },
                    { st.clockPos = cycle(listOf("left", "right"), st.clockPos, it) }),
                Adjust("Weather", "Top bar, next to the settings button", {
                    when (st.weatherMode) { "off" -> "Off"; "temp" -> "Temperature"; "condition" -> "Temperature + sky"; "city" -> "Temperature + city"; else -> "Everything" }
                }, { st.weatherMode = cycle(listOf("all", "condition", "city", "temp", "off"), st.weatherMode, it); app.weather.reload() }),
                Act("Weather city", st.weatherCity.ifBlank { "Not set" } + app.weather.found.value.takeIf { it.isNotBlank() }?.let { "  →  $it" }.orEmpty(), "Change") {
                    dialog = TextEntry("Weather city", st.weatherCity, "City name") { st.weatherCity = it; app.weather.reload() }
                },
                Toggle("Fahrenheit", "", { st.weatherF }, { st.weatherF = it }),
                Toggle("Auto-hide clock and settings button", "Hidden while you browse; they appear when you go up to the settings button", { st.topAutoHide }, { st.topAutoHide = it }),
                Adjust("Shown at start for", "Then hidden until you go up to them", { "${st.topHideSec} s" }, { st.topHideSec += it }),
                Adjust("Corner radius", "Dock and app tiles (0 = square)", { "${st.cornerRadius}" }, { st.cornerRadius += it * 2 }),
                Adjust("Glass blur", "0 turns blur off", { "${st.blurRadius}" }, { st.blurRadius += it * 4 }),
                Adjust("Glass refraction", "How much the rim bends what is behind it (0 = flat)", { "${st.refraction}" }, { st.refraction += it * 2 }).takeIf { refractionWorks },
                Adjust("Glass brightness", "", { "${st.glassTint}" }, { st.glassTint += it * 2 }),
                Toggle("Colour on focus", "Like tvOS: icons stay white, the selected one shows its colours on a light plate", { st.focusColor }, { st.focusColor = it }),
                Adjust("Selected icon plate", "With Colour on focus", { if (st.focusPlate == "dark") "Dark" else "Light" },
                    { st.focusPlate = cycle(listOf("light", "dark"), st.focusPlate, it) }),
                Toggle("Bounce at the edges", "Pressing past the first or last icon nudges it, like tvOS", { st.edgeBounce }, { st.edgeBounce = it }),
                Toggle("Lift selected icon", "The selected icon rises a little, glass and all", { st.tileLift }, { st.tileLift = it }),
                Toggle("Show phone apps too", "Apps without a TV launcher entry", { st.showMobileApps }, { st.showMobileApps = it; app.apps.reload() }),
            ).filterNotNull()
        },
        Category("Dock & icons") {
            val imported = st.dockOrder.size // read for recomposition on reorder
            @Suppress("UNUSED_VARIABLE") val unused = imported + allApps.size
            buildList {
                add(Adjust("Icon pack", "Projectivy / Blueprint / Nova packs installed on this TV",
                    { if (st.iconPack.isEmpty()) "None (original icons)" else (packLabels[st.iconPack] ?: st.iconPack) },
                    { st.iconPack = cycle(packList, st.iconPack, it) }))
                add(Adjust("Dock size", "Smaller makes the dock and its icons narrower", { pct(st.dockScale) }, { st.dockScale = ((st.dockScale * 20).roundToInt() + it) / 20f }))
                add(Adjust("Dock background", "Behind the apps row and the Apps folder", { styleName(st.dockStyle) },
                    { st.dockStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.dockStyle, it) }))
                add(Adjust("Settings background", "Behind settings and the icon picker", { styleName(st.panelStyle) },
                    { st.panelStyle = cycle(listOf("liquid", "clear", "amoled"), st.panelStyle, it) }))
                add(Adjust("Icon style", "How the plate behind every icon looks", {
                    styleName(st.tileStyle)
                }, { st.tileStyle = cycle(listOf("liquid", "water", "water_still", "clear", "amoled", "bow"), st.tileStyle, it) }))
                add(Note("Change one icon: on the home screen hold OK on the app → Change icon. Pick from an icon pack, a photo on the TV, or a link to a PNG."))
                add(Note("Icon packs: install any Android TV / Projectivy icon pack from Google Play, then choose it above. Every app takes the pack's icon; you can still change single apps."))
                add(Act("Find icon packs on Google Play", "Projectivy / Android TV icon packs", "") { openPlay(ctx, null, "projectivy icon pack") })
                add(Note("Many icons at once: copy <package>.png files (e.g. com.netflix.ninja.png) into the icon folder below, then Import."))
                add(Note("On the home screen, hold OK on an app for App info, Move, Change icon and Hide."))
                add(Note("Here: OK picks an app up, ◀ ▶ moves it, OK puts it down. Hold OK (or Menu) for its options."))
                add(Act("Import icons from the icon folder", "", "") {
                    io {
                        val n = app.icons.importDropFolder(app.apps.apps.value.map { it.pkg })
                        if (n > 0) "Imported $n icons" else "No new icons. Folder: ${app.icons.dropFolder.absolutePath}"
                    }
                })
                add(Note("Folder for <package>.png files: ${app.icons.dropFolder.absolutePath}"))
                app.apps.ordered(includeHidden = true).forEach { add(AppItem(it)) }
            }
        },
        Category("Catalogs") {
            buildList {
                add(Act("TMDB token", "Title logos, full-size art, synopsis, platform", if (st.tmdbToken.isBlank()) "Not set" else "Set") {
                    dialog = TextEntry("TMDB read access token", "", "Paste the token") {
                        st.tmdbToken = it; app.catalogs.clearEnrichment()
                    }
                })
                add(Act("Connect to Nuvio", "Open Nuvio > Add-ons > Manage from phone first", "") { io { app.catalogs.connectLocal() } })
                add(Act("Add add-on link", "Paste a manifest link", "") {
                    dialog = TextEntry("Add-on link", "", "https://…/manifest.json or stremio://…") { u -> io { app.catalogs.addAddon(u) } }
                })
                add(Act("Refresh catalogs", status, "") { io { app.catalogs.refresh(); app.catalogs.status.value } })
                add(Act("Select all catalogs", "", "") { app.catalogs.selectAll(true); io { app.catalogs.refresh(); "Done" } })
                add(Act("Select none", "", "") { app.catalogs.selectAll(false); io { app.catalogs.refresh(); "Done" } })
                addons.sorted().forEach { u ->
                    add(Act("Add-on · ${app.catalogs.addonName(u)}", hostOf(u), "Remove") {
                        dialog = MenuDialog("Remove ${app.catalogs.addonName(u)}?", listOf("Remove add-on" to { io { app.catalogs.removeAddon(u) } }))
                    })
                }
                if (catalogs.isEmpty()) add(Note("No catalogs yet. Connect to Nuvio or add an add-on link."))
                catalogs.forEach { c ->
                    add(Toggle(c.name, "${c.addonName} · ${c.type}", { c.key in selected }, {
                        app.catalogs.toggleCatalog(c.key)
                    }))
                }
            }
        },
        Category("Continue Watching") {
            val trakt by app.trakt.state.collectAsState()
            val traktUser by app.trakt.username.collectAsState()
            val cwList by app.cw.entries.collectAsState()
            val fetched by app.cw.lastFetch.collectAsState()
            val cwError by app.cw.lastError.collectAsState()
            val refreshCheck by app.trakt.refreshCheck.collectAsState()
            buildList {
                add(Adjust("Show Continue Watching", "Where titles you are watching appear", {
                    when (st.continueMode) { "off" -> "Off"; "mixed" -> "Mixed with catalogs"; else -> "At the start" }
                }, { st.continueMode = cycle(listOf("first", "mixed", "off"), st.continueMode, it) }))
                if (st.continueMode == "first") add(Adjust("Slides at the start", "How many titles in progress open the carousel", {
                    if (st.cwLead <= 0) "All" else "${st.cwLead}"
                }, { st.cwLead = cycle(listOf(1, 2, 3, 4, 5, 0), st.cwLead, it) }))
                add(Toggle("Progress bar", "Shows Continue · S1 · E3 · 42% under the title", { st.cwProgress }, { st.cwProgress = it }))
                add(Note("Trakt"))
                if (trakt == Trakt.State.NotConfigured) add(Note("Trakt not configured. This build has no Trakt client ID (see SETUP.md)."))
                else {
                    // Sign-in, the code and "Signed in as …" all live in this one row, so focus never leaves the page.
                    val ago = if (fetched <= 0L) "not loaded yet" else "updated ${((System.currentTimeMillis() - fetched) / 60_000).coerceAtLeast(0)} min ago"
                    add(TraktBox(trakt, traktUser, "${cwList.size} titles · $ago · OK updates now (otherwise at most every 30 minutes)",
                        // Hidden debug action: hold OK on "Signed in as …" to refresh the Trakt token now.
                        onLong = if (trakt == Trakt.State.SignedIn) ({ io { "Force Trakt refresh now: " + app.trakt.forceRefresh() } }) else null,
                    ) {
                        when (trakt) {
                            Trakt.State.Checking -> Unit
                            Trakt.State.Starting, is Trakt.State.Waiting -> app.trakt.cancelSignIn()
                            Trakt.State.SignedIn -> io {
                                if (st.continueMode == "off") "Continue Watching is off"
                                else { app.cw.fetch(true); app.cw.lastError.value.ifBlank { "Continue Watching updated" } }
                            }
                            else -> app.trakt.startSignIn()
                        }
                    })
                    if (trakt == Trakt.State.SignedIn && cwError.isNotBlank()) add(Note(cwError))
                    if (trakt == Trakt.State.SignedIn && refreshCheck.isNotBlank()) add(Note("Force Trakt refresh now · $refreshCheck"))
                    if (trakt == Trakt.State.SignedIn) add(Act("Sign out of Trakt", "Removes the sign-in and the list from this TV", "") { io { app.trakt.signOut(); "Signed out of Trakt" } })
                }
            }
        },
        Category("Nuvio") {
            listOf(
                Adjust("Open titles in", "Where a carousel title opens", {
                    when (st.nuvioTarget) { "nuviotv" -> "NuvioTV"; "legacy" -> "Nuvio Legacy"; "reshaped" -> "Nuvio Re-Shaped"; else -> "Automatic" }
                }, {
                    st.nuvioTarget = cycle(listOf("auto", "nuviotv", "legacy", "reshaped"), st.nuvioTarget, it)
                }),
                Act("TMDB token", "Adds title logos, full-size art, synopsis and platform", if (st.tmdbToken.isBlank()) "Not set" else "Set") {
                    dialog = TextEntry("TMDB read access token", "", "Paste the token") {
                        st.tmdbToken = it; app.catalogs.clearEnrichment()
                    }
                },
            )
        },
        Category("Screensaver") {
            buildList {
                val system = runCatching { AndroidSettings.Secure.getString(ctx.contentResolver, "screensaver_components") }.getOrNull().orEmpty()
                if (system.isNotBlank() && !system.contains(ctx.packageName)) {
                    add(Note("Your system screensaver is already ${system.substringBefore('/')}. Leave \"Start after\" on Off and it will keep working on this launcher."))
                }
                val aerialViews = "com.neilturner.aerialviews"
                val avInstalled = remember { runCatching { ctx.packageManager.getPackageInfo(aerialViews, 0) }.isSuccess }
                val avComponent = "$aerialViews/.ui.screensaver.DreamActivity"
                add(Note("Recommended: Aerial Views, a free system screensaver on Google Play with your own 4K videos, clock and info."))
                if (!avInstalled) add(Act("Install Aerial Views", "Opens it on Google Play", "Google Play") { openPlay(ctx, aerialViews, null) })
                else {
                    add(Act("Open Aerial Views settings", "Choose videos, clock, overlays", "") {
                        ctx.packageManager.getLaunchIntentForPackage(aerialViews)?.let { runCatching { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                    })
                }
                add(Note("Steps: 1. Install Aerial Views. 2. Pick it in the TV's screensaver settings (Use as system screensaver, below)."))
                add(Adjust("Start after", "Idle time on the launcher", { if (st.idleMinutes == 0) "Off" else "${st.idleMinutes} min" }, {
                    st.idleMinutes = cycle(listOf(0, 1, 2, 3, 5, 10, 15, 30), st.idleMinutes, it)
                }))
                add(Toggle("Show clock", "", { st.saverClock }, { st.saverClock = it }))
                add(Toggle("Back on the home screen starts it", "Like Apple TV: press Back on the home screen", { st.backSaver }, { st.backSaver = it }))
                add(Act("Add video link", "For Frost-flix as system screensaver: a direct https .mp4 / .mov link", "") {
                    dialog = TextEntry("Video link", "", "https://…/video.mp4") { u ->
                        if (u.trim().startsWith("https://")) st.aerialUrls = (st.aerialUrls + u.trim()).distinct() else toast(ctx, "The link must start with https://")
                    }
                })
                st.aerialUrls.forEach { u ->
                    add(Act("Video link", hostOf(u), "Remove") { st.aerialUrls = st.aerialUrls - u })
                }
                add(Act("Preview now", "") { ctx.startActivity(Intent(ctx, ScreensaverActivity::class.java)) })
                add(Act("Use as system screensaver", "Pick Frost-flix in the list", "") { openSystem(ctx, AndroidSettings.ACTION_DREAM_SETTINGS) })
            }
        },
        Category("System") {
            listOf(
                Toggle("Home button opens Frost-flix", "Needs the accessibility service below", { st.interceptHome }, { st.interceptHome = it }),
                Act("Turn on the accessibility service", "Find Frost-flix and switch it on", "") { openSystem(ctx, AndroidSettings.ACTION_ACCESSIBILITY_SETTINGS) },
            ) + (
                // Android TV / Google TV (Shield included) has no home app picker that works with the remote: keys
                // stayed on Frost-flix behind it. On a TV the Home button is taken over by the accessibility service.
                if (isTv) listOf(Note("Android TV has no usable \"default home app\" choice. To make Home open Frost-flix, switch on the option above, then turn on the Frost-flix accessibility service (Android Settings → Device Preferences → Accessibility → Frost-flix)."))
                else listOf(Act("Choose default home app", "", "") { openSystem(ctx, AndroidSettings.ACTION_HOME_SETTINGS) })
            )
        },
        Category("About", Kind.About),
    )

    // Catalog choices made here are applied when the screen closes, but only if they changed what the
    // carousel was last loaded with (selected catalogs, titles per catalog, total titles, shuffle).
    DisposableEffect(Unit) {
        onDispose { if (app.catalogs.needsReload()) app.scope.launch(Dispatchers.IO) { runCatching { app.catalogs.refresh() } } }
    }
    BackHandler { if (rightFocused) runCatching { catFocus[cat].requestFocus() } else onClose() }
    LaunchedEffect(Unit) { delay150(); runCatching { firstCat.requestFocus() } }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)).padding((28 * s).dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((16 * s).dp)) {
            GlassPanel(Modifier.width((230 * s).dp).fillMaxHeight(), (30 * s).dp, style = st.panelStyle, darken = 0.42f) {
                // Scrolls when the list is taller than the screen (the last entry, About, was cut off).
                Column(Modifier.padding(horizontal = (14 * s).dp, vertical = (12 * s).dp).verticalScroll(rememberScrollState())) {
                    Text("Settings", color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = (8 * s).dp, bottom = (8 * s).dp))
                    categories.forEachIndexed { i, c ->
                        var f by remember { mutableStateOf(false) }
                        Text(
                            c.title, color = Color.White, fontSize = (13.5f * s).sp,
                            fontWeight = if (cat == i) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.fillMaxWidth().padding(vertical = (1.5f * s).dp)
                                .clip(RoundedCornerShape((14 * s).dp))
                                .background(if (f) Color.White.copy(alpha = 0.30f) else if (cat == i) Color.White.copy(alpha = 0.12f) else Color.Transparent)
                                .focusRequester(catFocus[i])
                                .then(if (i == initialCat) Modifier.focusRequester(firstCat) else Modifier)
                                .onFocusChanged { f = it.isFocused; if (it.isFocused) cat = i }
                                .clickable { cat = i }
                                .padding(horizontal = (12 * s).dp, vertical = (7.5f * s).dp),
                        )
                    }
                }
            }
            GlassPanel(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged { rightFocused = it.hasFocus }
                    // Left goes back to this page's own category; Up/Down at the ends stay in the page.
                    .focusProperties {
                        exit = { dir ->
                            when (dir) {
                                androidx.compose.ui.focus.FocusDirection.Left -> catFocus[cat]
                                androidx.compose.ui.focus.FocusDirection.Up, androidx.compose.ui.focus.FocusDirection.Down -> FocusRequester.Cancel
                                else -> FocusRequester.Default
                            }
                        }
                    }
                    .focusGroup(),
                (30 * s).dp, style = st.panelStyle, darken = 0.42f,
            ) {
                val c = categories[shownCat]
                Column(Modifier.padding((18 * s).dp)) {
                    Text(c.title, color = Color.White, fontSize = (20 * s).sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(start = (6 * s).dp, bottom = (8 * s).dp))
                    if (c.kind == Kind.About) AboutPane(app, s, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } })
                    else {
                        val rows = c.rows()
                        LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
                            items(rows.size) { i -> RowView(rows[i], s, app, onApp = { e -> appMenu(app, e, ctx, scope) { dialog = it } }) }
                        }
                    }
                }
            }
        }
    }
    DialogHost(dialog) { dialog = null }
}

/** This app's source code (GPL-3.0), shown as a QR code in About. */
private const val SOURCE_REPO = "dazzler2012/frost-flix"

private suspend fun delay150() = kotlinx.coroutines.delay(150)

private fun appMenu(app: GlassApp, e: AppEntry, ctx: Context, scope: kotlinx.coroutines.CoroutineScope, show: (DialogState) -> Unit) {
    val hidden = e.pkg in app.settings.hiddenApps
    val opts = mutableListOf<Pair<String, () -> Unit>>()
    opts += (if (hidden) "Show in dock" else "Hide from dock") to { app.apps.toggleHidden(e.pkg) }
    opts += "Icon from a link" to {
        show(TextEntry("Icon link for ${e.label}", "", "https://…/icon.png") { u ->
            scope.launch(Dispatchers.IO) { toast(ctx, runCatching { app.icons.setFromUrl(e.pkg, u.trim()); "Icon updated" }.getOrElse { it.message ?: "Failed" }) }
        })
    }
    if (app.icons.has(e.pkg)) opts += "Reset to the original icon" to { app.icons.reset(e.pkg) }
    opts += "Open app" to { app.apps.launchIntent(e)?.let { runCatching { ctx.startActivity(it) } } }
    show(MenuDialog(e.label, opts))
}

@Composable
private fun RowView(row: SRow, s: Float, app: GlassApp, onApp: (AppEntry) -> Unit) {
    when (row) {
        is Note -> Text(row.text, color = Color.White.copy(alpha = 0.55f), fontSize = (11.5f * s).sp, modifier = Modifier.padding(horizontal = (8 * s).dp, vertical = (6 * s).dp))
        is Toggle -> Frame(s, onClick = { row.set(!row.get()) }) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            val on = row.get()
            Box(Modifier.width((38 * s).dp).height((22 * s).dp).clip(RoundedCornerShape(50)).background(if (on) Color(0xFF7B61FF) else Color.White.copy(alpha = 0.22f)).padding((3 * s).dp)) {
                Box(Modifier.size((16 * s).dp).clip(RoundedCornerShape(50)).background(Color.White).align(if (on) Alignment.CenterEnd else Alignment.CenterStart))
            }
        }
        is Adjust -> {
            var editing by remember { mutableStateOf(false) }
            Frame(s, editing = editing, onKey = { ev ->
                when {
                    !editing -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) row.step(if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) editing = false; true }
                    // Up/Down while editing: stay on the row
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) editing = false }, onClick = { editing = !editing }) { f ->
                Label(row.title, if (editing) "◀ ▶ to change · OK when done" else row.desc, s, Modifier.weight(1f))
                if (editing) Text("‹  ${row.value()}  ›", color = Color(0xFFB9ABFF), fontSize = (13.5f * s).sp, fontWeight = FontWeight.SemiBold)
                else Text(row.value(), color = Color.White.copy(alpha = if (f) 1f else 0.75f), fontSize = (13 * s).sp)
            }
        }
        is Act -> Frame(s, onClick = row.onClick, onLong = row.onLong) { f ->
            Label(row.title, row.desc, s, Modifier.weight(1f))
            if (row.value.isNotEmpty()) Text(row.value, color = Color.White.copy(alpha = 0.7f), fontSize = (12.5f * s).sp)
        }
        is TraktBox -> TraktBoxView(row, s)
        is AppItem -> {
            val e = row.entry
            val hidden = e.pkg in app.settings.hiddenApps
            val art by produceState<ImageBitmap?>(null, e.pkg) { value = app.apps.artwork(e)?.bmp }
            var moving by remember { mutableStateOf(false) }
            Frame(s, editing = moving, onKey = { ev ->
                when {
                    ev.key == Key.Menu -> { if (ev.type == KeyEventType.KeyDown) onApp(e); true }
                    !moving -> false
                    ev.key == Key.DirectionLeft || ev.key == Key.DirectionRight -> {
                        if (ev.type == KeyEventType.KeyDown) app.apps.move(e.pkg, if (ev.key == Key.DirectionLeft) -1 else 1); true
                    }
                    ev.key == Key.Back -> { if (ev.type == KeyEventType.KeyUp) moving = false; true }
                    ev.key == Key.DirectionUp || ev.key == Key.DirectionDown -> true
                    else -> false
                }
            }, onFocus = { if (!it) moving = false }, onClick = { moving = !moving }, onLong = { onApp(e) }) { f ->
                Box(Modifier.size((34 * s).dp).clip(RoundedCornerShape((8 * s).dp)).background(Color.White.copy(alpha = 0.10f)), contentAlignment = Alignment.Center) {
                    art?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((3 * s).dp)) }
                }
                Spacer(Modifier.width((10 * s).dp))
                Label(e.label, e.pkg, s, Modifier.weight(1f))
                val flags = listOfNotNull(if (hidden) "Hidden" else null, if (app.icons.has(e.pkg)) "Custom icon" else null).joinToString(" · ")
                if (flags.isNotEmpty()) Text(flags, color = Color(0xFFB9ABFF), fontSize = (11.5f * s).sp)
                Text(if (moving) "  ◀ ▶ · OK" else "", color = Color(0xFFB9ABFF), fontSize = (12 * s).sp)
            }
        }
    }
}

/**
 * Signed out / failed: "Sign in to Trakt". Getting a code / waiting: QR code, address and code, and "Cancel".
 * Signed in: "Signed in as <username>". The button is the same composable in every state, so it keeps focus.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun TraktBoxView(row: TraktBox, s: Float) {
    val t = row.state
    val showCode = t == Trakt.State.Starting || t is Trakt.State.Waiting
    val bring = remember { androidx.compose.foundation.relocation.BringIntoViewRequester() }
    // The block grows when the code appears; scroll it fully into view.
    LaunchedEffect(t is Trakt.State.Waiting, showCode) { if (showCode) { kotlinx.coroutines.delay(60); runCatching { bring.bringIntoView() } } }
    val dim = Color.White.copy(alpha = 0.6f)
    Row(
        Modifier.fillMaxWidth().bringIntoViewRequester(bring),
        horizontalArrangement = Arrangement.spacedBy((18 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (t is Trakt.State.Waiting) {
            val qr = rememberQr(t.qrUrl, 600, margin = 4)
            Box(Modifier.size((210 * s).dp).clip(RoundedCornerShape((10 * s).dp)).background(Color.White)) {
                qr?.let { Image(it, "QR code: ${t.qrUrl}", Modifier.fillMaxSize(), filterQuality = androidx.compose.ui.graphics.FilterQuality.None) }
            }
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            when (t) {
                Trakt.State.Starting -> Text("Getting a code from Trakt…", color = Color.White, fontSize = (16 * s).sp, modifier = Modifier.padding(start = (8 * s).dp))
                is Trakt.State.Waiting -> Column(Modifier.padding(start = (8 * s).dp, bottom = (6 * s).dp)) {
                    Text("Scan the QR code with your phone, or open", color = dim, fontSize = (12 * s).sp)
                    Text(t.url.removePrefix("https://"), color = Color.White, fontSize = (22 * s).sp, fontWeight = FontWeight.SemiBold)
                    Text("and enter this code", color = dim, fontSize = (12 * s).sp, modifier = Modifier.padding(top = (4 * s).dp))
                    Text(
                        t.userCode, color = Color.White, fontSize = (38 * s).sp, fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, letterSpacing = (3 * s).sp,
                    )
                    Text(
                        "Waiting for Trakt… The code works until " + java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(t.expiresAt)),
                        color = dim, fontSize = (11 * s).sp,
                    )
                }
                is Trakt.State.Failed -> Text(t.message, color = Color(0xFFFFB4A9), fontSize = (12 * s).sp, modifier = Modifier.padding(horizontal = (8 * s).dp))
                else -> Unit
            }
            val (title, desc) = when (t) {
                Trakt.State.Checking -> "Trakt" to "Checking the sign-in…"
                Trakt.State.Starting, is Trakt.State.Waiting -> "Cancel" to "Stop signing in"
                Trakt.State.SignedIn -> (if (row.username.isNotBlank()) "Signed in as ${row.username}" else "Signed in to Trakt") to row.signedInInfo
                is Trakt.State.Failed -> "Sign in to Trakt" to "Try again"
                else -> "Sign in to Trakt" to "Continue Watching comes from what NuvioTV scrobbles to Trakt"
            }
            Frame(s, onClick = row.onClick, onLong = row.onLong) { Label(title, desc, s, Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun Label(title: String, desc: String, s: Float, modifier: Modifier) {
    Column(modifier) {
        Text(title, color = Color.White, fontSize = (14 * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (desc.isNotBlank()) Text(desc, color = Color.White.copy(alpha = 0.5f), fontSize = (10.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Frame(
    s: Float,
    onKey: ((androidx.compose.ui.input.key.KeyEvent) -> Boolean)? = null,
    onClick: () -> Unit,
    onLong: (() -> Unit)? = null,
    onFocus: (Boolean) -> Unit = {},
    editing: Boolean = false,
    content: @Composable androidx.compose.foundation.layout.RowScope.(Boolean) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused; onFocus(it.isFocused) }
            .clip(RoundedCornerShape((14 * s).dp))
            .background(when {
                editing -> Color(0xFF7B61FF).copy(alpha = 0.34f)
                focused -> Color.White.copy(alpha = 0.26f)
                else -> Color.White.copy(alpha = 0.06f)
            })
            .tvPress(onLong = onLong, onKey = onKey, onClick = onClick)
            .padding(horizontal = (14 * s).dp, vertical = (9 * s).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content(focused) }
}


/** A QR code made off the main thread (a 600 px code is 360 000 pixels); null until it is ready. */
@Composable
private fun rememberQr(text: String, sizePx: Int, margin: Int = 2): ImageBitmap? =
    produceState<ImageBitmap?>(null, text, sizePx, margin) {
        value = withContext(Dispatchers.Default) { runCatching { BitmapTracker.track(com.glasslauncher.tv.net.Net.qr(text, sizePx, margin).asImageBitmap(), "qr") }.getOrNull() }
    }.value

/** Version, the source code's page as a QR code, credits and the last crash (if any). */
@Composable
private fun AboutPane(app: GlassApp, s: Float, onApp: (com.glasslauncher.tv.data.AppEntry) -> Unit) {
    val ctx = LocalContext.current
    val version = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull() ?: "?" }
    val qr = rememberQr("https://github.com/$SOURCE_REPO", 420)
    val crash = remember { java.io.File(ctx.filesDir, "last-crash.txt").takeIf { it.exists() }?.readText()?.lineSequence()?.take(2)?.joinToString(" · ").orEmpty() }
    // Hidden: hold OK on the version for the Memory line (refreshed every 3 s while shown).
    var showMemory by remember { mutableStateOf(false) }
    val memory by produceState("", showMemory) {
        while (showMemory) {
            value = withContext(Dispatchers.IO) { runCatching { com.glasslauncher.tv.MemoryStats.read(app, pss = true) }.getOrDefault("") }
            kotlinx.coroutines.delay(3000)
        }
    }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((18 * s).dp)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size((150 * s).dp).clip(RoundedCornerShape((12 * s).dp)).background(Color.White)) {
                qr?.let { Image(it, "GitHub", Modifier.fillMaxSize()) }
            }
            Spacer(Modifier.height((6 * s).dp))
            Text("github.com/$SOURCE_REPO · Source code", color = Color.White.copy(alpha = 0.75f), fontSize = (9.5f * s).sp)
            Text("Scan with your phone", color = Color.White.copy(alpha = 0.5f), fontSize = (9 * s).sp)
        }
        val rows = buildList<SRow> {
            add(Act("Frost-flix $version", "No self-updates", "", onLong = { showMemory = !showMemory }) {})
            if (showMemory) {
                add(Note("Memory now · " + memory.ifBlank { "measuring…" }))
                add(Note(com.glasslauncher.tv.FrameStats.describe()))
                add(Note(app.lastBackground.ifBlank { "Memory in the background: not measured yet (it is noted ~5 s after another app comes to the front)" }))
            }
            add(Note("Frost-flix by Darren · Based on Glassio by mohammadmrh12 · GPL-3.0 · Fonts: Inter (SIL OFL) · Weather: Open-Meteo · This product uses the TMDB API but is not endorsed or certified by TMDB."))
            if (crash.isNotBlank()) add(Act("Last crash", crash.take(120), "Clear") { java.io.File(ctx.filesDir, "last-crash.txt").delete(); toast(ctx, "Cleared") })
        }
        LazyColumn(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy((4 * s).dp)) {
            items(rows.size) { i -> RowView(rows[i], s, app, onApp = onApp) }
        }
    }
}
