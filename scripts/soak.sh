#!/usr/bin/env bash
# Screen-off soak test: counts RTSP frames for N minutes with the screen off.
# Usage: scripts/soak.sh <adb-serial> [minutes] [phone-wifi-ip]
# minutes: default 30. phone-wifi-ip: read the streams over Wi-Fi from this address. Without it,
# the script uses adb forwards on 127.0.0.1, and the test does not use the phone's Wi-Fi.
# The app must already stream (run scripts/device-test.sh first, or press Start).
set -u
SERIAL=${1:?usage: $0 <adb-serial> [minutes] [phone-wifi-ip]}
MINUTES=${2:-30}
HOST=${3:-}
export ANDROID_SERIAL=$SERIAL
if [ -n "$HOST" ]; then
  # Direct Wi-Fi access. adb only turns the screen off.
  H=http://$HOST:8080
  R=rtsp://$HOST:8554/
else
  H=http://127.0.0.1:18080
  R=rtsp://127.0.0.1:18554/
  trap 'adb forward --remove tcp:18080 >/dev/null 2>&1; adb forward --remove tcp:18554 >/dev/null 2>&1' EXIT
  adb forward tcp:18080 tcp:8080 >/dev/null
  adb forward tcp:18554 tcp:8554 >/dev/null
fi
temp() { curl -s --max-time 3 "$H/api/status" | python3 -c 'import json,sys; print(json.load(sys.stdin)["batteryTempC"])'; }

if adb shell dumpsys power | grep -qE 'mScreenOn=true|Display Power: state=ON'; then
  adb shell input keyevent 26   # power button: screen off
fi
sleep 2
adb shell dumpsys power | grep -qE 'mScreenOn=true|Display Power: state=ON' && { echo "screen is still on"; exit 1; }

T0=$(temp)
SECONDS_TOTAL=$((MINUTES * 60))
echo "screen off, battery ${T0} °C, counting frames from $R for $MINUTES min"
FRAMES=$(timeout $((SECONDS_TOTAL + 60)) ffmpeg -v error -rtsp_transport tcp -i "$R" -an \
  -t "$SECONDS_TOTAL" -f null - -progress pipe:1 2>/dev/null | grep '^frame=' | tail -1 | cut -d= -f2)
T1=$(temp)
FRAMES=${FRAMES:-0}
AVG=$(python3 -c "print(round($FRAMES / $SECONDS_TOTAL, 1))")
echo "frames: $FRAMES  average fps: $AVG  battery: ${T0} °C -> ${T1} °C"
# Pass: at least 5 fps on average for the whole time (a dropped stream ends ffmpeg early).
python3 -c "import sys; sys.exit(0 if $FRAMES >= 5 * $SECONDS_TOTAL else 1)" && echo PASS || { echo FAIL; exit 1; }
