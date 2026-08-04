#!/usr/bin/env bash
# 反向断言循环（#34 ticket）——防止披露约束过度纠正。
#
# 场景：用户明确问价（"宝马X3 M多少钱"），回复必须包含价格数字。
# 与 diag-x3-loop.sh 互为镜像：那边锁"没问价不报价"，这边锁"问价必须报价"。
#
# VERDICT:
#   GREEN (exit 0) = reply contains price figures -> disclosure intact
#   RED   (exit 1) = price question got no price -> over-correction bug
#   LOOP-ERROR (exit 2) = harness failure (app down, bad HTTP), not a verdict
#
# Each run uses a fresh userId so chat memory never contaminates the repro.
set -u
PORT="${PORT:-8080}"
RUN_ID="price-$(date +%s)-$$"
BODY_FILE="$(mktemp)"
RESP_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$RESP_FILE"' EXIT

printf '{"message":"宝马X3 M多少钱","userId":"%s"}' "$RUN_ID" > "$BODY_FILE"

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

if grep -qE '指导价|全款|金融|[0-9]+(\.[0-9]+)?万' "$RESP_FILE"; then
  echo "=== VERDICT: GREEN — price question answered with numbers ==="
  exit 0
else
  echo "=== VERDICT: RED — price question got no price (over-correction) ==="
  exit 1
fi