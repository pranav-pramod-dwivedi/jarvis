package com.pr4nav.jarvis.capabilities

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.JarvisAccessibilityService
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import kotlin.concurrent.thread

/**
 * High-Level On-Screen Context Reader & Analyzer.
 *
 * Enables JARVIS to "see" and understand whatever the user is currently looking at:
 * - "What is on my screen?"
 * - "Summarize this article"
 * - "Read the text on screen"
 * - "Extract phone numbers / codes on screen"
 */
object ScreenContextReader {

    private const val TAG = "ScreenContextReader"

    fun isAccessibilityActive(): Boolean {
        return JarvisAccessibilityService.instance != null
    }

    fun getVisibleScreenSummary(): String {
        if (!isAccessibilityActive()) {
            return "Accessibility Service is not enabled. Please enable JARVIS in Accessibility Settings to allow screen reading."
        }
        val raw = JarvisAccessibilityService.screenText()
        if (raw.isBlank()) {
            return "No text content detected on the current screen."
        }
        return raw
    }

    fun analyzeAndSummarizeScreen(context: Context, userQuery: String = "Summarize what is on my screen concisely", onResult: (String) -> Unit) {
        if (!isAccessibilityActive()) {
            onResult("Accessibility service is disabled. Enable JARVIS in Accessibility Settings to analyze screen content.")
            return
        }

        thread(name = "ScreenAnalyzer") {
            try {
                val screenContent = getVisibleScreenSummary()
                val prompt = "Current screen text content:\n\"\"\"\n$screenContent\n\"\"\"\n\nUser request: $userQuery\nProvide a clear, natural, spoken summary in 2-3 sentences."
                UnifiedAssistantDispatcher.execute(context, prompt) { res ->
                    onResult(res.speechResponse)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Screen analysis error: ${e.message}", e)
                onResult("Unable to analyze screen content at this moment.")
            }
        }
    }
}
