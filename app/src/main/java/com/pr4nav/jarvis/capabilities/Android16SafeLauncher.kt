package com.pr4nav.jarvis.capabilities

import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import com.pr4nav.jarvis.Shell

/**
 * Android 14 / 15 / 16 (API 34–36) Safe Activity & Intent Launcher.
 * Overcomes strict Background Activity Launch (BAL) restrictions,
 * PendingIntent requirements, and security sandboxing on modern Android.
 */
object Android16SafeLauncher {
    private const val TAG = "Android16SafeLauncher"

    /**
     * Builds ActivityOptions with explicit background activity launch authorization
     * required by Android 14 (API 34), Android 15 (API 35), and Android 16 (API 36).
     */
    fun createActivityOptions(context: Context): Bundle? {
        return if (Build.VERSION.SDK_INT >= 34) {
            try {
                ActivityOptions.makeBasic().apply {
                    // MODE_BACKGROUND_ACTIVITY_START_ALLOWED = 1
                    setPendingIntentBackgroundActivityStartMode(
                        ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                    )
                }.toBundle()
            } catch (t: Throwable) {
                Log.w(TAG, "Failed to build BAL ActivityOptions: ${t.message}")
                null
            }
        } else {
            null
        }
    }

    /**
     * Safely starts an Activity from any context (background service, overlay, coroutine, receiver)
     * using progressive resilience tiers to ensure execution on Android 16.
     */
    fun startActivitySafe(context: Context, intent: Intent): Boolean {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = createActivityOptions(context)

        // Tier 1: Direct startActivity with BAL-authorized ActivityOptions
        try {
            if (options != null) {
                context.startActivity(intent, options)
            } else {
                context.startActivity(intent)
            }
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Tier 1 direct startActivity failed: ${e.message}")
        }

        // Tier 2: PendingIntent with background activity start grant
        try {
            val requestCode = (System.currentTimeMillis() % 65535).toInt()
            val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            val pi = PendingIntent.getActivity(context, requestCode, intent, flags, options)
            pi.send()
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Tier 2 PendingIntent send failed: ${e.message}")
        }

        // Tier 3: Root Shell am start (Bypasses all Android 16 BAL and security gating)
        if (RootCapability.state == RootCapability.State.AVAILABLE) {
            try {
                val cmd = buildShellCommand(intent)
                if (cmd.isNotBlank()) {
                    val res = RootCapability.exec(cmd)
                    if (res.success) return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Tier 3 Root am start failed: ${e.message}")
            }
        }

        // Tier 4: Native Termux am start
        try {
            val cmd = buildShellCommand(intent)
            if (cmd.isNotBlank()) {
                Shell.termux(cmd)
                return true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Tier 4 Termux am start failed: ${e.message}")
        }

        return false
    }

    private fun buildShellCommand(intent: Intent): String {
        val action = intent.action
        val data = intent.dataString
        val comp = intent.component
        val pkg = comp?.packageName ?: intent.`package`
        val cls = comp?.className

        val sb = StringBuilder("am start")
        if (!pkg.isNullOrBlank() && !cls.isNullOrBlank()) {
            sb.append(" -n $pkg/$cls")
        } else if (!pkg.isNullOrBlank()) {
            sb.append(" -p $pkg")
        }
        if (!action.isNullOrBlank()) {
            sb.append(" -a '$action'")
        }
        if (!data.isNullOrBlank()) {
            sb.append(" -d '$data'")
        }
        sb.append(" -f 0x10000000") // FLAG_ACTIVITY_NEW_TASK
        return sb.toString()
    }
}
