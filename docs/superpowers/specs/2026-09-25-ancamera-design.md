# ancamera — design

Date: 2026-09-25
Status: approved in chat, waiting for spec review

## 1. Purpose

Use an old Android phone as a LAN monitoring camera. The app streams the phone camera in standard formats:

- RTSP with H.264 video, for NVR tools (Frigate, Home Assistant, Blue Iris), VLC and ffmpeg.
- MJPEG and JPEG snapshots over HTTP, for web browsers.

The first target phone is an LG G3 D802 (Android 4.4.2, API 19). The app must also work on Android 8 and later.

## 2. Requirements

| ID | Requirement |
|---|---|
| R1 | One APK runs on API 19 and on API 26+. |
| R2 | RTSP stream: H.264, video only, on port 8554. |
| R3 | HTTP on port 8080: web page, MJPEG stream, JPEG snapshot, JSON API. |
| R4 | Streaming continues when the screen is off. |
| R5 | You can change camera settings from the web page. |
| R6 | Optional username and password. RTSP and HTTP use the same credentials (Basic auth). |
| R7 | LAN only. The app does not give internet access. Use a VPN for remote access. |

Out of scope: audio, auto-start at boot, TLS, internet relay, recording on the phone, motion detection, multiple RTSP paths or profiles, automatic heat throttling.

## 3. Verified facts (probe of 2026-09-25)

A throwaway probe gave these facts. The probe code was not kept.

- RTSP-Server 1.4.3 and RootEncoder 2.8.1 build with `minSdk 19`. They need `compileSdk 37`, AGP 9.3.x and Gradle 9.7.1. With `minSdk` below 21, multidex is necessary.
- On the G3, the library background mode (`RtspServerCamera1(Context)`) streams H.264 Baseline at 640×480, 1280×720 and 1920×1080, also with the screen off.
- On the API 34 emulator, background mode streams H.264.
- **Library bug:** in background mode, `startPreview(CameraHelper.Facing.BACK, …)` does not change the camera that opens. The front camera opens. Workaround: find the camera ID with `Camera.getCameraInfo()` and call `startPreview(cameraId, w, h, fps, rotation)`.
- The RTSP SDP announces an empty AAC track unless the app calls `setOnlyVideo(true)`. Without that call, ffmpeg waits for audio.
- The RTSP server supports Basic auth only.
- The H.264 encoder of the API 19 emulator does not work, even with plain `MediaCodec`. Do not use the API 19 emulator for video tests.
- In dim light the camera gives 9–11 fps, because of long exposure times. This is not a library limit.
- The G3 back camera has only the preview fps ranges (1,30) and (10,30) fps. The library selects (10,30), so auto exposure can lower the frame rate to 10 fps. A fixed fps range is not possible.
- G3 exposure controls (2026-09-26): 20 scene modes (`sports`, `action`, `night`, …), ISO `auto`, `ISO_HJR`, `ISO100`–`ISO1600` (Qualcomm keys `iso` and `iso-values`), exposure compensation −12..12 in steps of 1/6 EV. `ISO800` makes the exposure about 4 times shorter. `sports` and `action` do not change the exposure.
- **Library bug (API < 21):** MediaCodec does not set `position` and `limit` of the buffers from `getOutputBuffers()`. So `limit()` is the full buffer capacity (1–2 MB), not the frame size. The RTSP sender copies `[0, limit())` into its 400-frame queue for each frame. A short Wi-Fi delay then fills the 128 MB heap, and the encoder thread stops with `OutOfMemoryError`. Workaround: `TrimmedRtspServerCamera1` trims each frame to `[offset, offset + size)` before the RTSP server gets it. A frame with a range outside the buffer is dropped.
- G3 encoder limits: H.264 up to 3840×2160, 40 Mbit/s, 120 fps.

## 4. Architecture

```
MainActivity ──start/stop──▶ CameraService (foreground service, type=camera)
                                 │  holds PARTIAL wake lock + Wi-Fi lock
                                 ▼
                      StreamEngine ───────────────▶ TrimmedRtspServerCamera1(Context)  :8554
                      (the only class that         (background mode: GL + encoder surface)
                       uses the libraries)              │
                                 │ JPEG frames when requested (GL frame capture)
                                 ▼
                      HttpServer :8080  ── /, /mjpeg, /snapshot.jpg, /api/*
                                 │
                      Settings (SharedPreferences) ◀── web UI / MainActivity
```

### 4.1 Project

