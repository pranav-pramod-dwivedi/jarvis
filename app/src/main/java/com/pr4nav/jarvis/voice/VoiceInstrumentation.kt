package com.pr4nav.jarvis.voice

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Diagnostic instrumentation for Voice Assistant events.
 * Logs and tracks:
 * - wakeWordActivations
 * - falseActivations
 * - sttSessionsStarted
 * - sttSessionsCompleted
 * - sttErrors
 * - vadEvents
 * - listener start/stop timestamps
 */
object VoiceInstrumentation {

    private const val TAG = "JarvisVoiceAudit"
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    data class Event(
        val timestamp: Long,
        val type: String,
        val details: String,
        val assistantState: String
    )

    private val eventLog = mutableListOf<Event>()

    @Volatile var wakeWordActivations = 0
    @Volatile var falseActivations = 0
    @Volatile var sttSessionsStarted = 0
    @Volatile var sttSessionsCompleted = 0
    @Volatile var sttErrors = 0
    @Volatile var vadEvents = 0

    @Volatile var lastStartReason: String = "None"
    @Volatile var lastStopReason: String = "None"
    @Volatile var lastStartTime: Long = 0
    @Volatile var lastStopTime: Long = 0

    @Synchronized
    fun log(type: String, details: String, state: String) {
        val now = System.currentTimeMillis()
        val formattedTime = dateFormat.format(Date(now))
        val evt = Event(now, type, details, state)
        if (eventLog.size > 200) {
            eventLog.removeAt(0)
        }
        eventLog.add(evt)
        Log.i(TAG, "[$formattedTime] [$state] $type: $details")
    }

    fun onVadEvent(details: String, state: String) {
        vadEvents++
        log("VAD_ACTIVITY", details, state)
    }

    fun onWakeWordConfirmed(wakeWord: String, state: String) {
        wakeWordActivations++
        log("WAKE_CONFIRMED", "Wake Word: $wakeWord (Total: $wakeWordActivations)", state)
    }

    fun onFalseActivation(reason: String, state: String) {
        falseActivations++
        log("FALSE_ACTIVATION", reason, state)
    }

    fun onListenerStart(reason: String, state: String) {
        sttSessionsStarted++
        lastStartReason = reason
        lastStartTime = System.currentTimeMillis()
        log("STT_SESSION_START", "Reason: $reason (Total starts: $sttSessionsStarted)", state)
    }

    fun onListenerStop(reason: String, state: String) {
        sttSessionsCompleted++
        lastStopReason = reason
        lastStopTime = System.currentTimeMillis()
        val durationMs = if (lastStartTime > 0) lastStopTime - lastStartTime else 0
        log("STT_SESSION_COMPLETE", "Reason: $reason (Duration: ${durationMs}ms, Total: $sttSessionsCompleted)", state)
    }

    fun onError(errorCode: Int, errorMessage: String, state: String) {
        sttErrors++
        log("STT_ERROR", "Code: $errorCode, Msg: $errorMessage, Total errors: $sttErrors", state)
    }

    fun onSuccess(recognizedText: String, state: String) {
        log("STT_SUCCESS", "\"$recognizedText\"", state)
    }

    @Synchronized
    fun getRecentLog(): List<Event> = eventLog.toList()
}
