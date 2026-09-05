package com.pr4nav.jarvis.capabilities

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.pr4nav.jarvis.Shell

/**
 * Universal Phone Call & Dialer Manager for Android 16 (API 36).
 * Handles contact name resolution, direct TelecomManager call placement,
 * Background Activity Launch (BAL) bypasses, and root execution tiers.
 */
object PhoneCallManager {
    private const val TAG = "PhoneCallManager"

    data class CallResult(
        val success: Boolean,
        val status: String,
        val contactName: String,
        val phoneNumber: String,
        val method: String,
        val message: String
    )

    /**
     * Resolves the target query (contact name or raw phone number) and initiates a call.
     */
    fun placeCall(context: Context, query: String): CallResult {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return CallResult(false, "EMPTY_TARGET", "", "", "NONE", "Please specify a contact or phone number to call.")
        }

        var resolvedName = trimmed
        var resolvedNumber = ""

        val digits = trimmed.count { it.isDigit() }
        val looksLikeNumber = digits >= 5 && (trimmed.startsWith("+") || trimmed.firstOrNull()?.isDigit() == true)

        if (looksLikeNumber) {
            resolvedNumber = trimmed.replace(Regex("[^0-9+]"), "")
            resolvedName = trimmed
        } else {
            // Attempt resolution via ContactResolver
            when (val resolution = ContactResolver.resolve(context, trimmed)) {
                is ContactResolutionResult.Single -> {
                    resolvedName = resolution.contact.name
                    resolvedNumber = resolution.contact.number
                }
                is ContactResolutionResult.Ambiguous -> {
                    val match = resolution.matches.first()
                    resolvedName = match.name
                    resolvedNumber = match.number
                }
                is ContactResolutionResult.NotFound -> {
                    if (digits >= 4) {
                        resolvedNumber = trimmed.replace(Regex("[^0-9+]"), "")
                    } else {
                        return CallResult(
                            false,
                            "CONTACT_NOT_FOUND",
                            trimmed,
                            "",
                            "NONE",
                            "Could not find contact '$trimmed' in your contacts list."
                        )
                    }
                }
                is ContactResolutionResult.PermissionRequired -> {
                    if (digits >= 4) {
                        resolvedNumber = trimmed.replace(Regex("[^0-9+]"), "")
                    } else {
                        return CallResult(
                            false,
                            "PERMISSION_REQUIRED",
                            trimmed,
                            "",
                            "NONE",
                            "Contacts permission required to find '$trimmed'. Please grant Contacts access."
                        )
                    }
                }
            }
        }

        val cleanNumber = resolvedNumber.replace(Regex("[^0-9+]"), "")
        if (cleanNumber.isBlank()) {
            return CallResult(false, "INVALID_NUMBER", resolvedName, "", "NONE", "No valid phone number found for '$resolvedName'.")
        }

        val uri = Uri.parse("tel:$cleanNumber")

