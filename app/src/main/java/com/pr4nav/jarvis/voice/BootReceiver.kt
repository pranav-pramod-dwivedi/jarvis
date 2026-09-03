package com.pr4nav.jarvis.voice

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pr4nav.jarvis.MainActivity

/**
 * Handles device boot and package update events.
 * On Android 14+ (API 34-36), background startup of microphone foreground services is restricted.
 * On API >= 34: posts a high-priority action notification so user can activate hands-free listening.
 * On API < 34: directly restores the voice service.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "BootReceiver"
        const val BOOT_NOTIF_ID = 9101
        const val BOOT_CHANNEL_ID = "jarvis_boot_restore"
    }

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED ||
            action == "android.intent.action.QUICKBOOT_POWERON" ||
            action == "com.htc.intent.action.QUICKBOOT_POWERON") {

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

                // Android 14+ (API 34-36) strictly forbids starting a microphone foreground service from BOOT_COMPLETED.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    Log.i(TAG, "Android 14+ detected: Posting notification for user to tap and activate voice FGS.")
                    showBootNotification(context)
                } else {
                    Log.i(TAG, "Restoring JARVIS Core voice service directly (API < 34)...")
                    try {
                        JarvisVoiceService.start(context)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to start JARVIS service on boot: ${e.message}", e)
                    }
                }
            }
        }
    }

    fun showBootNotification(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                BOOT_CHANNEL_ID,
                "JARVIS System Restore",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when JARVIS needs activation after reboot"
            }
            nm.createNotificationChannel(channel)
        }

        val launchIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("auto_start_voice", true)
        }
        val pi = PendingIntent.getActivity(
            context,
            BOOT_NOTIF_ID,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, BOOT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("JARVIS Assistant Ready")
            .setContentText("Device restarted. Tap to activate hands-free listening.")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        try {
            nm.notify(BOOT_NOTIF_ID, notif)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to post boot notification: ${e.message}")
        }
    }
}
