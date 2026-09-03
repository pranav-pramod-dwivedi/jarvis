package com.pr4nav.jarvis.companion

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Opt-in Companion Mode Assistant Manager.
 *
 * Settings -> Assistant -> Companion Mode -> ON / OFF (Default: OFF)
 *
 * Rules:
 * - Battery-conscious: Uses Android accessibility signals / app transitions rather than constant LLM looping.
 * - No constant cloud requests.
 * - No unnecessary screenshot captures.
 * - Gentle, human suggestions: "Looking for something?", "Need help finding that file?"
 * - Can be paused or muted instantly.
 */
object CompanionManager {

    private const val TAG = "CompanionManager"
    private const val PREFS_NAME = "jarvis_companion_prefs"
    private const val KEY_COMPANION_ENABLED = "companion_mode_enabled"
    private const val KEY_COMPANION_PAUSED = "companion_mode_paused"

    private val isRunning = AtomicBoolean(false)
    private var lastSuggestionTime = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    private var repeatCount = 0
    private var lastActivePackage: String? = null

    fun isEnabled(context: Context): Boolean {
        // Greyed out for now per user directive
        return false
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPANION_ENABLED, enabled).apply()
        if (enabled) start(context) else stop()
    }

    fun isPaused(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_COMPANION_PAUSED, false)
    }

    fun setPaused(context: Context, paused: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_COMPANION_PAUSED, paused).apply()
    }

    fun start(context: Context) {
        if (!isEnabled(context) || isPaused(context) || isRunning.getAndSet(true)) return
        Log.i(TAG, "Companion Mode started in lightweight passive mode")
    }

    fun stop() {
        isRunning.set(false)
        Log.i(TAG, "Companion Mode stopped")
    }

    /**
     * Called on window state / accessibility events from JarvisAccessibilityService.
     * Evaluates whether proactive, non-intrusive help is appropriate.
     */
    fun onAccessibilityEvent(context: Context, packageName: String?, eventType: Int) {
        if (!isEnabled(context) || isPaused(context)) return

        val now = System.currentTimeMillis()
        if (now - lastSuggestionTime < 60_000L) {
            // Rate-limit proactive suggestions to at most once per 60 seconds
            return
        }

        if (packageName != null && packageName == lastActivePackage) {
            repeatCount++
        } else {
            repeatCount = 1
            lastActivePackage = packageName
        }

        // Example trigger: User repeatedly searching settings or downloads
        if (repeatCount >= 3 && packageName != null) {
            when {
                packageName.contains("settings") -> {
                    lastSuggestionTime = now
                    repeatCount = 0
                    notifyProactive(context, "Looking for a specific setting? I can open it directly for you.")
                }
                packageName.contains("documentsui") || packageName.contains("file") || packageName.contains("download") -> {
                    lastSuggestionTime = now
                    repeatCount = 0
                    notifyProactive(context, "Looking for a file? Ask me and I'll find it instantly.")
                }
            }
        }
    }

    private fun notifyProactive(context: Context, suggestion: String) {
        mainHandler.post {
            try {
                android.widget.Toast.makeText(context, "🤖 JARVIS: $suggestion", android.widget.Toast.LENGTH_LONG).show()
                Log.d(TAG, "Proactive suggestion offered: $suggestion")
            } catch (e: Exception) {
                Log.w(TAG, "Failed notifying suggestion: ${e.message}")
            }
        }
    }
}
