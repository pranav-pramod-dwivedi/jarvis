package com.pr4nav.jarvis.needle

import android.content.Context

enum class RiskLevel {
    LOW,     // Harmless local actions: torch, battery, volume, time, play music, gui
    MEDIUM,  // State modifications: alarm, timer, safe file operations, safe diag
    HIGH     // Destructive operations or external communications: file delete, messaging
}

object NeedleConfig {

    private const val PREFS_NAME = "needle_config"
    private const val KEY_HIGH_THRESH = "high_confidence_thresh"
    private const val KEY_MED_THRESH = "med_confidence_thresh"
    private const val KEY_DESTRUCTIVE_THRESH = "destructive_thresh"

    var highConfidenceThreshold: Double = 0.75
    var mediumConfidenceThreshold: Double = 0.50
    var destructiveThreshold: Double = 0.90

    fun init(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        highConfidenceThreshold = prefs.getFloat(KEY_HIGH_THRESH, 0.75f).toDouble()
        mediumConfidenceThreshold = prefs.getFloat(KEY_MED_THRESH, 0.50f).toDouble()
        destructiveThreshold = prefs.getFloat(KEY_DESTRUCTIVE_THRESH, 0.90f).toDouble()
    }

    fun save(context: Context, high: Double, med: Double, destructive: Double) {
        highConfidenceThreshold = high
        mediumConfidenceThreshold = med
        destructiveThreshold = destructive
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putFloat(KEY_HIGH_THRESH, high.toFloat())
            .putFloat(KEY_MED_THRESH, med.toFloat())
            .putFloat(KEY_DESTRUCTIVE_THRESH, destructive.toFloat())
            .apply()
    }

    fun riskLevel(tool: String): RiskLevel = when {
        tool.startsWith("file.delete") || tool.startsWith("messaging.send") || tool.startsWith("root.exec") ->
            RiskLevel.HIGH
        tool.startsWith("file.write") || tool.startsWith("device.alarm") || tool.startsWith("device.timer") ||
        tool.startsWith("system.alarm") || tool.startsWith("system.timer") || tool.startsWith("notes.create") ->
            RiskLevel.MEDIUM
        else ->
            RiskLevel.LOW
    }
}
