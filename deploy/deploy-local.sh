#!/usr/bin/env bash
# Deploy 9chat-android THẲNG lên VPS, KHÔNG qua git/CI.
#   build release (ký bằng keystore.properties) -> scp APK + version.json
#   -> app trên máy tự nhắc cập nhật.
#
# Dùng: bash deploy/deploy-local.sh ["ghi chú hiển thị trong app"]
#
# Yêu cầu: đã có ~/.ssh/9chat_deploy (key deploy) + keystore.properties ở repo root.
set -euo pipefail

cd "$(dirname "$0")/.."   # về repo root

KEY=~/.ssh/9chat_deploy
HOST=root@103.170.123.106
DEST=/www/wwwroot/9chat.vn/app
APK=app/build/outputs/apk/release/app-release.apk
VC_FILE="deploy/.vercode"     # bộ đếm versionCode local (gitignored)
NOTES="${1:-Đã có bản cập nhật mới. Nhấn Cập nhật để cài.}"

# --- kiểm tra tiền đề ---
[ -f "$KEY" ] || { echo "❌ Thiếu $KEY (key deploy)"; exit 1; }
[ -f keystore.properties ] || { echo "❌ Thiếu keystore.properties (để ký release)"; exit 1; }

# --- versionCode tự tăng (seed 100 để luôn > mọi bản CI cũ) ---
VC=$(cat "$VC_FILE" 2>/dev/null || echo 100)
VC=$((VC + 1))
VNAME="1.0.$VC"

echo "== [1/3] Build release versionCode=$VC ($VNAME) =="
./gradlew assembleRelease --no-daemon -PverCode="$VC" -PverName="$VNAME" -q
[ -f "$APK" ] || { echo "❌ Không thấy APK sau build"; exit 1; }

echo "== [2/3] Upload APK 9chat-$VC.apk =="
ssh -i "$KEY" "$HOST" "mkdir -p $DEST"
scp -i "$KEY" "$APK" "$HOST:$DEST/9chat-$VC.apk"

echo "== [3/3] Ghi version.json (SAU khi APK đã lên) =="
printf '{"versionCode":%d,"versionName":"%s","apkUrl":"https://9chat.vn/app/9chat-%d.apk","notes":"%s","mandatory":false}\n' \
  "$VC" "$VNAME" "$VC" "$NOTES" \
  | ssh -i "$KEY" "$HOST" "cat > $DEST/version.json"
ssh -i "$KEY" "$HOST" "chown -R www:www $DEST"

echo "$VC" > "$VC_FILE"
echo "✅ XONG — versionCode $VC. App sẽ tự nhắc cập nhật khi mở lên."
