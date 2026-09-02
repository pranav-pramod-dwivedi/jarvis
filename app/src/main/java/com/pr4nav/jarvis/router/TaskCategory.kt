package com.pr4nav.jarvis.router

/**
 * Explicit auto-router task categories according to the JARVIS multi-layer routing architecture.
 */
enum class TaskCategory(val label: String, val description: String) {
    DEVICE_COMMAND(
        "Device Command",
        "On-device control: Bluetooth, Wi-Fi, torch, volume, apps, calls, settings, files, screenshot"
    ),
    CASUAL(
        "Casual Conversation",
        "Normal conversation, opinions, greetings, small talk, companion interaction"
    ),
    GENERAL_KNOWLEDGE(
        "General Knowledge",
        "Facts, GK, science, history, geography, conceptual questions"
    ),
    CODING(
        "Coding",
        "Programming, code generation, debugging, refactoring, building, script automation"
    ),
    COMPLEX_REASONING(
        "Complex Reasoning",
        "Multi-step planning, workspace analysis, deep debugging, agent workflows"
    )
}

data class TaskClassification(
    val category: TaskCategory,
    val confidence: Float,
    val reasoning: String,
    val detectedObject: String? = null,
    val detectedAction: String? = null
)
