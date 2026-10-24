# Changes from upstream Glassio v1.9.3

Base: upstream tag `v1.9.3` (commit `8409fcf`, versionCode 25).

## Identity

- `applicationId` changed from `com.glasslauncher.tv` to `tv.darren.glassio`. The Kotlin package and `namespace` stay `com.glasslauncher.tv`.
- App label changed from "Glassio" to "Glassio (D)".
- `versionName` changed to `1.9.3-d1`. `versionCode` stays 25.
- Release builds are no longer signed with the debug key. They are signed only when CI provides the keystore from secrets. Otherwise they are unsigned.

## Removed: adb client, key storage and everything that used it

- `data/LocalShell.kt` deleted: the adb-over-localhost client, adb key generation and key storage (`files/adb/adbkey`), and silent `pm install`.
- `dev.mobile:dadb` dependency and its ProGuard rules removed.
- `data/ContinueWatching.kt` deleted: reading Nuvio's log through adb. Also removed with it:
  - the "Continue Watching" settings page,
  - the Continue Watching slides, progress line, "jump to what I just watched" and pinned-image cache in the carousel,
  - the settings `continueMode`, `cwLead`, `continueJump`, `cwLegacy` and `cwProgress`.
- `saver/SystemDream.kt` deleted: starting the TV's own screensaver and changing the screen timeout through adb.
  - The screensaver type "TV screensaver" was removed. A stored value of `system` now plays the poster slideshow.
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
- The HOME / LAUNCHER / LEANBACK_LAUNCHER intent filters are unchanged. Pressing Home while Glassio is in front still returns to the first slide.

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

## Network

- `android:usesCleartextTraffic="false"`.
- Remaining `http://` URL in app code: `CatalogRepository.connectLocal()` calls `http://127.0.0.1:8080-8089/api/addons` ("Connect to Nuvio"). Cleartext is now off, so this feature is expected to fail. This wasn't tested on a device. Add-ons can still be added with "Add add-on link" (https only).
- Icon links and add-on links that start with `http://` will also fail. Use `https://`.

## Docs and build

- New: `CREDITS.md`, `CHANGES.md`, `SETUP.md`, `.github/workflows/build.yml`.
- `LICENSE` (GPL-3.0) was added from upstream commit `aac6937`. The `v1.9.3` tag itself has no LICENSE file.
- The upstream READMEs moved to `docs/upstream/` with a note on top and without the screenshot links. There is a new short `README.md`.
- `.gitignore` now also ignores `*.jks`, `*.keystore` and `keystore.properties`. `local.properties` was already ignored.

## Not changed

- The accessibility service for the Home button, icon packs, add-on catalog carousel, weather, aerial-video and poster screensavers, glass, focus, carousel and dock behaviour.
