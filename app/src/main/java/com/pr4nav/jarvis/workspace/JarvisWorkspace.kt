package com.pr4nav.jarvis.workspace

import android.content.Context
import android.os.Environment
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.SessionState
import org.json.JSONObject
import java.io.File

sealed class WorkspaceValidationResult {
    data class Allowed(val resolvedPath: String) : WorkspaceValidationResult()
    data class Violation(
        val requested: String,
        val allowedRoot: String,
        val suggested: String,
        val reason: String
    ) : WorkspaceValidationResult() {
        fun toErrorJson(): JSONObject = JSONObject().apply {
            put("error", "WORKSPACE_BOUNDARY")
            put("requested", requested)
            put("allowed_root", allowedRoot)
            put("suggested", suggested)
            put("reason", reason)
        }
    }
}

/**
 * Canonical JARVIS Workspace & Hard Playground Boundary Manager.
 *
 * All agent-created projects, generated files, temporary work, experiments,
 * and agent playground content live under `/storage/emulated/0/JARVIS/` by default.
 */
object JarvisWorkspace {

    const val ROOT_DIR = "/storage/emulated/0/JARVIS"
    const val WORKSPACE_DIR = "$ROOT_DIR/workspace"
    const val PROJECTS_DIR = "$ROOT_DIR/projects"
    const val GENERATED_DIR = "$ROOT_DIR/generated"
    const val DOWNLOADS_DIR = "$ROOT_DIR/downloads"
    const val TEMP_DIR = "$ROOT_DIR/temp"
    const val LOGS_DIR = "$ROOT_DIR/logs"
    const val MEMORY_DIR = "$ROOT_DIR/memory"
    const val AGENTS_DIR = "$ROOT_DIR/agents"

    // Allowed storage trees for agent reading & user operations
    private val ALLOWED_STORAGE_PREFIXES = listOf(
        ROOT_DIR,
        "/storage/emulated/0/Download",
        "/storage/emulated/0/Documents",
        "/storage/emulated/0/DCIM",
        "/storage/emulated/0/Pictures",
        "/storage/emulated/0/Music",
        "/sdcard/JARVIS",
        "/sdcard/Download"
    )

    private val FORBIDDEN_PROJECT_ROOTS = listOf(
        "/root",
        "/tmp",
        "/home",
        "/data/local/tmp",
        "/system",
        "/data/data/com.termux/files/usr"
    )

    private var initialized = false

    /**
     * Initializes the canonical workspace tree on physical storage.
     * Guaranteed 1:1 synchronization between Agent, UI, and File Manager.
     */
    fun initWorkspace(context: Context? = null) {
        if (initialized) return

        try {
            val dirs = listOf(
                ROOT_DIR,
                WORKSPACE_DIR,
                PROJECTS_DIR,
                GENERATED_DIR,
                DOWNLOADS_DIR,
                TEMP_DIR,
                LOGS_DIR,
                MEMORY_DIR,
                AGENTS_DIR
            )

            for (dir in dirs) {
                val f = File(dir)
                if (!f.exists()) {
                    f.mkdirs()
                }
            }

            // Set global shared session directory to default workspace
            SessionState.dir = WORKSPACE_DIR
            initialized = true
        } catch (_: Exception) {
            // Fallback gracefully in restricted unit test environments
            SessionState.dir = WORKSPACE_DIR
            initialized = true
        }
    }

    /**
     * Normalizes any input path (relative, ~, ./, /root/...) into a safe path
     * anchored within the JARVIS workspace or allowed storage.
     */
    fun normalizePath(rawPath: String, cwd: String = SessionState.dir): String {
        val trimmed = rawPath.trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "./") {
            return cwd.ifBlank { WORKSPACE_DIR }
        }

        // Tilde expansion (~ -> WORKSPACE_DIR)
        if (trimmed == "~" || trimmed == "~/") {
            return WORKSPACE_DIR
        }
        if (trimmed.startsWith("~/")) {
            val sub = trimmed.removePrefix("~/").trimStart('/')
            return "$WORKSPACE_DIR/$sub"
        }

