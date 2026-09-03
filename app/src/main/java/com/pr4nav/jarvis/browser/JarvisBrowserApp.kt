package com.pr4nav.jarvis.browser

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Metadata and runtime representation of a dynamic JarvisBrowser application.
 */
data class JarvisBrowserApp(
    val id: String,
    val title: String,
    val description: String,
    val entryPath: String,
    val directory: File,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val tags: List<String> = emptyList(),
    val isTemporary: Boolean = false,
    val icon: String = "⚡",
    val version: String = "1.0.0"
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("title", title)
        put("description", description)
        put("entry_path", entryPath)
        put("directory", directory.absolutePath)
        put("created_at", createdAt)
        put("updated_at", updatedAt)
        put("is_temporary", isTemporary)
        put("icon", icon)
        put("version", version)
        val tagsArr = JSONArray()
        tags.forEach { tagsArr.put(it) }
        put("tags", tagsArr)
    }

    companion object {
        fun fromManifest(dir: File, isTemp: Boolean = false): JarvisBrowserApp? {
            val manifestFile = File(dir, "manifest.json")
            val indexFile = File(dir, "index.html")
            if (!indexFile.exists()) return null

            return try {
                val json = if (manifestFile.exists()) JSONObject(manifestFile.readText()) else JSONObject()
                val id = json.optString("id", dir.name)
                val title = json.optString("title", dir.name.replace("-", " ").replaceFirstChar { it.uppercase() })
                val description = json.optString("description", "Dynamic JarvisBrowser Application")
                val createdAt = json.optLong("created_at", dir.lastModified())
                val updatedAt = json.optLong("updated_at", dir.lastModified())
                val icon = json.optString("icon", "⚡")
                val version = json.optString("version", "1.0.0")
                val tagsList = mutableListOf<String>()
                val tagsJson = json.optJSONArray("tags")
                if (tagsJson != null) {
                    for (i in 0 until tagsJson.length()) {
                        tagsList.add(tagsJson.getString(i))
                    }
                }

                JarvisBrowserApp(
                    id = id,
                    title = title,
                    description = description,
                    entryPath = indexFile.absolutePath,
                    directory = dir,
                    createdAt = createdAt,
                    updatedAt = updatedAt,
                    tags = tagsList,
                    isTemporary = isTemp,
                    icon = icon,
                    version = version
                )
            } catch (_: Exception) {
                null
            }
        }
    }
}
