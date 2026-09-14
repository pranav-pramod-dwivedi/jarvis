package com.pr4nav.jarvis.artifacts

import android.content.Context
import android.os.Environment
import android.util.Log
import com.pr4nav.jarvis.browser.JarvisBrowserAppManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ArtifactRecord(
    val id: String,
    val title: String,
    val type: String, // UI_APP, DASHBOARD, SIMULATION, CHART, CODE, DOCUMENT
    val filePath: String,
    val createdAt: Long,
    val updatedAt: Long,
    val summary: String,
    val tags: List<String> = emptyList()
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("type", type)
        put("file_path", filePath)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("summary", summary)
        put("tags", JSONArray(tags))
    }

    companion object {
        fun fromJson(json: JSONObject): ArtifactRecord {
            val tagsList = mutableListOf<String>()
            val tagsArr = json.optJSONArray("tags")
            if (tagsArr != null) {
                for (i in 0 until tagsArr.length()) {
                    tagsList.add(tagsArr.optString(i))
                }
            }
            return ArtifactRecord(
                id = json.optString("id"),
                title = json.optString("title", "Artifact"),
                type = json.optString("type", "DOCUMENT"),
                filePath = json.optString("file_path"),
                createdAt = json.optLong("created_at", System.currentTimeMillis()),
                updatedAt = json.optLong("updated_at", System.currentTimeMillis()),
                summary = json.optString("summary", ""),
                tags = tagsList
            )
        }
    }
}

/**
 * Manages durable storage, indexing, discovery, and Markdown export of all JARVIS-generated
 * interactive artifacts, UI mini-apps, widgets, simulations, and documents.
 *
 * Primary storage: /sdcard/JARVIS/artifacts/
 * Master Index:    /sdcard/JARVIS/artifacts/artifacts_index.json
 */
object JarvisArtifactManager {

    private const val TAG = "JarvisArtifactMgr"
    private const val INDEX_FILE = "artifacts_index.json"

    fun getArtifactsDir(context: Context): File {
        val ext = Environment.getExternalStorageDirectory()
        val dir = if (ext != null && ext.canWrite()) {
            File(ext, "JARVIS/artifacts")
        } else {
            File(context.filesDir, "JARVIS/artifacts")
        }
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun getIndexFile(context: Context): File {
        return File(getArtifactsDir(context), INDEX_FILE)
    }

    @Synchronized
    fun saveArtifact(
        context: Context,
        id: String,
        title: String,
        type: String,
        content: String,
        fileExtension: String = "html",
        summary: String = "",
        tags: List<String> = emptyList()
    ): ArtifactRecord {
        val cleanId = id.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty {
            "artifact-${System.currentTimeMillis()}"
        }
        val dir = getArtifactsDir(context)
        val ext = fileExtension.removePrefix(".").ifEmpty { "html" }
        val targetFile = File(dir, "$cleanId.$ext")
        try {
            targetFile.writeText(content)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write artifact file: ${e.message}", e)
        }

        val record = ArtifactRecord(
            id = cleanId,
            title = title.ifBlank { cleanId },
            type = type,
            filePath = targetFile.absolutePath,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            summary = summary.ifBlank { "Generated $type: $title" },
            tags = tags
        )

        updateIndex(context, record)
        return record
    }

    @Synchronized
    fun registerExistingFile(
        context: Context,
        id: String,
        title: String,
        type: String,
        file: File,
        summary: String = "",
        tags: List<String> = emptyList()
    ): ArtifactRecord {
        val record = ArtifactRecord(
            id = id,
            title = title,
            type = type,
            filePath = file.absolutePath,
            createdAt = file.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
            summary = summary.ifBlank { "Registered $type: $title" },
            tags = tags
        )
        updateIndex(context, record)
        return record
    }

    @Synchronized
    private fun updateIndex(context: Context, record: ArtifactRecord) {
        val all = listArtifacts(context).toMutableList()
        val existingIndex = all.indexOfFirst { it.id == record.id }
        if (existingIndex >= 0) {
            all[existingIndex] = record
        } else {
            all.add(0, record)
        }

        try {
            val arr = JSONArray()
            all.forEach { arr.put(it.toJson()) }
            getIndexFile(context).writeText(arr.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "Failed writing artifacts index: ${e.message}", e)
        }
    }

    @Synchronized
    fun listArtifacts(context: Context): List<ArtifactRecord> {
        val records = mutableListOf<ArtifactRecord>()
        val indexFile = getIndexFile(context)
        if (indexFile.exists()) {
            try {
                val text = indexFile.readText()
                val arr = JSONArray(text)
                for (i in 0 until arr.length()) {
                    records.add(ArtifactRecord.fromJson(arr.getJSONObject(i)))
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error parsing artifacts index: ${e.message}")
            }
        }

        // Also aggregate apps from JarvisBrowserAppManager so browser apps are always indexed
        try {
            val browserApps = JarvisBrowserAppManager.listApps(context)
            for (app in browserApps) {
                if (records.none { it.id == app.id }) {
                    records.add(
                        ArtifactRecord(
                            id = app.id,
                            title = app.title,
                            type = "UI_APP",
                            filePath = app.entryPath,
                            createdAt = app.createdAt,
                            updatedAt = app.updatedAt,
                            summary = app.description,
                            tags = app.tags
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        return records.sortedByDescending { it.updatedAt }
    }

    fun getArtifact(context: Context, id: String): ArtifactRecord? {
        val cleanId = id.trim().lowercase()
        return listArtifacts(context).firstOrNull { it.id.equals(cleanId, ignoreCase = true) }
    }

    @Synchronized
    fun deleteArtifact(context: Context, id: String): Boolean {
        val cleanId = id.trim()
        return try {
            val all = listArtifacts(context).toMutableList()
            val target = all.firstOrNull { it.id == cleanId } ?: return false
            try {
                val f = java.io.File(target.filePath)
                if (f.exists() && f.parent?.contains("artifacts") == true) f.delete()
            } catch (_: Exception) { }
            all.remove(target)
            val arr = JSONArray()
            // Only persist index entries that live in our own dir; browser apps re-aggregate live.
            val ownDir = getArtifactsDir(context).absolutePath
            all.filter { it.filePath.startsWith(ownDir) }.forEach { arr.put(it.toJson()) }
            getIndexFile(context).writeText(arr.toString(2))
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed deleting artifact: ${e.message}", e)
            false
        }
    }

    /**
     * Produces a clean, formatted Markdown table of all artifacts for inclusion
     * in the persistent session context archive.
     */
    fun getArtifactsMarkdownTable(context: Context): String {
        val artifacts = listArtifacts(context)
        if (artifacts.isEmpty()) {
            return "_No generated artifacts or UI apps yet._\n"
        }

        val sb = StringBuilder()
        sb.append("| Date | Artifact ID | Title | Type | Path | Description |\n")
        sb.append("|---|---|---|---|---|---|\n")

        for (a in artifacts.take(25)) {
            val dateStr = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(a.updatedAt))
            val cleanTitle = a.title.replace("|", "/")
            val cleanDesc = a.summary.take(60).replace("|", "/").replace("\n", " ")
            sb.append("| $dateStr | `${a.id}` | $cleanTitle | ${a.type} | `${a.filePath}` | $cleanDesc |\n")
        }
        return sb.toString()
    }
}
