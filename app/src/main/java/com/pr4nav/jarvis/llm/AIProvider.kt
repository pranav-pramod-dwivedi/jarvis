package com.pr4nav.jarvis.llm

import android.content.Context
import com.pr4nav.jarvis.router.RouteEngine

/**
 * AI providers behind one selector. Chat UI lists providers only;
 * models are always fetched live — never hardcoded.
 */
enum class AIProvider(
    val title: String,
    val engine: RouteEngine,
    val hint: String
) {
    KIRA("Kira AI", RouteEngine.KIRA, "Free cascade · 1M context"),
    GROQ("Groq", RouteEngine.GROQ, "LPU inference · quotas apply"),
    GEMINI("Gemini", RouteEngine.GEMINI, "Google cloud · API key"),
    OLLAMA("Ollama Cloud", RouteEngine.OLLAMA, "Cloud models · API key");

    fun currentModel(context: Context): String = when (this) {
        KIRA -> {
            val raw = KiraClient.getModel(context)
            if (raw == KiraClient.MODEL_AUTO) {
                "Auto · ${KiraClient.modelLabel(KiraClient.lastAutoModel())}"
            } else {
                KiraClient.modelLabel(raw)
            }
        }
        GROQ -> GroqClient.getModel(context)
        GEMINI -> GeminiCloudLLM.getModel(context)
        OLLAMA -> OllamaClient.getModel(context)
    }

    fun currentModelId(context: Context): String = when (this) {
        KIRA -> KiraClient.getModel(context)
        GROQ -> GroqClient.getModel(context)
        GEMINI -> GeminiCloudLLM.getModel(context)
        OLLAMA -> OllamaClient.getModel(context)
    }

    fun setModel(context: Context, id: String) = when (this) {
        KIRA -> KiraClient.setModel(context, id)
        GROQ -> GroqClient.setModel(context, id)
        GEMINI -> GeminiCloudLLM.setModel(context, id)
        OLLAMA -> OllamaClient.setModel(context, id)
    }

    fun hasKey(context: Context): Boolean = when (this) {
        KIRA -> KiraClient.getApiKey(context).isNotBlank()
        GROQ -> GroqClient.getApiKey(context).isNotBlank()
        GEMINI -> GeminiCloudLLM.getApiKey(context).isNotBlank()
        OLLAMA -> OllamaClient.getApiKey(context).isNotBlank()
    }

    fun fetchModels(
        context: Context,
        onSuccess: (List<String>) -> Unit,
        onError: (String) -> Unit
    ) = when (this) {
        KIRA -> KiraClient.fetchAvailableModels(context = context, onSuccess = onSuccess, onError = onError)
        GROQ -> GroqClient.fetchAvailableModels(context = context, onSuccess = onSuccess, onError = onError)
        GEMINI -> GeminiCloudLLM.fetchAvailableModels(context = context, onSuccess = onSuccess, onError = onError)
        OLLAMA -> OllamaClient.fetchModels(context = context, onSuccess = onSuccess, onError = onError)
    }
}
