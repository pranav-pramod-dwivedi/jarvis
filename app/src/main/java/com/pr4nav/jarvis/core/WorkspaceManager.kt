package com.pr4nav.jarvis.core

import android.content.Context
import java.io.File

/**
 * Explicit Workspace Manager.
 * Eliminates magic hardcoded workspace locations and ensures active workspace is validated and writable.
 */
object WorkspaceManager {

    private const val PREFS_NAME = "jarvis_workspaces"
    private const val KEY_ACTIVE = "active_workspace"
    private const val KEY_RECENTS = "recent_workspaces"

    data class ValidationResult(
        val valid: Boolean,
        val path: String,
        val isDirectory: Boolean,
        val isWritable: Boolean,
        val error: String? = null
    )

    fun getDefaultWorkspace(context: Context): String {
        val ext = EnvironmentManager.sharedStorageDir()
        return if (ext.exists() && ext.canWrite()) {
            File(ext, "Projects").apply { if (!exists()) mkdirs() }.absolutePath
        } else {
            File(context.filesDir, "workspace").apply { if (!exists()) mkdirs() }.absolutePath
        }
    }

    fun getActiveWorkspace(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_ACTIVE, null)
        if (!saved.isNullOrBlank()) {
            val v = validateWorkspace(saved)
            if (v.valid) return saved
        }
        val def = getDefaultWorkspace(context)
        setActiveWorkspace(context, def)
        return def
    }

    fun setActiveWorkspace(context: Context, path: String): ValidationResult {
        val v = validateWorkspace(path)
        if (v.valid) {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_ACTIVE, path).apply()
            addRecent(context, path)
        }
        return v
    }

    fun validateWorkspace(path: String): ValidationResult {
        try {
            val f = File(path)
            if (!f.exists()) {
                val created = f.mkdirs()
                if (!created && !f.exists()) {
                    return ValidationResult(false, path, isDirectory = false, isWritable = false, error = "Directory does not exist and cannot be created")
                }
            }
            if (!f.isDirectory) {
                return ValidationResult(false, path, isDirectory = false, isWritable = false, error = "Path is a file, not a directory")
            }
            // Test writability safely with a probe
            val probe = File(f, ".jarvis_write_probe_${System.currentTimeMillis()}.tmp")
            val canWrite = try {
                probe.writeText("OK")
                val ok = probe.readText() == "OK"
                probe.delete()
                ok
            } catch (_: Exception) {
                false
            }

            if (!canWrite) {
                return ValidationResult(false, path, isDirectory = true, isWritable = false, error = "Directory is read-only or permission denied")
            }

            return ValidationResult(true, path, isDirectory = true, isWritable = true)
        } catch (e: Exception) {
            return ValidationResult(false, path, isDirectory = false, isWritable = false, error = e.message ?: "Validation exception")
        }
    }

    fun getRecentWorkspaces(context: Context): List<String> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_RECENTS, "") ?: ""
        return raw.split(";").filter { it.isNotBlank() }
    }

    private fun addRecent(context: Context, path: String) {
        val current = getRecentWorkspaces(context).toMutableList()
        current.remove(path)
        current.add(0, path)
        val trimmed = current.take(5).joinToString(";")
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_RECENTS, trimmed).apply()
    }
}
