package com.pr4nav.jarvis.capabilities

import android.content.Intent
import androidx.core.content.FileProvider
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.SessionState
import com.pr4nav.jarvis.tools.PathPolicy
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONArray
import org.json.JSONObject

object FileCapability : Capability {

    override val name = "files"

    private fun entryJson(e: Fs.Entry): JSONObject = JSONObject()
        .put("name", e.name).put("path", e.path).put("dir", e.isDir)
        .put("size", e.size).put("modified", e.modified)
        .put("mime", mimeOf(e))

    fun mimeOf(e: Fs.Entry): String =
        if (e.isDir) "inode/directory"
        else {
            val ext = e.name.trim().substringAfterLast('.', "").trim().lowercase()
            android.webkit.MimeTypeMap.getSingleton()
                .getMimeTypeFromExtension(ext)
                ?: "application/octet-stream"
        }

    fun list(path: String): CapabilityResult = guard(path) {
        val items = Fs.list(it)
        CapabilityResult.ok(
            JSONArray().apply { for (e in items) put(entryJson(e)) }.toString(),
            "count" to items.size.toString(), "backend" to Fs.backendFor(it).id.name
        )
    }

    fun read(path: String): CapabilityResult = guard(path) {
        val content = Fs.read(it)
        CapabilityResult.ok(
            JSONObject().put("path", it).put("content", content)
                .put("chars", content.length).toString(),
            "backend" to Fs.backendFor(it).id.name
        )
    }

    fun write(path: String, content: String, append: Boolean): CapabilityResult {
        val p = PathPolicy.resolve(SessionState.dir, path)
        if (!Fs.hasAllFiles && !p.startsWith(PathPolicy.APP_DATA) &&
            !p.startsWith(Capabilities.require().getExternalFilesDir(null)?.absolutePath ?: "/x")
        ) return CapabilityResult.fail(
            "Writing outside app storage needs All-Files-Access. Grant it in JARVIS → PERMISSIONS."
        )
        return try {
            Fs.write(p, content, append)
            CapabilityResult.ok(
                JSONObject().put("path", p).put("bytes", content.toByteArray().size).toString()
            )
        } catch (e: Exception) { fail(e) }
    }

    fun create(path: String): CapabilityResult = guard(path, writeGate = true) {
        Fs.create(it); CapabilityResult.ok(JSONObject().put("path", it).toString())
    }

    fun mkdir(path: String): CapabilityResult = guard(path, writeGate = true) {
        Fs.mkdir(it); CapabilityResult.ok(JSONObject().put("path", it).toString())
    }

    fun rename(from: String, to: String): CapabilityResult = guard(from, writeGate = true) { src ->
        val dst = PathPolicy.resolve(SessionState.dir, to)
        Fs.rename(src, dst)
        CapabilityResult.ok(JSONObject().put("from", src).put("to", dst).toString())
    }

    fun move(src: String, dst: String): CapabilityResult = guard(src, writeGate = true) { s ->
        val d = PathPolicy.resolve(SessionState.dir, dst)
        Fs.move(s, d)
        CapabilityResult.ok(JSONObject().put("from", s).put("to", d).toString())
    }

    fun copy(src: String, dst: String): CapabilityResult = guard(src, writeGate = true) { s ->
        val d = PathPolicy.resolve(SessionState.dir, dst)
        Fs.copy(s, d)
        CapabilityResult.ok(JSONObject().put("from", s).put("to", d).toString())
    }

    fun delete(path: String): CapabilityResult = guard(path, writeGate = true) {
        Fs.delete(it); CapabilityResult.ok(JSONObject().put("deleted", it).toString())
    }

    fun search(rootPath: String, query: String, max: Int): CapabilityResult = guard(rootPath) { r ->
        val hits = Fs.search(r, query, max.coerceIn(1, 500))
        CapabilityResult.ok(
            JSONArray().apply { for (e in hits) put(entryJson(e)) }.toString(),
            "count" to hits.size.toString(), "root" to r
        )
    }

