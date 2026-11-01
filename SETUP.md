# Setup: signing key, releases, installing on the Shield

SETUP.md is for building and releasing Frost-flix. For using the app on your TV, see [USER-GUIDE.md](USER-GUIDE.md).

## 1. Create the signing key (once, on Windows)

You need `keytool`, which comes with any Java JDK (for example Eclipse Temurin 17).

Open PowerShell in a folder that is **not** inside this repository, then run:

```powershell
keytool -genkeypair -v -keystore glassio-d.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias glassio-d
```

- It asks for a password, then your name and similar details (any values are fine).
- With the PKCS12 format the key password is the same as the keystore password. Use that one password for both secrets below.
- The file name `glassio-d.jks` and the alias `glassio-d` are left from the old name. Keep them: the release workflow and your existing secrets use them, and the APK must keep the same signing key to install over the app you already have.
- Back up `glassio-d.jks` and the password somewhere safe. If you lose them you can't ship updates that install over the existing app; you would have to uninstall it first.
- Never commit the `.jks` file. `.gitignore` blocks `*.jks` and `*.keystore`.

Convert the keystore to base64 and copy it to the clipboard:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$PWD\glassio-d.jks")) | Set-Clipboard
```

## 2. Add the four secrets on GitHub

On GitHub, open the repository, then **Settings → Secrets and variables → Actions → New repository secret**. Add:

| Name | Value |
|---|---|
| `KEYSTORE_BASE64` | the base64 text from the clipboard |
| `KEYSTORE_PASSWORD` | the keystore password |
| `KEY_ALIAS` | `glassio-d` |
| `KEY_PASSWORD` | the same password again |

If any of them is missing, the release job stops at its first step and names the missing secret.

### Trakt client ID (for Continue Watching)

Add one more secret:

| Name | Value |
|---|---|
| `TRAKT_CLIENT_ID` | the **Client ID** of your Trakt app |

- Get it from your app in the Trakt developer portal. Only the Client ID is needed. Frost-flix never uses a Client Secret.
- Frost-flix sends a redirect URI when it renews the sign-in. The default is `http://localhost`. The redirect URI registered in your Trakt app must match it **exactly** (same scheme, no trailing slash, no port). Otherwise renewing fails and you have to sign in again every 7 days.
- To use a different redirect URI, add a repository secret or variable named `TRAKT_REDIRECT_URI` with the exact value. No code change is needed.
- The ID is put into the APK at build time. It is never stored in this repository.
- Each CI build runs "Check Trakt client ID". It prints the length and the first and last 4 characters of the secret (never the full value) and the HTTP status Trakt returns for a device code request (200 means Trakt accepts it). Compare those with the Client ID in your Trakt app.
- If the secret is missing, the build still works. The app then shows "Trakt not configured" on the Continue Watching page.
- For a local build, set the environment variable `TRAKT_CLIENT_ID` (and `TRAKT_REDIRECT_URI` if it isn't `http://localhost`) before running Gradle. Don't write it into any file in the repo.

## 3. Make a release

Every push to `main` builds an unsigned APK only to check that the build still works. It isn't installable.

To publish a signed APK, push a tag that starts with `v`:

```powershell
git tag v1.9.3-d1
git push origin v1.9.3-d1
```

The **Build** workflow (Actions tab) then builds and signs the APK and creates a GitHub release named "Frost-flix v…" with `frost-flix.apk` attached.

For later versions, raise `versionCode` and `versionName` in `app/build.gradle.kts` first. Android refuses an update whose `versionCode` isn't higher than the one installed.

## 4. Install on the NVIDIA Shield with Downloader

1. Install **Downloader** (by AFTVnews) from the Play Store on the Shield.
2. Allow Downloader to install apps. On Android TV 11 this is usually under **Settings → Device Preferences → Security & restrictions → Unknown sources**. The exact menu names may differ on your firmware.
3. In Downloader, type this URL. It always points to the newest release:

   `https://github.com/dazzler2012/frost-flix/releases/latest/download/frost-flix.apk`

   Releases made before the rename (v1.9.3-d1 and v1.9.3-d2) only have `glassio-d.apk`. The new link works once a release with `frost-flix.apk` exists.

4. Choose **Install** when Downloader asks. Then delete the downloaded file when Downloader offers to.

To update later, repeat step 3 after publishing a new tag.

## 5. First-run settings on the TV

- **Make it the home app:** Settings → System → Choose default home app.
- **Home button:** Settings → System → turn on "Home button opens Frost-flix", then switch on the Frost-flix accessibility service in the Android TV accessibility settings. Android turns that service off after every update of a sideloaded app, so turn it on again after updating. When it is off, the home screen shows "Home button is off." with a "Turn it back on" button that opens the accessibility settings.
- **Weather:** Settings → Clock & glass → Weather → Off.
- **Continue Watching:** Settings → Continue Watching → Sign in to Trakt. Open the address shown on your phone and type the code. The list comes from what NuvioTV scrobbles to Trakt, so turn on Trakt in NuvioTV too.
- **Watchlist row:** with Trakt signed in, your Trakt watchlist appears as one row under the apps (go down into the dock). Switch it off under Settings → Continue Watching → Watchlist row.
- **Top Shelf:** the picture above the dock follows the selected app. Settings → Dock & icons → Top Shelf. "Top Shelf: use apps' own rows" (off by default) asks Android for the "TV listings" permission when you switch it on; "Check apps' own rows" then shows which apps give pictures.
- **Backup:** Settings → Backup → Export settings writes `Download/Frost-flix/frost-flix-settings-<date>.json`. Add-on links are only included when you tick the box. Import settings shows every change and applies nothing until you confirm.
- **Catalogs:** Settings → Catalogs → Add add-on link. Type the manifest URL with the remote. Only `https://` links work, because cleartext HTTP is blocked (except 127.0.0.1, which Connect to Nuvio uses).

This app has its own package name (`tv.darren.glassio`), so it installs alongside the original Glassio and never replaces it.
