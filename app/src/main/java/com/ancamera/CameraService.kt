package com.ancamera

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.ancamera.http.HttpBackend
import com.ancamera.http.HttpResponse
import com.ancamera.http.HttpServer
import com.ancamera.http.Router
import com.ancamera.settings.ApplyKind
import com.ancamera.settings.Capabilities
import com.ancamera.settings.PatchResult
import com.ancamera.settings.Settings
import com.ancamera.settings.SettingsJson
import com.ancamera.settings.SettingsRules
import com.ancamera.settings.SettingsStore
import com.ancamera.stream.Backoff
import com.ancamera.stream.CameraProbe
import com.ancamera.stream.EngineState
import com.ancamera.stream.StatusJson
import com.ancamera.stream.StatusSnapshot
import com.ancamera.stream.StreamEngine
import com.ancamera.stream.Watchdog
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException

/**
 * Foreground service that owns the stream engine and the HTTP server. It holds a partial wake
 * lock and a Wi-Fi lock, so streaming continues with the screen off. Engine calls run on the
 * main thread; HTTP threads post to it.
 */
class CameraService : Service(), HttpBackend {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var engine: StreamEngine
    private val settingsLock = Any()
    @Volatile private var settings = Settings()
    @Volatile private var caps = Capabilities(emptyMap())
    @Volatile private var http: HttpServer? = null
    @Volatile private var httpError: String? = null
    private var indexHtml = ByteArray(0)
    private val backoff = Backoff()
    private val httpBackoff = Backoff()
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var startedAtMs = 0L
    private var started = false
    private var lastNotificationText: String? = null

