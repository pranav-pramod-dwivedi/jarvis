package com.pr4nav.jarvis.registry

import android.content.Context
import com.pr4nav.jarvis.llm.GroqClient
import com.pr4nav.jarvis.llm.KiraClient
import com.pr4nav.jarvis.system.GamingModeManager

object AgentDomain {

    fun getCapabilities(): List<CapabilityDef> = listOf(
        // Kira AI Platform (Primary Default)
        CapabilityDef(
            id = "kira.status",
            category = "kira",
            name = "Kira AI Platform Status",
            description = "Check status of the Kira AI primary engine & free model cascade (glm-5.3-free -> mimo-v2.5-free -> qwen3.8-flash-free)",
            aliases = listOf("kira status", "check kira", "is kira running", "kira ai"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                val key = KiraClient.getApiKey(ctx)
                val model = KiraClient.getModel(ctx)
                val status = if (key.isNotBlank()) "CONFIGURED ($model)" else "FREE TIER DEFAULT ($model)"
                CapabilityExecutionResult.ok("✨ Kira AI: $status\nCascade: ${KiraClient.FREE_MODEL_CASCADE.joinToString(" ➔ ")}")
            }
        ),

        // Groq Compound Agent (Primary Secondary)
        CapabilityDef(
            id = "groq.status",
            category = "groq",
            name = "Groq LPU Status",
            description = "Check status of Groq Compound LPU engine and quotas",
            aliases = listOf("groq status", "check groq", "groq quota"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                val key = GroqClient.getApiKey(ctx)
                val model = GroqClient.getModel(ctx)
                val metrics = GroqClient.getUsageMetrics(ctx)
                val status = if (key.isNotBlank()) "ACTIVE ($model)" else "NOT CONFIGURED"
                CapabilityExecutionResult.ok("⚡ Groq LPU: $status\nQuotas: ${metrics.rpdUsed}/${metrics.rpdLimit} RPD · ${metrics.currentTpm}/${metrics.tpmLimit} TPM")
            }
        ),

        // Gaming Mode & Emergency Force Stop
        CapabilityDef(
            id = "system.gaming_mode",
            category = "system",
            name = "Gaming Mode / Force Stop All",
            description = "Force stops all background processes, floating HUD, listeners, and WakeLocks to achieve 0% CPU & battery usage",
            aliases = listOf("gaming mode", "force stop", "stop all processes", "kill background", "game mode"),
            backend = BackendType.ANDROID_API,
            execute = { ctx, _ ->
                GamingModeManager.forceStopAll(ctx)
                CapabilityExecutionResult.ok("🎮 Gaming Mode Engaged: All background services, listeners, HUD, and subprocesses terminated. 0% CPU & battery active.")
            }
        )
    )
}
