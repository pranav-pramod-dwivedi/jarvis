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

    fun readable(path: String): Boolean {
        if (path.startsWith("saf:/")) return true
        val p = normalize(path)
        if (p.startsWith(TERMUX_HOME)) return true
        if (p.startsWith(PRIMARY)) return true
        if (p == "/sdcard" || p.startsWith("/sdcard/")) return true
        if (p.startsWith(APP_DATA) || p.startsWith("/data/user/0/com.pr4nav.jarvis")) return true
        return false
    }

    fun writableWithoutRoot(path: String): Boolean = readable(path)
}
