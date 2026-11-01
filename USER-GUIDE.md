# Frost-flix user guide

How to install, set up and use Frost-flix on an Android TV or Google TV device, with the remote.

- Building the app and making releases is covered in [SETUP.md](SETUP.md), not here.
- Menu names in **bold** are exactly what Frost-flix shows on screen.
- Anything marked *(unverified)* hasn't been checked in the code or in an official source, or hasn't been tested on a TV.

## Contents

1. [What Frost-flix is](#1-what-frost-flix-is)
2. [What you need](#2-what-you-need)
3. [Install and update](#3-install-and-update)
4. [Make it your Home screen](#4-make-it-your-home-screen)
5. [First setup, page by page](#5-first-setup-page-by-page)
6. [Trakt](#6-trakt)
7. [Backup and restore](#7-backup-and-restore)
8. [Privacy](#8-privacy)
9. [Troubleshooting](#9-troubleshooting)

---

## 1. What Frost-flix is

Frost-flix is a home screen (launcher) for Android TV with a frosted, liquid-glass look. It shows a large carousel of films and shows from your catalog add-ons, a dock with your apps, and optionally your Trakt Continue Watching and Watchlist.

What it isn't:

- **It is not a video player.** It plays nothing itself.
- **It opens titles in NuvioTV.** Pressing OK on a title hands it to NuvioTV (or Nuvio Legacy / Nuvio Re-Shaped if that is what you have), and you play it there.

---

## 2. What you need

### Device

- An Android TV or Google TV device with **Android 8 or newer**.
- Tested on an **NVIDIA Shield (Android 11)**.
- On **Android 13 and newer** (most current Google TV devices):
  - **Glass refraction** works there (the rim of the glass bends what is behind it). On older Android versions the glass is flat and the **Glass refraction** row is hidden.
  - Android may block the accessibility service of a sideloaded app until you allow it. If the Frost-flix switch is greyed out, open the Frost-flix app info and look for **Allow restricted settings**, then try again. *(Unverified on a TV: the menu name and where it is may differ.)*

### Apps

- **Downloader** by AFTVnews, from Google Play. It installs Frost-flix from a web link.
- **NuvioTV**. Its official source is the [NuvioMedia/NuvioTV repository on GitHub](https://github.com/NuvioMedia/NuvioTV), which offers an Android TV APK under Releases and also links a Google Play listing.
  - **Either build works.** Frost-flix looks for both the GitHub build (`com.nuvio.tv`) and the Google Play build (`com.nuvio.app`). *(The Google Play build is unverified on a TV.)*
- **An icon pack** (optional). Any Projectivy, Blueprint or Nova icon pack installed on the TV works. Settings has a **Find icon packs on Google Play** button.
  - Two examples, with the Downloader codes published in each pack's own README:
    - **Projectivy Icon Pack:** Downloader code `9257057` ([source](https://github.com/SicMundus86/ProjectivyIconPack)).
    - **Core Builds Icon Pack:** Downloader code `5270601` ([source](https://github.com/brevityA/CoreBuildsApps)).

### Accounts (both optional, both free)

- **Trakt** (trakt.tv):
  - Adds **Continue Watching** (films and episodes you are partway through, plus the next episode of shows you are watching).
  - Adds the **Watchlist row** under your apps.
  - Without it, the carousel only shows titles from your catalogs.
- **TMDB** (themoviedb.org), for a **read access token**:
  - Adds title logos, full-size pictures, the synopsis, the streaming platform, and several pictures per title (**Backdrop gallery**).
  - Without it, Frost-flix uses the pictures and text your add-ons provide.

---

## 3. Install and update

### First install

1. Install **Downloader** from Google Play.
2. Allow Downloader to install apps. On a Shield this is usually **Settings → Device Preferences → Security & restrictions → Unknown sources**, then turn on Downloader. *(Menu names may differ on your TV.)*
3. Open Downloader and type this address:

   `https://github.com/dazzler2012/frost-flix/releases/latest/download/frost-flix.apk`

4. Choose **Install** when asked.
5. Choose **Delete** when Downloader offers to remove the downloaded file.
6. Go back to **Unknown sources** and turn Downloader **off** again. Turn it on again only when you update.

### Updating

1. Turn **Unknown sources** on for Downloader.
2. Open the same address in Downloader and install. Your settings and Trakt sign-in are kept.
3. Turn **Unknown sources** off again.
4. **Turn the Frost-flix accessibility service back on.** Android switches it off every time a sideloaded app is updated.
   - If **Home button opens Frost-flix** is on, the home screen reminds you with **"Home button is off."** and a **Turn it back on** button.
   - Press **Turn it back on**, find **Frost-flix** and switch it on.

Frost-flix never updates itself. About shows **No self-updates**.

---

## 4. Make it your Home screen

On Android TV and Google TV there is no usable "default home app" choice, so the Home button is handled by an accessibility service.

### Turn it on

1. Open Frost-flix and go up to the settings button.
2. Open **System**.
3. Turn on **Home button opens Frost-flix**.
4. Choose **Turn on the accessibility service**.
5. In Android's accessibility settings, choose **Frost-flix** and switch it on. (On a Shield: **Settings → Device Preferences → Accessibility → Frost-flix**.)
6. Press Home. Frost-flix should appear.

What the service does:

- It only looks at the Home key while **Home button opens Frost-flix** is on. All other keys pass straight through.
- Pressing Home while Frost-flix is already showing takes you back to the first slide.

### Switch back to another launcher

- **Back to the stock home screen:** turn off **Home button opens Frost-flix** under **System**. You can also switch the Frost-flix accessibility service off.
- **Another launcher such as Projectivy:**
  1. Turn off **Home button opens Frost-flix** first, so the two apps don't fight over the Home button.
  2. Then turn on that launcher's own Home-button override. Projectivy has one. *(Unverified: its exact name and place in Projectivy's settings.)*

---

## 5. First setup, page by page

Open Settings from the button at the top of the home screen. The pages are listed on the left in this order.

How the controls work:

- **Switches:** press OK to turn them on or off.
- **Values with ‹ ›:** press OK, change with ◀ ▶, then press OK (or Back) when done.
- **Leaving a page:** press ◀ to go back to the page list, and Back to close Settings.

"Suggested for a Shield" values are suggestions, not tested results.

### Performance

- **Mode:**
  - **Auto** picks a mode from the TV's memory and graphics chip.
  - **Performance** is smoothest.
  - **Balanced** gives sharp pictures with smooth navigation.
  - **Quality** is sharpest.
  - Changing any row below switches Mode to **Custom**.
  - *Suggested for a Shield:* **Auto**. It picks **Balanced** on a Shield TV Pro (3 GB) and **Performance** on the 2 GB Shield TV.
- **Glass detail:** sharper glass uses more memory. Standard (480 px) is fine.
- **Picture quality:** **High (1280 px)** or **Maximum (original)**.
- **Prepare ahead:** how many slides are loaded before you reach them.
- **Slide transition:** fade length in milliseconds.
- **Glass refraction:** only shown on Android 13 and newer. 0 means flat glass.
- **Backdrop gallery:** several pictures per title. Needs the TMDB token.

### Carousel

- **Auto-play** and **Slide time** set whether, and how often, slides change on their own.
- **Title style:** Logo, Text or Hidden.
- **Size rows:** **Logo / title size**, **Rating, year, platform size**, **Genres size**, **Synopsis size** and **Synopsis lines** change how big the text is.
- **What is shown:** **Rating, year, platform**, **Genres line**, **Synopsis**, and **Synopsis only when the carousel is selected**.
- **Dots style:** Dots, Progress bar or Hidden.
- **Titles per catalog** and **Total titles** set how many titles load. 0 means every title, in an endless loop.
- **Shuffle all catalogs:** a new random mix each time the list loads.
- **Synopsis language:** English or Arabic. Needs the TMDB token.
- **Artwork:** which picture each title uses.
  - To change one title, hold OK on it in the carousel (or press Menu) to switch to its next picture.
  - **Reset pictures picked per title** undoes those choices.
- **Reload carousel now:** loads the catalogs again.

### Clock & glass

- **Clock:** **Show clock**, **24-hour clock**, **Show date** and **Clock position**.
- **Weather:** off by default.
  - To use it, choose what to show, then set **Weather city**.
  - **Fahrenheit** switches the unit.
- **Auto-hide clock and settings button:** hides them while you browse. **Shown at start for** sets how long they stay at first.
- **Glass look:** **Corner radius**, **Glass blur**, **Glass brightness** and (Android 13+) **Glass refraction**.
- **Colour on focus:** like tvOS. Icons rest as white (or grey) shapes, and the selected one shows its real colours on a plate.
  - **Selected icon plate** chooses Light or Dark.
  - Leave it off if many of your icons turn into white squares (see [Troubleshooting](#icons-look-like-white-squares)).
- **Bounce at the edges** and **Lift selected icon:** small motion effects.
- **Show phone apps too:** also lists apps without a TV launcher entry.

### Dock & icons

- **Icon pack:** pick an installed pack, or **None (original icons)**. Every app takes the pack's icon.
- **Dock size**, **Dock background**, **Settings background** and **Icon style** set the look.
- **Top Shelf** (on by default): when an app is selected in the dock, the picture above changes to match that app.
  - NuvioTV, Stremio, Kodi and Plex show your Continue Watching and Watchlist pictures.
  - Other apps show a soft gradient in their icon's colour, with their banner.
- **Top Shelf: use apps' own rows** (off by default): uses the rows apps publish for the Android TV home screen.
  - Turning it on makes Android ask for a permission (TV listings). Allow it.
  - If you refuse, the setting stays off.
  - Pictures from an app's own servers are skipped. Only pictures that need no new network host are used.
  - **Check apps' own rows** lists which apps give pictures.
- **Change one icon:**
  1. On the home screen, hold OK on the app.
  2. Choose **Change icon**.
  3. Pick from **Suggested**, an icon pack, **Photos on this TV**, **From a link** (an `https://` PNG), or **Original icon**.
- **Other options when you hold OK on an app:** **App info**, **Move** and **Hide**.
- **Reorder apps here:** OK picks an app up, ◀ ▶ moves it, and OK puts it down.
- **Many icons at once:**
  1. Copy files named `<package>.png` into the icon folder shown on this page.
  2. Choose **Import icons from the icon folder**.

### Catalogs

This page fills the carousel.

- **TMDB token:** paste your TMDB read access token. It shows **Set** when stored.
- **Connect to Nuvio:** copies the add-ons you already have in Nuvio.
  1. In Nuvio, open **Add-ons → Manage from phone** and leave it open.
  2. In Frost-flix, choose **Connect to Nuvio**.
  3. It says how many add-ons and catalogs it imported.
- **Add add-on link:** paste a catalog add-on's manifest link.
  - **Only `https://` links work** (or `stremio://`).
  - `http://` links are blocked.
- **Refresh catalogs**, **Select all catalogs** and **Select none** do what they say.
- **Your add-ons:** each one is listed as **Add-on · name**. Press OK to remove one.
- **Your catalogs:** each catalog has a switch. Turn on the ones you want in the carousel.

### Continue Watching

- **Show Continue Watching:**
  - **At the start** puts titles you are watching first.
  - **Mixed with catalogs** mixes them in.
  - **Off** hides them.
- **Slides at the start:** how many in-progress titles open the carousel.
- **Progress bar:** shows "Continue · S1 · E3 · 42%" under the title.
- **Watchlist row** (on by default): your Trakt watchlist as one row under the apps. Go down into the dock, past the last row of apps, to see it.
- **Sign in to Trakt:**
  1. Choose **Sign in to Trakt**.
  2. A QR code, an address and an 8-character code appear.
  3. Scan the QR code with your phone, or open the address and type the code.
  4. When it works, the row shows **Signed in as …**.
  - **Cancel** stops signing in.
  - The details are in [Trakt](#6-trakt).
- **Sign out of Trakt:** removes the sign-in and the lists from this TV.

### Nuvio

- **Open titles in:** **Automatic** (default), **NuvioTV**, **Nuvio Legacy** or **Nuvio Re-Shaped**.
  - **Automatic** tries NuvioTV first (GitHub build, then Google Play build), then Nuvio Legacy, then Nuvio Re-Shaped.
  - *Suggested:* **Automatic**.
- **TMDB token:** the same setting as on **Catalogs**.

### Screensaver

- **Recommended: Aerial Views,** a free system screensaver on Google Play.
  - **Install Aerial Views** opens it on Google Play.
  - Once it is installed, **Open Aerial Views settings** lets you set it up.
- **Start after:** starts the Frost-flix poster slideshow after you have been idle on the home screen. **Off** by default.
  - If your TV already has a system screensaver, leave this on **Off**.
- **Show clock:** shows the clock in that slideshow.
- **Back on the home screen starts it:** pressing Back on the home screen starts the slideshow.
- **Add video link:** direct `https://` .mp4 or .mov links. Only used when Frost-flix itself is the system screensaver.
- **Preview now:** shows the slideshow.
- **Use as system screensaver:** opens Android's screensaver settings.

### System

- **Home button opens Frost-flix** and **Turn on the accessibility service:** see [Make it your Home screen](#4-make-it-your-home-screen).

### Backup

- **Include add-on and video links**, **Export settings** and **Import settings:** see [Backup and restore](#7-backup-and-restore).

### About

- Shows the Frost-flix version, credits, and a QR code to this app's source code on GitHub.
- **Last crash** appears only after a crash. **Clear** removes it.
- **Hidden Memory and Frames lines:** hold OK on **Frost-flix 1.9.3-…** (the version row). Hold OK again to hide them.
  - **Memory now:** what Frost-flix uses right now. Refreshed every 3 seconds.
  - **Frames:** how smooth the home screen has been since it opened (how many frames were slow).
  - **Memory in the background:** what Frost-flix used about 5 seconds after another app came to the front.

---

## 6. Trakt

### How sign-in works

- Frost-flix uses Trakt's TV sign-in. You approve it on your phone or computer, so you never type a password on the TV.
- The code works for a limited time. The screen shows **"The code works until HH:MM"**.
- After that, choose **Sign in to Trakt** again.
- Once signed in, Frost-flix renews the sign-in by itself.
- If Trakt ends the sign-in, Frost-flix asks you to sign in again.

### NuvioTV must send what you watch to Trakt

- Continue Watching only knows what Trakt knows.
- **Turn on Trakt in NuvioTV too**, so NuvioTV records ("scrobbles") your progress to Trakt. *(Unverified: where that setting is in NuvioTV.)*
- Without it, Continue Watching stays empty.

### Edit your watchlist

1. On your phone or computer, open trakt.tv and sign in.
2. Add or remove films and shows on your watchlist.
3. Frost-flix shows the newest 30.

### Update now

- Frost-flix checks Trakt at most every 30 minutes while it is on screen. It never checks while another app is in front.
- **To update now:**
  - Settings → **Continue Watching** → press OK on **Signed in as …**, or
  - turn **Watchlist row** off and on again. Turning it on fetches the watchlist at once.

---

## 7. Backup and restore

### Export

1. Settings → **Backup**.
2. Leave **Include add-on and video links** off unless you need them (see the warning below).
3. Choose **Export settings**.
4. The file is saved as `Download/Frost-flix/frost-flix-settings-<date>.json`.
   - On Android 8 and 9 it goes to `Android/data/tv.darren.glassio/files/Frost-flix` instead.

What is in the file:

- Your layout, dock order, hidden apps and custom icons.
- Carousel, catalog, Continue Watching, Watchlist and Top Shelf settings.

What is never in the file:

- The Trakt sign-in.
- The TMDB token.

### The add-on links warning

- **Add-on links can contain private keys.** Anyone with the file could use them.
- That is why **Include add-on and video links** is off every time you open Settings.
- Exporting with links asks once more. You can choose **Export with links** or **Export without links**.

### Import

1. Settings → **Backup** → **Import settings**.
2. Choose a file from the list.
3. Frost-flix lists every change, for example "Dock size: 100% → 80%". Nothing changes yet.
4. Choose **Apply these changes**, or **Cancel**.

Nothing is ever imported on its own.

### Keep a copy off the TV

- After you uninstall and reinstall Frost-flix, Android may no longer show backup files made by the earlier install. *(Unverified on a TV.)*
- So copy the backup file to a PC or a USB stick after exporting.
- To restore, copy it back into the `Download/Frost-flix` folder on the TV. *(Unverified: whether a file copied in from outside always shows up in the list.)*

---

## 8. Privacy

### What is sent, and where

- **Trakt** (`api.trakt.tv`, `auth.trakt.tv`), only when you sign in:
  - the sign-in itself,
  - requests for your in-progress titles, your watchlist and your Trakt username.
- **TMDB** (`api.themoviedb.org`, `image.tmdb.org`), only when a TMDB token is set:
  - IMDb IDs or titles of the films and shows being shown, to fetch logos, pictures and text.
  - Some pictures for Continue Watching, the Watchlist and Top Shelf also load from `image.tmdb.org`.
- **Your catalog add-ons:** catalog and title-detail requests, plus their pictures.
- **Weather**, only when Weather is on and a city is set:
  - The city name goes to Open-Meteo (`geocoding-api.open-meteo.com`), and if not found there, to OpenStreetMap (`nominatim.openstreetmap.org`).
  - The city's coordinates go to Open-Meteo (`api.open-meteo.com`).
- **Links you type yourself:** icon links and video links.
- **Google Play:** opened only when you press one of the Google Play buttons.
- **On the TV only:** **Connect to Nuvio** talks to Nuvio on the TV itself (`127.0.0.1`). Nothing leaves the TV.
- Every request identifies itself as `Frost-flix/<version>`.

### What is never sent

- No analytics, no tracking and no crash reports. The last crash is only kept on the TV, under About.
- Your Trakt sign-in is only sent to Trakt, and your TMDB token only to TMDB.
- Frost-flix runs no server, reads no files from outside the app on its own, and doesn't update itself.

### How things are stored

- **Trakt tokens:** stored only in Frost-flix's private storage, encrypted with a key from the Android Keystore. They are never shown or logged.
- **TMDB token:** stored in Frost-flix's private storage but **not encrypted**. It is never put in a backup.
- **Only `https://` connections:** the only exception is Connect to Nuvio on the TV itself.

---

## 9. Troubleshooting

### Home goes to another launcher

1. Check Settings → **System** → **Home button opens Frost-flix** is on.
2. Check the Frost-flix accessibility service is on. It is switched off after every update.
3. If another launcher (such as Projectivy) also has its Home override on, turn that one off.

### "Home button is off."

- Android switched the accessibility service off, usually after an update.
- Press **Turn it back on**, find **Frost-flix**, and switch it on.
- The banner disappears once the service is on again.

### Trakt sign-in fails with HTTP 401

- Example message: "HTTP 401 · invalid_client … (Trakt doesn't know this build's Client ID; check the TRAKT_CLIENT_ID secret)".
- This isn't something you can fix on the TV. The app was built with a wrong or missing Trakt Client ID.
- The person who builds Frost-flix has to fix it (see [SETUP.md](SETUP.md)).
- **"Trakt not configured"** means the build has no Client ID at all.

### The Watchlist row isn't showing yet

- You must be signed in to Trakt.
- **Watchlist row** must be on, under Settings → **Continue Watching**.
- The row sits under the apps. Go down into the dock, past the last row of apps.
- The first fetch may still be running. Turn **Watchlist row** off and on again to fetch now.
- If your Trakt watchlist is empty, there is nothing to show.

### Pressing OK on a title opens its page, not playback

- This is expected. Frost-flix opens the title's page in NuvioTV, and you start playback there.
- If NuvioTV can't open the title directly, Frost-flix opens NuvioTV instead and says so.
- **"Nuvio is not installed"** means no supported Nuvio app was found. Install NuvioTV from its GitHub Releases or Google Play (see [Apps](#apps)).

### Icons look like white squares

- This happens with **Colour on focus** on.
- Frost-flix draws every icon that has any see-through part as a plain white shape while it isn't selected.
- Many icons (especially icon-pack icons) are a filled square with see-through corners. Those become white squares.
- Icons with no see-through part at all show in grey instead.
- **Fix:** turn **Colour on focus** off (Settings → **Clock & glass**), or use an icon pack with line-style (glyph) icons.

### It's slow for a moment after coming back from another app

- This is normal.
- To save memory, Frost-flix lets go of its screen and pictures while another app is in front.
- On return it builds the screen again and reloads pictures from its disk cache. The carousel comes back on the same slide.

### Reporting a problem

- Open an issue at [github.com/dazzler2012/frost-flix/issues](https://github.com/dazzler2012/frost-flix/issues).
- Say which TV and Android version you have, and which Frost-flix version is shown under About.
- **Don't paste** add-on links, tokens, codes, IP addresses or your Trakt username.
