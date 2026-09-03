package com.pr4nav.jarvis.tools.catalog

import com.pr4nav.jarvis.browser.JarvisBrowserActivity
import com.pr4nav.jarvis.browser.JarvisBrowserAppManager
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.fail
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.ok
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.prop
import com.pr4nav.jarvis.tools.catalog.CatalogSchemaHelper.schema
import org.json.JSONArray

/**
 * Canonical tool pack for JarvisBrowser:
 * Dynamic on-demand UI & web-app generation, rendering, launching, and persistence.
 */
object JarvisBrowserTools {

    fun register(reg: (CanonicalToolDef) -> Unit) {

        // 1. browser_render_app: Generate and instantly launch an interactive UI
        reg(CanonicalToolDef(
            name = "browser_render_app",
            description = "Generates and renders an on-demand, interactive HTML/CSS/JS mini web-app in JarvisBrowser. Use whenever a visual UI, animation, interactive simulation, chart, comparison table, or custom dashboard improves the response.",
            argumentSchema = schema(
                prop("app_id", "string", "Unique alphanumeric slug for the app, e.g. 'faradays-law', 'workout-chart', 'solar-eclipse'"),
                prop("title", "string", "Human-readable title for the app header"),
                prop("html", "string", "Complete self-contained HTML5 code or body content (can include SVG, Canvas, responsive styles)"),
                prop("css", "string", "Optional CSS styling"),
                prop("js", "string", "Optional client JavaScript logic (can use window.jarvis.speak, window.jarvis.callTool)"),
                prop("description", "string", "Short description of the visualization/tool"),
                prop("explanation_speech", "string", "Spoken verbal explanation that JARVIS will speak while the user interacts with the UI"),
                prop("is_temporary", "boolean", "True for temporary/one-off previews; false to save permanently in user's JarvisBrowser library"),
                prop("icon", "string", "Emoji icon representing the app (e.g. ⚡, 🪐, 📊, 🧭, 💡)"),
                required = listOf("app_id", "title", "html")
            ),
            execute = { ctx, args ->
                val appId = args.optString("app_id").trim()
                val title = args.optString("title").trim()
                val html = args.optString("html").trim()
                if (appId.isEmpty() || title.isEmpty() || html.isEmpty()) {
                    return@CanonicalToolDef fail("INVALID_ARGS", "app_id, title, and html cannot be empty")
                }
                val css = args.optString("css").takeIf { it.isNotBlank() }
                val js = args.optString("js").takeIf { it.isNotBlank() }
                val description = args.optString("description", "Dynamic JarvisBrowser Application")
                val speech = args.optString("explanation_speech").takeIf { it.isNotBlank() }
                val isTemp = args.optBoolean("is_temporary", true)
                val icon = args.optString("icon", "⚡")

                val app = JarvisBrowserAppManager.createApp(
                    ctx = ctx,
                    appId = appId,
                    title = title,
                    description = description,
                    html = html,
                    css = css,
                    js = js,
                    isTemporary = isTemp,
                    icon = icon
                )

                // Launch the interactive UI surface
                JarvisBrowserActivity.launch(
                    context = ctx,
                    appId = app.id,
                    explanationSpeech = speech
                )

                ok(
                    "Launched JarvisBrowser app '${app.title}'.",
                    mapOf(
                        "app_id" to app.id,
                        "title" to app.title,
                        "entry_path" to app.entryPath,
                        "is_temporary" to app.isTemporary,
                        "speech_queued" to (speech != null)
                    )
                )
            }
        ))

        // 2. browser_launch_app: Launch an existing / previously saved app
        reg(CanonicalToolDef(
            name = "browser_launch_app",
            description = "Launches an existing or saved JarvisBrowser app by ID, title, or search query (e.g. 'faraday', 'workout').",
            argumentSchema = schema(
                prop("query", "string", "App ID, title, or search keyword of the saved app to launch"),
                prop("explanation_speech", "string", "Optional verbal speech to speak upon launching"),
                required = listOf("query")
            ),
            execute = { ctx, args ->
                val query = args.optString("query").trim()
                if (query.isEmpty()) return@CanonicalToolDef fail("INVALID_ARGS", "Query cannot be empty")
                val speech = args.optString("explanation_speech").takeIf { it.isNotBlank() }

                val app = JarvisBrowserAppManager.findAppByQuery(ctx, query)
                    ?: return@CanonicalToolDef fail("NOT_FOUND", "No JarvisBrowser app found matching '$query'. Available: ${JarvisBrowserAppManager.listApps(ctx).map { it.title }}")

                JarvisBrowserActivity.launch(
                    context = ctx,
                    appId = app.id,
                    explanationSpeech = speech
                )

                ok("Opened JarvisBrowser app '${app.title}'.", mapOf("app_id" to app.id, "title" to app.title))
            }
        ))

        // 3. browser_list_apps: View all permanent JarvisBrowser apps
        reg(CanonicalToolDef(
            name = "browser_list_apps",
            description = "Lists all saved and permanent JarvisBrowser mini-apps in the user's library.",
            argumentSchema = schema(),
            execute = { ctx, _ ->
                val apps = JarvisBrowserAppManager.listApps(ctx)
                val arr = JSONArray()
                apps.forEach { arr.put(it.toJson()) }
                ok(
                    "Found ${apps.size} saved JarvisBrowser apps.",
                    mapOf("count" to apps.size, "apps" to arr)
                )
            }
        ))

        // 4. browser_save_app: Persist a temporary app permanently
        reg(CanonicalToolDef(
            name = "browser_save_app",
            description = "Saves a temporary JarvisBrowser app permanently into the user's reusable apps library.",
            argumentSchema = schema(
                prop("app_id", "string", "The ID of the temporary app to save"),
                prop("title", "string", "Optional updated title"),
                prop("description", "string", "Optional updated description"),
                required = listOf("app_id")
            ),
            execute = { ctx, args ->
                val appId = args.optString("app_id").trim()
                if (appId.isEmpty()) return@CanonicalToolDef fail("INVALID_ARGS", "app_id cannot be empty")
                val title = args.optString("title").takeIf { it.isNotBlank() }
                val desc = args.optString("description").takeIf { it.isNotBlank() }

                val success = JarvisBrowserAppManager.saveTemporaryApp(ctx, appId, title, desc)
                if (success) {
                    ok("App '$appId' has been saved permanently to your JarvisBrowser library.")
                } else {
                    fail("NOT_FOUND", "Could not find temporary app '$appId' to save.")
                }
            }
        ))

        // 5. browser_delete_app: Delete a saved app
        reg(CanonicalToolDef(
            name = "browser_delete_app",
            description = "Deletes a saved JarvisBrowser mini-app from storage.",
            argumentSchema = schema(
                prop("app_id", "string", "The ID of the app to delete"),
                required = listOf("app_id")
            ),
            execute = { ctx, args ->
                val appId = args.optString("app_id").trim()
                if (appId.isEmpty()) return@CanonicalToolDef fail("INVALID_ARGS", "app_id cannot be empty")
                val deleted = JarvisBrowserAppManager.deleteApp(ctx, appId)
                if (deleted) {
                    ok("Deleted JarvisBrowser app '$appId'.")
                } else {
                    fail("NOT_FOUND", "App '$appId' was not found.")
                }
            }
        ))
    }
}
