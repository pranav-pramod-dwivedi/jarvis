package com.pr4nav.jarvis.opencode

class OpenCodeException(
    val code: Code,
    message: String,
    cause: Throwable? = null,
    val httpStatus: Int? = null,
    val detail: String? = null
) : Exception(message, cause) {

    enum class Code {
        UNAVAILABLE,
        AUTH,
        TIMEOUT,
        NOT_FOUND,
        BAD_REQUEST,
        SERVER,
        NETWORK_IO,
        MALFORMED,
        UNSUPPORTED,
        BUSY,
        PROCESS
    }

    val isTransient: Boolean
        get() = code == Code.UNAVAILABLE || code == Code.TIMEOUT ||
            code == Code.NETWORK_IO || code == Code.SERVER

    override fun toString(): String =
        "OpenCodeException(${code}${httpStatus?.let { ",http=$it" } ?: ""}: $message)"

    companion object {
        fun unavailable(msg: String, cause: Throwable? = null) =
            OpenCodeException(Code.UNAVAILABLE, msg, cause)
        fun auth(msg: String = "Authentication failed") =
            OpenCodeException(Code.AUTH, msg)
        fun timeout(msg: String) = OpenCodeException(Code.TIMEOUT, msg)
        fun malformed(msg: String, cause: Throwable? = null) =
            OpenCodeException(Code.MALFORMED, msg, cause)
        fun busy(msg: String) = OpenCodeException(Code.BUSY, msg)
        fun unsupported(what: String) =
            OpenCodeException(Code.UNSUPPORTED, "Unsupported by this OpenCode build: $what")
    }
}
