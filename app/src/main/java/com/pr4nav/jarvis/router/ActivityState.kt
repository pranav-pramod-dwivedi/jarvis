package com.pr4nav.jarvis.router

/**
 * Observable real-time activity states corresponding to actual application and router execution.
 * Guaranteed no simulated or fake thinking states.
 */
enum class ActivityState(val label: String, val icon: String) {
    LISTENING("Listening", "●"),
    UNDERSTANDING("Understanding", "●"),
    THINKING("Thinking", "●"),
    READING("Reading", "●"),
    SEARCHING("Searching", "●"),
    CHECKING("Checking", "●"),
    PLANNING("Planning", "●"),
    EXECUTING("Executing", "●"),
    WAITING_FOR_RESULT("Waiting for result", "●"),
    DONE("Done", "✓")
}

data class ActivityEvent(
    val state: ActivityState,
    val detail: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    fun toStatusString(): String = "${state.icon} ${state.label} · $detail"
}
