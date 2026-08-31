package com.pr4nav.jarvis.voice

import android.content.Context
import java.util.regex.Pattern

/**
 * Dedicated Wake Word Engine Interface & Architecture.
 *
 * Separates Voice Activity Detection (VAD) from Wake Word verification.
 * Pluggable contract for on-device wake-word engines (e.g. Porcupine, OpenWakeWord, MicroWakeWord).
 */
interface WakeWordEngine {

    val name: String
    val isInstalled: Boolean

    fun initialize(context: Context): Boolean
    fun start(onWakeWordDetected: (String) -> Unit)
    fun stop()
    fun processAudioFrame(audioFrame: ShortArray): Boolean

    companion object {

        private val WAKE_PATTERN = Pattern.compile(
            """(?i)\b(?:hey|ok|okay|yo|hello|hi|sun|bhai|arrey|arre)?\s*(?:jarvis|javis|jarves|jarvees|jarvish|zarvis|jaervis|j-aa-r-v-i-s|j\s*a\s*r\s*v\s*i\s*s)\b"""
        )

        private val STOP_PATTERN = Pattern.compile(
            """(?i)\b(?:stop|shut\s*up|quiet|chup|ruko|bas|cancel|pause)\b"""
        )

        fun containsWakeWord(text: String): Boolean {
            if (text.isBlank()) return false
            val matcher = WAKE_PATTERN.matcher(text.trim())
            return matcher.find()
        }

        fun isStopCommand(text: String): Boolean {
            if (text.isBlank()) return false
            val matcher = STOP_PATTERN.matcher(text.trim())
            return matcher.find()
        }

        fun extractCommand(text: String): String {
            val trimmed = text.trim()
            val matcher = WAKE_PATTERN.matcher(trimmed)
            if (matcher.find()) {
                val after = trimmed.substring(matcher.end()).trim()
                return after.replaceFirst(Regex("""^[,:\-–—\s]+"""), "")
                    .replaceFirst(Regex("""(?i)^(?:can\s+you\s+|please\s+|kripya\s+|zara\s+)"""), "")
                    .trim()
            }
            return trimmed
        }
    }
}
