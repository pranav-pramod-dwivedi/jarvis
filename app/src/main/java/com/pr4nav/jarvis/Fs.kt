package com.pr4nav.jarvis

import android.content.Context
import android.content.SharedPreferences
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

/**
 * JARVIS unified filesystem layer.
 * One interface for the UI and the agent. Backends are selected automatically.
 * The caller never needs to know whether an op used Java/SAF/Termux/root.
 */
object Fs {

    enum class B { JAVA, SAF, TERMUX, ROOT, NONE }

    data class Entry(
        val name: String, val path: String, val isDir: Boolean,
        val size: Long, val modified: Long, val hidden: Boolean
    ) {
        val mime: String
            get() = if (isDir) "inode/directory"
            else (MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
                ?: "application/octet-stream")
    }

    class FsException(msg: String) : Exception(msg)

    interface Backend {
        val id: B
        val available: Boolean
        val rootPath: String
        fun list(path: String): List<Entry>
        fun read(path: String): String
        fun write(path: String, content: String, append: Boolean = false)
        fun mkdir(path: String)
        fun create(path: String)
        fun delete(path: String)
        fun rename(from: String, to: String)
        fun copy(src: String, dst: String)
        fun move(src: String, dst: String)
        fun exists(path: String): Boolean
        fun stat(path: String): Entry
        fun search(root: String, query: String, max: Int = 100): List<Entry>
    }

    // ---------------- Java (java.io.File) backend ----------------
    // Full power when MANAGE_EXTERNAL_STORAGE is granted; app dirs always work.
    object Java : Backend {
        override val id = B.JAVA
        override val rootPath = "/storage/emulated/0"
        override val available get() = true

        private fun f(path: String) = File(path)
        private fun e(file: File) = Entry(
            file.name, file.absolutePath, file.isDirectory,
            if (file.isFile) file.length() else 0L, file.lastModified(), file.name.startsWith(".")
        )

        override fun list(path: String): List<Entry> {
            val d = f(path)
            if (!d.exists()) throw FsException("Not found: $path")
            if (!d.isDirectory) throw FsException("Not a directory: $path")
            if (!d.canRead()) throw FsException("Permission denied: $path")
            return d.listFiles()?.map { e(it) }?.sortedWith(
                compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() }
            ) ?: emptyList()
        }

        override fun read(path: String): String {
            val file = f(path)
            if (!file.exists()) throw FsException("Not found: $path")
            if (file.length() > 2_000_000) throw FsException("File too large to read inline (>2MB): $path")
            return file.readText()
        }

        override fun write(path: String, content: String, append: Boolean) {
            val file = f(path)
            file.parentFile?.mkdirs()
            if (!file.exists() && !file.createNewFile()) throw FsException("Cannot create: $path")
            if (!file.canWrite()) throw FsException("Permission denied: $path")
            if (append) file.appendText(content) else file.writeText(content)
        }

        override fun mkdir(path: String) {
            if (!f(path).mkdirs() && !f(path).isDirectory) throw FsException("Cannot create folder: $path")
        }

        override fun create(path: String) {
            val file = f(path)
            file.parentFile?.mkdirs()
            if (!file.createNewFile() && !file.exists()) throw FsException("Cannot create: $path")
        }

        override fun delete(path: String) {
            val file = f(path)
            if (!file.exists()) return
            if (!file.canWrite()) throw FsException("Permission denied: $path")
            if (file.isDirectory) file.deleteRecursively() else if (!file.delete()) throw FsException("Delete failed: $path")
        }

        override fun rename(from: String, to: String) {
            if (!f(from).renameTo(f(to))) throw FsException("Rename failed: $from -> $to")
        }

        override fun copy(src: String, dst: String) {
            val s = f(src); if (!s.exists()) throw FsException("Not found: $src")
            val d = f(dst)
            if (s.isDirectory) {
                d.mkdirs()
                s.listFiles()?.forEach { copy(it.absolutePath, File(d, it.name).absolutePath) }
            } else {
                d.parentFile?.mkdirs()
                FileInputStream(s).use { i -> FileOutputStream(d).use { o -> i.copyTo(o) } }
            }
        }

