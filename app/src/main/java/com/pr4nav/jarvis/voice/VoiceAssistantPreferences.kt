package com.pr4nav.jarvis.voice

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager

/**
 * Settings & State Manager for JARVIS Hands-Free Voice Assistant.
 */
object VoiceAssistantPreferences {

    private const val PREFS_NAME = "jarvis_voice_assistant_prefs"

    const val KEY_HANDS_FREE_ENABLED = "hands_free_enabled"
    const val KEY_LANGUAGE = "voice_language" // "auto", "en", "hi"
    const val KEY_SPEECH_RATE = "tts_speech_rate" // 0.8 to 1.5
    const val KEY_CONVERSATION_MODE = "conversation_mode_enabled"
    const val KEY_FOLLOW_UP_DURATION_SEC = "follow_up_duration_sec"
    const val KEY_BARGE_IN_ENABLED = "barge_in_enabled"
    const val KEY_START_ON_BOOT = "start_on_boot"
    const val KEY_WAKE_CONFIDENCE = "wake_confidence_threshold" // default 0.50f

    fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isHandsFreeEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_HANDS_FREE_ENABLED, true) // Default: ON (Always Listen)

    fun setHandsFreeEnabled(context: Context, enabled: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_HANDS_FREE_ENABLED, enabled).apply()
        if (enabled) {
            JarvisVoiceService.start(context)
        } else {
            JarvisVoiceService.stop(context)
        }
    }

    fun getWakeConfidenceThreshold(context: Context): Float =
        getPrefs(context).getFloat(KEY_WAKE_CONFIDENCE, 0.30f)

    fun setWakeConfidenceThreshold(context: Context, threshold: Float) =
        getPrefs(context).edit().putFloat(KEY_WAKE_CONFIDENCE, threshold).apply()

    fun isConversationMode(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_CONVERSATION_MODE, true)

    fun setConversationMode(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_CONVERSATION_MODE, enabled).apply()

    fun getFollowUpDurationSec(context: Context): Int =
        getPrefs(context).getInt(KEY_FOLLOW_UP_DURATION_SEC, 6)

    fun isBargeInEnabled(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_BARGE_IN_ENABLED, true)

    fun isStartOnBoot(context: Context): Boolean =
        getPrefs(context).getBoolean(KEY_START_ON_BOOT, true)

    fun setStartOnBoot(context: Context, enabled: Boolean) =
        getPrefs(context).edit().putBoolean(KEY_START_ON_BOOT, enabled).apply()

    fun getSpeechRate(context: Context): Float =
        getPrefs(context).getFloat(KEY_SPEECH_RATE, 1.05f)

    fun setSpeechRate(context: Context, rate: Float) =
        getPrefs(context).edit().putFloat(KEY_SPEECH_RATE, rate).apply()

    fun getLanguage(context: Context): String =
        getPrefs(context).getString(KEY_LANGUAGE, "auto") ?: "auto"

    fun setLanguage(context: Context, lang: String) =
        getPrefs(context).edit().putString(KEY_LANGUAGE, lang).apply()

    /**
     * Checks if the app is exempt from battery optimizations.
     */
    fun isBatteryOptimizationsIgnored(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        return pm?.isIgnoringBatteryOptimizations(context.packageName) ?: false
    }
}
