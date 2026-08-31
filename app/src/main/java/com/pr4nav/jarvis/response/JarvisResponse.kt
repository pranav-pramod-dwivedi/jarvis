package com.pr4nav.jarvis.response

/**
 * The Single Canonical User Response Object.
 * The UI and TTS layers ONLY consume this object and NEVER receive raw model/tool JSON.
 */
data class JarvisResponse(
    /** Clean, natural human language strictly formatted for user display */
    val text: String,

    /** Clean, natural speech strictly formatted for TTS (no markdown, no debug symbols) */
    val speechText: String = text,

    /** Terminal status contract */
    val status: TerminationStatus = TerminationStatus.FINAL_ANSWER,

    /** Intended response mode */
    val mode: ResponseMode = ResponseMode.ANSWER,

    /** Whether this represents a graceful error */
    val isError: Boolean = false,

    /** Diagnostic metadata ONLY accessible to the Developer Test Lab / Inspector */
    val debugMetadata: Map<String, Any> = emptyMap()
) {
    companion object {
        fun of(
            text: String,
            speechText: String = text,
            status: TerminationStatus = TerminationStatus.FINAL_ANSWER,
            mode: ResponseMode = ResponseMode.ANSWER,
            query: String? = null
        ): JarvisResponse {
            val cleanText = UserResponseSanitizer.sanitize(text, query)
            val cleanSpeech = UserResponseSanitizer.sanitizeForSpeech(speechText, query)
            return JarvisResponse(
                text = cleanText,
                speechText = cleanSpeech,
                status = status,
                mode = mode,
                isError = false
            )
        }

        fun error(
            message: String,
            query: String? = null
        ): JarvisResponse {
            val cleanText = UserResponseSanitizer.sanitize(message, query)
            val cleanSpeech = UserResponseSanitizer.sanitizeForSpeech(message, query)
            return JarvisResponse(
                text = cleanText,
                speechText = cleanSpeech,
                status = TerminationStatus.EXECUTION_FAILED,
                mode = ResponseMode.ANSWER,
                isError = true
            )
        }
    }
}
