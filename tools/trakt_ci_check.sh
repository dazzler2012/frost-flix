#!/usr/bin/env bash
# CI check of the TRAKT_CLIENT_ID secret. Prints only its length, first and last 4 characters and the HTTP status
# auth.trakt.tv returns for a device code request with it. Never prints the full value or Trakt's reply.
# Trims the value the same way app/build.gradle.kts does: leading/trailing whitespace and newlines only.
# Never fails the build.
set -u
id="${TRAKT_CLIENT_ID-}"
id="${id#"${id%%[![:space:]]*}"}"
id="${id%"${id##*[![:space:]]}"}"
if [ -z "$id" ]; then
  echo "TRAKT_CLIENT_ID: not set"
  exit 0
fi
code=$(jq -cn --arg c "$id" '{client_id: $c}' | curl -sS --max-time 20 -o /dev/null -w '%{http_code}' -X POST https://auth.trakt.tv/oauth/device/code \
  -H 'Content-Type: application/json' -H 'trakt-api-version: 2' -H "trakt-api-key: $id" --data-binary @-) || code=000
echo "TRAKT_CLIENT_ID: length ${#id}, first 4 '${id:0:4}', last 4 '${id: -4}', Trakt device code HTTP $code"
[ "$code" = 200 ] || echo "::warning title=Trakt device code::HTTP $code"
exit 0
