package com.pr4nav.jarvis.setup

import android.content.Context

/**
 * Manages first-run setup state for JARVIS.
 * Ensures the first-time setup screen only appears once.
 */
object SetupManager {
    private const val PREFS_NAME = "jarvis_setup_prefs"
    private const val KEY_SETUP_COMPLETED = "key_first_time_setup_completed"

    fun isSetupCompleted(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_SETUP_COMPLETED, false)
    }

    fun setSetupCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_SETUP_COMPLETED, completed)
            .apply()
    }
}
