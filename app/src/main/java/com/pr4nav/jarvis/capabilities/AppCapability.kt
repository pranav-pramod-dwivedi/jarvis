package com.pr4nav.jarvis.capabilities

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONArray
import org.json.JSONObject

object AppCapability : Capability {

    override val name = "apps"

    data class AppInfo(val label: String, val pkg: String)

    fun launchables(): List<AppInfo> {
        val ctx = Capabilities.require()
        val pm = ctx.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = if (Build.VERSION.SDK_INT >= 33)
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        else @Suppress("DEPRECATION") pm.queryIntentActivities(intent, 0)
        return resolved.mapNotNull {
            val pkg = it.activityInfo?.applicationInfo?.packageName ?: return@mapNotNull null
            val label = try { it.loadLabel(pm).toString() } catch (_: Exception) { pkg }
            AppInfo(label, pkg)
        }.distinctBy { it.pkg }.sortedBy { it.label.lowercase() }
    }

    private val appAliases = mapOf(
        "chrome" to listOf("com.android.chrome", "chrome", "google chrome"),
        "browser" to listOf("com.android.chrome", "chrome", "browser", "org.mozilla.firefox", "com.brave.browser", "com.opera.browser"),
        "camera" to listOf("camera", "google camera", "com.android.camera", "com.google.android.GoogleCamera"),
        "gallery" to listOf("gallery", "photos", "google photos", "com.google.android.apps.photos", "com.miui.gallery"),
        "photos" to listOf("google photos", "photos", "gallery", "com.google.android.apps.photos"),
        "spotify" to listOf("spotify", "com.spotify.music"),
        "music" to listOf("spotify", "youtube music", "apple music", "amazon music", "music", "jiosaavn", "wynk"),
        "youtube" to listOf("youtube", "com.google.android.youtube"),
        "maps" to listOf("maps", "google maps", "com.google.android.apps.maps"),
        "whatsapp" to listOf("whatsapp", "com.whatsapp"),
        "gmail" to listOf("gmail", "email", "com.google.android.gm"),
        "mail" to listOf("gmail", "email", "outlook", "mail"),
        "settings" to listOf("settings", "com.android.settings"),
        "phone" to listOf("phone", "dialer", "contacts", "com.google.android.dialer"),
        "dialer" to listOf("phone", "dialer", "com.google.android.dialer"),
        "messages" to listOf("messages", "messaging", "sms", "com.google.android.apps.messaging"),
        "sms" to listOf("messages", "messaging", "sms"),
        "files" to listOf("files", "file manager", "my files", "com.google.android.apps.nbu.files"),
        "notes" to listOf("keep notes", "google keep", "notes", "com.google.android.keep"),
        "calculator" to listOf("calculator", "google calculator", "com.google.android.calculator"),
        "clock" to listOf("clock", "google clock", "alarm", "com.google.android.deskclock"),
        "telegram" to listOf("telegram", "org.telegram.messenger"),
        "instagram" to listOf("instagram", "com.instagram.android"),
        "termux" to listOf("termux", "com.termux"),
        "terminal" to listOf("termux", "terminal")
    )

    fun find(query: String): AppInfo? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val apps = launchables()

        // 1. Exact label match
        val exactLabel = apps.firstOrNull { it.label.lowercase() == q }
        if (exactLabel != null) return exactLabel

        // 2. Exact package match
        val exactPkg = apps.firstOrNull { it.pkg.lowercase() == q || it.pkg.lowercase().endsWith(".$q") }
        if (exactPkg != null) return exactPkg

        // 3. Known aliases match
        val aliasCandidates = appAliases[q]
        if (aliasCandidates != null) {
            for (alias in aliasCandidates) {
                val match = apps.firstOrNull {
                    it.label.lowercase() == alias ||
                    it.pkg.lowercase() == alias ||
                    it.label.lowercase().contains(alias) ||
                    it.pkg.lowercase().contains(alias)
                }
                if (match != null) return match
            }
        }

        // 4. Word boundary match in label (e.g. "Google Chrome" matches "chrome")
        val wordMatch = apps.firstOrNull { app ->
            val words = app.label.lowercase().split("\\s+".toRegex())
            words.any { it == q }
        }
        if (wordMatch != null) return wordMatch

        // 5. Starts with query
        val startsWith = apps.firstOrNull { it.label.lowercase().startsWith(q) }
        if (startsWith != null) return startsWith

        // 6. Contains query in label (shortest label first to prioritize exact app over companion tools)
        val containsLabel = apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }
        if (containsLabel != null) return containsLabel

        // 7. Contains query in package name
        return apps.firstOrNull { it.pkg.lowercase().contains(q.replace(' ', '.')) && q.length > 3 }
    }

    fun isInstalled(pkgOrName: String): CapabilityResult {
        val app = find(pkgOrName)
        val exact = try {
            Capabilities.require().packageManager.getPackageInfo(pkgOrName, 0); true
        } catch (_: Exception) { false }
        return if (exact || app != null) CapabilityResult.ok(
            JSONObject().put("installed", true).put("pkg", app?.pkg ?: pkgOrName)
                .put("label", app?.label ?: "").toString()
        ) else CapabilityResult.fail(
            "No installed app matches '$pkgOrName'",
            "installed" to "false"
        )
    }

    fun launch(query: String): CapabilityResult {
        val ctx = Capabilities.require()
        val target = find(query)
            ?: return CapabilityResult.fail(
                "No installed app matches '$query'. Use app.search to list launchable apps."
            )
        return try {
            val i = ctx.packageManager.getLaunchIntentForPackage(target.pkg)
                ?: return CapabilityResult.fail("App '${target.label}' has no launchable activity")
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
            CapabilityResult.ok(
                JSONObject().put("launched", true).put("pkg", target.pkg)
                    .put("label", target.label).toString()
            )
        } catch (e: Exception) {
            CapabilityResult.fail("Launch failed: ${e.message}")
        }
    }

    fun openAppSettings(pkg: String): CapabilityResult = try {
        val ctx = Capabilities.require()
        ctx.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        CapabilityResult.ok(JSONObject().put("opened", "app settings for $pkg").toString())
    } catch (e: Exception) { CapabilityResult.fail(e.message ?: "failed") }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = true

    override fun status(): String =
        "✓ Apps — ${try { "${launchables().size} launchable" } catch (_: Exception) { "query unavailable" }}"

    override fun tools() = listOf(
        ToolDef("app.launch", "Open an installed app by name or package", """{"name":"chrome"}""",
            null, { a -> launch(a.getString("name")).envelope() }),
        ToolDef("app.search", "List launchable apps (optionally filtered)", """{"filter":"..."}""",
            null,
            {
                val f = it.optString("filter", "").lowercase()
                val apps = launchables().filter { ap ->
                    f.isBlank() || ap.label.lowercase().contains(f) || ap.pkg.contains(f)
                }.take(60)
                CapabilityResult.ok(
                    JSONArray().apply {
                        for (ap in apps) put(JSONObject().put("label", ap.label).put("pkg", ap.pkg))
                    }.toString(), "count" to apps.size.toString()
                ).envelope()
            }),
        ToolDef("app.installed", "Check whether an app exists", """{"name":"com.chrome.dev"}""",
            null, { a -> isInstalled(a.getString("name")).envelope() }),
        ToolDef("app.settings", "Open the system App-Info page for a package", """{"pkg":"..."}""",
            null, { a -> openAppSettings(a.getString("pkg")).envelope() })
    )
}
