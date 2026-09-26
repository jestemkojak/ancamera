#!/usr/bin/env bash
# Screen-off soak test: counts RTSP frames for N minutes with the screen off.
# Usage: scripts/soak.sh <adb-serial> [minutes] [phone-wifi-ip]
# minutes: default 30. phone-wifi-ip: read the streams over Wi-Fi from this address. Without it,
# the script uses adb forwards on 127.0.0.1, and the test does not use the phone's Wi-Fi.
# The app must already stream (run scripts/device-test.sh first, or press Start).
# The script writes its logs to a new directory and shows the path:
#   ffmpeg.log    ffmpeg errors
#   progress.txt  ffmpeg progress (frame count and stream time)
#   status.log    /api/status once a minute (state, fps, RTSP clients, battery temperature)
#   logcat.txt    the phone log
set -u
SERIAL=${1:?usage: $0 <adb-serial> [minutes] [phone-wifi-ip]}
MINUTES=${2:-30}
HOST=${3:-}
export ANDROID_SERIAL=$SERIAL
LOG=$(mktemp -d "${TMPDIR:-/tmp}/ancamera-soak.XXXXXX")
PIDS=()
cleanup() {
  [ ${#PIDS[@]} -gt 0 ] && kill "${PIDS[@]}" 2>/dev/null
  if [ -z "$HOST" ]; then
    adb forward --remove tcp:18080 >/dev/null 2>&1
    adb forward --remove tcp:18554 >/dev/null 2>&1
  fi
}
trap cleanup EXIT
if [ -n "$HOST" ]; then
  # Direct Wi-Fi access. adb only turns the screen off.
  H=http://$HOST:8080
  R=rtsp://$HOST:8554/
else
  H=http://127.0.0.1:18080
  R=rtsp://127.0.0.1:18554/
  adb forward tcp:18080 tcp:8080 >/dev/null
  adb forward tcp:18554 tcp:8554 >/dev/null
fi
temp() { curl -s --max-time 3 "$H/api/status" | python3 -c 'import json,sys; print(json.load(sys.stdin)["batteryTempC"])'; }
status_line() {
  curl -s --max-time 3 "$H/api/status" | python3 -c 'import json,sys; s=json.load(sys.stdin); print("state=%s fps=%s rtspClients=%s batteryTempC=%s" % (s["state"], s["fps"], s["rtspClients"], s["batteryTempC"]))' 2>/dev/null \
    || echo "no status"
}
poll() { while :; do echo "$(date +%T) $(status_line)"; sleep 60; done; }

echo "logs: $LOG"
adb logcat -v time >"$LOG/logcat.txt" 2>&1 &
PIDS+=($!)

if adb shell dumpsys power | grep -qE 'mScreenOn=true|Display Power: state=ON'; then
  adb shell input keyevent 26   # power button: screen off
fi
sleep 2
adb shell dumpsys power | grep -qE 'mScreenOn=true|Display Power: state=ON' && { echo "screen is still on"; exit 1; }

T0=$(temp)
SECONDS_TOTAL=$((MINUTES * 60))
echo "screen off, battery ${T0} °C, counting frames from $R for $MINUTES min"
poll >"$LOG/status.log" &
PIDS+=($!)
# -timeout (10 s, in microseconds): a stream that stops sending data ends ffmpeg with an error.
# Without it, ffmpeg waits with no frames until the outer timeout.
START=$SECONDS
timeout $((SECONDS_TOTAL + 60)) ffmpeg -v error -timeout 10000000 -rtsp_transport tcp -i "$R" -an \
  -t "$SECONDS_TOTAL" -f null - -progress "$LOG/progress.txt" 2>"$LOG/ffmpeg.log"
RC=$?
ELAPSED=$((SECONDS - START))
T1=$(temp)
FRAMES=$(grep '^frame=' "$LOG/progress.txt" 2>/dev/null | tail -1 | cut -d= -f2)
FRAMES=${FRAMES:-0}
OUT_TIME=$(grep '^out_time=' "$LOG/progress.txt" 2>/dev/null | tail -1 | cut -d= -f2)
AVG=$(python3 -c "print(round($FRAMES / $SECONDS_TOTAL, 1))")
echo "frames: $FRAMES  average fps: $AVG  battery: ${T0} °C -> ${T1} °C"
echo "ffmpeg: exit code $RC after ${ELAPSED} s, stream time ${OUT_TIME:-none}"
# The null muxer logs many harmless "non monotonically increasing dts" lines. Do not show them.
ERRORS=$(grep -v 'non monotonically increasing dts' "$LOG/ffmpeg.log" | tail -5)
[ -n "$ERRORS" ] && printf 'ffmpeg errors (all lines are in ffmpeg.log):\n%s\n' "$ERRORS"
echo "logs: $LOG"
# Pass: at least 5 fps on average for the whole time (a dropped or stalled stream ends ffmpeg early).
python3 -c "import sys; sys.exit(0 if $FRAMES >= 5 * $SECONDS_TOTAL else 1)" && echo PASS || { echo FAIL; exit 1; }
