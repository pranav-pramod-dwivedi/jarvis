package com.pr4nav.jarvis.voice

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import java.util.Locale

/**
 * Diagnostics & Exemption Helper for Android Battery Management and OEM Background Killers.
 *
 * Provides legitimate Android API calls to check and request battery optimization exemptions,
 * and detects OEM-specific background task killers (MIUI/HyperOS, Samsung, Huawei, etc.)
 * with actionable user guidance.
 */
object BatteryOptimizationHelper {

    private const val TAG = "BatteryOptimization"

    data class OemGuidance(
        val manufacturer: String,
        val brand: String,
        val isAggressiveOem: Boolean,
        val guidanceTitle: String,
        val guidanceSteps: List<String>
    )

    /**
     * Checks if the app is currently exempt from standard Android battery optimizations.
     */
    fun isBatteryOptimizationExempt(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Requests battery optimization exemption using Android standard Intent.
     */
    @SuppressLint("BatteryLife")
    fun requestBatteryOptimizationExemption(activity: Activity) {
        try {
            if (!isBatteryOptimizationExempt(activity)) {
                val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                    data = Uri.parse("package:${activity.packageName}")
                }
                activity.startActivity(intent)
            }
        } catch (e: Exception) {
            Log.w(TAG, "ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS failed, falling back to general settings: ${e.message}")
            openGeneralBatterySettings(activity)
        }
    }

    /**
     * Opens general battery optimization settings screen.
     */
    fun openGeneralBatterySettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to open battery settings: ${e.message}")
            try {
                val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(appDetails)
            } catch (_: Exception) {}
        }
    }

    /**
     * Returns device manufacturer and OEM-specific background kill prevention guidance.
     */
    fun getOemGuidance(): OemGuidance {
        val manufacturer = (Build.MANUFACTURER ?: "Generic").lowercase(Locale.ROOT)
        val brand = (Build.BRAND ?: "Generic").lowercase(Locale.ROOT)

        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco") -> {
                OemGuidance(
                    manufacturer = Build.MANUFACTURER ?: "Xiaomi",
                    brand = Build.BRAND ?: "Xiaomi",
                    isAggressiveOem = true,
                    guidanceTitle = "Xiaomi MIUI / HyperOS Optimization",
                    guidanceSteps = listOf(
                        "1. Open App Info -> Enable 'Autostart'",
                        "2. Battery Saver -> Set to 'No restrictions'",
                        "3. Security App -> Speed Boost -> Lock JARVIS in Recents"
                    )
                )
            }
            manufacturer.contains("samsung") -> {
                OemGuidance(
                    manufacturer = Build.MANUFACTURER ?: "Samsung",
                    brand = Build.BRAND ?: "Samsung",
                    isAggressiveOem = true,
                    guidanceTitle = "Samsung One UI Optimization",
                    guidanceSteps = listOf(
                        "1. Settings -> Battery -> Background usage limits",
                        "2. Add JARVIS to 'Never sleeping apps'",
                        "3. Lock JARVIS in Recents screen"
                    )
                )
            }
            manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                OemGuidance(
                    manufacturer = Build.MANUFACTURER ?: "Huawei",
                    brand = Build.BRAND ?: "Huawei",
                    isAggressiveOem = true,
                    guidanceTitle = "Huawei / Honor EMUI Optimization",
                    guidanceSteps = listOf(
                        "1. Settings -> Battery -> App launch",
                        "2. Find JARVIS -> Switch to 'Manage manually'",
                        "3. Enable Auto-launch, Secondary launch, and Run in background"
                    )
                )
            }
            manufacturer.contains("oppo") || manufacturer.contains("realme") || manufacturer.contains("oneplus") -> {
                OemGuidance(
                    manufacturer = Build.MANUFACTURER ?: "Oppo",
                    brand = Build.BRAND ?: "Oppo",
                    isAggressiveOem = true,
                    guidanceTitle = "ColorOS / OxygenOS / RealmeUI Optimization",
                    guidanceSteps = listOf(
                        "1. App Info -> Battery usage -> Allow background activity",
                        "2. Enable 'Allow auto-launch'",
                        "3. Lock JARVIS in Recent tasks"
                    )
                )
            }
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> {
                OemGuidance(
                    manufacturer = Build.MANUFACTURER ?: "Vivo",
                    brand = Build.BRAND ?: "Vivo",
                    isAggressiveOem = true,
                    guidanceTitle = "Vivo FuntouchOS / OriginOS Optimization",
                    guidanceSteps = listOf(
                        "1. Settings -> Battery -> High background power consumption -> Enable for JARVIS",
                        "2. Settings -> Permission Management -> Autostart -> Allow JARVIS"
                    )
                )
            }
            else -> {
                OemGuidance(
                    manufacturer = Build.MANUFACTURER ?: "Generic Android",
                    brand = Build.BRAND ?: "Android",
                    isAggressiveOem = false,
                    guidanceTitle = "Standard Android Background Management",
                    guidanceSteps = listOf(
                        "1. Settings -> Apps -> JARVIS -> Battery -> Unrestricted",
                        "2. Keep notification enabled for Foreground Service"
                    )
                )
            }
        }
    }
}
