package com.pr4nav.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kotlin.concurrent.thread

class PermissionsActivity : AppCompatActivity() {

    private lateinit var listHost: LinearLayout
    private lateinit var summary: TextView
    private var rootOk: Boolean? = null
    private val queue = ArrayDeque<Array<String>>()

    companion object {
        private const val RC = 7001
        private const val GREEN = 0xFF4CAF50.toInt()
        private const val AMBER = 0xFFF9A825.toInt()
        private const val RED = 0xFFE53935.toInt()
        private const val GREY = 0xFF9E9E9E.toInt()
    }

    private inner class Row(
        val title: String,
        val why: String,
        val optional: Boolean,
        val special: Boolean,
        val granted: () -> Boolean,
        val go: (() -> Unit)? = null
    ) {
        var extraActionLabel: String? = null
        var extraAction: (() -> Unit)? = null
        fun withExtra(label: String, action: () -> Unit): Row {
            extraActionLabel = label; extraAction = action; return this
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_permissions)
        listHost = findViewById(R.id.perm_list)
        summary = findViewById(R.id.perm_summary)
        findViewById<Button>(R.id.btn_grant_all).setOnClickListener { grantAll() }
        checkRoot()
    }

    override fun onResume() {
        super.onResume()
        rebuild()
    }

    // ================= rows =================