- One Gradle module: `app`. Language: Kotlin (built into AGP 9).
- `minSdk 19`, `compileSdk 37`, `targetSdk 36`, multidex enabled.
- Pinned dependencies (JitPack): `com.github.pedroSG94:RTSP-Server:1.4.3`, `com.github.pedroSG94.RootEncoder:library:2.8.1`.
- Package: `com.ancamera`.
- Framework UI classes only. No AppCompat and no other AndroidX UI libraries.

### 4.2 Units

| Unit | Job | Depends on |
|---|---|---|
| `Settings` | Typed, validated settings. Persisted in SharedPreferences. The validation logic has no Android dependencies. | nothing |
| `StreamEngine` | Starts, stops and reconfigures the RTSP stream. Applies the camera-ID workaround, `setOnlyVideo(true)`, auth and the exposure settings. Gives JPEG frames on request. Reports status. | RootEncoder, RTSP-Server |
| `TrimmedRtspServerCamera1` | App copy of the final library class `RtspServerCamera1`, background constructor only. Trims each video frame before the RTSP server gets it (API < 21 bug). Sets scene mode and ISO on the `android.hardware.Camera`, which it gets by reflection from private RootEncoder 2.8.1 fields. | RootEncoder, RTSP-Server |
| `HttpServer` | HTTP server on `ServerSocket`, one thread for each connection, maximum 8 connections. Serves the page, MJPEG, snapshot and JSON API. Checks Basic auth. | `StreamEngine` and `Settings`, through interfaces |
| `CameraService` | Foreground service of type `camera`, with a notification. Holds a partial wake lock and a Wi-Fi lock. Owns the engine and the HTTP server. Applies settings changes. `START_STICKY`. | all units above |
| `MainActivity` | Start/stop button. Shows the RTSP and HTTP URLs with the phone IP. Asks for runtime permissions. Sets the first password. | `CameraService` |

`StreamEngine` and `TrimmedRtspServerCamera1` are the only units that import library classes. All library workarounds are in these units.

### 4.3 How the two streams share the camera

One camera and one H.264 encoder feed RTSP. MJPEG and snapshots get frames from the same GL pipeline through the library frame capture, and then use `Bitmap.compress(JPEG)`. Frame capture runs only while an MJPEG client or a snapshot request is active. The expected MJPEG rate on the G3 at 720p is 5–10 fps.

### 4.4 Android version rules

- API 34+: a camera foreground service must start while the app is in the foreground. The service starts only from `MainActivity`.
- API 33+: ask for `POST_NOTIFICATIONS`.
- API 23+: ask for `CAMERA` at run time.
- API 19–22: no runtime permissions.

## 5. HTTP interface (port 8080)

When a password is set, all paths need Basic auth.

Before auth, the server does these checks against DNS rebinding and cross-site requests:
- The `Host` header must be an IP literal, `localhost`, or a name that ends with `.local`, `.lan`, `.home.arpa` or `.internal`. Otherwise `403`. A request with no `Host` header is allowed.
- `POST /api/settings` needs `Content-Type: application/json`. Otherwise `415`.
- A `POST` with an `Origin` header must come from the same host and port. Otherwise `403`.
- The server closes connections from addresses that are not loopback, private, link-local or IPv6 unique local, with no response. The RTSP server does not filter addresses.
- The full request must arrive in 10 s. Otherwise the server closes the connection.

| Method + path | Result |
|---|---|
| `GET /` | Web page (`assets/index.html`, inline JS, no CDN). Live MJPEG view and settings form. |
| `GET /mjpeg` | `multipart/x-mixed-replace` MJPEG. Optional `?fps=` (default 5, maximum 15). |
| `GET /snapshot.jpg` | One current JPEG. |
| `GET /api/status` | JSON: streaming state, camera ID and facing, size, fps sent, RTSP client count, MJPEG client count, battery level and temperature, uptime, last error. |
| `GET /api/settings` | JSON: current settings and allowed values (camera list, and for each camera: supported sizes, scene modes, ISO values and the exposure compensation range). |
| `POST /api/settings` | Partial JSON update. Validate, save, apply. Returns the new settings, or `400` with the name of the bad field. |

### 5.1 Settings

| Setting | Default | How the app applies a change |
|---|---|---|
| camera (back/front) | back | stream restart |
| resolution | 1280×720 | stream restart |
| fps | 20 | stream restart |
| bitrate | 2.5 Mbit/s | live |
| rotation (0/90/180/270) | 0 | stream restart |
| torch | off | live |
| exposureCompensation (index, one index = step EV) | 0 | live |
| sceneMode | auto | live |
| iso | auto | live |
| MJPEG quality | 70 | live |
| RTSP port | 8554 | server restart |
| HTTP port | 8080 | server restart |
| username / password | none | server restart |

