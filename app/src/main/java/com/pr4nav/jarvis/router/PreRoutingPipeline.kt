package com.pr4nav.jarvis.router

import android.content.Context
import com.pr4nav.jarvis.CmdGuard
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.environment.JarvisEnvironment
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolCapabilityRegistry
import com.pr4nav.jarvis.workspace.JarvisWorkspace
import org.json.JSONObject
import java.util.Locale

sealed class PreRoutingDecision {
    /** Existing tool matched with high confidence -> execute immediately without cloud call */
    data class DirectToolMatch(
        val toolName: String,
        val arguments: JSONObject,
        val confidence: Float,
        val reason: String
    ) : PreRoutingDecision()

    /** Blocked by security guard or safety policy */
    data class Blocked(val reason: String) : PreRoutingDecision()

    /** Route through model classifier (Casual, GK, Coding, Complex Reasoning) */
    data class ModelRoute(
        val classification: TaskClassification,
        val normalizedInput: String,
        val isExistingToolChecked: Boolean = true
    ) : PreRoutingDecision()
}

/**
 * Pre-Routing Filter Layer.
 * Enforces "Existing Tool First", safety checks, path normalization, and workspace grounding.
 */
object PreRoutingPipeline {

    fun filter(context: Context?, input: String): PreRoutingDecision {
        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 1. Safety Guard Filter
        val blockedReason = CmdGuard.check(trimmed)
        if (blockedReason != null) {
            return PreRoutingDecision.Blocked(blockedReason)
        }

        // 1b. Conversational Context & Disambiguation Continuation
        val contextResolution = com.pr4nav.jarvis.context.ContextManager.resolveContinuation(trimmed)
        if (contextResolution is com.pr4nav.jarvis.context.ContextContinuationResult.ResolvedAction) {
            return PreRoutingDecision.DirectToolMatch(
                toolName = contextResolution.toolName,
                arguments = contextResolution.arguments,
                confidence = contextResolution.confidence,
                reason = "Context Continuation: ${contextResolution.reason}"
            )
        }

        // 2. Direct Query: Capabilities & Environment Discovery
        val isCapabilityQuery = (lower.contains("control") && (lower.contains("what") || lower.contains("phone") || lower.contains("can you"))) ||
                (lower.contains("what can you") && (lower.contains("do") || lower.contains("phone"))) ||
                lower.contains("what tools") || lower.contains("list your capabilities") ||
                lower == "capabilities" || lower == "tools"

        if (isCapabilityQuery) {
            val summary = ToolCapabilityRegistry.getCapabilitiesSummary(context)
            val args = JSONObject().put("query", "capabilities")
            return PreRoutingDecision.DirectToolMatch(
                toolName = "jarvis_environment",
                arguments = args,
                confidence = 0.99f,
                reason = "Direct capability discovery inquiry; resolved from live registry."
            )
        }

        if (lower == "environment" || lower == "env" || lower.contains("system info") || lower.contains("device info")) {
            return PreRoutingDecision.DirectToolMatch(
                toolName = "jarvis_environment",
                arguments = JSONObject(),
                confidence = 0.99f,
                reason = "Live environment status inquiry."
            )
        }

        // 2b. Coding / Project creation bypass (Never match open_app on "Make a React app")
        val isCreationQuery = (lower.startsWith("make ") || lower.startsWith("build ") || lower.startsWith("create ") ||
                lower.startsWith("write ") || lower.startsWith("develop ") || lower.startsWith("code ")) &&
                (lower.contains("app") || lower.contains("game") || lower.contains("program") || lower.contains("script") ||
                 lower.contains("calculator") || lower.contains("site") || lower.contains("website") || lower.contains("function"))
        if (isCreationQuery) {
            val classification = JarvisRouter.classify(trimmed)
            return PreRoutingDecision.ModelRoute(
                classification = classification,
                normalizedInput = trimmed,
                isExistingToolChecked = true
            )
        }

        // 3. Direct Filesystem Search ("find my downloaded PDF", "search for ...")
        if (lower.startsWith("find my downloaded ") || lower.startsWith("find my downloads") ||
            lower.startsWith("find my ") || (lower.startsWith("search for ") && lower.contains("file")) ||
            lower.contains("downloaded pdf") || lower.contains("downloaded files")) {
            val searchTerm = when {
                lower.contains("pdf") -> "pdf"
                lower.startsWith("find my downloaded ") -> lower.removePrefix("find my downloaded ").trim()
                lower.startsWith("find my ") -> lower.removePrefix("find my ").trim()
                lower.startsWith("search for ") -> lower.removePrefix("search for ").replace("file", "").trim()
                else -> lower.replace("find", "").replace("search", "").trim()
            }

            val searchPath = when {
                lower.contains("download") -> "/storage/emulated/0/Download"
                lower.contains("document") -> "/storage/emulated/0/Documents"
                else -> JarvisWorkspace.ROOT_DIR
            }

            val args = JSONObject().apply {
                put("query", searchTerm)
                put("path", searchPath)
            }
            return PreRoutingDecision.DirectToolMatch(
                toolName = "search_files",
                arguments = args,
                confidence = 0.98f,
                reason = "Filesystem document search resolved to physical storage without LLM."
            )
        }

        // 4. Existing Tool First: Deterministic Language Normalization (<5ms)
        val normalized = LanguageNormalizer.normalize(trimmed)
        if (normalized != null && normalized.confidence >= 0.90f) {
            CanonicalToolRegistry.init(context)
            val toolDef = CanonicalToolRegistry.get(normalized.tool)
            if (toolDef != null) {
                // Ensure arguments are normalized to workspace if path is present
                val args = normalized.args
                if (args.has("path")) {
                    val p = args.optString("path")
                    args.put("path", JarvisWorkspace.normalizePath(p))
                }

                return PreRoutingDecision.DirectToolMatch(
                    toolName = normalized.tool,
                    arguments = args,
                    confidence = normalized.confidence,
                    reason = "Direct match in Canonical Tool Registry [${normalized.tool}] via Needle Reflex."
                )
            }
        }

        // 5. Pass to Task Classifier (Casual, GK, Coding, Complex Reasoning)
        val classification = JarvisRouter.classify(trimmed)
        return PreRoutingDecision.ModelRoute(
            classification = classification,
            normalizedInput = trimmed,
            isExistingToolChecked = true
        )
    }
}