    private fun rows(): List<Row> = listOf(
        Row("Files & storage", "Read/write files, projects, documents, downloads.", false, true,
            { hasAllFiles() }, { requestFiles() }),
        Row("Microphone", "Voice conversations + wake-word/VAD.", false, false,
            { granted(Manifest.permission.RECORD_AUDIO) },
            { requestRuntime(Manifest.permission.RECORD_AUDIO) }),
        Row("Location", "Only for location-aware commands. Background access is a separate second step.", false, false,
            { granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
                    (Build.VERSION.SDK_INT < 29 || granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)) },
            {
                val base = listOf(
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ).filter { !granted(it) }
                when {
                    base.isNotEmpty() -> requestRuntime(*base.toTypedArray())
                    Build.VERSION.SDK_INT >= 29 && !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION) ->
                        requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), RC)
                    else -> rebuild()
                }
            }),
        Row("Notifications", "Background status, responses, task completion.", false, false,
            { Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS) },
            { if (Build.VERSION.SDK_INT >= 33) requestRuntime(Manifest.permission.POST_NOTIFICATIONS) }),
        Row("Display over other apps", "Floating JARVIS orb/overlay.", false, true,
            { Settings.canDrawOverlays(this) },
            {
                openSettings(
                    { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) })
            }),
        Row("Battery optimization exemption", "Keeps the assistant alive in the background.", false, true,
            { (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName) },
            {
                openSettings(
                    { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) })
            }),
        Row("Accessibility", "UI interaction: reading screen, clicks, typing, automation.", false, true,
            { accessibilityEnabled() },
            { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }),
        Row("Alarms & exact reminders", "Schedule alarms and time-critical tasks.", false, true,
            { Build.VERSION.SDK_INT < 31 ||
                    (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms() },
            {
                if (Build.VERSION.SDK_INT >= 31) openSettings(
                    { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) })
            }),
        Row("Nearby devices / Bluetooth", "Bluetooth & local device control.", false, false,
            { Build.VERSION.SDK_INT < 31 ||
                    (granted(Manifest.permission.BLUETOOTH_SCAN) && granted(Manifest.permission.BLUETOOTH_CONNECT)) },
            {
                if (Build.VERSION.SDK_INT >= 31) requestRuntime(
                    Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            }),
        Row("Photos & media", "Read images, video and audio libraries.", true, false,
            {
                if (Build.VERSION.SDK_INT >= 33)
                    granted(Manifest.permission.READ_MEDIA_IMAGES) &&
                            granted(Manifest.permission.READ_MEDIA_VIDEO) &&
                            granted(Manifest.permission.READ_MEDIA_AUDIO)
                else hasAllFiles()
            },
            {
                if (Build.VERSION.SDK_INT >= 33) requestRuntime(
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VIDEO,
                    Manifest.permission.READ_MEDIA_AUDIO)
                else requestFiles()
            }),
        Row("Audio control", "Adjust volume and audio routing.", true, false,
            { granted(Manifest.permission.MODIFY_AUDIO_SETTINGS) },
            { requestRuntime(Manifest.permission.MODIFY_AUDIO_SETTINGS) }),
        Row("Camera", "Vision features and scanning (optional).", true, false,
            { granted(Manifest.permission.CAMERA) },
            { requestRuntime(Manifest.permission.CAMERA) }),
        Row("Contacts", "Address-book commands (optional).", true, false,
            { granted(Manifest.permission.READ_CONTACTS) && granted(Manifest.permission.WRITE_CONTACTS) },
            { requestRuntime(Manifest.permission.READ_CONTACTS, Manifest.permission.WRITE_CONTACTS) }),
        Row("Calendar", "Read/create events on command (optional).", true, false,
            { granted(Manifest.permission.READ_CALENDAR) && granted(Manifest.permission.WRITE_CALENDAR) },
            { requestRuntime(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR) }),
        Row("Phone", "Call/device-state commands (optional).", true, false,
            { granted(Manifest.permission.READ_PHONE_STATE) && granted(Manifest.permission.CALL_PHONE) },
            { requestRuntime(Manifest.permission.READ_PHONE_STATE, Manifest.permission.CALL_PHONE) }),
        Row("SMS / messaging", "Send/read SMS commands (optional).", true, false,
            { granted(Manifest.permission.SEND_SMS) && granted(Manifest.permission.READ_SMS) &&
                    granted(Manifest.permission.RECEIVE_SMS) },
            { requestRuntime(Manifest.permission.SEND_SMS, Manifest.permission.RECEIVE_SMS, Manifest.permission.READ_SMS) }),
        Row("Notification listener", "Understand/manage notifications (optional).", true, true,
            { NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName) },
            { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }),
        Row("Device administrator", "Device-management functions (optional).", true, true,
            { (getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager)
                .isAdminActive(ComponentName(this, AdminReceiver::class.java)) },
            {
                startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                    .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN,
                        ComponentName(this, AdminReceiver::class.java))
                    .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION,
                        getString(R.string.device_admin_explanation)))
            }),
        Row("USB / OTG drives",
            "External drives/devices — Android prompts automatically when one is attached.",
            true, false, { true }, null),
        Row("Internet", "Talk to services and the Termux bridge.", false, false, { true }, null),
        Row("Root (superuser)",
            "Advanced automation via su — Magisk/SuperSU prompt at first use. Root tool calls are gated and logged.",
            true, false, { com.pr4nav.jarvis.capabilities.RootCapability.state ==
                    com.pr4nav.jarvis.capabilities.RootCapability.State.AVAILABLE },
            null).apply {
            if (com.pr4nav.jarvis.capabilities.RootCapability.available() &&
                CapabilitiesInitDone()
            ) {
                val enabled = com.pr4nav.jarvis.capabilities.RootCapability.toolsEnabled()
                withExtra(
                    if (enabled) "DISABLE ROOT TOOLS" else "ENABLE ROOT TOOLS"
                ) {
                    com.pr4nav.jarvis.capabilities.RootCapability
                        .setToolsEnabled(!enabled)
                    rebuild()
                }
            }
        }
    )

    private fun CapabilitiesInitDone(): Boolean =
        com.pr4nav.jarvis.capabilities.Capabilities.app != null

    // ================= actions =================

    private fun grantAll() {
        queue.clear()
        for (g in runtimeGroups()) queue.addLast(g)
        pump()
    }

    private fun runtimeGroups(): List<Array<String>> {
        val groups = mutableListOf<Array<String>>()
        fun group(vararg perms: String) {
            val missing = perms.filter { !granted(it) }
            if (missing.isNotEmpty()) groups.add(missing.toTypedArray())
        }
        group(Manifest.permission.RECORD_AUDIO)
        val loc = listOf(
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION)
        group(*loc.toTypedArray())
        if (Build.VERSION.SDK_INT >= 29 && granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            !granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION))
            group(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) group(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_AUDIO)
        if (Build.VERSION.SDK_INT >= 33) group(Manifest.permission.POST_NOTIFICATIONS)
        group(Manifest.permission.MODIFY_AUDIO_SETTINGS)
        return groups
    }

    private fun pump() {
        while (queue.isNotEmpty()) {
            val g = queue.removeFirst()
            val need = g.filter { !granted(it) }.toTypedArray()
            if (need.isNotEmpty()) {
                requestPermissions(need, RC)
                return
            }
        }
        summary.text = summary.text.toString() +
                "  ·  system-screen items: tap GRANT individually"
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        rebuild()
        pump()
    }

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            openSettings(
                { Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")) },
                { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) })
        } else {
            requestRuntime(Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    private fun requestRuntime(vararg perms: String) {
        val need = perms.filter { !granted(it) }.toTypedArray()
        if (need.isNotEmpty()) requestPermissions(need, RC)
    }

    private fun openSettings(make: () -> Intent, fallback: (() -> Intent)? = null) {
        try {
            startActivity(make())
        } catch (_: Exception) {
            try { fallback?.let { startActivity(it()) } } catch (_: Exception) {}
        }
    }

    private fun granted(p: String): Boolean =
        ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun accessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val svc = JarvisAccessibilityService::class.java.name
        return enabled.split(':').any {
            it.equals("$packageName/$svc", true) ||
                    it.equals("$packageName/.${svc.substringAfterLast('.')}", true)
        }
    }

    private fun checkRoot() {
        thread {
            val ok = com.pr4nav.jarvis.capabilities.RootCapability.detect()
            rootOk = ok
            runOnUiThread { rebuild() }
        }
    }

    // ================= rendering =================

    private fun rebuild() {
        listHost.removeAllViews()
        val rs = rows()
        val reqOk = rs.count { !it.optional && it.granted() }
        val reqAll = rs.count { !it.optional }
        val optOk = rs.count { it.optional && it.granted() }
        val optAll = rs.count { it.optional }
        summary.text = "Essential $reqOk/$reqAll · Optional $optOk/$optAll"
        for (r in rs) listHost.addView(rowView(r))
        listHost.addView(capabilitiesHeader())
        for (c in com.pr4nav.jarvis.capabilities.Capabilities.all()) {
            listHost.addView(TextView(this).apply {
                text = "  " + safeStatus(c)
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setPadding(0, dp(3), 0, dp(1))
            })
        }
    }

    private fun safeStatus(c: com.pr4nav.jarvis.capabilities.Capability): String =
        try { c.status() } catch (e: Exception) { "? ${c.name} — ${e.message}" }

    private fun capabilitiesHeader(): TextView = TextView(this).apply {
        text = "\nCAPABILITIES (permission ≠ working)"
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(20), 0, dp(2))
    }

    private fun rowView(r: Row): LinearLayout {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(16), 0, dp(2))
        }
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val ok = r.granted()
        val checkingRoot = r.title.startsWith("Root") && rootOk == null
        val title = TextView(this).apply {
            text = r.title + if (r.optional) "  ·" else ""
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        }
        val status = TextView(this).apply {
            text = when {
                ok -> "✓"
                checkingRoot -> "?"
                r.optional -> "○ optional"
                else -> "✗"
            }
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(when {
                ok -> GREEN
                checkingRoot -> GREY
                r.optional -> AMBER
                else -> RED
            })
            setPadding(dp(8), 0, 0, 0)
        }
        head.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        head.addView(status)
        box.addView(head)
        box.addView(TextView(this).apply {
            text = r.why
            textSize = 12f
            alpha = 0.75f
        })
        if (r.go != null && !ok) {
            box.addView(Button(this).apply {
                text = "GRANT"
                setOnClickListener { r.go.invoke() }
            })
        }
        if (ok && r.extraActionLabel != null && r.extraAction != null) {
            box.addView(Button(this).apply {
                text = r.extraActionLabel
                setOnClickListener { r.extraAction!!.invoke() }
            })
        }
        return box
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
}
