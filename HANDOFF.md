# Handoff

Last updated: 15 Nov 2026. `claude/reliability-docs` is at 1.9.3-d22 (versionCode 46).

## Home role & accessibility fallback (since 1.9.3-d18 / d19)

Since 1.9.3-d19 Frost-flix has the `HOME` intent filter again and can hold the system `HOME` role:
- Make Frost-flix the system Home app: `adb shell cmd role add-role-holder android.app.role.HOME tv.darren.glassio`
- Switch back to another launcher: run the same command with that launcher's package (for example `adb shell cmd role add-role-holder android.app.role.HOME com.google.android.tvlauncher`).
- The accessibility service (`HomeKeyService`) is now an optional fallback when you don't use ADB, with two modes under Settings → System: **Catch Home key** (default) and **Watch launcher** (lets double-press Home for recent apps work on the Shield).

## Open: 1.9.3-d5 Trakt sign-in page, not checked on a TV

1.9.3-d5 (commit `1d018b9`) was only compiled, never run on a device. Later versions changed the same files, so check this on the current build.

On Settings → Continue Watching, check:

1. Press "Sign in to Trakt". Focus stays on the page and lands on "Cancel". The page does not switch to another category.
2. The QR code, the address and the 8-character code show without scrolling or with the page scrolled to them.
3. The QR code scans from the sofa with a phone camera.
4. When sign-in finishes, the page stays open and shows "Signed in as <username>".

## Open questions about Trakt (unverified)

- Whether Trakt's device-code reply ever includes a complete URL with the code (`verification_url_complete` / `verification_uri_complete`). If it doesn't, the QR opens `https://trakt.tv/activate` and you type the code on your phone.
- Whether Trakt's activate page accepts the code in the URL. Frost-flix never builds such a URL itself.
- Whether `GET /users/settings` returns `user.username`. If it doesn't, the row says "Signed in to Trakt".

## Next step

Install the current build on the Shield and run the four checks above. Record the result in CHANGES.md under "Fixed: Trakt sign-in page (1.9.3-d5)" and delete this file when nothing is open.
