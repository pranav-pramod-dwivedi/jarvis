package com.pr4nav.jarvis.opencode

data class OpenCodeConfig(
    val baseUrl: String = "http://127.0.0.1:4096",
    val username: String? = null,
    val password: String? = null,
    val connectTimeoutMs: Int = 4_000,
    val readTimeoutMs: Int = 20_000,
    val sseConnectTimeoutMs: Int = 6_000,
    val sseIdleTimeoutMs: Long = 35_000,
    val reconnectBaseDelayMs: Long = 1_000,
    val reconnectMaxDelayMs: Long = 15_000
) {
    val effectiveUsername: String get() = username ?: DEFAULT_USERNAME

    fun withBaseUrl(url: String): OpenCodeConfig = copy(baseUrl = url.trimEnd('/'))

    companion object {
        const val DEFAULT_USERNAME = "opencode"
        val DEFAULT_PORT_CANDIDATES = listOf(4096, 4097, 4098, 4099)

        fun basicAuthHeader(username: String, password: String): String {
            val raw = "$username:$password"
            val bytes = raw.toByteArray(Charsets.UTF_8)
            return "Basic " + androidAwareBase64(bytes, Base64Bridge.NO_WRAP)
        }

        fun androidAwareBase64(bytes: ByteArray, flags: Int = Base64Bridge.NO_WRAP): String =
            Base64Bridge.encode(bytes, flags)
    }
}

internal object Base64Bridge {
    const val NO_WRAP = 2

    fun encode(bytes: ByteArray, flags: Int = NO_WRAP): String {
        val b64 = try {
            val cls = Class.forName("android.util.Base64")
            val encodeToString = cls.getMethod(
                "encodeToString",
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            val noWrapField = cls.getField("NO_WRAP").getInt(null)
            val f = if (flags == NO_WRAP) noWrapField else flags
            encodeToString.invoke(null, bytes, f) as String
        } catch (_: Throwable) {
            java.util.Base64.getEncoder().encodeToString(bytes)
        }
        return b64
    }
}
