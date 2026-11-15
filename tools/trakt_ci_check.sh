#!/usr/bin/env bash
# CI check of the TRAKT_CLIENT_ID secret. Prints only whether it is set or missing, its length and the HTTP status
# auth.trakt.tv returns for a device code request with it. Never prints any characters of the secret or Trakt's reply.
# Trims the value the same way app/build.gradle.kts does: leading/trailing whitespace and newlines only.
# Never fails the build.
set -u
id="${TRAKT_CLIENT_ID-}"
id="${id#"${id%%[![:space:]]*}"}"
id="${id%"${id##*[![:space:]]}"}"
if [ -z "$id" ]; then
  echo "TRAKT_CLIENT_ID: missing"
  exit 0
fi
code=$(jq -cn --arg c "$id" '{client_id: $c}' | curl -sS --max-time 20 -o /dev/null -w '%{http_code}' -X POST https://auth.trakt.tv/oauth/device/code \
  -H 'Content-Type: application/json' -H 'trakt-api-version: 2' -H "trakt-api-key: $id" --data-binary @-) || code=000
echo "TRAKT_CLIENT_ID: set (length ${#id}), Trakt device code HTTP $code"
[ "$code" = 200 ] || echo "::warning title=Trakt device code::HTTP $code"
exit 0
