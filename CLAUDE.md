# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## How to work
- For any change touching more than one file, propose a plan and wait for approval before editing.
- One feature or fix per task. Keep changes small.

## Build and verify
- Builds run on GitHub Actions after each push, not in this session. Do not install the Android SDK here.
- After pushing, check the GitHub Actions run for that commit and report pass/fail with the exact error.
- A task is not done until the GitHub build passes, or I confirm it's fine.

## End of every task
Give a short summary:
- Files changed
- Build result (pass/fail)
- Anything not done or uncertain

## Rules learned
<!-- Add one-line rules here when Claude makes a mistake -->

## What this is

Frost-flix is a hardened fork of the Android TV launcher [Glassio](https://github.com/mohammadmrh12/glassio) v1.9.3 (GPL-3.0), built with Kotlin + Jetpack Compose. Single Gradle module (`app`), no tests. `CHANGES.md` is the authoritative record of every deviation from upstream; update it whenever behaviour, settings, permissions, network hosts or identity change. `SETUP.md` covers signing, secrets, releases and installing on an NVIDIA Shield.

## Build details

CI runs these commands. Don't run them in this session (see "Build and verify").

```powershell
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # minified + resource-shrunk; unsigned unless SIGNING_* env vars are set
```

- JDK 17, Gradle 9 wrapper, AGP 8.13, Kotlin 2.4, compileSdk 36 / targetSdk 35 / minSdk 26.
- There is no test suite or lint setup; CI (`.github/workflows/build.yml`) only runs `assembleRelease`. Pushes to `main` build unsigned; tags `v*` build, sign from repo secrets and publish a GitHub release with `frost-flix.apk`.
- Build-time env vars (never write their values into the repo): `TRAKT_CLIENT_ID`, `TRAKT_REDIRECT_URI` (default `http://localhost`) → `BuildConfig`; `SIGNING_STORE_FILE`, `SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD` → release signing. Without a Trakt ID the app shows "Trakt not configured".
- Releasing: bump `versionCode` (must increase) and `versionName` (`1.9.3-dN` scheme) in `app/build.gradle.kts`, then push a `v…` tag.

## Identity — do not change

- `applicationId` is `tv.darren.glassio`; Kotlin package and `namespace` remain `com.glasslauncher.tv`. Changing either the applicationId, the signing key/alias (`glassio-d`), or the Android Keystore alias used for Trakt tokens breaks in-place updates or loses the user's Trakt sign-in.
- The visible name is "Frost-flix"; upstream credit to Glassio / mohammadmrh12 must stay in About, README and CREDITS.

## Hardening constraints (the point of this fork)

Do not reintroduce anything `CHANGES.md` lists as removed: adb/`dadb`, silent installs or the self-updater, `REQUEST_INSTALL_PACKAGES`, `WRITE_SECURE_SETTINGS`, settings via intent extras in `MainActivity`, the LAN "manage from phone" server, the Home Assistant webhook/`Http.post()`, `seed.json` import, Apple aerial feed or Apple TV icons. Settings are entered on the TV only.

- Network: `res/xml/network_security_config.xml` blocks cleartext except `127.0.0.1` (used only by `CatalogRepository.connectLocal()` for "Connect to Nuvio"). User-entered links must be `https://`. New network hosts should be called out in `CHANGES.md`.
- Trakt: device-code flow with Client ID only (no secret). All `/oauth/*` calls go to `auth.trakt.tv`, data to `api.trakt.tv`. Tokens are AES-GCM encrypted with an Android Keystore key in app-private storage and must never be logged or displayed; the same applies to the Client ID and device code (`tools/trakt_ci_check.sh` prints only length and first/last 4 chars).

## Architecture

- `GlassApp` (Application) is the composition root: it constructs and owns every singleton (`settings`, `http`, `icons`, `packs`, `apps`, `catalogs`, `weather`, `trakt`, `cw`), an app-wide `CoroutineScope`, the Coil image loader (sized by `settings.perfMode`), and global Compose state used as cross-screen signals (`homeRev`, `interaction`, `requestSettings`/`requestSettingsRev`). It also writes `last-crash.txt` for Settings → About. Composables reach everything via the `GlassApp` instance passed down from `MainActivity`.
- `MainActivity` is the HOME/LAUNCHER activity; it hosts `HomeScreen` and toggles between `Screen.Home` and `Screen.Settings`.
- `data/Settings.kt`: every option is a property over SharedPreferences (`"glass"`). Each getter reads a Compose `mutableIntStateOf` revision counter, so any composable reading a setting recomposes when any setter runs. Add new options here following the same `b/i/f/s` + `putX` pattern.
- `data/`: `CatalogRepository` (Stremio-style add-on manifests/catalogs feeding the hero carousel), `ContinueWatching` + `Trakt` (Trakt-backed Continue Watching slides), `Tmdb` (artwork fallback), `NuvioLaunch` (opens titles in NuvioTV → Nuvio Legacy → Nuvio Re-Shaped via `nuvio://` links; packages must also be in the manifest `<queries>`), `AppRepository`/`IconPacks`/`IconStore` (dock apps and icon packs), `Weather`, and `Http` (GET-only JSON helper with an on-disk cache that doubles as offline fallback).
- `ui/`: `HomeScreen.kt` (carousel, hero, dock, top bar — the largest file), `SettingsScreen.kt` (row types such as `Adjust` driven by D-pad key handling: OK enters edit mode, Left/Right change, OK/Back exits), `Glass.kt`/`GlassCache.kt` (the liquid-glass rendering and cached blurred backgrounds). UI is D-pad/remote-first; focus and key handling matter more than touch.
- `saver/`: `ScreensaverActivity` (in-app poster slideshow) and `GlassDreamService` (system screensaver, plays user-added https video links).
- `HomeKeyService`: accessibility service that makes the Home button open Frost-flix; Android disables it after each sideloaded update and it must be re-enabled manually.

## tools/

- `trakt_ci_check.sh`: run by CI to sanity-check the Trakt secret.
- `make_icon.py`: renders the icon/banner (needs numpy + Pillow).
- `patch_19x.py`, `framestats.py`: upstream development leftovers (string-replace source patchers already applied; a `dumpsys gfxinfo` summariser). Not part of the build.
