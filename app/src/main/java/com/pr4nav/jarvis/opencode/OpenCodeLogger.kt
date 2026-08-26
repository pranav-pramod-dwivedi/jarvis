package com.pr4nav.jarvis.opencode

object OpenCodeLogger {

    fun interface Sink {
        fun log(level: Int, tag: String, msg: String, tr: Throwable?)
    }

    const val VERBOSE = 2
    const val DEBUG = 3
    const val INFO = 4
    const val WARN = 5
    const val ERROR = 6

    @Volatile
    var level: Int = INFO

    val tagPrefix = "OC/"

    @Volatile
    private var sink: Sink = tryResolveAndroidSink()

    fun setSink(s: Sink) {
        sink = s
    }

    fun d(tag: String, msg: String, tr: Throwable? = null) = log(DEBUG, tag, msg, tr)
    fun i(tag: String, msg: String, tr: Throwable? = null) = log(INFO, tag, msg, tr)
    fun w(tag: String, msg: String, tr: Throwable? = null) = log(WARN, tag, msg, tr)
    fun e(tag: String, msg: String, tr: Throwable? = null) = log(ERROR, tag, msg, tr)

    private fun log(level: Int, tag: String, msg: String, tr: Throwable?) {
        if (level < this.level) return
        try {
            sink.log(level, tagPrefix + tag, msg, tr)
        } catch (_: Throwable) {
        }
    }

    private fun tryResolveAndroidSink(): Sink {
        return try {
            val cls = Class.forName("android.util.Log")
            val m = cls.getMethod("println", Int::class.javaPrimitiveType, String::class.java, String::class.java)
            return Sink { lv, tg, msg, tr ->
                val line = if (tr != null) "$msg · ${tr.javaClass.simpleName}: ${tr.message}" else msg
                m.invoke(null, lv, tg, line)
            }
        } catch (_: Throwable) {
            Sink { lv, tg, msg, tr ->
                val name = when (lv) {
                    DEBUG -> "D"
                    INFO -> "I"
                    WARN -> "W"
                    ERROR -> "E"
                    else -> "V"
                }
                println(name + "/" + tg + ": " + msg + (tr?.let { " · " + it.javaClass.simpleName } ?: ""))
            }
        }
    }
}
