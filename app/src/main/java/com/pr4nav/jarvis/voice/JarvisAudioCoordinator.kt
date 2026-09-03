package com.pr4nav.jarvis.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Coordinates Audio Focus (transient ducking for assistant speech)
 * and Modern Audio Routing (Bluetooth headset / wired headset detection)
 * for Android 11 through Android 16 (API 30-36).
 */
class JarvisAudioCoordinator(private val context: Context) {

    companion object {
        private const val TAG = "JarvisAudioCoord"
    }

    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val mainHandler = Handler(Looper.getMainLooper())

    private var activeFocusRequest: AudioFocusRequest? = null
    @Volatile private var hasAudioFocus: Boolean = false

    private var deviceCallback: AudioDeviceCallback? = null
    @Volatile var activeInputDevice: AudioDeviceInfo? = null
        private set

    /**
     * Requests transient ducking audio focus for assistant speech.
     * Media (music/podcasts) will duck while JARVIS speaks, and restore afterwards.
     */
    fun requestAssistantFocus(onFocusLost: () -> Unit = {}): Boolean {
        val am = audioManager ?: return false
        if (hasAudioFocus) return true

        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setOnAudioFocusChangeListener({ focusChange ->
                        when (focusChange) {
                            AudioManager.AUDIOFOCUS_LOSS,
                            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                                Log.i(TAG, "Audio focus lost ($focusChange); interrupting assistant speech")
                                hasAudioFocus = false
                                onFocusLost()
                            }
                            AudioManager.AUDIOFOCUS_GAIN -> {
                                hasAudioFocus = true
                            }
                        }
                    }, mainHandler)
                    .setAcceptsDelayedFocusGain(false)
                    .build()

                activeFocusRequest = focusRequest
                val res = am.requestAudioFocus(focusRequest)
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasAudioFocus
            } else {
                @Suppress("DEPRECATION")
                val res = am.requestAudioFocus(
                    { focusChange ->
                        if (focusChange == AudioManager.AUDIOFOCUS_LOSS || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            hasAudioFocus = false
                            onFocusLost()
                        }
                    },
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
                hasAudioFocus = (res == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
                hasAudioFocus
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to request audio focus: ${e.message}")
            false
        }
    }

    /**
     * Abandons audio focus, restoring background media playback to normal volume.
     */
    fun abandonAssistantFocus() {
        val am = audioManager ?: return
        if (!hasAudioFocus && activeFocusRequest == null) return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                activeFocusRequest?.let { am.abandonAudioFocusRequest(it) }
            } else {
                @Suppress("DEPRECATION")
                am.abandonAudioFocus(null)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to abandon audio focus: ${e.message}")
        } finally {
            activeFocusRequest = null
            hasAudioFocus = false
        }
    }

    /**
     * Initializes audio device monitoring.
     * Uses modern setCommunicationDevice on API 31+ to prioritize Bluetooth headsets when connected.
     */
    fun startDeviceMonitoring(onDeviceChanged: (AudioDeviceInfo?) -> Unit = {}) {
        val am = audioManager ?: return

        updateOptimalInputDevice()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val callback = object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio devices added: ${addedDevices?.size}")
                    updateOptimalInputDevice()
                    onDeviceChanged(activeInputDevice)
                }

                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                    Log.d(TAG, "Audio devices removed: ${removedDevices?.size}")
                    updateOptimalInputDevice()
                    onDeviceChanged(activeInputDevice)
                }
            }
            deviceCallback = callback
            am.registerAudioDeviceCallback(callback, mainHandler)
        }
    }

    private fun updateOptimalInputDevice() {
        val am = audioManager ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val commDevices = am.availableCommunicationDevices
                val btDevice = commDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                }
                if (btDevice != null) {
                    val success = am.setCommunicationDevice(btDevice)
                    activeInputDevice = btDevice
                    Log.i(TAG, "Set communication device to ${btDevice.productName} (type=${btDevice.type}, success=$success)")
                } else {
                    am.clearCommunicationDevice()
                    activeInputDevice = commDevices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
                    Log.i(TAG, "Cleared communication device; fallback to built-in mic")
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = am.getDevices(AudioManager.GET_DEVICES_INPUTS)
                activeInputDevice = devices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET
                } ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error updating communication device: ${e.message}")
        }
    }

    fun release() {
        abandonAssistantFocus()
        val am = audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try { am.clearCommunicationDevice() } catch (_: Exception) {}
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            deviceCallback?.let { try { am.unregisterAudioDeviceCallback(it) } catch (_: Exception) {} }
            deviceCallback = null
        }
        activeInputDevice = null
    }
}
