package com.pr4nav.jarvis.tools

import com.pr4nav.jarvis.intent.IntentCategory
import com.pr4nav.jarvis.intent.IntentClassifier
import org.json.JSONObject

data class ToolScoreBreakdown(
    val toolName: String,
    val totalScore: Int,
    val categoryScore: Int,
    val lexicalScore: Int,
    val schemaScore: Int,
    val isAccepted: Boolean,
    val rejectionReason: String? = null
)

/**
 * Calculates deterministic routing scores for candidate tool calls.
 * Ignores raw model confidence in favor of verifiable multi-factor score.
 */
object ToolRoutingScore {

    fun scoreCandidate(
        toolName: String,
        prompt: String,
        args: JSONObject,
        schemaValid: Boolean
    ): ToolScoreBreakdown {
        val classified = IntentClassifier.classify(prompt)
        val contract = ToolSemanticContracts.get(toolName)

        // 1. Run semantic contract guard first
        val guardRes = ToolSemanticContracts.checkContract(toolName, prompt, args)
        if (guardRes is SemanticCheckResult.Rejected) {
            return ToolScoreBreakdown(
                toolName = toolName,
                totalScore = 0,
                categoryScore = 0,
                lexicalScore = 0,
                schemaScore = if (schemaValid) 20 else 0,
                isAccepted = false,
                rejectionReason = "${guardRes.reasonCode}: ${guardRes.message}"
            )
        }

        // 2. Category Match (40 pts)
        val categoryScore = if (contract != null && contract.category == classified.category) {
            40
        } else if (contract == null) {
            20
        } else {
            0
        }

        // 3. Lexical Match (30 pts)
        val cleanPrompt = prompt.lowercase()
        var lexicalScore = 0
        if (contract != null) {
            for (example in contract.validExamples) {
                val words = example.lowercase().split(" ")
                if (words.any { cleanPrompt.contains(it) }) {
                    lexicalScore = 30
                    break
                }
            }
        } else {
            lexicalScore = 15
        }

        // 4. Schema Score (20 pts)
        val schemaScore = if (schemaValid) 20 else 0

        // 5. Context Score (10 pts)
        val contextScore = 10

        val total = categoryScore + lexicalScore + schemaScore + contextScore
        val accepted = total >= 50 && schemaValid

        return ToolScoreBreakdown(
            toolName = toolName,
            totalScore = total,
            categoryScore = categoryScore,
            lexicalScore = lexicalScore,
            schemaScore = schemaScore,
            isAccepted = accepted,
            rejectionReason = if (!accepted) "Low routing score ($total/100, threshold 50)" else null
        )
    }
}