        val hasCallPermission = try {
            ContextCompat.checkSelfPermission(context, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) { false }

        // ─── Tier 1: Official TelecomManager.placeCall (Direct, immune to BAL on Android 16) ───
        if (hasCallPermission) {
            try {
                val telecom = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                if (telecom != null) {
                    telecom.placeCall(uri, Bundle())
                    Log.i(TAG, "Placed direct call to $resolvedName via TelecomManager")
                    return CallResult(true, "CALL_PLACED", resolvedName, cleanNumber, "TELECOM_SERVICE", "📞 Calling $resolvedName ($cleanNumber)...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TelecomManager.placeCall failed: ${e.message}")
            }
        }

        // ─── Tier 2: ACTION_CALL with BAL-compliant ActivityOptions ───
        if (hasCallPermission) {
            try {
                val callIntent = Intent(Intent.ACTION_CALL, uri)
                if (Android16SafeLauncher.startActivitySafe(context, callIntent)) {
                    Log.i(TAG, "Placed direct call to $resolvedName via ACTION_CALL")
                    return CallResult(true, "CALL_PLACED", resolvedName, cleanNumber, "ACTION_CALL", "📞 Calling $resolvedName ($cleanNumber)...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "ACTION_CALL failed: ${e.message}")
            }
        }

        // ─── Tier 3: Root Execution (Full bypass of Android 16 permissions & BAL) ───
        if (RootCapability.state == RootCapability.State.AVAILABLE) {
            try {
                val res = RootCapability.exec("am start -a android.intent.action.CALL -d 'tel:$cleanNumber'")
                if (res.success) {
                    Log.i(TAG, "Placed direct call to $resolvedName via Root Shell")
                    return CallResult(true, "CALL_PLACED", resolvedName, cleanNumber, "ROOT_SHELL", "📞 Calling $resolvedName ($cleanNumber)...")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Root call failed: ${e.message}")
            }
        }

        // ─── Tier 4: Tap-to-call notification (Android 14–16 BAL-proof) ───
        // Background activity starts are blocked on Android 16, but a notification
        // tap is explicit user interaction, so the dialer/call always opens from it.
        if (hasCallPermission) {
            val callIntent = Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (postTapToCallNotification(context, "📞 Tap to call $resolvedName", cleanNumber, callIntent, 4101)) {
                return CallResult(true, "CALL_PENDING_TAP", resolvedName, cleanNumber, "NOTIFICATION_TAP", "📞 Tap the notification to call $resolvedName ($cleanNumber)...")
            }
        }

        // ─── Tier 5: Fallback to ACTION_DIAL (Opens dialer with number pre-filled) ───
        try {
            val dialIntent = Intent(Intent.ACTION_DIAL, uri)
            if (Android16SafeLauncher.startActivitySafe(context, dialIntent)) {
                return CallResult(
                    true,
                    "DIALER_OPENED",
                    resolvedName,
                    cleanNumber,
                    "ACTION_DIAL",
                    "Opened dialer for $resolvedName ($cleanNumber). Direct calling requires Phone permission."
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_DIAL failed: ${e.message}")
        }

        // ─── Tier 6: Tap-to-dial notification (needs no Phone permission) ───
        val dialTapIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (postTapToCallNotification(context, "📞 Tap to dial $resolvedName", cleanNumber, dialTapIntent, 4102)) {
            return CallResult(true, "DIALER_PENDING_TAP", resolvedName, cleanNumber, "NOTIFICATION_TAP", "📞 Tap the notification to dial $resolvedName ($cleanNumber)...")
        }

        // ─── Tier 7: Shell Termux fallback ───
        try {
            Shell.termux("am start -a android.intent.action.DIAL -d 'tel:$cleanNumber'")
            return CallResult(true, "DIALER_OPENED", resolvedName, cleanNumber, "TERMUX_SHELL", "Opening dialer for $resolvedName ($cleanNumber)...")
        } catch (e: Exception) {
            return CallResult(false, "CALL_FAILED", resolvedName, cleanNumber, "FAILED", "Failed to initiate call: ${e.message}")
        }
    }

    /**
     * Directly opens the dialer with the number pre-filled.
     */
    fun dialNumber(context: Context, number: String): CallResult {
        val cleanNumber = number.replace(Regex("[^0-9+]"), "").ifBlank { number.trim() }
        val uri = Uri.parse("tel:$cleanNumber")
        val dialIntent = Intent(Intent.ACTION_DIAL, uri)
        val success = Android16SafeLauncher.startActivitySafe(context, dialIntent)
        if (success) {
            return CallResult(true, "DIALER_OPENED", cleanNumber, cleanNumber, "ACTION_DIAL", "📞 Dialing $cleanNumber...")
        }
        val dialTapIntent = Intent(Intent.ACTION_DIAL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (postTapToCallNotification(context, "📞 Tap to dial $cleanNumber", cleanNumber, dialTapIntent, 4103)) {
            return CallResult(true, "DIALER_PENDING_TAP", cleanNumber, cleanNumber, "NOTIFICATION_TAP", "📞 Tap the notification to dial $cleanNumber...")
        }
        return CallResult(false, "DIAL_FAILED", cleanNumber, cleanNumber, "FAILED", "Failed to open dialer for $cleanNumber.")
    }

    /**
     * Posts a high-priority tap-to-call notification. Used as the Android 16
     * fallback: direct background launches may be blocked, but a notification
     * tap is user interaction and always launches the dialer/call screen.
     */
    private fun postTapToCallNotification(
        context: Context,
        title: String,
        number: String,
        target: Intent,
        notifId: Int
    ): Boolean {
        try {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.w(TAG, "POST_NOTIFICATIONS not granted; skipping tap-to-call notification")
                return false
            }
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                nm.createNotificationChannel(
                    NotificationChannel("jarvis_calls", "JARVIS Calls", NotificationManager.IMPORTANCE_HIGH).apply {
                        description = "Tap-to-call fallback for outgoing calls"
                    }
                )
            }
            val pi = PendingIntent.getActivity(
                context, notifId, target,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val notif = NotificationCompat.Builder(context, "jarvis_calls")
                .setSmallIcon(android.R.drawable.ic_menu_call)
                .setContentTitle(title)
                .setContentText(number)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            nm.notify(notifId, notif)
            Log.i(TAG, "Posted tap-to-call notification for $number")
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Tap-to-call notification failed: ${e.message}")
            return false
        }
    }
}
