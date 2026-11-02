# Handoff

Last updated: 2 Nov 2026. `main` is at 1.9.3-d17 (versionCode 41).

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
