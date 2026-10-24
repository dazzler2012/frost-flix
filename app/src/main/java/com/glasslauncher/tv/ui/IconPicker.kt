package com.glasslauncher.tv.ui

import android.Manifest
import android.content.ContentUris
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import com.glasslauncher.tv.GlassApp
import com.glasslauncher.tv.data.AppEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed interface IconSource {
    val title: String
    object Suggested : IconSource { override val title = "Suggested" }
    class Pack(val pkg: String, override val title: String) : IconSource
    object Photos : IconSource { override val title = "Photos on this TV" }
    object Link : IconSource { override val title = "From a link" }
    object Original : IconSource { override val title = "Original icon" }
}

private data class PackIcon(val pack: String, val name: String)

/** Full-screen chooser: an icon from any installed pack, a photo saved on the TV, a link, or the original. */
@Composable
fun IconPicker(app: GlassApp, entry: AppEntry, onClose: () -> Unit) {
    val s = LocalScale.current
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val packs = remember { app.packs.installed() }
    val sources = remember {
        buildList {
            add(IconSource.Suggested)
            packs.forEach { add(IconSource.Pack(it.pkg, it.label)) }
            add(IconSource.Photos); add(IconSource.Link); add(IconSource.Original)
        }
    }
    var sel by remember { mutableIntStateOf(0) }
    var dialog by remember { mutableStateOf<DialogState?>(null) }
    val first = remember { FocusRequester() }
    var rightFocused by remember { mutableStateOf(false) }
    BackHandler { if (rightFocused) runCatching { first.requestFocus() } else onClose() }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(120); runCatching { first.requestFocus() } }

    fun done(msg: String) { toast(ctx, msg); onClose() }
    fun applyPack(p: PackIcon) = scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val d = app.packs.drawable(p.pack, p.name) ?: error("This icon could not be loaded")
                app.icons.setFromDrawable(entry.pkg, d)
            }
        }
        done(if (r.isSuccess) "Icon updated" else r.exceptionOrNull()?.message ?: "Failed")
    }
    fun applyUri(u: Uri) = scope.launch {
        val r = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = ctx.contentResolver.openInputStream(u)?.use { it.readBytes() } ?: error("Could not read the file")
                app.icons.setFromBytes(entry.pkg, bytes)
            }
        }
        done(if (r.isSuccess) "Icon updated" else r.exceptionOrNull()?.message ?: "Failed")
    }

    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.82f)).padding((28 * s).dp)) {
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy((14 * s).dp)) {
            GlassPanel(Modifier.width((210 * s).dp).fillMaxHeight(), (18 * s).dp, style = app.settings.panelStyle, darken = 0.6f) {
                Column(Modifier.padding((12 * s).dp)) {
                    Text("Icon · ${entry.label}", color = Color.White, fontSize = (15 * s).sp, fontWeight = FontWeight.Medium,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = (6 * s).dp, bottom = (8 * s).dp))
                    LazyColumn(verticalArrangement = Arrangement.spacedBy((2 * s).dp)) {
                        itemsIndexed(sources) { i, src ->
                            var f by remember { mutableStateOf(false) }
                            Text(
                                src.title, color = Color.White, fontSize = (11.5f * s).sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontWeight = if (sel == i) FontWeight.SemiBold else FontWeight.Normal,
                                modifier = Modifier.fillMaxWidth()
                                    .clip(RoundedCornerShape((8 * s).dp))
                                    .background(if (f) Color.White.copy(alpha = 0.28f) else if (sel == i) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                                    .then(if (i == 0) Modifier.focusRequester(first) else Modifier)
                                    .onFocusChanged { f = it.isFocused; if (it.isFocused && src.isBrowsable()) sel = i }
                                    .tvPress {
                                        when (src) {
                                            IconSource.Link -> dialog = TextEntry("Icon link · ${entry.label}", "", "https://…/icon.png") { u ->
                                                scope.launch {
                                                    val r = withContext(Dispatchers.IO) { runCatching { app.icons.setFromUrl(entry.pkg, u.trim()) } }
                                                    done(if (r.isSuccess) "Icon updated" else r.exceptionOrNull()?.message ?: "Failed")
                                                }
                                            }
                                            IconSource.Original -> { app.icons.reset(entry.pkg); done("Back to the original icon") }
                                            else -> sel = i
                                        }
                                    }
                                    .padding(horizontal = (10 * s).dp, vertical = (7 * s).dp),
                            )
                        }
                    }
                }
            }
            GlassPanel(
                Modifier.weight(1f).fillMaxHeight().onFocusChanged { rightFocused = it.hasFocus },
                (18 * s).dp, style = app.settings.panelStyle, darken = 0.6f,
            ) {
                when (val src = sources[sel]) {
                    IconSource.Suggested ->
                        PackGrid(app, s, suggested(app, entry), "No pack has an icon that matches this app. Pick a pack on the left to browse all its icons.") { applyPack(it) }
                    is IconSource.Pack -> PackGrid(app, s, allOf(app, src.pkg), "This pack has no icons.") { applyPack(it) }
                    IconSource.Photos -> PhotoGrid(app, s) { applyUri(it) }
                    else -> {}
                }
            }
        }
    }
    DialogHost(dialog) { dialog = null }
}

private fun IconSource.isBrowsable() = this is IconSource.Suggested || this is IconSource.Pack || this is IconSource.Photos

