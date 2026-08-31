package com.pr4nav.jarvis.registry

import android.content.Context
import com.pr4nav.jarvis.needle.NeedleExecutor
import com.pr4nav.jarvis.needle.NeedleRouter
import com.pr4nav.jarvis.needle.RouteType

data class CompoundStepResult(
    val step: String,
    val capabilityId: String,
    val summary: String,
    val success: Boolean,
    val latencyMs: Long
)

data class CompoundExecutionResult(
    val steps: List<CompoundStepResult>,
    val aggregatedSummary: String,
    val totalLatencyMs: Long,
    val success: Boolean
)

/**
 * Level 3 Compound Command & Level 4 Workflow Execution Engine.
 * Decomposes multi-intent user instructions ("Turn off torch and set volume to 80%")
 * into discrete atomic capabilities and executes them sequentially.
 */
object JarvisWorkflowEngine {

    private val SPLIT_DELIMITERS = listOf(
        " and then ",
        " then ",
        " and ",
        ";",
        " & "
    )

    fun isCompound(input: String): Boolean {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return false
        val lower = trimmed.lowercase()

        // Do not split if the whole input literally is a registered capability ID or exact alias
        if (CapabilityRegistry.get(trimmed) != null) return false
        val allCaps = CapabilityRegistry.getAll()
        if (allCaps.any { cap -> cap.aliases.any { it.equals(trimmed, ignoreCase = true) } }) {
            return false
        }

        return SPLIT_DELIMITERS.any { lower.contains(it) }
    }

    fun split(input: String): List<String> {
        var segments = listOf(input.trim())
        for (delim in SPLIT_DELIMITERS) {
            val nextSegments = mutableListOf<String>()
            for (seg in segments) {
                if (seg.lowercase().contains(delim)) {
                    val parts = seg.split(Regex(Regex.escape(delim), RegexOption.IGNORE_CASE))
                    for (p in parts) {
                        val t = p.trim()
                        if (t.isNotEmpty()) nextSegments.add(t)
                    }
                } else {
                    nextSegments.add(seg)
                }
            }
            segments = nextSegments
        }
        return segments
    }

    fun executeCompound(context: Context, input: String): CompoundExecutionResult {
        val t0 = System.currentTimeMillis()
        val parts = split(input)
        val stepResults = mutableListOf<CompoundStepResult>()
        val summaries = mutableListOf<String>()
        var allOk = true

        for (part in parts) {
            val stepT0 = System.currentTimeMillis()
            val match = CapabilityRegistry.match(part)
            if (match != null) {
                val res = CapabilityRegistry.execute(match.capability.id, match.params, context)
                val stepLatency = System.currentTimeMillis() - stepT0
                stepResults.add(
                    CompoundStepResult(
                        step = part,
                        capabilityId = match.capability.id,
                        summary = res.summary,
                        success = res.success,
                        latencyMs = stepLatency
                    )
                )
                summaries.add("• [${match.capability.id} · ${stepLatency}ms] ${res.summary}")
                if (!res.success) allOk = false
            } else {
                val route = NeedleRouter.route(part, context)
                if (route.route == RouteType.DIRECT_TOOL && route.tool != null) {
                    val summary = NeedleExecutor.execute(context, route)
                    val stepLatency = System.currentTimeMillis() - stepT0
                    stepResults.add(
                        CompoundStepResult(
                            step = part,
                            capabilityId = route.tool,
                            summary = summary,
                            success = true,
                            latencyMs = stepLatency
                        )
                    )
                    summaries.add("• [${route.tool} · ${stepLatency}ms] $summary")
                } else {
                    summaries.add("• \"$part\": Could not resolve capability.")
                    allOk = false
                }
            }
        }

        val totalMs = System.currentTimeMillis() - t0
        val finalSummary = "🔗 Compound Action (${parts.size} steps · ${totalMs}ms):\n" + summaries.joinToString("\n")
        return CompoundExecutionResult(
            steps = stepResults,
            aggregatedSummary = finalSummary,
            totalLatencyMs = totalMs,
            success = allOk
        )
    }
}
