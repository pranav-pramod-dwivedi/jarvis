package com.pr4nav.jarvis.chat

/** Text assembly shared by streaming renderers; independent of Android views. */
class StreamingReply {
    private val text = StringBuilder()

    fun append(delta: String) {
        // Transport boundaries have no formatting meaning. Even whitespace-only
        // deltas can separate words or carry an intentional paragraph break.
        text.append(delta)
    }

    fun snapshot(): String = text.toString()

    // Final is an authoritative snapshot, never an additional text fragment.
    fun finish(finalText: String): String = finalText.trim().ifBlank { snapshot().trim() }
}
