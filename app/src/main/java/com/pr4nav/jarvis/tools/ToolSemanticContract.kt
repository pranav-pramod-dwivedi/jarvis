package com.pr4nav.jarvis.tools

import com.pr4nav.jarvis.intent.IntentCategory
import org.json.JSONObject

sealed class SemanticCheckResult {
    object Passed : SemanticCheckResult()
    data class Rejected(val reasonCode: String, val message: String) : SemanticCheckResult()
}

data class ToolSemanticContract(
    val toolName: String,
    val category: IntentCategory,
    val description: String,
    val validExamples: List<String>,
    val invalidExamples: List<String>,
    val customGuard: ((prompt: String, args: JSONObject) -> SemanticCheckResult)? = null
)

/**
 * Registry of Tool Semantic Contracts and Semantic Guards.
 * Protects against semantically invalid tool calls (e.g. calling media.play on "Who is Narendra Modi?").
 */
object ToolSemanticContracts {

    private val contracts = mutableMapOf<String, ToolSemanticContract>()

    init {
        // media.play & media.*
        register(
            ToolSemanticContract(
                toolName = "media.play",
                category = IntentCategory.MEDIA,
                description = "Play music, songs, tracks, or video content",
                validExamples = listOf("play Believer", "play some music", "play Arijit Singh", "gaana chalao"),
                invalidExamples = listOf("who is Narendra Modi", "what is 2+2", "explain Kotlin crash", "open settings"),
                customGuard = { prompt, args ->
                    val cleanPrompt = prompt.trim().lowercase()
                    val query = args.optString("query").trim().lowercase()

                    val isInformational = cleanPrompt.startsWith("who is") || cleanPrompt.startsWith("who was") ||
                            cleanPrompt.startsWith("what is") || cleanPrompt.startsWith("what was") ||
                            cleanPrompt.startsWith("why is") || cleanPrompt.startsWith("explain") ||
                            cleanPrompt.startsWith("where is") || cleanPrompt.startsWith("tell me about")

                    if (isInformational) {
                        SemanticCheckResult.Rejected(
                            "SEMANTIC_MISMATCH",
                            "media.play cannot satisfy an informational or general knowledge question"
                        )
                    } else if (cleanPrompt.contains("+") || cleanPrompt.contains("-") || cleanPrompt.contains("*") || cleanPrompt.contains("/")) {
                        SemanticCheckResult.Rejected(
                            "SEMANTIC_MISMATCH",
                            "media.play cannot satisfy a mathematical calculation"
                        )
                    } else {
                        SemanticCheckResult.Passed
                    }
                }
            )
        )

        // system.torch
        register(
            ToolSemanticContract(
                toolName = "system.torch",
                category = IntentCategory.DEVICE_CONTROL,
                description = "Turns device flashlight/torch on or off",
                validExamples = listOf("turn on flashlight", "torch on", "torch chalu kar", "light on kar"),
                invalidExamples = listOf("play music", "who is PM", "call Akhil"),
                customGuard = { prompt, _ ->
                    val clean = prompt.trim().lowercase()
                    val hasTorchCue = clean.contains("torch") || clean.contains("flashlight") || clean.contains("flash") || clean.contains("light on") || clean.contains("light off")
                    if (!hasTorchCue) {
                        SemanticCheckResult.Rejected(
                            "SEMANTIC_MISMATCH",
                            "system.torch requires flashlight or torch keywords"
                        )
                    } else {
                        SemanticCheckResult.Passed
                    }
                }
            )
        )

        // system.volume
        register(
            ToolSemanticContract(
                toolName = "system.volume",
                category = IntentCategory.DEVICE_CONTROL,
                description = "Adjusts device volume",
                validExamples = listOf("increase volume", "volume down", "mute phone", "volume badha"),
                invalidExamples = listOf("what is 2+2", "play music"),
                customGuard = { prompt, _ ->
                    val clean = prompt.trim().lowercase()
                    val hasVolCue = clean.contains("volume") || clean.contains("sound") || clean.contains("awaaz") || clean.contains("mute") || clean.contains("silent")
                    if (!hasVolCue) {
                        SemanticCheckResult.Rejected(
                            "SEMANTIC_MISMATCH",
                            "system.volume requires audio or volume keywords"
                        )
                    } else {
                        SemanticCheckResult.Passed
                    }
                }
            )
        )

        // search_web
        register(
            ToolSemanticContract(
                toolName = "search_web",
                category = IntentCategory.INFORMATION,
                description = "Searches web for information and facts",
                validExamples = listOf("search for Narendra Modi", "who is president of France", "google latest news"),
                invalidExamples = listOf("turn on flashlight", "mute phone")
            )
        )

        // call_contact
        register(
            ToolSemanticContract(
                toolName = "call_contact",
                category = IntentCategory.COMMUNICATION,
                description = "Places a phone call to a contact or number",
                validExamples = listOf("call Akhil", "dial 9876543210", "phone mummy"),
                invalidExamples = listOf("who is Narendra Modi", "turn on flashlight")
            )
        )

        // navigate
        register(
            ToolSemanticContract(
                toolName = "navigate",
                category = IntentCategory.NAVIGATION,
                description = "Provides turn-by-turn navigation or directions",
                validExamples = listOf("take me home", "navigate to Pune", "directions to airport"),
                invalidExamples = listOf("play Believer", "what is 2+2")
            )
        )

        // open_app
        register(
            ToolSemanticContract(
                toolName = "open_app",
                category = IntentCategory.APPS,
                description = "Opens an installed application",
                validExamples = listOf("open Instagram", "launch WhatsApp", "chrome kholo"),
                invalidExamples = listOf("who is Narendra Modi", "what is 2+2")
            )
        )
    }

    fun register(contract: ToolSemanticContract) {
        contracts[contract.toolName] = contract
    }

    fun get(toolName: String): ToolSemanticContract? = contracts[toolName]

    fun checkContract(toolName: String, prompt: String, args: JSONObject): SemanticCheckResult {
        val contract = contracts[toolName] ?: return SemanticCheckResult.Passed
        val custom = contract.customGuard
        if (custom != null) {
            return custom.invoke(prompt, args)
        }
        return SemanticCheckResult.Passed
    }
}
