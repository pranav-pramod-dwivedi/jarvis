package com.pr4nav.jarvis.voice

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Restores Hands-Free Voice Assistant Service after device reboot if enabled by the user.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == "android.intent.action.QUICKBOOT_POWERON" ||
            intent.action == "com.htc.intent.action.QUICKBOOT_POWERON") {

            Log.i("BootReceiver", "Device boot event received")
            val handsFreeEnabled = VoiceAssistantPreferences.isHandsFreeEnabled(context)
            val startOnBoot = VoiceAssistantPreferences.isStartOnBoot(context)

            if (handsFreeEnabled && startOnBoot) {
                Log.i("BootReceiver", "Restoring JARVIS Hands-Free Assistant service after reboot")
                JarvisVoiceService.start(context)
            }
        }
    }
}