@Composable
private fun suggested(app: GlassApp, e: AppEntry): List<PackIcon>? {
    val v by produceState<List<PackIcon>?>(null, e.pkg) {
        value = withContext(Dispatchers.IO) {
            app.packs.installed().flatMap { p -> app.packs.suggestions(p.pkg, e.component, e.label).map { PackIcon(p.pkg, it) } }
        }
    }
    return v
}

@Composable
private fun allOf(app: GlassApp, pack: String): List<PackIcon>? {
    val v by produceState<List<PackIcon>?>(null, pack) {
        value = withContext(Dispatchers.IO) { app.packs.drawableNames(pack).map { PackIcon(pack, it) } }
    }
    return v
}

@Composable
private fun PackGrid(app: GlassApp, s: Float, list: List<PackIcon>?, empty: String, onPick: (PackIcon) -> Unit) {
    when {
        list == null -> Hint("Loading icons…", s)
        list.isEmpty() -> Hint(empty, s)
        else -> LazyVerticalGrid(
            columns = GridCells.Adaptive((112 * s).dp),
            contentPadding = PaddingValues((14 * s).dp),
            horizontalArrangement = Arrangement.spacedBy((12 * s).dp),
            verticalArrangement = Arrangement.spacedBy((12 * s).dp),
        ) {
            items(list, key = { it.pack + "/" + it.name }) { p ->
                val bmp by produceState<ImageBitmap?>(null, p) {
                    value = withContext(Dispatchers.Default) {
                        runCatching { app.packs.drawable(p.pack, p.name)?.let { app.packs.render(it, 260).asImageBitmap() } }.getOrNull()
                    }
                }
                PickTile(s, p.name.replace('_', ' '), onClick = { onPick(p) }) {
                    bmp?.let { Image(it, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((6 * s).dp)) }
                }
            }
        }
    }
}

@Composable
private fun PhotoGrid(app: GlassApp, s: Float, onPick: (Uri) -> Unit) {
    val ctx = LocalContext.current
    val perm = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    val list by produceState<List<Uri>?>(null, granted) {
        value = withContext(Dispatchers.IO) {
            val out = mutableListOf<Uri>()
            app.icons.dropFolder.listFiles().orEmpty()
                .filter { it.extension.lowercase() in setOf("png", "jpg", "jpeg", "webp") }
                .sortedByDescending { it.lastModified() }.forEach { out += Uri.fromFile(it) }
            if (granted) runCatching {
                val base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                ctx.contentResolver.query(base, arrayOf(MediaStore.Images.Media._ID), null, null,
                    "${MediaStore.Images.Media.DATE_MODIFIED} DESC")?.use { c ->
                    while (c.moveToNext() && out.size < 400) out += ContentUris.withAppendedId(base, c.getLong(0))
                }
            }
            out
        }
    }
    Column(Modifier.fillMaxSize()) {
        if (!granted) {
            Row(Modifier.padding((14 * s).dp), verticalAlignment = Alignment.CenterVertically) {
                PickButton("Allow access to photos on this TV", s) { launcher.launch(perm) }
            }
        }
        val l = list
        when {
            l == null -> Hint("Loading…", s)
            l.isEmpty() -> Hint("No images yet. Allow access above, or copy images into ${app.icons.dropFolder.absolutePath}", s)
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive((112 * s).dp),
                contentPadding = PaddingValues((14 * s).dp),
                horizontalArrangement = Arrangement.spacedBy((12 * s).dp),
                verticalArrangement = Arrangement.spacedBy((12 * s).dp),
            ) {
                items(l, key = { it.toString() }) { u ->
                    val req = remember(u) { ImageRequest.Builder(ctx).data(u.toString()).size(320, 200).build() }
                    PickTile(s, null, onClick = { onPick(u) }) {
                        AsyncImage(model = req, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding((4 * s).dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Hint(text: String, s: Float) {
    Text(text, color = Color.White.copy(alpha = 0.65f), fontSize = (12 * s).sp, modifier = Modifier.padding((18 * s).dp))
}

@Composable
private fun PickButton(text: String, s: Float, onClick: () -> Unit) {
    var f by remember { mutableStateOf(false) }
    Text(
        text, color = if (f) Color.Black else Color.White, fontSize = (12 * s).sp, fontWeight = FontWeight.Medium,
        modifier = Modifier.clip(RoundedCornerShape((8 * s).dp))
            .background(if (f) Color.White else Color.White.copy(alpha = 0.14f))
            .onFocusChanged { f = it.isFocused }.tvPress(onClick = onClick)
            .padding(horizontal = (14 * s).dp, vertical = (8 * s).dp),
    )
}

@Composable
private fun PickTile(s: Float, label: String?, onClick: () -> Unit, content: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(if (focused) 1.1f else 1f, spring(0.72f, 520f), label = "pick")
    val shape = RoundedCornerShape((10 * s).dp)
    Column(
        Modifier.onFocusChanged { focused = it.isFocused }.tvPress(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier.fillMaxWidth().aspectRatio(1.6f)
                .graphicsLayer { scaleX = scale; scaleY = scale; shadowElevation = if (focused) 14f * s else 0f; this.shape = shape; clip = true }
                .background(Brush.verticalGradient(listOf(Color(0xFF2B2B35), Color(0xFF17171D))))
                .border(if (focused) 2.dp else 0.6.dp, Color.White.copy(alpha = if (focused) 0.9f else 0.14f), shape),
            contentAlignment = Alignment.Center,
        ) { content() }
        if (label != null) {
            Spacer(Modifier.height((3 * s).dp))
            Text(label, color = Color.White.copy(alpha = if (focused) 1f else 0.55f), fontSize = (9 * s).sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
    }
}
