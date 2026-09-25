#!/usr/bin/env bash
# End-to-end check of ancamera on one device or emulator.
# Usage: scripts/device-test.sh <adb-serial>
# Needs: adb, ffprobe, curl, python3. Uses adb port forwarding, so Wi-Fi is not necessary.
set -u
SERIAL=${1:?usage: $0 <adb-serial>}
export ANDROID_SERIAL=$SERIAL
cd "$(dirname "$0")/.."

APK=app/build/outputs/apk/debug/app-debug.apk
PKG=com.ancamera
H=http://127.0.0.1:18080
R=rtsp://127.0.0.1:18554/
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"; adb forward --remove tcp:18080 >/dev/null 2>&1; adb forward --remove tcp:18554 >/dev/null 2>&1' EXIT
PASSED=0
FAILED=0

pass() { echo "PASS  $*"; PASSED=$((PASSED + 1)); }
fail() { echo "FAIL  $*"; FAILED=$((FAILED + 1)); }
check() { local name=$1; shift; if "$@"; then pass "$name"; else fail "$name"; fi; }
# json '<python expression on d>' < file-or-pipe
json() { python3 -c 'import json,sys; d=json.load(sys.stdin); print(eval(sys.argv[1]))' "$1"; }
status_field() { curl -s --max-time 3 "${@:2}" "$H/api/status" | json "d['$1']" 2>/dev/null; }
activity() { adb shell am start -n $PKG/.MainActivity "$@" >/dev/null; }
# Login and reset commands. AdbCommandActivity needs the DUMP permission, which the adb shell has.
adbcmd() { adb shell am start -n $PKG/.AdbCommandActivity "$@" >/dev/null; }
wait_streaming() {
  local st=""
  for _ in $(seq 1 40); do
    st=$(status_field state "$@")
    [ "$st" = streaming ] && sleep 3 && return 0
    sleep 1
  done
  return 1
}
probe() { # probe <url> -> "codec_type:codec_name:width:height" per stream, one line each
  timeout 20 ffprobe -v error -rtsp_transport tcp -show_entries stream=codec_type,codec_name,width,height \
    -of json "$1" 2>/dev/null |
    python3 -c 'import json,sys; [print("%s:%s:%s:%s" % (s.get("codec_type"), s.get("codec_name"), s.get("width"), s.get("height"))) for s in json.load(sys.stdin).get("streams", [])]'
}

API=$(adb shell getprop ro.build.version.sdk | tr -d '\r')
echo "== install and start on $SERIAL (API $API)"
[ -f "$APK" ] || ./gradlew -q assembleDebug || exit 1
# -g grants runtime permissions and exists only on API 23+. Old adb returns 0 even when install fails.
GRANT=""
[ "$API" -ge 23 ] && GRANT="-g"
adb install -r $GRANT "$APK" >/dev/null
adb shell pm path $PKG | grep -q package: || { echo "install failed"; exit 1; }
adb shell am force-stop $PKG
adbcmd --ez resetSettings true
activity --ez autostart true
adb forward tcp:18080 tcp:8080 >/dev/null
adb forward tcp:18554 tcp:8554 >/dev/null

if wait_streaming; then
  pass "service reaches state streaming"
else
  fail "service reaches state streaming"
  echo "---- last app log lines"
  adb logcat -d -v time 'AndroidRuntime:E' 'CameraService:*' 'StreamEngine:*' 'CameraProbe:*' '*:S' | tail -30
  exit 1
fi

echo "== RTSP"
STREAMS=$(probe "$R")
SIZE=$(status_field size)
check "RTSP has exactly one stream, H.264 video $SIZE (got: $STREAMS)" \
  [ "$STREAMS" = "video:h264:${SIZE%x*}:${SIZE#*x}" ]

