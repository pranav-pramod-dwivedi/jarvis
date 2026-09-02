package com.pr4nav.jarvis.context

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

data class CandidateItem(
    val index: Int,
    val label: String,
    val value: String,
    val metadata: JSONObject? = null
)

sealed class ContextContinuationResult {
    data class ResolvedAction(
        val toolName: String,
        val arguments: JSONObject,
        val confidence: Float,
        val reason: String
    ) : ContextContinuationResult()

    data class ResolvedText(
        val text: String,
        val confidence: Float,
        val reason: String
    ) : ContextContinuationResult()

    object None : ContextContinuationResult()
}

/**
 * Deterministic Context Manager & Disambiguation Engine.
 *
 * Resolves:
 * 1. Disambiguation candidate selections ("Call Akhil" -> "first one" / "1" / "second").
 * 2. Device property state toggles ("Is Bluetooth on?" -> "Turn it off" -> "Actually turn it back on").
 * 3. Pronoun resolution via ConversationalContext.
 * 4. Context TTL expiry (5 minutes).
 */
object ContextManager {

    private const val CONTEXT_TTL_MS = 5 * 60 * 1000L // 5 minutes

    data class ActiveContext(
        var lastToolName: String? = null,
        var lastToolDomain: String? = null,
        var lastArguments: JSONObject? = null,
        var lastCandidates: List<CandidateItem> = emptyList(),
        var pendingAction: String? = null,
        var lastStateQuery: String? = null,
        var timestamp: Long = System.currentTimeMillis()
    )

    private val context = ActiveContext()

    fun updateToolContext(toolName: String, args: JSONObject, domain: String? = null) {
        context.lastToolName = toolName
        context.lastToolDomain = domain ?: when {
            toolName.contains("bluetooth") -> "bluetooth"
            toolName.contains("torch") || toolName.contains("flashlight") -> "torch"
            toolName.contains("wifi") -> "wifi"
            toolName.contains("volume") -> "volume"
            toolName.contains("call") -> "call"
            else -> "generic"
        }
        context.lastArguments = args
        context.timestamp = System.currentTimeMillis()

        ConversationalContext.updateContext(toolName, args)
    }

    fun setCandidateList(pendingAction: String, candidates: List<CandidateItem>) {
        context.pendingAction = pendingAction
        context.lastCandidates = candidates
        context.timestamp = System.currentTimeMillis()
    }

    fun clearCandidates() {
        context.pendingAction = null
        context.lastCandidates = emptyList()
    }

    fun isExpired(): Boolean {
        return (System.currentTimeMillis() - context.timestamp) > CONTEXT_TTL_MS
    }

    fun clear() {
        context.lastToolName = null
        context.lastToolDomain = null
        context.lastArguments = null
        context.lastCandidates = emptyList()
        context.pendingAction = null
        context.lastStateQuery = null
        context.timestamp = System.currentTimeMillis()
        ConversationalContext.clear()
    }

    /**
     * Resolves multi-turn input against active conversational context before invoking any LLM.
     */
    fun resolveContinuation(input: String): ContextContinuationResult {
        if (isExpired()) {
            clear()
            return ContextContinuationResult.None
        }

        val trimmed = input.trim()
        val lower = trimmed.lowercase(Locale.ROOT)

        // 0. Explicit Cancellation of Pending Candidates / Disambiguation
        if (lower in listOf("cancel", "cancel that", "abort", "stop", "never mind", "nevermind", "dismiss")) {
            if (context.lastCandidates.isNotEmpty() || context.pendingAction != null) {
                clearCandidates()
                return ContextContinuationResult.ResolvedText(
                    text = "Cancelled.",
                    confidence = 1.0f,
                    reason = "User explicitly cancelled pending selection."
                )
            }
        }

        // 1. Candidate List Selection (e.g. "first one", "the second one", "1", "2", "no, second", "actually third", "the other one")
        if (context.lastCandidates.isNotEmpty() && context.pendingAction != null) {
            val selectedIndex = parseOrdinalIndex(lower, context.lastCandidates.size)
            if (selectedIndex != null && selectedIndex in 1..context.lastCandidates.size) {
                val candidate = context.lastCandidates[selectedIndex - 1]
                val action = context.pendingAction!!
                val resolvedArgs = JSONObject()

                when (action) {
                    "call_contact" -> {
                        resolvedArgs.put("number", candidate.value)
                        resolvedArgs.put("contact", candidate.label)
                    }
                    "open_app" -> {
                        resolvedArgs.put("package", candidate.value)
                        resolvedArgs.put("app", candidate.label)
                    }
                    "open_file", "read_file" -> {
                        resolvedArgs.put("path", candidate.value)
                    }
                    else -> {
                        resolvedArgs.put("value", candidate.value)
                    }
                }

                val toolName = action
                val candidateLabel = candidate.label
                clearCandidates()

                return ContextContinuationResult.ResolvedAction(
                    toolName = toolName,
                    arguments = resolvedArgs,
                    confidence = 0.99f,
                    reason = "Resolved candidate #$selectedIndex ('$candidateLabel') from pending $toolName selection."
                )
            }
        }

        // 2. State & Property Toggle Continuation ("Turn it off", "Turn it back on", "Actually turn it on", "Actually turn it back on")
        val isOff = lower in listOf(
            "turn it off", "switch it off", "band kar do", "turn off", "disable it", "off",
            "turn that off", "switch that off", "actually turn it off", "please turn it off"
        )
        val isOn = lower in listOf(
            "turn it on", "switch it on", "chalu kar do", "turn on", "enable it",
            "actually turn it back on", "turn it back on", "on", "turn that on", "actually turn it on",
            "please turn it on", "switch it back on", "turn it on again"
        )

        if ((isOff || isOn) && context.lastToolDomain != null) {
            val targetState = isOn
            val domain = context.lastToolDomain!!

            val (tool, argKey) = when (domain) {
                "bluetooth" -> "system.bluetooth" to "state"
                "torch" -> "system.torch" to "state"
                "wifi" -> "system.wifi" to "state"
                else -> null to null
            }

            if (tool != null && argKey != null) {
                val args = JSONObject().put(argKey, targetState)
                updateToolContext(tool, args, domain)
                return ContextContinuationResult.ResolvedAction(
                    toolName = tool,
                    arguments = args,
                    confidence = 0.98f,
                    reason = "Contextual state toggle for $domain (state: $targetState)."
                )
            }
        }

        // 3. Pronoun resolution fallback
        val pronounResolved = ConversationalContext.resolvePronouns(trimmed)
        if (pronounResolved != trimmed) {
            return ContextContinuationResult.ResolvedText(
                text = pronounResolved,
                confidence = 0.95f,
                reason = "Resolved conversational pronouns ('$trimmed' -> '$pronounResolved')."
            )
        }

        return ContextContinuationResult.None
    }

    private fun parseOrdinalIndex(input: String, maxCount: Int): Int? {
        val clean = input
            .replace("the ", "")
            .replace("one", "")
            .replace("no,", "")
            .replace("no ", "")
            .replace("actually ", "")
            .replace("please ", "")
            .trim()
        when (clean) {
            "1", "first", "1st" -> return 1
            "2", "second", "2nd", "other" -> return 2
            "3", "third", "3rd" -> return 3
            "4", "fourth", "4th" -> return 4
            "5", "fifth", "5th" -> return 5
            "last" -> return maxCount
        }
        return clean.toIntOrNull()
    }
}
