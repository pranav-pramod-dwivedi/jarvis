package com.pr4nav.jarvis.pin

import org.json.JSONObject

enum class PinPriority {
    NORMAL,
    JEE_STUDY,
    CRITICAL,
    SYSTEM
}

data class StickyPin(
    val id: String,
    val title: String,
    val body: String,
    val createdAt: Long = System.currentTimeMillis(),
    val priority: PinPriority = PinPriority.NORMAL,
    val escalationStep: Int = 0, // 0: Silent, 1: Buzz, 2: Voice drop, 3: Full-screen / alarm
    val lastEscalatedAt: Long = System.currentTimeMillis(),
    val isProactive: Boolean = false,
    val targetTimeMs: Long = 0L,
    val acknowledged: Boolean = false
) {
    val notificationId: Int
        get() = kotlin.math.abs(id.hashCode())

    fun toJson(): JSONObject {
        return JSONObject().apply {
            put("id", id)
            put("title", title)
            put("body", body)
            put("createdAt", createdAt)
            put("priority", priority.name)
            put("escalationStep", escalationStep)
            put("lastEscalatedAt", lastEscalatedAt)
            put("isProactive", isProactive)
            put("targetTimeMs", targetTimeMs)
            put("acknowledged", acknowledged)
        }
    }

    companion object {
        fun fromJson(obj: JSONObject): StickyPin {
            val prioName = obj.optString("priority", PinPriority.NORMAL.name)
            val prio = try { PinPriority.valueOf(prioName) } catch (_: Exception) { PinPriority.NORMAL }
            return StickyPin(
                id = obj.optString("id", System.currentTimeMillis().toString()),
                title = obj.optString("title", "Pin"),
                body = obj.optString("body", ""),
                createdAt = obj.optLong("createdAt", System.currentTimeMillis()),
                priority = prio,
                escalationStep = obj.optInt("escalationStep", 0),
                lastEscalatedAt = obj.optLong("lastEscalatedAt", System.currentTimeMillis()),
                isProactive = obj.optBoolean("isProactive", false),
                targetTimeMs = obj.optLong("targetTimeMs", 0L),
                acknowledged = obj.optBoolean("acknowledged", false)
            )
        }
    }
}
