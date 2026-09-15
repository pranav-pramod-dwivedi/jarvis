package com.pr4nav.jarvis.tools

object PathPolicy {

    const val PRIMARY = "/storage/emulated/0"
    const val TERMUX_HOME = "/data/data/com.termux/files/home"
    const val APP_DATA = "/data/data/com.pr4nav.jarvis"
    const val APP_EXTERNAL = "$PRIMARY/Android/data/com.pr4nav.jarvis/files"

    fun resolve(cwd: String, raw: String): String {
        var p = raw.trim().trim('"')
        if (p.isEmpty()) return normalize(cwd)
        if (p == "~" || p == "~/") return TERMUX_HOME
        if (p.startsWith("~/")) p = TERMUX_HOME + "/" + p.removePrefix("~/")
        if (p == "saf://" || p.startsWith("saf://")) return p
        if (!p.startsWith("/")) p = (if (cwd.endsWith("/")) cwd else "$cwd/") + p
        return normalize(p)
    }

    fun normalize(path: String): String {
        val out = ArrayList<String>()
        for (seg in path.split("/")) {
            when (seg) {
                "", "." -> {}
                ".." -> if (out.isNotEmpty()) out.removeAt(out.size - 1)
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    private fun isWithin(path: String, base: String): Boolean {
        val cleanBase = base.trimEnd('/')
        return path == cleanBase || path.startsWith("$cleanBase/")
    }

    fun readable(path: String): Boolean {
        if (path.startsWith("saf:/")) return true
        val p = normalize(path)
        if (isWithin(p, TERMUX_HOME)) return true
        if (isWithin(p, PRIMARY)) return true
        if (isWithin(p, "/sdcard")) return true
        if (isWithin(p, APP_DATA) || isWithin(p, "/data/user/0/com.pr4nav.jarvis")) return true
        return false
    }

    fun writableWithoutRoot(path: String): Boolean = readable(path)
}
