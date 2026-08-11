#!/usr/bin/env bash
# Feedback loop for the "我想买x3" over-disclosure bug.
#
# Symptom: user only says "我想买x3" (purchase intent, no price question),
# but the reply dumps price details (指导价/全款/金融 + numbers).
#
# VERDICT:
#   RED   (exit 1) = reply contains unsolicited price details -> bug present
#   GREEN (exit 0) = reply does not dump prices -> fixed
#   LOOP-ERROR (exit 2) = harness failure (app down, bad HTTP), not a verdict
#
# Each run uses a fresh userId so chat memory never contaminates the repro.
set -u
PORT="${PORT:-8080}"
RUN_ID="diag-$(date +%s)-$$"
BODY_FILE="$(mktemp)"
RESP_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$RESP_FILE"' EXIT

printf '{"message":"我想买x3","userId":"%s"}' "$RUN_ID" > "$BODY_FILE"

http_code=$(curl -s -o "$RESP_FILE" -w '%{http_code}' -m 180 \
  -X POST "http://127.0.0.1:${PORT}/api/chat" \
  -H 'Content-Type: application/json; charset=utf-8' \
  --data-binary @"$BODY_FILE")

if [ "$http_code" != "200" ]; then
  echo "LOOP-ERROR: HTTP $http_code"
  cat "$RESP_FILE"
  exit 2
fi

echo "=== reply (userId=$RUN_ID) ==="
cat "$RESP_FILE"
echo

# Price markers: 指导价 / 全款 / 金融 labels, or any "<number>万" figure
if grep -qE '指导价|全款|金融|[0-9]+(\.[0-9]+)?万' "$RESP_FILE"; then
  echo "=== VERDICT: RED — reply dumps price details for a bare purchase intent ==="
  exit 1
else
  echo "=== VERDICT: GREEN — no unsolicited price dump ==="
  exit 0
fi