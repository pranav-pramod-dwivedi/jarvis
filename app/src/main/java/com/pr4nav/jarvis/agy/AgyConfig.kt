package com.pr4nav.jarvis.agy

data class AgyConfig(
    val baseUrl: String = DEFAULT_BASE_URL,
    val port: Int = DEFAULT_PORT,
    val defaultModel: String = "default",
    val defaultMode: String = "default",
    val connectTimeoutMs: Int = 5000,
    val readTimeoutMs: Int = 120_000
) {
    companion object {
        const val DEFAULT_PORT = 5050
        const val DEFAULT_BASE_URL = "http://127.0.0.1:5050"
    }
}
