package com.pr4nav.jarvis.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat

/**
 * Restores Hands-Free Voice Assistant Service after device reboot if enabled by the user.
 * Fully compliant with Android 12+ / 14+ / 15+ (SDK 36) background service launch restrictions.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON" ||
            action == "android.intent.action.LOCKED_BOOT_COMPLETED") {

            Log.i(TAG, "Device boot/package update event received ($action)")
            val handsFreeEnabled = VoiceAssistantPreferences.isHandsFreeEnabled(context)
            val startOnBoot = VoiceAssistantPreferences.isStartOnBoot(context)

            if (handsFreeEnabled && startOnBoot) {
                val hasMic = ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED

                if (!hasMic) {
                    Log.w(TAG, "Cannot start JARVIS on boot: RECORD_AUDIO permission missing")
                    return
                }

                Log.i(TAG, "Restoring JARVIS Core voice service after boot...")
                try {
                    JarvisVoiceService.start(context)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to start JARVIS service on boot: ${e.message}", e)
                }
            }
        }
    }
}
