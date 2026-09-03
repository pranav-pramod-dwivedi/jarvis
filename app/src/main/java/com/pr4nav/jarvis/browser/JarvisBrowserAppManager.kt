package com.pr4nav.jarvis.browser

import android.content.Context
import android.os.Environment
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Manages the lifecycle, storage, discovery, and persistence of JarvisBrowser mini-apps.
 * Standard storage: /storage/emulated/0/JARVIS/browser/apps/
 * Temporary storage: /storage/emulated/0/JARVIS/browser/temp/
 */
object JarvisBrowserAppManager {

    private const val TAG = "JarvisBrowserMgr"

    private fun getStorageRoot(ctx: Context): File {
        val ext = Environment.getExternalStorageDirectory()
        val base = if (ext != null && ext.canWrite()) {
            File(ext, "JARVIS/browser")
        } else {
            File(ctx.filesDir, "JARVIS/browser")
        }
        if (!base.exists()) base.mkdirs()
        return base
    }

    fun getAppsDir(ctx: Context): File {
        val dir = File(getStorageRoot(ctx), "apps")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    fun getTempDir(ctx: Context): File {
        val dir = File(getStorageRoot(ctx), "temp")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Creates or updates a mini web app with index.html, optional style.css, app.js, and manifest.json.
     */
    fun createApp(
        ctx: Context,
        appId: String,
        title: String,
        description: String,
        html: String,
        css: String? = null,
        js: String? = null,
        isTemporary: Boolean = false,
        tags: List<String> = emptyList(),
        icon: String = "⚡"
    ): JarvisBrowserApp {
        val cleanId = appId.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-").trim('-').ifEmpty {
            "app-${System.currentTimeMillis()}"
        }

        val targetDir = if (isTemporary) {
            File(getTempDir(ctx), cleanId)
        } else {
            File(getAppsDir(ctx), cleanId)
        }
        if (!targetDir.exists()) targetDir.mkdirs()

        // 1. Write optional CSS
        if (!css.isNullOrBlank()) {
            File(targetDir, "style.css").writeText(css)
        }

        // 2. Write optional JS
        if (!js.isNullOrBlank()) {
            File(targetDir, "app.js").writeText(js)
        }

        // 3. Prepare bundled/self-contained HTML
        val finalHtml = prepareHtmlDocument(html, css != null, js != null, title)
        val indexFile = File(targetDir, "index.html")
        indexFile.writeText(finalHtml)

        // 4. Write manifest.json
        val manifestObj = JSONObject().apply {
            put("id", cleanId)
            put("title", title)
            put("description", description)
            put("created_at", System.currentTimeMillis())
            put("updated_at", System.currentTimeMillis())
            put("is_temporary", isTemporary)
            put("icon", icon)
            put("version", "1.0.0")
            val tagsArr = JSONArray()
            tags.forEach { tagsArr.put(it) }
            put("tags", tagsArr)
        }
        File(targetDir, "manifest.json").writeText(manifestObj.toString(2))

        Log.i(TAG, "Created JarvisBrowser app '$cleanId' at: ${targetDir.absolutePath} (temp=$isTemporary)")
        return JarvisBrowserApp.fromManifest(targetDir, isTemporary) ?: JarvisBrowserApp(
            id = cleanId,
            title = title,
            description = description,
            entryPath = indexFile.absolutePath,
            directory = targetDir,
            tags = tags,
            isTemporary = isTemporary,
            icon = icon
        )
    }

    /**
     * Wraps raw HTML with standard meta tags, viewport, theme variables, and JarvisBridge client helper.
     */
    private fun prepareHtmlDocument(
        html: String,
        hasExternalCss: Boolean,
        hasExternalJs: Boolean,
        title: String
    ): String {
        val trimmed = html.trim()
        if (trimmed.startsWith("<!DOCTYPE html>", ignoreCase = true) || trimmed.startsWith("<html", ignoreCase = true)) {
            // Already full document, ensure bridge script is included
            return injectBridgeHelper(trimmed)
        }

        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <title>$title</title>
    <style>
        :root {
            --jv-bg: #0B1116;
            --jv-surface: #101820;
            --jv-surface-hi: #16232E;
            --jv-accent: #4FD1C5;
            --jv-accent-dim: #2A8F87;
            --jv-text: #E6F2FF;
            --jv-text-dim: #6E8CA0;
            --font-family: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Oxygen, Ubuntu, Cantarell, sans-serif;
        }
        * { box-sizing: border-box; margin: 0; padding: 0; }
        body {
            background-color: var(--jv-bg);
            color: var(--jv-text);
            font-family: var(--font-family);
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            overflow-x: hidden;
            -webkit-tap-highlight-color: transparent;
        }
    </style>
    ${if (hasExternalCss) """<link rel="stylesheet" href="style.css">""" else ""}
</head>
<body>
$trimmed
${if (hasExternalJs) """<script src="app.js"></script>""" else ""}
<script>
${bridgeClientJs()}
</script>
</body>
</html>
        """.trimIndent()
    }

    private fun injectBridgeHelper(html: String): String {
        val script = "<script>\n${bridgeClientJs()}\n</script>"
        return if (html.contains("</body>", ignoreCase = true)) {
            html.replace("</body>", "$script\n</body>")
        } else {
            "$html\n$script"
        }
    }

    private fun bridgeClientJs(): String = """
// Controlled JarvisBridge Client API
window.jarvis = {
    speak: function(text) {
        if (window.JarvisNative && window.JarvisNative.speak) {
            window.JarvisNative.speak(text);
        } else {
            console.log('[JarvisBridge:speak]', text);
        }
    },
    toast: function(msg) {
        if (window.JarvisNative && window.JarvisNative.toast) {
            window.JarvisNative.toast(msg);
        } else {
            console.log('[JarvisBridge:toast]', msg);
        }
    },
    close: function() {
        if (window.JarvisNative && window.JarvisNative.close) {
            window.JarvisNative.close();
        }
    },
    saveApp: function(title, desc) {
        if (window.JarvisNative && window.JarvisNative.saveCurrentApp) {
            window.JarvisNative.saveCurrentApp(title || '', desc || '');
        }
    },
    callTool: function(toolName, params) {
        return new Promise(function(resolve, reject) {
            var callbackId = 'cb_' + Date.now() + '_' + Math.floor(Math.random() * 1000);
            window['__jarvis_callbacks'] = window['__jarvis_callbacks'] || {};
            window['__jarvis_callbacks'][callbackId] = function(resJson, err) {
                delete window['__jarvis_callbacks'][callbackId];
                if (err) reject(new Error(err));
                else resolve(resJson ? JSON.parse(resJson) : {});
            };
            if (window.JarvisNative && window.JarvisNative.callTool) {
                window.JarvisNative.callTool(toolName, JSON.stringify(params || {}), callbackId);
            } else {
                reject(new Error('JarvisBridge not available'));
            }
        });
    }
};
window.__jarvisOnToolResult = function(callbackId, resultJson, errorMsg) {
    if (window['__jarvis_callbacks'] && window['__jarvis_callbacks'][callbackId]) {
        window['__jarvis_callbacks'][callbackId](resultJson, errorMsg);
    }
};
    """.trimIndent()

    fun getApp(ctx: Context, appId: String): JarvisBrowserApp? {
        val cleanId = appId.trim().lowercase().replace(Regex("[^a-z0-9_-]"), "-")
        // Check permanent first
        val permDir = File(getAppsDir(ctx), cleanId)
        if (permDir.exists()) {
            JarvisBrowserApp.fromManifest(permDir, false)?.let { return it }
        }
        // Check temp
        val tempDir = File(getTempDir(ctx), cleanId)
        if (tempDir.exists()) {
            JarvisBrowserApp.fromManifest(tempDir, true)?.let { return it }
        }
        return null
    }

    fun findAppByQuery(ctx: Context, query: String): JarvisBrowserApp? {
        val q = query.trim().lowercase()
        val all = listApps(ctx)
        // Exact ID match
        all.firstOrNull { it.id.equals(q, ignoreCase = true) }?.let { return it }
        // Exact Title match
        all.firstOrNull { it.title.equals(q, ignoreCase = true) }?.let { return it }
        // Partial ID / Title / Tag match
        return all.firstOrNull { app ->
            app.id.contains(q) || app.title.lowercase().contains(q) || app.tags.any { it.lowercase().contains(q) }
        }
    }

    fun listApps(ctx: Context): List<JarvisBrowserApp> {
        val apps = mutableListOf<JarvisBrowserApp>()
        val dir = getAppsDir(ctx)
        dir.listFiles()?.filter { it.isDirectory }?.forEach { sub ->
            JarvisBrowserApp.fromManifest(sub, false)?.let { apps.add(it) }
        }
        return apps.sortedByDescending { it.updatedAt }
    }

    /**
     * Promotes a temporary mini-app to permanent storage.
     */
    fun saveTemporaryApp(ctx: Context, appId: String, title: String? = null, description: String? = null): Boolean {
        val tempApp = getApp(ctx, appId) ?: return false
        if (!tempApp.isTemporary) return true // Already permanent

        val targetDir = File(getAppsDir(ctx), tempApp.id)
        if (targetDir.exists()) targetDir.deleteRecursively()
        targetDir.mkdirs()

        tempApp.directory.copyRecursively(targetDir, overwrite = true)

        // Update manifest
        val manifestFile = File(targetDir, "manifest.json")
        val json = if (manifestFile.exists()) JSONObject(manifestFile.readText()) else JSONObject()
        json.put("is_temporary", false)
        if (!title.isNullOrBlank()) json.put("title", title)
        if (!description.isNullOrBlank()) json.put("description", description)
        json.put("updated_at", System.currentTimeMillis())
        manifestFile.writeText(json.toString(2))

        Log.i(TAG, "Promoted temporary app '${tempApp.id}' to permanent saved app")
        return true
    }

    fun deleteApp(ctx: Context, appId: String): Boolean {
        val cleanId = appId.trim().lowercase()
        var deleted = false
        val permDir = File(getAppsDir(ctx), cleanId)
        if (permDir.exists()) {
            deleted = permDir.deleteRecursively() || deleted
        }
        val tempDir = File(getTempDir(ctx), cleanId)
        if (tempDir.exists()) {
            deleted = tempDir.deleteRecursively() || deleted
        }
        return deleted
    }
}