`CameraProbe` reads the allowed values of each camera at service start. Scene modes and exposure compensation use the standard `Camera.Parameters` API. ISO uses the Qualcomm keys `iso` and `iso-values`. A camera without a list accepts only the default. A value that the new camera does not support goes back to the default when the camera changes. The app sets scene mode, then ISO, then exposure compensation, after each engine start and on each change. A failed camera call sets the last error, and the stream continues.

In low light, a fixed `iso` or a negative `exposureCompensation` makes the exposure shorter, so the frame rate stays higher. The picture is then noisier or darker.

A stream restart takes about 1–2 s. RTSP clients disconnect and reconnect by themselves. After a server restart, the page shows a warning and reloads.

The web page cannot set a password when no password is set. Only `MainActivity` can set the first password. This prevents a lock-out by another LAN user.

### 5.2 URLs

- RTSP: `rtsp://[user:pass@]<phone-ip>:8554/`
- Browser: `http://<phone-ip>:8080/`
- Home Assistant: `http://<phone-ip>:8080/mjpeg` and `http://<phone-ip>:8080/snapshot.jpg`, or the RTSP URL.

## 6. Error handling

| Failure | Behaviour |
|---|---|
| Camera open fails | Status `error`. Retry after 2 s, 5 s, 10 s, 30 s, then every 60 s. The notification and `/api/status` show the last error. |
| Encoder crash | The library restarts the encoder. If frames stop, the watchdog below does a full engine restart. The app does not use the library `CodecErrorCallback`: its signature uses `MediaCodec.CodecException`, which exists only from API 21. |
| No frames for 10 s while streaming | Watchdog does a full engine restart with back-off. The frame signal is the library fps callback. It fires about once each second while the encoder makes frames (not once for each frame). |
| Saved settings not valid at start | Use the defaults and log the problem. |
| Chosen camera missing | Use camera ID 0 and report this in the status. |
| Port in use | Report in the notification and the status. Do not crash. HTTP port: if a changed port is not available, go back to the old port and show "HTTP port <new> not available, kept <old>". If the HTTP server does not run, retry with back-off. |
| RTSP port not available | The status and the notification show the error. The engine restarts with back-off. |
| Wi-Fi IP changes | No restart (servers listen on `0.0.0.0`). The activity and the notification show the current IP. |
| Battery above 45 °C | Warning in the status only. |
| The system kills the service | API 19-29: `START_STICKY` restarts it. API 30+: Android does not allow camera access from a restart in the background; the service shows a notification "Tap to start streaming again" and stops. |

## 7. Testing

1. JVM unit tests (`./gradlew testDebugUnitTest`): settings validation, HTTP request parsing, Basic auth, MJPEG multipart writer, settings and status JSON.
2. `scripts/device-test.sh <serial>` installs and starts the app, then checks:
   - `ffprobe` on RTSP: one H.264 stream of the correct size, no audio stream.
   - `adb shell dumpsys media.camera`: the chosen camera ID is open.
   - `/snapshot.jpg` gives a valid JPEG. `/mjpeg` gives at least 2 parts. `/api/status` gives valid JSON.
   - `POST /api/settings` with a new resolution, then `ffprobe` shows the new size.
   - A changed RTSP port and a changed HTTP port work, and the old ports work again after the change back.
   - With auth on: `401` without credentials, `200` with credentials, RTSP works with credentials.
3. Test targets: LG G3 (API 19) and the API 34 emulator. Do not use the API 19 emulator.
4. Screen-off soak on the G3 (`scripts/soak.sh <serial> 30 <phone-wifi-ip>`): 30 minutes with the screen off. Record the frame count and the battery temperature. The script writes the ffmpeg log, the app status once a minute and the phone log to a new directory.

## 8. Risks

- Upstream does not test API 19 (its sample app uses `minSdk 23`). Keep the versions pinned. Run the device test on the G3 before each library update.
- Old camera HALs can stall silently. The watchdog covers this.
- Scene mode and ISO use reflection on private RootEncoder 2.8.1 fields (`Camera1Base.cameraManager`, `Camera1ApiManager.camera`). A library update can break them. The library version is pinned for this reason. A failure only sets the last error.
- A phone that runs all day on a charger can get hot, and its battery can swell. The status shows the battery temperature. Monitor it during the first days of use.
