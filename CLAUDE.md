# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## How to work
- Before starting new work, switch to main and pull the latest changes.
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

## Project
Frost-flix is a hardened fork of the Android TV launcher Glassio v1.9.3 (GPL-3.0). There are no tests. Docs:
- `CHANGES.md`: every change from upstream, one section per version. Add an entry for every user-visible, permission, network or build change, and say what was not verified on a TV.
- `HANDOFF.md`: open checks on the device. Read it before starting work.
- `SETUP.md` (signing, secrets, install) and `USER-GUIDE.md` (end users). Keep them in step with behaviour changes.

## CI and releases
- Workflow `.github/workflows/build.yml`. A push to `main` or `claude/**` builds an unsigned `assembleRelease` only to check that it compiles. A tag `v*` builds a signed APK and publishes the release `frost-flix.apk`.
- To release: raise `versionCode` (it must go up) and `versionName` (`1.9.3-dN`) in `app/build.gradle.kts`, update README's version line and CHANGES.md, then tag exactly `v` + versionName. The release job fails if the tag and the versionName differ.
- Actions are pinned to commit SHAs with the version in a comment. Keep it that way.
- The build reads secrets from environment variables only: `TRAKT_CLIENT_ID`, `TRAKT_REDIRECT_URI` and `SIGNING_*`. Never write their values into the repo.

## Do not change
- `applicationId` `tv.darren.glassio`, the Kotlin package/namespace `com.glasslauncher.tv`, the signing key alias `glassio-d` and the Keystore alias of the Trakt tokens. Changing them breaks in-place updates or the Trakt sign-in.
- Glassio / mohammadmrh12 credit in About, README and CREDITS.

## Hardening rules
- Never reintroduce what CHANGES.md lists as removed: adb/dadb, the self-updater or silent installs, `REQUEST_INSTALL_PACKAGES`, `WRITE_SECURE_SETTINGS`, settings through intent extras, the LAN phone server, the webhook, startup file imports (`seed.json`), Apple aerials or icons. Settings are entered on the TV only; Backup import happens only after the user picks a file and confirms.
- Cleartext HTTP is blocked except `127.0.0.1` ("Connect to Nuvio"). User links must be `https://`. Record any new network host in CHANGES.md.
- Trakt: the Client ID only, never a secret. `/oauth/*` goes to `auth.trakt.tv`, data to `api.trakt.tv`. Never log or show tokens, the Client ID or device codes. Sign out only on a token-endpoint 401 or `invalid_grant`, never on network errors.
- Backup never exports the Trakt sign-in or the TMDB token. A new setting is only backed up if it is added to the allowlist in `data/Backup.kt`.

## Gotchas
- Not a home app: MainActivity has no `HOME` intent filter (removed in d17 on purpose). `HomeKeyService` (accessibility) opens it on Home. Android turns that service off after every sideloaded update.
- Background memory and network: images are software bitmaps (`allowHardware(false)`). Images are released when the app goes to the background, and refreshes are cancelled and run again in front (`Foreground.kt` `runInFront`). Background work must not hold bitmaps or open connections. Earlier attempts (d15 hardware bitmaps, d16 disposing the composition) were reverted.
- Trakt uses OkHttp with an authenticator. Don't cancel calls already running in the background, so a token refresh is never cut off halfway.
- Most recent changes have only been compiled, not run on a TV. Don't call behaviour verified unless a device test is recorded.
