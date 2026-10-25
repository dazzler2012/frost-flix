# Changes from upstream Glassio v1.9.3

Base: upstream tag `v1.9.3` (commit `8409fcf`, versionCode 25).

## Identity

- `applicationId` changed from `com.glasslauncher.tv` to `tv.darren.glassio`. The Kotlin package and `namespace` stay `com.glasslauncher.tv`.
- App label changed from "Glassio" to "Frost-flix" (it was "Glassio (D)" up to 1.9.3-d3).
- `versionName` changed to `1.9.3-d9`. `versionCode` is 33 (upstream 25).
- Release builds are no longer signed with the debug key. They are signed only when CI provides the keystore from secrets. Otherwise they are unsigned.

## Removed: adb client, key storage and everything that used it

- `data/LocalShell.kt` deleted: the adb-over-localhost client, adb key generation and key storage (`files/adb/adbkey`), and silent `pm install`.
- `dev.mobile:dadb` dependency and its ProGuard rules removed.
- The adb-based `data/ContinueWatching.kt` (reading Nuvio's log through adb) was deleted in 1.9.3-d1. Removed with it:
  - "jump to what I just watched" and the pinned-image cache in the carousel,
  - the settings `continueJump` and `cwLegacy`.
  - Since 1.9.3-d3 a new `data/ContinueWatching.kt` feeds Continue Watching from Trakt instead, with no adb. It brought back the "Continue Watching" settings page, the slides and progress line, and the settings `continueMode`, `cwLead` and `cwProgress` (see "Added: Continue Watching from Trakt" below).
- `saver/SystemDream.kt` deleted: starting the TV's own screensaver and changing the screen timeout through adb.
  - The screensaver type "TV screensaver" was removed.
  - The "Make Aerial Views the TV screensaver" button was removed (it wrote secure settings through adb). "Install Aerial Views", "Open Aerial Views settings" and "Use as system screensaver" remain.

## Removed: self-updater

- `data/Updater.kt` deleted: GitHub release checks, APK download, install through adb or `PackageInstaller`, and `InstallResultReceiver`.
- The update banner and the update pill next to the clock were removed from `ui/HomeScreen.kt`.
- The "Check for updates" and "Install update" rows were removed from Settings → About. About now shows the version and a QR code linking to the original project.
- `REQUEST_INSTALL_PACKAGES` permission and the receiver were removed from the manifest.

## Removed: WRITE_SECURE_SETTINGS

- The permission was removed from the manifest.
- `GlassApp.ensureHomeService()` was removed. It re-enabled the accessibility service by writing secure settings. After an update, turn the service on again by hand in the Android accessibility settings.

## Removed: intent-extra settings in MainActivity

- `MainActivity.handleExtras()` was removed, including `open_settings`, `icon_pack`, `blur`, `saver_mode`, `ha_webhook`, `continue_mode`, `reset_icon`, `open_folder`, `hero_autoplay`, `page_glass`, `focus_color`, `start_saver`, `dump_settings`, `perf_mode`, `refraction`, `art_choice`, `dock_style` and `tile_style`.
- `GlassApp.requestFolder` was removed (only those extras set it).
- The HOME / LAUNCHER / LEANBACK_LAUNCHER intent filters are unchanged. Pressing Home while Frost-flix is in front still returns to the first slide.

## Removed: "Manage from phone"

- `net/PhoneServer.kt` deleted: the LAN HTTP server (`ServerSocket` on ports 9090–9099) and its web page.
- `Net.localIp()` and `Net.token()` removed. `Net.qr()` stays for the About QR code.
- Removed: the "Manage from phone" settings page, the "Add add-ons from my phone (QR)" row, "Icon from my phone" in the app menu, and "From my phone" in the icon picker.

## Removed: Home Assistant webhook

- The webhook POST on title launch and `NuvioLaunch.testWebhook()` were removed.
- `Http.post()` was removed (only the webhook used it).
- The settings `haWebhook` and `haOnLaunch` and their rows were removed. The settings page "Nuvio & Home Assistant" is now "Nuvio" and keeps "Open titles in" and "TMDB token".

## Removed: Apple TV icons

- The "Apple TV icons" (default / dark / white / clear) sources were removed from the icon picker, along with their matching in "Suggested" and the file grid that showed them.
- `tools/appletv_icons.py` and `tools/push_appletv_icons.sh` were deleted.
- `docs/screenshots/` was deleted. The screenshots showed Apple TV icons and the Apple logo, along with other third-party logos and artwork.

## Removed: Apple aerial videos

- The in-app screensaver ("Type: Apple aerial videos") was removed, along with the "Apple aerial videos" toggle and the `sylvan.apple.com` feed in `saver/AerialSource.kt`.
- The screensaver started by Frost-flix ("Start after", "Back starts it", "Preview now") now always shows the poster slideshow. The settings `saverMode` and `useAppleAerials` were removed, and the "Type" row is gone.
- Kept: your own video links ("Add video link"). Only the system screensaver service (`GlassDreamService`, "Use as system screensaver") plays them.

## Removed: seed.json import

- `CatalogRepository.importSeed()` was removed. It read `seed.json` from the app's external files folder at startup and imported add-on URLs, catalog choices and a TMDB token from it. Tokens and add-ons can now only be entered on the TV.

## Removed: upstream adb developer scripts

- Deleted `tools/fs.sh`, `tools/fs2.sh`, `tools/jank.sh`, `tools/slidetest.sh`, `tools/soak.sh` and `tools/tvtest.sh`. They drove a TV over adb from a PC and were never part of the app.

## Network

- New `res/xml/network_security_config.xml`: cleartext is off by default, and allowed only for the domain `127.0.0.1` (subdomains not included).
- `android:usesCleartextTraffic="false"` stays in the manifest. Android ignores it when a network security config is set.
- The only remaining `http://` URL in app code is `CatalogRepository.connectLocal()` → `http://127.0.0.1:8080-8089/api/addons` ("Connect to Nuvio"). The config allows it. Not tested on a device.
- Icon links, add-on links and video links that start with `http://` are blocked. Use `https://`.

## Docs and build

- New: `CREDITS.md`, `CHANGES.md`, `SETUP.md`, `.github/workflows/build.yml`.
- `LICENSE` (GPL-3.0) was added from upstream commit `aac6937`. The `v1.9.3` tag itself has no LICENSE file.
- The upstream READMEs moved to `docs/upstream/` with a note on top and without the screenshot links. There is a new short `README.md`.
- `.gitignore` now also ignores `*.jks`, `*.keystore` and `keystore.properties`. `local.properties` was already ignored.

## Added: NuvioTV (`com.nuvio.tv`) support

- Carousel titles now also open in NuvioTV, using its `nuvio://meta?type=…&id=…` link.
- "Open titles in" has a new default, "Automatic": NuvioTV first, then Nuvio Legacy, then Nuvio Re-Shaped, whichever is installed. A fixed choice that is not installed falls back to the same order.
- If the installed Nuvio refuses the title link, Frost-flix opens the Nuvio app instead.
- `com.nuvio.tv` was added to `<queries>` in the manifest and to the preferred apps list.

## Added: Continue Watching from Trakt (1.9.3-d3)

Continue Watching is back, but fed from Trakt instead of reading Nuvio's log over adb. Nothing adb-related returns.

- **Sign-in:** Settings → Continue Watching → Sign in to Trakt. Device code flow with the Client ID only (no Client Secret). The TV shows the code and address, polls at Trakt's interval and stops when the code expires.
- **Tokens:** stored only in app-private storage, encrypted with an AES-GCM key from the Android Keystore. Both tokens are replaced after every refresh. The access token is refreshed when it has less than a day left. If Trakt answers `invalid_grant`, the tokens are deleted and the page asks you to sign in again. Tokens are never logged.
- **Sign out of Trakt:** deletes the tokens and the cached list, and asks Trakt to revoke the token.
- **Data:** in-progress movies and episodes (`/sync/playback`) and the next episode of shows you are partway through (`/sync/progress/watched`). Cached in app-private storage. Fetched on app start and at most every 30 minutes.
- **Carousel:** the Continue Watching slides and settings page are restored: "At the start" / "Mixed with catalogs" / "Off", "Slides at the start", and "Progress bar" with the "Continue · S1 · E3 · 42%" label. A next episode shows as "Up next · S1 · E4".
- **Artwork:** from the meta resource of your own add-ons (Cinemeta is skipped), then TMDB when a token is set. The Cinemeta lookups are not back.
- **Play:** opens the title in Nuvio with the existing launch code (NuvioTV first).
- **Network:** the only new hosts are `https://api.trakt.tv` (data) and `https://auth.trakt.tv` (sign-in, token refresh, revoke; since 1.9.3-d4).
- **Redirect URI:** the token refresh sends `redirect_uri`, from `BuildConfig.TRAKT_REDIRECT_URI` (default `http://localhost`, override with `TRAKT_REDIRECT_URI` at build time). It is only sent as text to Trakt; Frost-flix never connects to it.
- **Build:** the Client ID comes from the GitHub Actions secret `TRAKT_CLIENT_ID` and goes into `BuildConfig` at build time. It is not in the repo. Without it the page shows "Trakt not configured".
- **Still removed:** "Open on what I just watched", the Nuvio Legacy log toggle, the pinned-image cache and the settings `continueJump` and `cwLegacy`.

## Fixed: Trakt sign-in (1.9.3-d4)

- All `/oauth/*` requests (device code, device token, token refresh, revoke) now go to `https://auth.trakt.tv`, as Trakt's openapi.json requires. Data requests stay on `https://api.trakt.tv`.
- The address shown on the TV is the `verification_url` Trakt returns. There is no built-in fallback address any more.
- A failed sign-in shows the step (1 = get code, 2 = get token), the HTTP status and Trakt's `error` / `error_description`. The Client ID, device code and tokens are never shown or logged.
- Polling now stops on any unexpected 4xx answer instead of waiting silently until the code expires. 5xx answers are retried at the next interval.
- The build only trims leading and trailing whitespace and newlines from `TRAKT_CLIENT_ID` and `TRAKT_REDIRECT_URI`. Every other character is kept, including `-` and `_`. (Before, the Client ID lost every character that wasn't a letter or digit, which broke IDs containing `-` or `_` and caused the HTTP 401.)
- CI runs `tools/trakt_ci_check.sh`: it prints the Client ID's length, its first and last 4 characters and the HTTP status Trakt returns for a device code request. Never the full value.

## Fixed: Trakt sign-in page (1.9.3-d5)

- Pressing "Sign in to Trakt" no longer moves focus to the category list or switches the settings page. The sign-in button, "Cancel" and "Signed in as …" are one row that stays in place, so focus stays on it.
- The code appears right on Settings → Continue Watching: "Getting a code from Trakt…", then a QR code, the address and the 8-character code in large text, with focus on "Cancel".
- The QR code is made on the TV with ZXing (`com.google.zxing:core`, already used for the About QR). Black on white with a 4-module white border, 210 dp. It encodes `verification_url`. If Trakt's reply also has `verification_url_complete` or `verification_uri_complete` (https, same host, containing the code), the QR uses that instead.
- After sign-in the page stays open and shows "Signed in as <username>". The username comes from `GET /users/settings` on `https://api.trakt.tv` and is kept in app-private storage (not encrypted; it is not a secret). Signed-in users from 1.9.3-d4 get it loaded at app start. OK on that row updates Continue Watching now.
- A failed sign-in shows the whole error text above the button instead of one cut-off line.
- No new network hosts.

## Renamed to Frost-flix

- The visible name is now "Frost-flix": app label, launcher name, accessibility service and its description, the empty-carousel title, Settings and About texts.
- About still credits Glassio by mohammadmrh12 and links to the original repository. So do README.md and CREDITS.md.
- Releases are titled "Frost-flix v…" and the APK is `frost-flix.apk` (was `glassio-d.apk`). The CI artifact is `frost-flix-unsigned`.
- Unchanged on purpose: the `applicationId` `tv.darren.glassio`, the Kotlin package `com.glasslauncher.tv`, the Keystore alias of the Trakt tokens, and the signing key file and alias. The update installs over the existing app and keeps its settings and Trakt sign-in.

## Not changed

- The accessibility service for the Home button, icon packs, add-on catalog carousel, weather, the poster screensaver, glass, focus, carousel and dock behaviour.

## Performance and memory (1.9.3-d6)

- **Background:** catalog refresh, weather and Continue Watching wait while no Frost-flix screen is started (from ON_STOP until ON_START), so nothing runs while NuvioTV or another app is in front. The carousel's TMDB lookups and picture preloading also stop then (slide and gallery timers already stopped).
- **Memory released in the background:** when the last Frost-flix screen stops, and in `onTrimMemory` (UI hidden, or the TV running low), the baked backdrops, glass copies and Coil's decoded pictures are dropped. The home screen also lets go of the pictures it shows and loads them again on return (from the disk cache).
- **Image caches:** Coil's memory cache is 15% of the app's memory class (was 20–30% by mode), the disk cache is 256 MB (was 768 MB).
- **Baked backdrops:** never larger than 1920×1080, whatever size the screen reports. At most 2 are kept in "performance" mode (4 otherwise). A bake is no longer thrown away when you move on before it finishes.
- **Preloading:** keyed on the slides it prepares, not on the number of TMDB results, so it no longer restarts (and bakes again) every time any title gets its TMDB details.
- **Auto mode:** picks "balanced" for 3–4 GB boxes again, also below Android 13 (e.g. Shield TV Pro on Android 11). Boxes under 2.6 GB (e.g. the 2 GB Shield TV) stay on "performance". Glass refraction still needs Android 13.
- **Off the main thread:** reading the stored Trakt sign-in (Keystore decrypt) at app start, the QR codes in Settings, and importing icons from the icon folder. Settings → Continue Watching shows "Checking the sign-in…" for that moment.
- **Settings:** closing Settings reloads the carousel only when the selected catalogs, "Titles per catalog", "Total titles" or "Shuffle" changed.
- **Reply size:** API replies (add-ons, TMDB, Trakt, weather) are read up to 5 MB. A larger reply fails with "Reply is larger than 5 MB" (the cached copy is used if there is one).

## Settings and Trakt (1.9.3-d6)

- **Auto no longer overwrites your choices:** changing any value a performance mode sets (Glass detail, Picture quality, Prepare ahead, Slide transition / Transition speed, Glass refraction, Backdrop gallery, Pictures per title) switches Mode to Custom, on whichever page you change it. Auto is still applied at app start, but only while Mode is Auto.
- **Token refresh timing:** the access token is refreshed when less than half its lifetime is left, at most a day early (was always a day early, which refreshed on every request with 24-hour tokens). Tokens stored before 1.9.3-d6 are treated as 24-hour tokens until their first refresh.
- **Sign-out and expiry:** Continue Watching is cleared on "Sign out of Trakt" and when Trakt answers `invalid_grant`. Clearing waits for an update that is still running, and an update that finishes after sign-out no longer brings the list back.
- **Failed updates:** retried after 1, 2, 5 and 15 minutes, then every 30 minutes. The page shows the last error ("Couldn't update at 21:04: HTTP 503 · … · next try in 5 min") until an update works. OK on the Trakt row still updates at once and shows the result.
- **Force Trakt refresh now (hidden):** hold OK on "Signed in as …". Refreshes the token at once and shows "Refresh OK · new token valid until …" or the error (HTTP status and Trakt's `error` / `error_description`). The refresh sends `client_id`, `refresh_token`, `grant_type` and `redirect_uri`, no client secret, as Trakt's openapi.json describes. No token is ever shown.
- **Cancel during sign-in:** a reply that arrives after Cancel is ignored; its tokens are never saved.

## Home button and small fixes (1.9.3-d6)

- **Home button service:** asks Android for key events only while "Home button opens Frost-flix" is on (requested at runtime, no longer in the XML). Before, with the service switched on, every key press of the TV went through Frost-flix first, even with the option off. While it is on, the service checks one thing per key (is it HOME?) and passes every other key straight through. Two Home presses less than 400 ms apart count as one (was 1 s). If Frost-flix can't be brought to the front, the service logs it instead of crashing.
- **"Choose default home app":** hidden on Android TV / Google TV devices (Shield included). On the Shield the system picker opened, but the remote's keys kept moving focus in Frost-flix's settings behind it. In its place, System explains the accessibility-service route. The row stays on non-TV devices.
- **Removed:** the `WAKE_LOCK` permission (nothing used it), the unused pinned-image code in `GlassCache` and the carousel, the `PageGlass` test switch, the unused `http` parameter of `NuvioLaunch.open`, and `tools/patch_193.py`, `patch_194.py` and `patch_195.py` (one-off upstream edit scripts).
- **Weather:** off by default, with no default city (was Baghdad). Nothing is fetched until you choose a city and switch weather on. A city you already set stays.
- **User-Agent:** `Frost-flix/<version>` for every request (was `GlassLauncher/1.0` for add-ons, TMDB, weather and icon links).
- **https only, and the messages say so:** icon links, video links and add-on links must start with `https://` (add-on links may also start with `stremio://`). Before, the text said `http://` was fine, and the request then failed on the cleartext block.
- **Glass refraction:** both rows are hidden below Android 13, where refraction can't work.
- **Logs:** a failed add-on manifest logs only the host and the exception's class name. A failed "Add add-on link" shows only the class name too.

## Build workflow (1.9.3-d6)

- `actions/checkout`, `actions/setup-java` and `gradle/actions/setup-gradle` are pinned to full commit SHAs (v4.4.0, v4.9.1 and v4.4.3: the commits `@v4` pointed to), with the version in a comment next to each.
- The release job stops with an error if the tag is not `v` + `versionName` from `app/build.gradle.kts`.
- Re-running the release job is safe: if the release already exists, its `frost-flix.apk` is replaced instead of failing.
- CHANGES.md: the "Removed: adb client" section now says that the Trakt-based `ContinueWatching.kt` and three of its settings came back in 1.9.3-d3.

## Background memory (1.9.3-d7)

- **The home screen exists only while it is started.** On ON_STOP, MainActivity removes its screen from the window. That disposes the whole composition: remembered pictures, the slide's backdrop and glass, dock icons, dialogs and the render nodes that pointed at them. On ON_START it builds the screen again. The carousel comes back on the same slide; its pictures reload from the disk cache. Home pressed in another app still starts on the first slide. Settings, when it was open, comes back on its first page.
- **No bitmaps in static fields any more.** The glass copies, baked backdrops and their half-size copies moved from the singletons `GlassCache`, `BakeCache` and `GlassHalf` into `ImageCaches`, one instance per started MainActivity, dropped on ON_STOP. The only app-scope image caches left are Coil's memory cache and the dock icons. Both are emptied when Frost-flix goes to the background and in `onTrimMemory` (UI hidden).
- **Dock icons:** their cache now counts both copies of an icon (a transparent icon also has a darkened copy), is capped at 48 MB (was 64 MB, counting only one copy), and is emptied in the background. The icons are decoded again off the main thread on return.
- **Freed memory is really freed:** bitmap pixels are only freed when the garbage collector collects their Java object, and an idle app in the background may not collect for a long time. About 1.5 s after going to the background, Frost-flix now runs a GC (`Runtime.gc()`, finalization, `Runtime.gc()`).
- **Hidden Memory line:** Settings → About, hold OK on the version. Shows the native heap in use (`Debug.getNativeHeapAllocatedSize`) and its total size, Java heap used, total PSS, the bitmaps Frost-flix tracks (count and MB, by kind: backdrop, glass, icons, qr) and Coil's memory cache. A second line shows the same numbers as measured about 5 s after Frost-flix last went to the background. Refreshed every 3 s while shown. Tracking uses weak references, so it never keeps a bitmap alive.

## Icons and native memory (1.9.3-d8)

- **Icons decoded at their drawn size:** dock, folder and Settings icons were drawn into bitmaps 640 px wide (banners) or 256 px (icons), ~0.9 MB each. They are now made at the size a focused dock tile draws them: the tile's size in pixels (from the screen width and "Dock size") × 1.14, the largest focus scale. A wide opaque banner fills the tile (as it is cropped on screen); every other icon fits inside the tile's padding. At 1080p and full dock size that is about 347×195 for a banner. Large app pictures are drawn at a power-of-two multiple and halved down, so lettering stays sharp. Custom icon files are decoded with `inSampleSize`.
- **RGB_565 for opaque icons:** an icon with no see-through pixel is kept as RGB_565 (dithered; half the memory). Icons with transparency stay ARGB_8888.
- **Darkened copy only when needed:** a transparent icon's darkened copy (for light plates) is kept only if darkening changed a pixel; otherwise both looks use the same bitmap.
- **Icon cache:** capped at 24 MB (was 48 MB), counted by the bitmaps' real size.
- **Freed native memory given back (removed again in 1.9.3-d9):** about 2 s after going to the background, after the GC, Frost-flix calls `mallopt(M_PURGE)` (Android 9+) through a tiny JNI library, `libheappurge` (one C function, `app/src/main/cpp`). The allocator then returns the pages of freed memory to the system. The build now uses the NDK and CMake that AGP picks.
- **Memory line:** the background line now ends with the purge result, e.g. "M_PURGE (done, Scudo): PSS … → … MB, heap … → … MB". "done" means the allocator accepted it; the allocator name is read from the process's memory map names.
- **Baked backdrops stay software bitmaps (not hardware):** the half-size copy the clear glass samples is made from the baked backdrop on the CPU, which a hardware bitmap doesn't allow without a GPU read-back. On the Shield the GPU uses the same RAM, so a hardware bitmap would mostly move the bytes from the native heap to "Graphics" in PSS. And in the background no backdrop exists any more (since 1.9.3-d7), so it would not lower that number.

## GPU memory in the background (1.9.3-d9)

- **Finding:** the d8 diff adds no GPU allocation. No HardwareRenderer, RenderNode, ImageReader, Surface, Picture, hardware Canvas, `Bitmap.Config.HARDWARE`, ImageDecoder, offscreen layer, RenderEffect or AGSL shader was added; the icon scaling was already done on the CPU. The "Other mtrack" memory is the render thread's GPU cache (textures of pictures drawn while Frost-flix was in front). In the Android 11 source, a stopped window keeps the textures it used in the last 30 s, and TRIM_MEMORY_UI_HIDDEN only cuts the cache to half its limit. The limit is 48 bytes per pixel of the display mode, so that half is about 199 MB at 4K. Nothing purges it later while no frame is drawn.
- **GPU cache released in the background:** 35 s after Frost-flix goes to the background, it creates an Android `HardwareRenderer` that never draws and destroys it at once. Destroying a renderer purges every cached GPU resource not used in the last 30 s (Android 10+; `HardwareRenderer` is public API). The textures are uploaded again when the home screen is drawn again.
- **No hardware bitmaps:** Coil's image loader now decodes every picture into a software bitmap (`allowHardware(false)` for all requests, was only for 1920 px backdrops). Logos and posters drawn by `AsyncImage` were hardware bitmaps (GPU buffers) before.
- **Icons scaled on the CPU only:** the halving steps draw into new software ARGB_8888 bitmaps with a filtering Paint (same result as `createScaledBitmap` with filtering). A drawable holding a hardware bitmap is copied to software before it is drawn. Icon sizes, RGB_565 and sharpness are unchanged.
- **M_PURGE removed:** it freed 0.1 MB on the Shield. `libheappurge`, `NativeHeap.kt`, `app/src/main/cpp` and the CMake/NDK build are gone; the build no longer needs the NDK.
- **Memory line:** the background line now also shows "GPU: graphics … MB, private other … MB, total PSS … MB" from `Debug.getMemoryInfo` (the same summary as dumpsys meminfo; "private other" includes "Other mtrack"; this total PSS includes GPU memory, the plain "PSS" figure does not). About 36 s after leaving it adds the same numbers after the GPU cache release, or "GPU cache release: not available".
