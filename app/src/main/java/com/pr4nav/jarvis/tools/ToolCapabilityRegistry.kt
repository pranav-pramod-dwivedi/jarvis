package com.pr4nav.jarvis.tools

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class CapabilityDomain(val displayName: String) {
    DEVICE("Device Control & Sensors"),
    FILES("Filesystem & Storage"),
    SHELL("Terminal & Shell Automation"),
    AGENT("Autonomous Agent & Workspace")
}

data class ToolCapability(
    val name: String,
    val domain: CapabilityDomain,
    val description: String,
    val supportedActions: List<String>,
    val backend: ToolBackend,
    val permissions: List<String> = emptyList(),
    val available: Boolean = true
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("domain", domain.name)
        put("description", description)
        put("actions", JSONArray(supportedActions))
        put("backend", backend.name)
        put("permissions", JSONArray(permissions))
        put("available", available)
    }
}

/**
 * Live Tool Capability Registry & Discovery Engine.
 * Provides explicit capability awareness to router, models, and UI.
 */
object ToolCapabilityRegistry {

    private val CAPABILITIES = mutableListOf<ToolCapability>()

    init {
        registerDefaults()
    }

    private fun registerDefaults() {
        CAPABILITIES.clear()

        // 1. DEVICE DOMAIN
        CAPABILITIES.add(
            ToolCapability(
                name = "system.bluetooth",
                domain = CapabilityDomain.DEVICE,
                description = "Controls device Bluetooth state (enable, disable, status).",
                supportedActions = listOf("enable", "disable", "status", "toggle", "on", "off"),
                backend = ToolBackend.ANDROID_NATIVE,
                permissions = listOf("android.permission.BLUETOOTH_CONNECT")
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "system.torch",
                domain = CapabilityDomain.DEVICE,
                description = "Turns the camera flashlight/torch on or off.",
                supportedActions = listOf("enable", "disable", "toggle", "on", "off"),
                backend = ToolBackend.ANDROID_NATIVE,
                permissions = listOf("android.permission.CAMERA")
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "system.volume",
                domain = CapabilityDomain.DEVICE,
                description = "Adjusts or mutes device audio volume stream.",
                supportedActions = listOf("raise", "lower", "mute", "unmute", "set", "up", "down"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "open_app",
                domain = CapabilityDomain.DEVICE,
                description = "Launches an installed application by name or package.",
                supportedActions = listOf("launch", "open"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "close_app",
                domain = CapabilityDomain.DEVICE,
                description = "Closes or stops a background application package.",
                supportedActions = listOf("close", "stop", "kill"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "call_contact",
                domain = CapabilityDomain.DEVICE,
                description = "Initiates a phone call to a named contact or phone number.",
                supportedActions = listOf("call", "dial"),
                backend = ToolBackend.ANDROID_NATIVE,
                permissions = listOf("android.permission.CALL_PHONE")
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "send_message",
                domain = CapabilityDomain.DEVICE,
                description = "Sends an SMS or message to a recipient.",
                supportedActions = listOf("send", "message", "sms"),
                backend = ToolBackend.ANDROID_NATIVE,
                permissions = listOf("android.permission.SEND_SMS")
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "open_settings",
                domain = CapabilityDomain.DEVICE,
                description = "Opens device Settings or a specific subpage (wifi, bluetooth, display, sound, apps).",
                supportedActions = listOf("open", "show"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "take_screenshot",
                domain = CapabilityDomain.DEVICE,
                description = "Captures a screenshot of the current screen.",
                supportedActions = listOf("capture", "screenshot"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "get_battery",
                domain = CapabilityDomain.DEVICE,
                description = "Gets current battery percentage and charging state.",
                supportedActions = listOf("status", "query"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "get_location",
                domain = CapabilityDomain.DEVICE,
                description = "Gets the device GPS/network location and address.",
                supportedActions = listOf("query", "status"),
                backend = ToolBackend.ANDROID_NATIVE,
                permissions = listOf("android.permission.ACCESS_FINE_LOCATION")
            )
        )

        // 2. FILES DOMAIN
        CAPABILITIES.add(
            ToolCapability(
                name = "read_file",
                domain = CapabilityDomain.FILES,
                description = "Reads text contents of a file inside workspace or allowed storage.",
                supportedActions = listOf("read", "view", "cat"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "write_file",
                domain = CapabilityDomain.FILES,
                description = "Writes or appends text content to a file inside the workspace.",
                supportedActions = listOf("write", "create", "append"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "delete_file",
                domain = CapabilityDomain.FILES,
                description = "Deletes a file or directory within the workspace.",
                supportedActions = listOf("delete", "remove", "rm"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "search_files",
                domain = CapabilityDomain.FILES,
                description = "Performs filesystem search for files matching query in downloads, docs, or workspace.",
                supportedActions = listOf("search", "find"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
        CAPABILITIES.add(
            ToolCapability(
                name = "list_files",
                domain = CapabilityDomain.FILES,
                description = "Lists files and directories at a given path.",
                supportedActions = listOf("list", "ls"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )

        // 3. SHELL DOMAIN
        CAPABILITIES.add(
            ToolCapability(
                name = "run_command",
                domain = CapabilityDomain.SHELL,
                description = "Executes shell commands in Termux/Ubuntu proot or local Android shell.",
                supportedActions = listOf("run", "exec", "shell"),
                backend = ToolBackend.TERMUX
            )
        )

        // 4. AGENT DOMAIN
        CAPABILITIES.add(
            ToolCapability(
                name = "jarvis_environment",
                domain = CapabilityDomain.AGENT,
                description = "Discovers real system environment, storage mounts, and toolchain state.",
                supportedActions = listOf("discover", "status", "snapshot"),
                backend = ToolBackend.ANDROID_NATIVE
            )
        )
    }

    fun getAll(): List<ToolCapability> = CAPABILITIES.toList()

    fun getByDomain(domain: CapabilityDomain): List<ToolCapability> =
        CAPABILITIES.filter { it.domain == domain }

    fun get(name: String): ToolCapability? =
        CAPABILITIES.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /**
     * Validates that an action string is valid for a given tool.
     * Rejects invalid/malformed actions (e.g., bluetooth("banana")).
     */
    fun validateAction(toolName: String, action: String): Boolean {
        val cleanName = when (toolName.lowercase()) {
            "bluetooth", "set_bluetooth" -> "system.bluetooth"
            "torch", "flashlight" -> "system.torch"
            "volume" -> "system.volume"
            else -> toolName
        }

        val cap = get(cleanName) ?: return false
        val cleanAction = action.lowercase().trim()
        if (cleanAction.isEmpty()) return true
        return cap.supportedActions.any { it.equals(cleanAction, ignoreCase = true) }
    }

    /**
     * Formats all live capabilities into a structured summary for agents and developer mode.
     */
    fun getCapabilitiesSummary(context: Context? = null): String = buildString {
        append("JARVIS TOOL CAPABILITIES\n\n")
        for (domain in CapabilityDomain.values()) {
            append("--- ${domain.name} (${domain.displayName}) ---\n")
            val caps = getByDomain(domain)
            for (c in caps) {
                append("• ${c.name}: ${c.description} [Actions: ${c.supportedActions.joinToString(", ")}]\n")
            }
            append("\n")
        }
    }
}
