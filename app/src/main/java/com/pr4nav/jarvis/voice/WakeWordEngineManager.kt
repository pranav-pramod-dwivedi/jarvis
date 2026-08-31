package com.pr4nav.jarvis.voice

import android.content.Context
import android.util.Log

/**
 * Manages Wake Word Engine discovery, initialization, and verification.
 * Honestly reports whether a native on-device wake-word model is installed
 * vs running in VAD-only or Manual Trigger mode.
 */
object WakeWordEngineManager {

    private const val TAG = "WakeWordEngineManager"

    private var activeEngine: WakeWordEngine? = null

    fun getActiveEngine(context: Context): WakeWordEngine {
        if (activeEngine == null) {
            val engine = OnnxWakeWordEngine()
            engine.initialize(context)
            activeEngine = engine
        }
        return activeEngine!!
    }

    /**
     * Checks if a true local wake-word model is installed and operational.
     */
    fun isWakeWordEngineInstalled(context: Context): Boolean {
        return getActiveEngine(context).isInstalled
    }

    /**
     * Engine implementation placeholder for future ONNX / TFLite / Porcupine wake models.
     */
    private class PluggableLocalWakeWordEngine : WakeWordEngine {
        override val name: String = "Pluggable Local Wake Word Engine"
        override val isInstalled: Boolean = false // Honestly reports NOT INSTALLED until model file is present

        override fun initialize(context: Context): Boolean {
            Log.d(TAG, "Initialized $name (Model not present in app storage)")
            return false
        }

        override fun start(onWakeWordDetected: (String) -> Unit) {}
        override fun stop() {}
        override fun processAudioFrame(audioFrame: ShortArray): Boolean = false
    }
}
