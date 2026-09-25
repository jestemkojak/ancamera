package com.ancamera

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.ancamera.settings.Capabilities
import com.ancamera.settings.PatchResult
import com.ancamera.settings.SettingsRules
import com.ancamera.settings.SettingsStore

/**
 * Start/stop, stream URLs and the phone-only password setting.
 * adb extra (for scripts/device-test.sh): --ez autostart true, on a fresh launch only.
 * The reset and login extras go to AdbCommandActivity instead, which needs the DUMP permission.
 */
class MainActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var store: SettingsStore
    private lateinit var info: TextView
    private lateinit var toggle: Button
    private lateinit var user: EditText
    private lateinit var pass: EditText

    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, 2_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SettingsStore(this)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad)
        }
        info = TextView(this).apply { textSize = 16f }
        toggle = Button(this).apply { setOnClickListener { onToggle() } }
        user = EditText(this).apply { hint = "Username"; setSingleLine() }
        pass = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val save = Button(this).apply {
            text = "Save login (empty = no password)"
            setOnClickListener { saveCredentials(user.text.toString(), pass.text.toString()) }
        }
        column.addView(info)
        column.addView(toggle)
        column.addView(TextView(this).apply { text = "\nLogin for RTSP and the web page"; textSize = 16f })
        column.addView(user)
        column.addView(pass)
        column.addView(save)
        setContentView(ScrollView(this).apply { addView(column) })
        if (savedInstanceState == null && !isFromHistory(intent)) handleExtras(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (!isFromHistory(intent)) handleExtras(intent)
    }

    /** True when the intent relaunches the activity from Recents, not from a fresh adb command. */
    private fun isFromHistory(intent: Intent?): Boolean =
        (intent?.flags ?: 0) and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0

    override fun onResume() {
        super.onResume()
        main.post(refresher)
    }

    override fun onPause() {
        main.removeCallbacks(refresher)
        super.onPause()
    }

    private fun handleExtras(intent: Intent?) {
        intent ?: return
        if (intent.getBooleanExtra("autostart", false) && !CameraService.running) startWithPermissions()
    }

    private fun refresh() {
        val s = store.load()
        val ip = Net.lanIpv4() ?: "<no network>"
        info.text = if (CameraService.running) {
            "Streaming\n\nRTSP: rtsp://$ip:${s.rtspPort}/\nWeb:  http://$ip:${s.httpPort}/\n" +
                (if (s.authEnabled) "Login required (user: ${s.username})" else "No password set")
        } else {
            "Stopped"
        }
        toggle.text = if (CameraService.running) "Stop" else "Start"
    }

    private fun onToggle() {
        if (CameraService.running) CameraService.stop(this) else startWithPermissions()
        main.postDelayed({ refresh() }, 500)
    }

    private fun startWithPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            val wanted = mutableListOf(Manifest.permission.CAMERA)
            if (Build.VERSION.SDK_INT >= 33) wanted.add(Manifest.permission.POST_NOTIFICATIONS)
            val missing = wanted.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), REQUEST_PERMISSIONS)
                return
            }
        }
        CameraService.start(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQUEST_PERMISSIONS) return
        val cameraIndex = permissions.indexOf(Manifest.permission.CAMERA)
        val cameraOk = cameraIndex < 0 || grantResults.getOrNull(cameraIndex) == PackageManager.PERMISSION_GRANTED
        if (cameraOk) CameraService.start(this) else toast("Camera permission is necessary")
        main.postDelayed({ refresh() }, 500)
    }

    private fun saveCredentials(username: String, password: String) {
        val patch = mapOf("username" to username, "password" to password)
        when (val r = SettingsRules.applyPatch(store.load(), patch, Capabilities(emptyMap()), fromWeb = false)) {
            is PatchResult.Invalid -> toast(r.reason)
            is PatchResult.Ok -> {
                store.save(r.settings)
                CameraService.reload(this)
                toast(if (r.settings.authEnabled) "Login saved" else "Password removed")
                refresh()
            }
        }
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_SHORT).show()

    companion object {
        private const val REQUEST_PERMISSIONS = 1
    }
}
