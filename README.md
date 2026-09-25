# ancamera

Turn an old Android phone (Android 4.4+) into a LAN monitoring camera.

- **RTSP (H.264, video only):** `rtsp://<phone-ip>:8554/`, for VLC, ffmpeg, Frigate, Home Assistant, Blue Iris.
- **Web page:** `http://<phone-ip>:8080/`, with a live view and camera settings.
- **MJPEG:** `http://<phone-ip>:8080/mjpeg?fps=5` (maximum 15 fps).
- **Snapshot:** `http://<phone-ip>:8080/snapshot.jpg`.
- **Status and settings API:** `GET /api/status`, `GET /api/settings`, `POST /api/settings` (partial JSON).

## Use

1. Install the APK and open **ancamera**.
2. Optional: set a username and password. RTSP and the web page then need them (`rtsp://user:pass@<phone-ip>:8554/`).
3. Push **Start**. The stream continues when the screen is off. Keep the phone on a charger.
4. Open the web page to change the camera, resolution, fps, bitrate, rotation or torch.

The first password can be set only on the phone. After that, the web page can change it.

## Build

Needs the Android SDK with platform 37 and JDK 21.

```bash
./gradlew assembleDebug testDebugUnitTest
```

## Test on a device

```bash
scripts/device-test.sh <adb-serial>   # end-to-end checks through adb port forwarding
scripts/soak.sh <adb-serial> 30       # 30 minutes with the screen off
```

Do not use an API 19 emulator for video tests: its H.264 encoder does not work.
Design: `docs/superpowers/specs/2026-09-25-ancamera-design.md`.
