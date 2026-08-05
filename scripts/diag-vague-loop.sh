#!/usr/bin/env bash
# 模糊路由重构（#43 ticket，#35 规格）红绿端到端验收脚本。
# 先例：diag-x3-loop.sh（RED/GREEN/LOOP-ERROR 三态判定）。
#
# 场景（每次运行全新 userId，防记忆污染）：
#   S1 "有x3吗"          别名命中+高置信 → 判清晰，不得误注入引导（不得反问城市）
#   S2 "推荐一款车"       零信号+无画像 → 应反问城市（禁止静默退回全局），且不冗长
#   S3 续答 "杭州"        城市抓取入库后 → 不得再问城市，应向推荐方向推进
#   S4 "我在杭州，推荐一款车" 画像有城市 → 给 1-2 款起点选项，不冗长
#   S5 画像落库验证        本轮 userId 的城市应进 customer_profile
#
# VERDICT:
#   RED   (exit 1) = 行为回归（引导未注入/误注入、重复问城市、画像未落库）
#   GREEN (exit 0) = 模糊场景行为符合预期
#   LOOP-ERROR (exit 2) = 环境问题（应用未启动、HTTP 异常、DB 不可探测），非行为判定
#
# 用法（先启动应用：docker compose 起 Chroma + API key 环境变量）：
#   PORT=8080 DB_FILE=company_inventory.db bash scripts/diag-vague-loop.sh
set -u
PORT="${PORT:-8080}"
DB_FILE="${DB_FILE:-company_inventory.db}"
BASE="http://127.0.0.1:${PORT}"
RUN_ID="vague-$(date +%s)-$$"
BODY_FILE="$(mktemp)"
RESP_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE" "$RESP_FILE"' EXIT

RED_COUNT=0

loop_error() {
  echo "=== VERDICT: LOOP-ERROR — $1（环境问题，非行为判定） ==="
  exit 2
}

# 发送一条消息；HTTP 非 200 → LOOP-ERROR。
# 注意：body 走临时文件而非 argv——Git Bash 传给原生 curl.exe 的 argv
# 会被重编码为 GBK，中文消息会 mojibake（与 diag-x3-loop.sh 同款做法）。
chat() { # $1=userId $2=message；回复写入 $RESP_FILE
  printf '{"message":"%s","userId":"%s"}' "$2" "$1" > "$BODY_FILE"
  local http_code
  http_code=$(curl -s -o "$RESP_FILE" -w '%{http_code}' -m 180 \
    -X POST "${BASE}/api/chat" \
    -H 'Content-Type: application/json; charset=utf-8' \
    --data-binary @"$BODY_FILE") || loop_error "curl 失败（应用未启动？）"
  [ "$http_code" = "200" ] || loop_error "HTTP $http_code"
}

red() { # $1=场景 $2=原因
  echo "--- $1: RED — $2"
  RED_COUNT=$((RED_COUNT + 1))
}

green() { # $1=场景
  echo "--- $1: GREEN"
}

reply_bytes() { wc -c < "$RESP_FILE" | tr -d ' '; }

# 画像城市探测：python（sqlite3 模块）优先，sqlite3 CLI 兜底；
# 输出 城市 / __none__（无记录）/ __dberror__（库异常）/ __notool__（无探测工具）
profile_city() { # $1=userId
  if command -v python >/dev/null 2>&1; then
    python - "$1" "$DB_FILE" <<'PY'
import sqlite3, sys
sys.stdout.reconfigure(encoding="utf-8")  # Windows 重定向 stdout 默认 GBK，强制 UTF-8
uid, db = sys.argv[1], sys.argv[2]
try:
    con = sqlite3.connect(db)
    rows = con.execute(
        "SELECT city FROM customer_profile"
        " WHERE external_user_id = ? AND city IS NOT NULL", (uid,)).fetchall()
    print(rows[0][0] if rows else "__none__")
except Exception:
    print("__dberror__")
PY
  elif command -v sqlite3 >/dev/null 2>&1; then
    sqlite3 "$DB_FILE" \
      "SELECT COALESCE(MAX(city),'__none__') FROM customer_profile WHERE external_user_id='$1' AND city IS NOT NULL;"
  else
    echo "__notool__"
  fi
}

