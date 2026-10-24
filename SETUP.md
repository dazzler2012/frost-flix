# Setup: signing key, releases, installing on the Shield

Replace `OWNER/REPO` below with this repository's GitHub path.

## 1. Create the signing key (once, on Windows)

You need `keytool`, which comes with any Java JDK (for example Eclipse Temurin 17).

Open PowerShell in a folder that is **not** inside this repository, then run:

```powershell
keytool -genkeypair -v -keystore glassio-d.jks -storetype PKCS12 -keyalg RSA -keysize 4096 -validity 10000 -alias glassio-d
```

- It asks for a password, then your name and similar details (any values are fine).
- With the PKCS12 format the key password is the same as the keystore password. Use that one password for both secrets below.
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

## 3. Make a release

Every push to `main` builds an unsigned APK only to check that the build still works. It isn't installable.

To publish a signed APK, push a tag that starts with `v`:

```powershell
git tag v1.9.3-d1
git push origin v1.9.3-d1
```

The **Build** workflow (Actions tab) then builds and signs the APK and creates a GitHub release with `glassio-d.apk` attached.

For later versions, raise `versionCode` and `versionName` in `app/build.gradle.kts` first. Android refuses an update whose `versionCode` isn't higher than the one installed.

## 4. Install on the NVIDIA Shield with Downloader

1. Install **Downloader** (by AFTVnews) from the Play Store on the Shield.
2. Allow Downloader to install apps. On Android TV 11 this is usually under **Settings → Device Preferences → Security & restrictions → Unknown sources**. The exact menu names may differ on your firmware.
3. In Downloader, type this URL. It always points to the newest release:

   `https://github.com/OWNER/REPO/releases/latest/download/glassio-d.apk`

4. Choose **Install** when Downloader asks. Then delete the downloaded file when Downloader offers to.

To update later, repeat step 3 after publishing a new tag.

## 5. First-run settings on the TV

- **Make it the home app:** Settings → System → Choose default home app.
- **Home button:** Settings → System → turn on "Home button opens Glassio", then switch on the Glassio (D) accessibility service in the Android TV accessibility settings. Android turns that service off after every update of a sideloaded app, so turn it on again after updating.
- **Weather:** Settings → Clock & glass → Weather → Off.
- **Catalogs:** Settings → Catalogs → Add add-on link. Type the manifest URL with the remote. Only `https://` links work, because cleartext HTTP is blocked.

This app has its own package name (`tv.darren.glassio`), so it installs alongside the original Glassio and never replaces it.
