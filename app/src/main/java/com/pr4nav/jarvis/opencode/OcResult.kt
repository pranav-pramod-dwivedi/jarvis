package com.pr4nav.jarvis.opencode

sealed class OcResult<out T> {

    data class Ok<T>(val value: T) : OcResult<T>()
    data class Err(val error: OpenCodeException) : OcResult<Nothing>()

    val isOk: Boolean get() = this is Ok

    fun getOrNull(): T? = (this as? Ok)?.value

    fun errorOrNull(): OpenCodeException? = (this as? Err)?.error

    inline fun <R> map(transform: (T) -> R): OcResult<R> = when (this) {
        is Ok -> Ok(transform(value))
        is Err -> this
    }

    inline fun onOk(block: (T) -> Unit): OcResult<T> {
        if (this is Ok) block(value)
        return this
    }

    inline fun onErr(block: (OpenCodeException) -> Unit): OcResult<T> {
        if (this is Err) block(error)
        return this
    }

    fun getOrThrow(): T = when (this) {
        is Ok -> value
        is Err -> throw error
    }

    companion object {
        fun <T> ok(value: T): OcResult<T> = Ok(value)
        fun err(error: OpenCodeException): OcResult<Nothing> = Err(error)
    }
}

inline fun <T> ocTry(tag: String, block: () -> T): OcResult<T> = try {
    OcResult.Ok(block())
} catch (e: OpenCodeException) {
    OpenCodeLogger.w(tag, e.message ?: e.code.name)
    OcResult.Err(e)
} catch (e: Exception) {
    OpenCodeLogger.e(tag, "unexpected: ${e.message}", e)
    OcResult.Err(OpenCodeException(OpenCodeException.Code.NETWORK_IO, e.message ?: "unexpected", e))
}
