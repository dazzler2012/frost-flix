#!/usr/bin/env bash
# CI check of the TRAKT_CLIENT_ID secret. Prints only its length and first/last 4 characters, never the full value,
# then asks auth.trakt.tv for a device code with it and prints the HTTP status and Trakt's error text.
# Cleans the value the same way app/build.gradle.kts does (keeps ASCII letters and digits only).
# Never fails the build: problems are reported as warnings.
set -u
raw="${TRAKT_CLIENT_ID-}"
id=$(printf '%s' "$raw" | LC_ALL=C tr -cd 'A-Za-z0-9')
if [ -z "$id" ]; then
  echo "TRAKT_CLIENT_ID: not set"
  exit 0
fi
removed=$(( ${#raw} - ${#id} ))
echo "TRAKT_CLIENT_ID: length ${#id}, first 4 '${id:0:4}', last 4 '${id: -4}', $removed character(s) removed (spaces, newlines, quotes, other)"
[ "${#id}" -eq 64 ] || echo "::warning title=Trakt Client ID length::Expected 64 characters, got ${#id}."
out=$(mktemp)
code=$(curl -sS --max-time 20 -o "$out" -w '%{http_code}' -X POST https://auth.trakt.tv/oauth/device/code \
  -H 'Content-Type: application/json' -H 'trakt-api-version: 2' -H "trakt-api-key: $id" \
  -d "{\"client_id\":\"$id\"}") || code=000
if [ "$code" = 200 ]; then
  echo "Trakt device code request: HTTP 200, Trakt accepts this Client ID"
else
  err=$(jq -r '[.error // empty, .error_description // empty] | join(": ")' "$out" 2>/dev/null)
  echo "::warning title=Trakt rejected the Client ID::POST auth.trakt.tv/oauth/device/code returned HTTP $code ${err:-(no JSON error text)}"
fi
rm -f "$out"
exit 0
