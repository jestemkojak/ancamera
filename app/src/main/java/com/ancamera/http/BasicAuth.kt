package com.ancamera.http

import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object BasicAuth {
    /** True when auth is off (both empty) or the Authorization header has the right credentials. */
    @OptIn(ExperimentalEncodingApi::class)
    fun isAuthorized(header: String?, username: String, password: String): Boolean {
        if (username.isEmpty() && password.isEmpty()) return true
        if (header == null || !header.regionMatches(0, "Basic ", 0, 6, ignoreCase = true)) return false
        val decoded = try {
            Base64.decode(header.substring(6).trim()).toString(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            return false
        }
        val colon = decoded.indexOf(':')
        if (colon < 0) return false
        // Compare both parts every time, so the time taken does not show which part was wrong.
        val userOk = same(decoded.substring(0, colon), username)
        val passOk = same(decoded.substring(colon + 1), password)
        return userOk and passOk
    }

    private fun same(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(Charsets.UTF_8), b.toByteArray(Charsets.UTF_8))
}