    fun stat(path: String): CapabilityResult = guard(path) {
        val e = Fs.stat(it)
        CapabilityResult.ok(entryJson(e).toString(), "backend" to Fs.backendFor(it).id.name)
    }

    fun open(path: String): CapabilityResult = guard(path) { p ->
        val ctx = Capabilities.require()
        val file = java.io.File(p)
        if (!file.exists()) return@guard CapabilityResult.fail("Not found: $p")
        val uri = FileProvider.getUriForFile(ctx, "com.pr4nav.jarvis.fileprovider", file)
        val i = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, mimeOf(Fs.stat(p)))
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
        CapabilityResult.ok(JSONObject().put("opened", p).toString())
    }

    private fun guard(path: String, writeGate: Boolean = false, block: (String) -> CapabilityResult): CapabilityResult {
        val resolved = PathPolicy.resolve(SessionState.dir, path)
        if (!writeGate && !resolved.startsWith("saf:/") && !PathPolicy.readable(resolved))
            return CapabilityResult.fail(
                "Path not accessible to JARVIS: $resolved (allowed: shared storage, Termux home, saf://)"
            )
        return try {
            block(resolved)
        } catch (e: Fs.FsException) { CapabilityResult.fail(e.message ?: "filesystem error") }
        catch (e: Exception) { CapabilityResult.fail(e.message ?: "filesystem error") }
    }

    private fun fail(e: Exception) = CapabilityResult.fail(e.message ?: "write failed")

    override fun available(): Boolean = true
    override fun permitted(): Boolean = Fs.hasAllFiles
    override fun status(): String =
        "✓ Files — ${Fs.accessLevel} · backend router ready"

    override fun tools() = listOf(
        ToolDef("file.list", "List a directory", """{"path":"..."}""", null,
            { a -> list(a.optString("path", SessionState.dir)).envelope() }),
        ToolDef("file.read", "Read a text file", """{"path":"..."}""",
            { null }, { a -> read(a.getString("path")).envelope() }),
        ToolDef("file.write", "Write text to a file (creates/overwrites)", """{"path":"...","content":"...","append":false}""",
            { null }, { a -> write(a.getString("path"), a.optString("content", ""), a.optBoolean("append", false)).envelope() }),
        ToolDef("file.create", "Create an empty file", """{"path":"..."}""",
            { null }, { a -> create(a.getString("path")).envelope() }),
        ToolDef("file.mkdir", "Create a directory", """{"path":"..."}""",
            { null }, { a -> mkdir(a.getString("path")).envelope() }),
        ToolDef("file.rename", "Rename a file/folder", """{"from":"...","to":"..."}""",
            { null }, { a -> rename(a.getString("from"), a.getString("to")).envelope() }),
        ToolDef("file.copy", "Copy file/folder", """{"src":"...","dst":"..."}""",
            { null }, { a -> copy(a.getString("src"), a.getString("dst")).envelope() }),
        ToolDef("file.move", "Move file/folder", """{"src":"...","dst":"..."}""",
            { null }, { a -> move(a.getString("src"), a.getString("dst")).envelope() }),
        ToolDef("file.delete", "Delete file/folder", """{"path":"..."}""",
            { null }, { a -> delete(a.getString("path")).envelope() }),
        ToolDef("file.search", "Search files by name under a root", """{"root":"...","query":"...","max":50}""",
            { null },
            { a -> search(a.optString("root", SessionState.dir), a.optString("query", ""), a.optInt("max", 50)).envelope() }),
        ToolDef("file.stat", "File metadata (size/mime/modified)", """{"path":"..."}""",
            { null }, { a -> stat(a.getString("path")).envelope() }),
        ToolDef("file.open", "Open a file in the best matching Android app", """{"path":"..."}""",
            { null }, { a -> open(a.getString("path")).envelope() })
    )
}