        // Handle forbidden /root, /tmp, /home project creation attempts
        for (forbidden in FORBIDDEN_PROJECT_ROOTS) {
            if (trimmed == forbidden) {
                return WORKSPACE_DIR
            }
            if (trimmed.startsWith("$forbidden/")) {
                val rel = trimmed.removePrefix("$forbidden/").trimStart('/')
                return "$WORKSPACE_DIR/$rel"
            }
        }

        // Absolute path within storage
        if (trimmed.startsWith("/storage/emulated/0/") || trimmed.startsWith("/sdcard/")) {
            return trimmed.replace("/sdcard/", "/storage/emulated/0/")
        }

        if (trimmed.startsWith("/")) {
            return trimmed
        }

        // Relative path anchored to cwd (or WORKSPACE_DIR)
        val base = if (cwd.isNotBlank()) cwd.trimEnd('/') else WORKSPACE_DIR
        val cleanRel = trimmed.removePrefix("./").trimStart('/')
        return "$base/$cleanRel"
    }

    /**
     * Validates path access against hard workspace boundaries.
     * Prevents agents from silently creating projects in /root, /, /tmp, etc.
     */
    fun validateAccess(path: String, isWrite: Boolean = false): WorkspaceValidationResult {
        val normalized = normalizePath(path)
        val canonical = try {
            java.io.File(normalized).canonicalPath
        } catch (_: Exception) {
            normalized
        }

        // If path explicitly targets forbidden system roots for project creation
        for (forbidden in FORBIDDEN_PROJECT_ROOTS) {
            if (path.trim() == forbidden || path.trim().startsWith("$forbidden/") ||
                canonical == forbidden || canonical.startsWith("$forbidden/")) {
                val sub = path.trim().removePrefix(forbidden).trimStart('/')
                val suggested = if (sub.isNotBlank()) "$WORKSPACE_DIR/$sub" else WORKSPACE_DIR
                return WorkspaceValidationResult.Violation(
                    requested = path,
                    allowedRoot = WORKSPACE_DIR,
                    suggested = suggested,
                    reason = "Project creation outside JARVIS workspace is prohibited. Target: $forbidden"
                )
            }
        }

        // If writing files or creating projects, enforce that destination is inside JARVIS root or allowed storage
        if (isWrite) {
            val isAllowed = canonical.startsWith(ROOT_DIR) ||
                    canonical.startsWith("/sdcard/JARVIS") ||
                    canonical.startsWith("/data/data/com.termux/files/home")

            if (!isAllowed) {
                val relName = canonical.substringAfterLast('/')
                return WorkspaceValidationResult.Violation(
                    requested = path,
                    allowedRoot = ROOT_DIR,
                    suggested = "$WORKSPACE_DIR/$relName",
                    reason = "Writing files or creating projects outside the canonical JARVIS workspace ($ROOT_DIR) is forbidden."
                )
            }
        }

        return WorkspaceValidationResult.Allowed(canonical)
    }

    /**
     * Returns structured workspace storage telemetry.
     */
    fun getStorageTelemetry(): JSONObject {
        val json = JSONObject()
        json.put("workspace_root", ROOT_DIR)
        json.put("current_cwd", SessionState.dir)
        json.put("workspace_dir", WORKSPACE_DIR)
        json.put("projects_dir", PROJECTS_DIR)
        json.put("generated_dir", GENERATED_DIR)
        json.put("downloads_dir", DOWNLOADS_DIR)

        try {
            val stat = Fs.Java.storageInfo()
            json.put("available_bytes", stat.first)
            json.put("total_bytes", stat.second)
            json.put("available_mb", stat.first / (1024 * 1024))
        } catch (_: Exception) {
            json.put("available_mb", "unknown")
        }

        return json
    }
}