echo "=== 模糊路由红绿验收（RUN_ID=$RUN_ID, PORT=$PORT） ==="

# ---- S1：有x3吗 —— 别名命中判清晰，不得误注入引导 ----
chat "$RUN_ID-s1" "有x3吗"
echo "[S1] 有x3吗 → $(cat "$RESP_FILE")"
if grep -q "哪个城市" "$RESP_FILE"; then
  red S1 "清晰查询被误注入引导（反问城市）"
elif ! grep -qE '[Xx]3|宝马' "$RESP_FILE"; then
  red S1 "回复未落在 X3/宝马上（别名命中应判清晰直答）"
else
  green S1
fi

# ---- S2：推荐一款车 —— 零信号无画像 → 反问城市且不冗长 ----
chat "$RUN_ID-s2" "推荐一款车"
echo "[S2] 推荐一款车 → $(cat "$RESP_FILE")"
if ! grep -q "城市" "$RESP_FILE"; then
  red S2 "深模糊无画像未反问城市（疑似静默退回全局）"
elif [ "$(reply_bytes)" -gt 1200 ]; then
  red S2 "反问城市的回复过长（$(reply_bytes) 字节 > 1200）"
else
  green S2
fi

# ---- S3：续答 杭州 —— 不得再问城市，应向推荐方向推进 ----
chat "$RUN_ID-s2" "杭州"
echo "[S3] 杭州 → $(cat "$RESP_FILE")"
if grep -q "哪个城市" "$RESP_FILE"; then
  red S3 "客户已回答城市，又被重复询问（画像跨轮状态失效）"
elif ! grep -q '[？?]' "$RESP_FILE"; then
  red S3 "回答城市后未向推荐推进（引导应以一个轻问题收尾）"
else
  green S3
fi

# ---- S4：我在杭州，推荐一款车 —— 画像有城市 → 1-2 款选项，不冗长 ----
chat "$RUN_ID-s4" "我在杭州，推荐一款车"
echo "[S4] 我在杭州，推荐一款车 → $(cat "$RESP_FILE")"
if grep -q "哪个城市" "$RESP_FILE"; then
  red S4 "同轮已给城市仍反问城市（画像抓取未生效）"
elif [ "$(reply_bytes)" -gt 1200 ]; then
  red S4 "推荐回复过长（$(reply_bytes) 字节 > 1200，应为 1-2 款起点选项）"
elif ! grep -q '[？?]' "$RESP_FILE"; then
  red S4 "推荐未以轻问题收尾（档 3 引导形态：起点选项 + 一个轻问题）"
else
  green S4
fi

# ---- S5：画像落库验证 ----
[ -f "$DB_FILE" ] || loop_error "找不到 $DB_FILE（画像表所在库）"
S5_FAIL=0
for u in "$RUN_ID-s2" "$RUN_ID-s4"; do
  city="$(profile_city "$u")"
  case "$city" in
    __notool__)  loop_error "python / sqlite3 均不可用，无法探测画像表" ;;
    __dberror__) loop_error "画像库探测失败（DB 损坏或表缺失）" ;;
    __none__)    red S5 "$u 的城市未落 customer_profile"; S5_FAIL=1 ;;
    杭州)        echo "[S5] $u → city=$city" ;;
    *)           red S5 "$u 画像城市异常：$city（期望 杭州）"; S5_FAIL=1 ;;
  esac
done
[ "$S5_FAIL" -eq 0 ] && green S5

# ---- 终判 ----
if [ "$RED_COUNT" -gt 0 ]; then
  echo "=== VERDICT: RED — $RED_COUNT 个场景行为回归 ==="
  exit 1
fi
echo "=== VERDICT: GREEN — 模糊场景行为符合预期 ==="
exit 0