    private val retry = Runnable {
        if (!running) return@Runnable
        startEngine()
    }
    private val httpRetry = Runnable {
        if (!running || http != null) return@Runnable
        startHttp()
    }
    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            if (engine.state == EngineState.STREAMING) {
                if (engine.rtspFailed) {
                    Log.w(TAG, "${engine.lastError}, restarting the engine")
                    engine.stop()
                    scheduleRetry()
                } else if (Watchdog.isStalled(true, engine.lastFrameAtMs, now)) {
                    Log.w(TAG, "no frames for ${Watchdog.STALL_MS} ms, restarting the engine")
                    engine.stop()
                    scheduleRetry()
                } else if (engine.fps > 0) {
                    backoff.reset()
                }
            }
            updateNotification()
            main.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        store = SettingsStore(this)
        engine = StreamEngine(this)
        indexHtml = assets.open("index.html").use { it.readBytes() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null && Build.VERSION.SDK_INT >= 30) {
            // A sticky restart runs in the background. API 30+ does not give camera access to it,
            // so tell the user and stop. A sticky restart does not need startForeground.
            showStoppedNotification()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!started) {
            started = true
            running = true
            startedAtMs = SystemClock.elapsedRealtime()
            acquireLocks()
            caps = CameraProbe.capabilities()
            settings = SettingsRules.sanitize(store.load(), caps)
            store.save(settings)
            startEngine()
            startHttp()
            main.postDelayed(tick, TICK_MS)
        } else if (intent?.action == ACTION_RELOAD) {
            reloadFromStore()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        http?.stop()
        http = null
        engine.stop()
        releaseLocks()
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    // ---- engine and server ----

    private fun startEngine() {
        if (!running) return
        main.removeCallbacks(retry)
        if (!engine.start(settings)) {
            Log.w(TAG, "engine start failed (${engine.lastError})")
            scheduleRetry()
        }
        updateNotification()
    }

    /** Starts the engine again after the next back-off delay. */
    private fun scheduleRetry() {
        main.removeCallbacks(retry)
        val delay = backoff.nextDelayMs()
        Log.w(TAG, "engine retry in $delay ms")
        main.postDelayed(retry, delay)
    }

    /** Returns false when the port is not available. Then a retry starts after a back-off delay. */
    private fun startHttp(): Boolean {
        main.removeCallbacks(httpRetry)
        val ok = try {
            val server = HttpServer(settings.httpPort, Router(this, indexHtml), this)
            server.start()
            http = server
            httpError = null
            httpBackoff.reset()
            true
        } catch (e: IOException) {
            httpError = "HTTP port ${settings.httpPort} not available: ${e.message}"
            Log.e(TAG, httpError!!)
            if (running) main.postDelayed(httpRetry, httpBackoff.nextDelayMs())
            false
        }
        updateNotification()
        return ok
    }

    private fun reloadFromStore() {
        val old: Settings
        val new: Settings
        synchronized(settingsLock) {
            old = settings
            new = SettingsRules.sanitize(store.load(), caps)
            settings = new
        }
        apply(old, new, SettingsRules.classify(old, new))
    }

    private fun apply(old: Settings, new: Settings, kind: ApplyKind) {
        if (!running) return
        when (kind) {
            ApplyKind.NONE -> {}
            ApplyKind.LIVE -> {
                if (old.bitrate != new.bitrate) engine.setBitrate(new.bitrate)
                if (old.torch != new.torch) engine.setTorch(new.torch)
            }
            ApplyKind.STREAM_RESTART -> startEngine()
            ApplyKind.SERVER_RESTART -> {
                if (old.httpPort != new.httpPort) {
                    http?.stop()
                    http = null
                    if (!startHttp()) {
                        // The new port is not available. Go back to the old port.
                        synchronized(settingsLock) {
                            settings = settings.copy(httpPort = old.httpPort)
                            store.save(settings)
                        }
                        startHttp()
                        httpError = "HTTP port ${new.httpPort} not available, kept ${old.httpPort}"
                        Log.e(TAG, httpError!!)
                    }
                }
                startEngine() // RTSP port and RTSP credentials are set when the engine starts
            }
        }
        updateNotification()
    }

    // ---- HttpBackend (called on HTTP threads) ----

    override fun credentials(): Pair<String, String> = settings.let { it.username to it.password }

    override fun statusJson(): String = StatusJson.toJson(snapshot()).toString()

    override fun settingsJson(): String = SettingsJson.toJson(settings, caps).toString()

    override fun updateSettings(body: String): HttpResponse {
        val patch = try {
            SettingsJson.patchFromJson(JSONObject(body))
        } catch (e: JSONException) {
            return HttpResponse.json(400, JSONObject().put("error", "body is not a JSON object").toString())
        }
        val old: Settings
        val result: PatchResult.Ok
        synchronized(settingsLock) {
            old = settings
            when (val r = SettingsRules.applyPatch(old, patch, caps, fromWeb = true)) {
                is PatchResult.Invalid -> return HttpResponse.json(
                    400, JSONObject().put("error", r.reason).put("field", r.field).toString(),
                )
                is PatchResult.Ok -> result = r
            }
            settings = result.settings
            store.save(result.settings)
        }
        // Let the response go out before a server restart closes this connection.
        val delay = if (result.kind == ApplyKind.SERVER_RESTART) SERVER_RESTART_DELAY_MS else 0L
        main.postDelayed({ apply(old, result.settings, result.kind) }, delay)
        val json = SettingsJson.toJson(result.settings, caps).put("apply", result.kind.wire)
        return HttpResponse.json(200, json.toString())
    }

    override fun jpegFrame(timeoutMs: Long): ByteArray? = engine.captureJpeg(settings.mjpegQuality, timeoutMs)

    // ---- status, notification, locks ----

    private fun snapshot(): StatusSnapshot {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val tenthsC = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        val s = settings
        return StatusSnapshot(
            state = engine.state,
            cameraId = engine.cameraId,
            facing = engine.facing.wire,
            size = s.size.toString(),
            fps = engine.fps,
            rtspClients = engine.rtspClients,
            mjpegClients = http?.mjpegClients ?: 0,
            batteryPercent = if (level >= 0 && scale > 0) level * 100 / scale else -1,
            batteryTempC = tenthsC / 10.0,
            uptimeSec = (SystemClock.elapsedRealtime() - startedAtMs) / 1000,
            lastError = httpError ?: engine.lastError,
            ip = Net.lanIpv4(),
            rtspPort = s.rtspPort,
            httpPort = s.httpPort,
            authEnabled = s.authEnabled,
        )
    }

    private fun notificationText(): String {
        val s = settings
        val ip = Net.lanIpv4() ?: "no network"
        val stream = when (engine.state) {
            EngineState.STREAMING -> "rtsp://$ip:${s.rtspPort}/ · ${engine.fps} fps · ${engine.rtspClients} RTSP viewer(s)" +
                (engine.lastError?.let { " · $it" } ?: "")
            EngineState.ERROR -> "Error: ${engine.lastError}"
            EngineState.STOPPED -> "Starting…"
        }
        return httpError?.let { "$stream · $it" } ?: stream
    }

    private fun buildNotification(text: String, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0,
        )
        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("ancamera")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(ongoing)
            .setAutoCancel(!ongoing)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "Streaming", NotificationManager.IMPORTANCE_LOW))
        }
    }

    private fun showStoppedNotification() {
        createChannel()
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(STOPPED_NOTIFICATION_ID, buildNotification("ancamera stopped. Tap to start streaming again.", ongoing = false))
    }

    /** Returns false when Android does not allow a camera foreground service now (API 34+ from background). */
    private fun enterForeground(): Boolean {
        createChannel()
        val n = buildNotification(notificationText())
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
            true
        } catch (e: RuntimeException) {
            Log.e(TAG, "cannot start foreground service", e)
            false
        }
    }

    private fun updateNotification() {
        val text = notificationText()
        if (text == lastNotificationText) return
        lastNotificationText = text
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    // No wake lock timeout: the stream runs until the user stops it.
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ancamera:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
        val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        @Suppress("DEPRECATION")
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "ancamera:stream").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        wifiLock = null
    }

    companion object {
        private const val TAG = "CameraService"
        private const val CHANNEL_ID = "stream"
        private const val NOTIFICATION_ID = 1
        private const val STOPPED_NOTIFICATION_ID = 2
        private const val TICK_MS = 2_000L
        private const val SERVER_RESTART_DELAY_MS = 500L
        const val ACTION_RELOAD = "com.ancamera.action.RELOAD"

        @Volatile var running = false
            private set

        /** Call from a visible activity (API 34+ rule for camera foreground services). */
        fun start(context: Context) {
            val intent = Intent(context, CameraService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CameraService::class.java))
        }

        /** Makes a running service read the saved settings again. */
        fun reload(context: Context) {
            if (running) context.startService(Intent(context, CameraService::class.java).setAction(ACTION_RELOAD))
        }
    }
}
