# Changes from upstream Glassio v1.9.3

Base: upstream tag `v1.9.3` (commit `8409fcf`, versionCode 25).

## Identity

- `applicationId` changed from `com.glasslauncher.tv` to `tv.darren.glassio`. The Kotlin package and `namespace` stay `com.glasslauncher.tv`.
- App label changed from "Glassio" to "Frost-flix" (it was "Glassio (D)" up to 1.9.3-d3).
- `versionName` changed to `1.9.3-d5`. `versionCode` is 29 (upstream 25).
- Release builds are no longer signed with the debug key. They are signed only when CI provides the keystore from secrets. Otherwise they are unsigned.

## Removed: adb client, key storage and everything that used it

- `data/LocalShell.kt` deleted: the adb-over-localhost client, adb key generation and key storage (`files/adb/adbkey`), and silent `pm install`.
- `dev.mobile:dadb` dependency and its ProGuard rules removed.
- `data/ContinueWatching.kt` deleted: reading Nuvio's log through adb. Also removed with it:
  - the "Continue Watching" settings page,
  - the Continue Watching slides, progress line, "jump to what I just watched" and pinned-image cache in the carousel,
  - the settings `continueMode`, `cwLead`, `continueJump`, `cwLegacy` and `cwProgress`.
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
