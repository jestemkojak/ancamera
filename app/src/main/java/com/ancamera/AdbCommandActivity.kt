package com.ancamera

import android.app.Activity
import android.os.Bundle
import android.util.Log
import com.ancamera.settings.Capabilities
import com.ancamera.settings.PatchResult
import com.ancamera.settings.Settings
import com.ancamera.settings.SettingsRules
import com.ancamera.settings.SettingsStore

/**
 * Runs adb commands from scripts/device-test.sh: reset settings, or set the login.
 * It has no UI. The DUMP permission means only the adb shell (and the system) can start it.
 */
class AdbCommandActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            val store = SettingsStore(this)
            var changed = false
            if (intent.getBooleanExtra("resetSettings", false)) {
                store.save(Settings())
                changed = true
            }
            if (intent.hasExtra("username") || intent.hasExtra("password")) {
                val username = intent.getStringExtra("username") ?: ""
                val password = intent.getStringExtra("password") ?: ""
                val patch = mapOf("username" to username, "password" to password)
                when (val r = SettingsRules.applyPatch(store.load(), patch, Capabilities(emptyMap()), fromWeb = false)) {
                    is PatchResult.Invalid -> Log.w(TAG, "login not saved: ${r.reason}")
                    is PatchResult.Ok -> {
                        store.save(r.settings)
                        changed = true
                    }
                }
            }
            if (changed) CameraService.reload(this)
        }
        finish()
    }

    companion object {
        private const val TAG = "AdbCommandActivity"
    }
}
