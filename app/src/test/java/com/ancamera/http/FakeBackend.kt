package com.ancamera.http

class FakeBackend(
    var user: String = "",
    var pass: String = "",
    var frame: ByteArray? = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()),
) : HttpBackend {
    var lastPatch: String? = null

    override fun credentials() = user to pass
    override fun statusJson() = """{"state":"streaming"}"""
    override fun settingsJson() = """{"settings":{}}"""
    override fun updateSettings(body: String): HttpResponse {
        lastPatch = body
        return HttpResponse.json(200, """{"ok":true}""")
    }
    override fun jpegFrame(timeoutMs: Long): ByteArray? = frame
}