CAM_ID=$(status_field cameraId)
APP_PID=$(adb shell ps | tr -d '\r' | awk -v p=$PKG '$NF == p { print $2 }')
adb shell dumpsys media.camera | tr -d '\r' > "$TMP/camera.txt"
camera_open() {
  # API 19-27: "Camera N static information:" ... "Client[0] (...) PID: X"
  awk -v id="$CAM_ID" -v pid="$APP_PID" '
    /^Camera [0-9]+ static information/ { c = $2 + 0 }
    $0 ~ ("PID: " pid "([^0-9]|$)") && c == id { found = 1 }
    END { exit !found }' "$TMP/camera.txt" ||
  # API 28+: "Camera ID: N, ..., PID: X" in the active client list
  grep -qE "Camera ID: $CAM_ID[^0-9].*PID: $APP_PID([^0-9]|$)" "$TMP/camera.txt"
}
check "dumpsys shows camera $CAM_ID open by pid $APP_PID" camera_open

echo "== HTTP"
CODE=$(curl -s -o "$TMP/snap.jpg" -w '%{http_code} %{content_type}' --max-time 10 "$H/snapshot.jpg")
check "snapshot is 200 image/jpeg (got: $CODE)" [ "$CODE" = "200 image/jpeg" ]
check "snapshot starts with JPEG magic" [ "$(head -c 2 "$TMP/snap.jpg" | od -An -tx1 | tr -d ' \n')" = "ffd8" ]
PARTS=$(curl -s --max-time 5 "$H/mjpeg?fps=5" | grep -ac -- '--ancameraframe')
check "mjpeg sends at least 2 parts in 5 s (got: $PARTS)" [ "${PARTS:-0}" -ge 2 ]
check "status JSON has fps and battery" \
  python3 -c 'import json,sys; d=json.load(sys.stdin); assert "fps" in d and "batteryTempC" in d' \
  < <(curl -s --max-time 3 "$H/api/status")
INDEX=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$H/")
check "web page is 200 (got: $INDEX)" [ "$INDEX" = 200 ]

echo "== settings"
curl -s --max-time 3 "$H/api/settings" > "$TMP/settings.json"
OLD_SIZE=$(json "d['settings']['size']" < "$TMP/settings.json")
NEW_SIZE=$(json "next((s for s in (['640x480'] + d['allowed']['size'][d['settings']['camera']]) if s != d['settings']['size'] and s in d['allowed']['size'][d['settings']['camera']]), '')" < "$TMP/settings.json")
APPLY=$(curl -s --max-time 5 -X POST -d "{\"size\":\"$NEW_SIZE\"}" "$H/api/settings" | json "d['apply']")
check "size change $OLD_SIZE -> $NEW_SIZE is a stream restart (got: $APPLY)" [ "$APPLY" = stream_restart ]
sleep 2; wait_streaming
STREAMS=$(probe "$R")
check "RTSP now streams $NEW_SIZE (got: $STREAMS)" [ "$STREAMS" = "video:h264:${NEW_SIZE%x*}:${NEW_SIZE#*x}" ]
BAD=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X POST -d '{"fps":999}' "$H/api/settings")
check "fps 999 is rejected with 400 (got: $BAD)" [ "$BAD" = 400 ]
FIRSTPW=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -X POST -d '{"username":"a","password":"b"}' "$H/api/settings")
check "web page cannot set the first password (got: $FIRSTPW)" [ "$FIRSTPW" = 400 ]
curl -s --max-time 5 -X POST -d "{\"size\":\"$OLD_SIZE\"}" "$H/api/settings" >/dev/null
sleep 2; wait_streaming

echo "== auth"
adbcmd --es username testuser --es password testpass
sleep 2
check "service streams again after the login change" wait_streaming -u testuser:testpass
NOAUTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 "$H/api/status")
check "HTTP without login is 401 (got: $NOAUTH)" [ "$NOAUTH" = 401 ]
WITHAUTH=$(curl -s -o /dev/null -w '%{http_code}' --max-time 3 -u testuser:testpass "$H/api/status")
check "HTTP with login is 200 (got: $WITHAUTH)" [ "$WITHAUTH" = 200 ]
check "RTSP without login gives no stream" [ -z "$(probe "$R")" ]
check "RTSP with login gives the H.264 stream" \
  [ "$(probe rtsp://testuser:testpass@127.0.0.1:18554/ | cut -d: -f1,2)" = "video:h264" ]

echo "== clean up"
adbcmd --ez resetSettings true
sleep 2
check "back to defaults and streaming without login" wait_streaming

echo
echo "passed: $PASSED  failed: $FAILED"
[ "$FAILED" -eq 0 ]
