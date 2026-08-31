package com.pr4nav.jarvis.registry

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import com.pr4nav.jarvis.capabilities.AppCapability
import com.pr4nav.jarvis.router.JarvisIntentRouter

object AppDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        CapabilityDef(
            id = "app.launch",
            category = "app",
            name = "Launch Application",
            description = "Open any installed application by name or package",
            aliases = listOf("open", "launch", "start app", "open app"),
            optionalParams = listOf("name", "package"),
            execute = { ctx, params ->
                val name = (params["name"] as? String) ?: (params["package"] as? String) ?: ""
                if (name.isBlank()) CapabilityExecutionResult.fail("Please specify an app name to launch.")
                else {
                    JarvisIntentRouter.routeAndExecute(ctx, "Open $name") { _ -> }
                    CapabilityExecutionResult.ok("🚀 Launching $name.")
                }
            }
        ),

        CapabilityDef(
            id = "app.settings",
            category = "app",
            name = "Open App Settings",
            description = "Open Android application info settings",
            aliases = listOf("app info", "app settings", "application settings"),
            optionalParams = listOf("package"),
            execute = { ctx, params ->
                val pkg = (params["package"] as? String) ?: ctx.packageName
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", pkg, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("⚙️ Opened App Settings for $pkg.")
            }
        ),

        CapabilityDef(
            id = "app.notification_settings",
            category = "app",
            name = "Notification Settings",
            description = "Open notification settings for an app or system",
            aliases = listOf("notification settings", "manage notifications"),
            execute = { ctx, _ ->
                val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                    putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("🔔 Notification settings opened.")
            }
        ),

        CapabilityDef(
            id = "app.check_installed",
            category = "app",
            name = "Check App Installed",
            description = "Verify if a specific package or app name is installed",
            aliases = listOf("is app installed", "check app"),
            optionalParams = listOf("package"),
            execute = { ctx, params ->
                val pkg = (params["package"] as? String) ?: ""
                val pm = ctx.packageManager
                val installed = try {
                    pm.getPackageInfo(pkg, 0)
                    true
                } catch (_: Exception) { false }
                CapabilityExecutionResult.ok("📦 Package '$pkg' is ${if (installed) "installed" else "not installed"}.")
            }
        ),

        CapabilityDef(
            id = "app.share_content",
            category = "app",
            name = "Share Text / Content",
            description = "Share text or link using the Android system share sheet",
            aliases = listOf("share text", "share content"),
            optionalParams = listOf("text"),
            execute = { ctx, params ->
                val text = (params["text"] as? String) ?: ""
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, text)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(Intent.createChooser(intent, "Share via").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                CapabilityExecutionResult.ok("📤 Opened share dialog.")
            }
        ),

        CapabilityDef(
            id = "app.open_url",
            category = "app",
            name = "Open Web URL",
            description = "Open any web URL in the default browser",
            aliases = listOf("open url", "open website", "visit"),
            optionalParams = listOf("url"),
            execute = { ctx, params ->
                var url = (params["url"] as? String) ?: ""
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(intent)
                CapabilityExecutionResult.ok("🌐 Opening $url.")
            }
        )
    )
}