        override fun move(src: String, dst: String) {
            val s = f(src)
            if (!s.renameTo(f(dst))) { copy(src, dst); delete(src) }
        }

        override fun exists(path: String) = f(path).exists()

        override fun stat(path: String) = e(f(path)).let {
            if (!f(path).exists()) throw FsException("Not found: $path") else it
        }

        override fun search(root: String, query: String, max: Int): List<Entry> {
            val out = ArrayList<Entry>()
            val q = query.lowercase()
            val queue = ArrayDeque<File>()
            queue.add(f(root))
            while (out.size < max && queue.isNotEmpty()) {
                val d = queue.removeFirst()
                val kids = d.listFiles() ?: continue
                for (k in kids) {
                    if (k.name.lowercase().contains(q)) out.add(e(k))
                    if (k.isDirectory && !k.isHidden && out.size < max) queue.add(k)
                }
            }
            return out
        }

        fun storageInfo(): Pair<Long, Long> {
            val st = android.os.StatFs("/storage/emulated/0")
            return Pair(st.availableBytes, st.totalBytes)
        }
    }

    // ---------------- SAF backend (persisted tree URI) ----------------
    object Saf : Backend {
        override val id = B.SAF
        override val rootPath = "saf://tree"
        override var available = false
        private var treeUri: android.net.Uri? = null
        private lateinit var prefs: SharedPreferences

        fun init(ctx: Context) {
            prefs = ctx.getSharedPreferences("jarvis_fs", Context.MODE_PRIVATE)
            val s = prefs.getString("saf_tree", null)
            if (s != null) {
                treeUri = android.net.Uri.parse(s)
                available = true
            }
        }

        fun save(ctx: Context, uri: android.net.Uri) {
            val cr = ctx.contentResolver
            cr.takePersistableUriPermission(
                uri,
                android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            prefs.edit().putString("saf_tree", uri.toString()).apply()
            treeUri = uri; available = true
        }

        private fun df(path: String): androidx.documentfile.provider.DocumentFile? {
            val uri = treeUri ?: return null
            val rel = path.removePrefix("saf:/").trimStart('/')
            var dir = androidx.documentfile.provider.DocumentFile.fromTreeUri(app, uri) ?: return null
            if (rel.isEmpty()) return dir
            for (seg in rel.split("/").filter { it.isNotEmpty() }) {
                dir = dir.findFile(seg) ?: return null
            }
            return dir
        }

        private lateinit var app: Context
        fun setApp(ctx: Context) { app = ctx.applicationContext }

        override fun list(path: String): List<Entry> {
            val dir = df(path) ?: throw FsException("SAF location unavailable")
            return dir.listFiles().map {
                Entry(it.name ?: "?", "$path/${it.name}", it.isDirectory, it.length(), it.lastModified(), (it.name ?: "").startsWith("."))
            }.sortedWith(compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() })
        }

        override fun read(path: String): String {
            val f = df(path) ?: throw FsException("SAF file unavailable: $path")
            return app.contentResolver.openInputStream(f.uri)?.bufferedReader()?.use { it.readText() }
                ?: throw FsException("Cannot open: $path")
        }

        override fun write(path: String, content: String, append: Boolean) {
            var f = df(path)
            if (f == null) {
                val parent = df(path.substringBeforeLast('/')) ?: throw FsException("SAF parent unavailable")
                f = parent.createFile("text/plain", path.substringAfterLast('/')) ?: throw FsException("SAF create failed")
            }
            app.contentResolver.openOutputStream(f.uri, if (append) "wa" else "w")?.use {
                it.write(content.toByteArray())
            } ?: throw FsException("SAF write failed: $path")
        }

        override fun mkdir(path: String) {
            val parent = df(path.substringBeforeLast('/')) ?: throw FsException("SAF parent unavailable")
            parent.createDirectory(path.substringAfterLast('/')) ?: throw FsException("SAF mkdir failed")
        }

        override fun create(path: String) = write(path, "", false)

        override fun delete(path: String) {
            df(path)?.delete() ?: throw FsException("SAF delete failed: $path")
        }

        override fun rename(from: String, to: String) {
            val f = df(from) ?: throw FsException("SAF rename failed")
            f.renameTo(to.substringAfterLast('/'))
        }

        override fun copy(src: String, dst: String) = write(dst, read(src))
        override fun move(src: String, dst: String) { copy(src, dst); delete(src) }
        override fun exists(path: String) = df(path) != null

        override fun stat(path: String): Entry {
            val f = df(path) ?: throw FsException("Not found: $path")
            return Entry(f.name ?: "?", path, f.isDirectory, f.length(), f.lastModified(), (f.name ?: "").startsWith("."))
        }

        override fun search(root: String, query: String, max: Int): List<Entry> {
            val out = ArrayList<Entry>()
            val q = query.lowercase()
            val queue = ArrayDeque<String>(); queue.add(root)
            while (out.size < max && queue.isNotEmpty()) {
                val p = queue.removeFirst()
                try { for (e in list(p)) { if (e.name.lowercase().contains(q)) out.add(e); if (e.isDir) queue.add(e.path) } }
                catch (_: Exception) {}
            }
            return out
        }
    }

    // ---------------- Root backend (only when su is granted) ----------------
    object Root : Backend {
        override val id = B.ROOT
        override val rootPath = "/"
        var granted: Boolean? = null
        override val available get() = granted == true

        fun detect(): Boolean {
            return try {
                val p = ProcessBuilder("su", "-c", "id").start()
                val ok = p.inputStream.bufferedReader().readText().contains("uid=0")
                p.waitFor()
                granted = ok; ok
            } catch (_: Exception) { granted = false; false }
        }

        private fun sh(cmd: String): String {
            val r = Shell.root(cmd)
            return if (r.rc != 0 && r.err.isNotBlank()) "ERR: ${r.err}" else r.out
        }

        override fun list(path: String): List<Entry> {
            val out = sh("ls -1Apl --time-style=+%s '$path'")
            if (out.startsWith("ERR:")) throw FsException(out.removePrefix("ERR:"))
            return Termux.parseLs(out, path).sortedWith(
                compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() }
            )
        }

        override fun read(path: String): String {
            val out = sh("cat '$path'")
            if (out.startsWith("ERR:")) throw FsException(out.removePrefix("ERR:"))
            return out
        }

        override fun write(path: String, content: String, append: Boolean) {
            val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
            val op = if (append) ">>" else ">"
            val r = sh("echo '$b64' | base64 -d $op '$path'")
            if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:"))
        }

        override fun mkdir(path: String) { val r = sh("mkdir -p '$path'"); if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:")) }
        override fun create(path: String) { val r = sh("touch '$path'"); if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:")) }
        override fun delete(path: String) { val r = sh("rm -rf '$path'"); if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:")) }
        override fun rename(from: String, to: String) { val r = sh("mv '$from' '$to'"); if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:")) }
        override fun copy(src: String, dst: String) { val r = sh("cp -r '$src' '$dst'"); if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:")) }
        override fun move(src: String, dst: String) { val r = sh("mv '$src' '$dst'"); if (r.startsWith("ERR:")) throw FsException(r.removePrefix("ERR:")) }
        override fun exists(path: String) = !sh("test -e '$path' && echo Y").startsWith("ERR:") && sh("test -e '$path' && echo Y").contains("Y")
        override fun stat(path: String): Entry {
            val out = sh("stat -c '%F|%s|%Y|%n' '$path'")
            if (out.startsWith("ERR:")) throw FsException(out.removePrefix("ERR:"))
            val p = out.split("|")
            return Entry(p.getOrElse(3) { path }.substringAfterLast('/'), path, p.getOrElse(0) { "" } == "directory",
                p.getOrElse(1) { "0" }.toLongOrNull() ?: 0, (p.getOrElse(2) { "0" }.toLongOrNull() ?: 0) * 1000, false)
        }

        override fun search(root: String, query: String, max: Int): List<Entry> {
            val out = sh("find '$root' -name '*$query*' 2>/dev/null | head -$max")
            if (out.startsWith("ERR:")) throw FsException(out.removePrefix("ERR:"))
            return out.lines().filter { it.isNotBlank() }.map { p ->
                Entry(p.substringAfterLast('/'), p, false, 0, 0, false)
            }
        }
    }

    // ---------------- Termux backend (private Termux home via bridge) ----------------
    object Termux : Backend {
        override val id = B.TERMUX
        override val rootPath = "/data/data/com.termux/files/home"
        override val available get() = Shell.termuxReachable()

        private fun q(s: String) = "'" + s.replace("'", "'\\''") + "'"

        /** Parse `ls -1Apl --time-style=+%s` lines into entries with REAL size+date. */
        fun parseLs(out: String, path: String): List<Entry> =
            out.lines().filter { it.isNotBlank() && !it.startsWith("total ") }.mapNotNull { line ->
                val m = Regex("^([\\-dlbcps][rwxstST+-]+)\\s+\\d+\\s+\\S+\\s+\\S+\\s+(\\d+)\\s+(\\d+)\\s+(.+)$").find(line)
                    ?: return@mapNotNull null
                val perms = m.groupValues[1]
                val size = m.groupValues[2].toLongOrNull() ?: 0L
                val mod = m.groupValues[3].toLongOrNull() ?: 0L
                var name = m.groupValues[4].trim()
                val isDir = perms.startsWith("d") || name.endsWith("/")
                if (name.endsWith("/")) name = name.trimEnd('/')
                if (perms.startsWith("l")) name = name.substringBefore(" ->").trim()
                Entry(name, "$path/$name", isDir, if (isDir) 0L else size, mod * 1000L, name.startsWith("."))
            }

        override fun list(path: String): List<Entry> {
            val r = Shell.termux("ls -1Apl --time-style=+%s ${q(path)} 2>&1")
            if (r.rc != 0) throw FsException(r.err.ifBlank { r.out })
            return parseLs(r.out, path).sortedWith(
                compareByDescending<Entry> { it.isDir }.thenBy { it.name.lowercase() }
            )
        }

        override fun read(path: String): String {
            val r = Shell.termux("cat '$path' 2>&1")
            if (r.rc != 0) throw FsException(r.out.ifBlank { "read failed" })
            return r.out
        }

        override fun write(path: String, content: String, append: Boolean) {
            val b64 = android.util.Base64.encodeToString(content.toByteArray(), android.util.Base64.NO_WRAP)
            val op = if (append) ">>" else ">"
            val r = Shell.termux("echo '$b64' | base64 -d $op '$path'")
            if (r.rc != 0) throw FsException(r.out.ifBlank { "write failed" })
        }

        override fun mkdir(path: String) { Shell.termux("mkdir -p '$path'").let { if (it.rc != 0) throw FsException(it.out) } }
        override fun create(path: String) { Shell.termux("touch '$path'").let { if (it.rc != 0) throw FsException(it.out) } }
        override fun delete(path: String) { Shell.termux("rm -rf '$path'").let { if (it.rc != 0) throw FsException(it.out) } }
        override fun rename(from: String, to: String) { Shell.termux("mv '$from' '$to'").let { if (it.rc != 0) throw FsException(it.out) } }
        override fun copy(src: String, dst: String) { Shell.termux("cp -r '$src' '$dst'").let { if (it.rc != 0) throw FsException(it.out) } }
        override fun move(src: String, dst: String) { Shell.termux("mv '$src' '$dst'").let { if (it.rc != 0) throw FsException(it.out) } }
        override fun exists(path: String) = Shell.termux("test -e '$path' && echo Y").out.contains("Y")

        override fun stat(path: String): Entry {
            val out = Shell.termux("stat -c '%F|%s|%Y|%n' '$path' 2>&1").out
            val p = out.split("|")
            if (p.size < 4) throw FsException("stat failed: $path")
            return Entry(p[3].substringAfterLast('/'), path, p[0] == "directory", p[1].toLongOrNull() ?: 0, (p[2].toLongOrNull() ?: 0) * 1000, false)
        }

        override fun search(root: String, query: String, max: Int): List<Entry> {
            val out = Shell.termux("find '$root' -name '*$query*' 2>/dev/null | head -$max").out
            return out.lines().filter { it.isNotBlank() }.map { p -> Entry(p.substringAfterLast('/'), p, false, 0, 0, false) }
        }
    }

    // ---------------- Router ----------------
    private lateinit var appCtx: Context

    fun init(ctx: Context) {
        appCtx = ctx.applicationContext
        Saf.setApp(appCtx)
        Saf.init(appCtx)
        Root.detect()
    }

    /** Resolve relative paths against the shared cwd (browser + agent share SessionState.dir). */
    fun resolve(path: String): String {
        val p = path.trim()
        if (p.isEmpty()) return SessionState.dir
        if (p.startsWith("/") || p.startsWith("saf:")) return p
        return SessionState.dir.trimEnd('/') + "/" + p
    }

    /** Structured FS log + enriched errors at the single choke point every caller shares. */
    private fun <T> routed(op: String, path: String, block: () -> T): T {
        val b = backendFor(path)
        val t0 = System.currentTimeMillis()
        try {
            val r = block()
            android.util.Log.i("JARVIS_FSC", "$op ok backend=${b.id} path=$path ${System.currentTimeMillis() - t0}ms")
            return r
        } catch (e: FsException) {
            android.util.Log.w("JARVIS_FSC", "$op FAIL backend=${b.id} path=$path: ${e.message}")
            throw FsException("${e.message}\n[op=$op backend=${b.id} path=$path]")
        } catch (e: Exception) {
            android.util.Log.w("JARVIS_FSC", "$op FAIL backend=${b.id} path=$path: ${e.message}")
            throw FsException("Fs.$op failed: ${e.message}\n[path=$path backend=${b.id}]")
        }
    }

    /** Best backend for a given path. Agent never sees this detail. */
    fun backendFor(path: String): Backend = when {
        path.startsWith("/data/data/com.termux") -> Termux
        path.startsWith(Saf.rootPath) -> Saf
        path.startsWith("/storage/emulated/0") && hasAllFiles -> Java
        path.startsWith("/sdcard") -> Java
        Root.available -> Root
        else -> Java
    }

    val hasAllFiles: Boolean
        get() = android.os.Environment.isExternalStorageManager()

    val accessLevel: String
        get() = when {
            Root.available -> "Root Access"
            hasAllFiles -> "Full Storage Access"
            Saf.available -> "SAF Access"
            else -> "App-Private Access"
        }

    fun list(path: String) = routed("list", path) { backendFor(path).list(path) }
    fun read(path: String) = routed("read", path) { backendFor(path).read(path) }
    fun write(path: String, content: String, append: Boolean = false) = routed("write", path) { backendFor(path).write(path, content, append) }
    fun mkdir(path: String) = routed("mkdir", path) { backendFor(path).mkdir(path) }
    fun create(path: String) = routed("create", path) { backendFor(path).create(path) }
    fun delete(path: String) = routed("delete", path) { backendFor(path).delete(path) }
    fun rename(from: String, to: String) = routed("rename", from) { backendFor(from).rename(from, to) }
    fun copy(src: String, dst: String) = routed("copy", src) { backendFor(src).copy(src, dst) }
    fun move(src: String, dst: String) = routed("move", src) { backendFor(src).move(src, dst) }
    fun exists(path: String) = routed("exists", path) { backendFor(path).exists(path) }
    fun stat(path: String) = routed("stat", path) { backendFor(path).stat(path) }
    fun search(root: String, query: String, max: Int = 100) = routed("search", root) { backendFor(root).search(root, query, max) }
}
