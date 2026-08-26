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

    fun find(query: String): AppInfo? {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return null
        val apps = launchables()
        return apps.firstOrNull { it.label.lowercase() == q }
            ?: apps.firstOrNull { it.pkg.lowercase() == q || it.pkg.lowercase().endsWith(".$q") }
            ?: apps.filter { it.label.lowercase().contains(q) }.minByOrNull { it.label.length }
            ?: apps.firstOrNull { it.pkg.lowercase().contains(q.replace(' ', '.')) && q.length > 3 }
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
