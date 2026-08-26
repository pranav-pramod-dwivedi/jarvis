package com.pr4nav.jarvis.capabilities

import android.content.ComponentName
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.pr4nav.jarvis.JarvisNotificationListener
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONArray
import org.json.JSONObject

object NotificationCapability : Capability {

    override val name = "notifications"

    fun listenerGranted(): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(Capabilities.require())
            .contains(Capabilities.require().packageName)

    fun active(): CapabilityResult {
        if (!listenerGranted())
            return CapabilityResult.fail("Notification listener access not granted — enable in system Settings → Notification access")
        val snaps = JarvisNotificationListener.snapshot()
        return CapabilityResult.ok(
            JSONArray().apply {
                for (s in snaps.take(100)) put(JSONObject()
                    .put("key", s.key).put("pkg", s.pkg).put("title", s.title)
                    .put("text", s.text.ifBlank { s.bigText }).put("postedAt", s.postedAt)
                    .put("ongoing", s.ongoing).put("clearable", s.clearable))
            }.toString(),
            "count" to snaps.size.toString(),
            "connected" to JarvisNotificationListener.connected.toString()
        )
    }

    fun dismiss(key: String): CapabilityResult {
        if (!listenerGranted()) return CapabilityResult.fail("Notification listener access not granted")
        val svc = boundService()
            ?: return CapabilityResult.fail("Listener service is not connected yet — reopen JARVIS or wait a moment")
        return try {
            svc.cancelNotification(key)
            CapabilityResult.ok(JSONObject().put("dismissed", key).toString())
        } catch (e: Exception) {
            CapabilityResult.fail("Dismiss failed: ${e.message}")
        }
    }

    fun dismissAll(): CapabilityResult {
        if (!listenerGranted()) return CapabilityResult.fail("Notification listener access not granted")
        val svc = boundService()
            ?: return CapabilityResult.fail("Listener service is not connected yet")
        return try {
            svc.cancelAllNotifications()
            CapabilityResult.ok(JSONObject().put("dismissed", "all").toString())
        } catch (e: Exception) {
            CapabilityResult.fail("Dismiss-all failed: ${e.message}")
        }
    }

    fun boundService(): JarvisNotificationListener? =
        JarvisNotificationListener.instance

    private fun openListenerSettings(): CapabilityResult = try {
        Capabilities.require().startActivity(
            Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        CapabilityResult.ok(JSONObject().put("opened", "notification access settings").toString())
    } catch (e: Exception) { CapabilityResult.fail(e.message ?: "failed") }

    override fun available(): Boolean = true
    override fun permitted(): Boolean = try { listenerGranted() } catch (_: Exception) { false }

    override fun status(): String = when {
        !permitted() -> "○ Notifications — listener access not granted (optional)"
        !JarvisNotificationListener.connected -> "△ Notifications — granted, service reconnecting"
        else -> "✓ Notifications — ${JarvisNotificationListener.snapshot().size} live notifications visible"
    }

    override fun tools() = listOf(
        ToolDef("notification.list", "List visible notifications (app/title/text)", "{}",
            { if (Capabilities.app != null && !listenerGranted()) "notification listener access not granted" else null },
            { _ -> active().envelope() }),
        ToolDef("notification.dismiss", "Dismiss one notification by key", """{"key":"..."}""",
            { if (Capabilities.app != null && !listenerGranted()) "notification listener access not granted" else null },
            { a -> dismiss(a.getString("key")).envelope() }),
        ToolDef("notification.dismissAll", "Dismiss all clearable notifications", "{}",
            { if (Capabilities.app != null && !listenerGranted()) "notification listener access not granted" else null },
            { _ -> dismissAll().envelope() }),
        ToolDef("notification.openSettings", "Open Notification Access settings", "{}", null,
            { _ -> openListenerSettings().envelope() })
    )
}
