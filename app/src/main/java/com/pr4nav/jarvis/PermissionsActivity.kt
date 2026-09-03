package com.pr4nav.jarvis

import android.Manifest
import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pr4nav.jarvis.capabilities.RootCapability
import kotlinx.coroutines.launch
import kotlin.concurrent.thread

class PermissionsActivity : ComponentActivity() {

    private val queue = ArrayDeque<Array<String>>()
    private val refreshTrigger = mutableStateOf(0)

    companion object {
        private const val RC = 7001
    }

    data class PermItem(
        val title: String,
        val description: String,
        val isEssential: Boolean,
        val isGranted: Boolean,
        val onGrant: (() -> Unit)? = null
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        checkRoot()

        setContent {
            val trigger = refreshTrigger.value
            val items = remember(trigger) { getPermissionItems() }

            PermissionsPage(
                items = items,
                onGrantAll = { grantAll() },
                onProceedToChat = {
                    val intent = Intent(this, com.pr4nav.jarvis.setup.JarvisWakeLoadingActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.value++
    }

    private fun getPermissionItems(): List<PermItem> = listOf(
        PermItem(
            title = "Files & Storage",
            description = "Read and write project code, scripts, files, and downloads in workspace.",
            isEssential = true,
            isGranted = hasAllFiles(),
            onGrant = { requestFiles() }
        ),
        PermItem(
            title = "Microphone",
            description = "Real-time hands-free voice conversations and wake-word voice activation.",
            isEssential = true,
            isGranted = granted(Manifest.permission.RECORD_AUDIO),
            onGrant = { requestRuntime(Manifest.permission.RECORD_AUDIO) }
        ),
        PermItem(
            title = "Display Over Other Apps",
            description = "Floating Siri-style HUD companion overlay and heads-up dynamic interface.",
            isEssential = true,
            isGranted = Settings.canDrawOverlays(this),
            onGrant = {
                openSettings(
                    { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
                )
            }
        ),
        PermItem(
            title = "Accessibility Service",
            description = "Screen text reading with bounding coordinates and virtual touch gestures.",
            isEssential = true,
            isGranted = accessibilityEnabled(),
            onGrant = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ),
        PermItem(
            title = "Termux Command Bridge",
            description = "Execute shell commands inside Termux and PRoot Ubuntu Linux environment.",
            isEssential = true,
            isGranted = TermuxBridge.hasPermission(),
            onGrant = { requestRuntime("com.termux.permission.RUN_COMMAND") }
        ),
        PermItem(
            title = "Battery Optimization Exemption",
            description = "Prevent Android OS from terminating the background assistant during sleep.",
            isEssential = true,
            isGranted = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName),
            onGrant = {
                openSettings(
                    { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
                )
            }
        ),
        PermItem(
            title = "Notifications",
            description = "Foreground background service persistence and proactive completion alerts.",
            isEssential = true,
            isGranted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            onGrant = { if (Build.VERSION.SDK_INT >= 33) requestRuntime(Manifest.permission.POST_NOTIFICATIONS) }
        ),
        PermItem(
            title = "Alarms & Reminders",
            description = "Schedule precise time-critical alarms, routines, and recurring reminders.",
            isEssential = false,
            isGranted = Build.VERSION.SDK_INT < 31 || (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms(),
            onGrant = {
                if (Build.VERSION.SDK_INT >= 31) openSettings(
                    { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
                )
            }
        ),
        PermItem(
            title = "Phone Calls",
            description = "Initiate hands-free phone calls via voice commands without touching the device.",
            isEssential = false,
            isGranted = granted(Manifest.permission.CALL_PHONE),
            onGrant = { requestRuntime(Manifest.permission.CALL_PHONE) }
        ),
        PermItem(
            title = "Contacts",
            description = "Resolve contact names (e.g. 'Call Mom') into phone numbers from your address book.",
            isEssential = false,
            isGranted = granted(Manifest.permission.READ_CONTACTS),
            onGrant = { requestRuntime(Manifest.permission.READ_CONTACTS) }
        ),
        PermItem(
            title = "SMS & Messages",
            description = "Send and read text messages via voice commands and quick assistant actions.",
            isEssential = false,
            isGranted = granted(Manifest.permission.SEND_SMS),
            onGrant = { requestRuntime(Manifest.permission.SEND_SMS) }
        ),
        PermItem(
            title = "Root (Superuser)",
            description = "Direct system-level execution via su with automated safety gating.",
            isEssential = false,
            isGranted = RootCapability.state == RootCapability.State.AVAILABLE,
            onGrant = {
                val enabled = RootCapability.toolsEnabled()
                RootCapability.setToolsEnabled(!enabled)
                refreshTrigger.value++
            }
        )
    )

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
        if (Build.VERSION.SDK_INT >= 33) group(Manifest.permission.POST_NOTIFICATIONS)
        group(Manifest.permission.CALL_PHONE)
        group(Manifest.permission.READ_CONTACTS)
        group(Manifest.permission.SEND_SMS)
        group("com.termux.permission.RUN_COMMAND")
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
        refreshTrigger.value++
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refreshTrigger.value++
        pump()
    }

    private fun hasAllFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else granted(Manifest.permission.WRITE_EXTERNAL_STORAGE)

    private fun requestFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            openSettings(
                { Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")) },
                { Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION) }
            )
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
            RootCapability.detect()
            runOnUiThread { refreshTrigger.value++ }
        }
    }
}

// ─── Main Screen (Identical design to TermuxPermissionFixPage) ───────────────

@Composable
fun PermissionsPage(
    items: List<PermissionsActivity.PermItem>,
    onGrantAll: () -> Unit,
    onProceedToChat: () -> Unit
) {
    val screenAlpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val dmSansFamily = remember {
        try {
            FontFamily(Font(R.font.dm_sans))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
    }

    fun proceedWithFadeOut(action: () -> Unit) {
        coroutineScope.launch {
            screenAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
            action()
        }
    }

    val reqOk = items.count { it.isEssential && it.isGranted }
    val reqAll = items.count { it.isEssential }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .alpha(screenAlpha.value)
    ) {
        // Scrollable Documentation Content
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 24.dp, end = 24.dp, top = 160.dp, bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            itemsIndexed(items) { index, item ->
                val stepNumber = String.format("%02d", index + 1)
                PermDocumentationCard(
                    stepNumber = stepNumber,
                    title = item.title,
                    description = item.description,
                    isGranted = item.isGranted,
                    onCardClick = { item.onGrant?.invoke() },
                    dmSansFamily = dmSansFamily
                )
            }

            // Action Buttons Section at Bottom
            item {
                Spacer(modifier = Modifier.height(8.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Primary White Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFFFFF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clickable {
                                if (reqOk < reqAll) onGrantAll()
                                else proceedWithFadeOut { onProceedToChat() }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (reqOk < reqAll) {
                                Icon(
                                    imageVector = Icons.Default.Refresh,
                                    contentDescription = "Grant",
                                    tint = Color(0xFF000000),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Grant All Essentials",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = Color(0xFF000000),
                                    fontFamily = dmSansFamily
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Check,
                                    contentDescription = "Proceed",
                                    tint = Color(0xFF000000),
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Proceed to JARVIS",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    color = Color(0xFF000000),
                                    fontFamily = dmSansFamily
                                )
                            }
                        }
                    }

                    // Skip / Secondary Button
                    TextButton(
                        onClick = { proceedWithFadeOut { onProceedToChat() } },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(36.dp),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Text(
                            text = "Continue anyway",
                            fontFamily = dmSansFamily,
                            fontWeight = FontWeight.Normal,
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 13.sp
                        )
                    }
                }
            }
        }

        // Pinned Translucent Top Header Bar (copied from TermuxPermissionFixPage)
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .drawBehind {
                    drawLine(
                        color = Color(0x20FFFFFF),
                        start = Offset(0f, size.height),
                        end = Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                },
            color = Color(0xD907080A) // Translucent dark glass backdrop
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Text(
                    text = "SYSTEM CONFIGURATION",
                    fontSize = 11.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.45f),
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "System Permissions",
                    fontSize = 24.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "JARVIS requires device capabilities for autonomous execution. Grant the permissions below:",
                    fontSize = 12.5.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.60f),
                    lineHeight = 17.sp
                )
            }
        }
    }
}

// ─── Step Card Component (copied from DocumentationCard in TermuxPermissionFixPage) ──

@Composable
private fun PermDocumentationCard(
    stepNumber: String,
    title: String,
    description: String,
    isGranted: Boolean,
    onCardClick: () -> Unit,
    dmSansFamily: FontFamily
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF13151A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isGranted) { onCardClick() }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = stepNumber,
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.4f),
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = title,
                    fontSize = 16.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                    modifier = Modifier.weight(1f)
                )

                if (isGranted) {
                    Text(
                        text = "Granted",
                        fontSize = 12.sp,
                        fontFamily = dmSansFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF10B981)
                    )
                } else {
                    Text(
                        text = "Grant →",
                        fontSize = 12.sp,
                        fontFamily = dmSansFamily,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.70f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                fontSize = 13.sp,
                fontFamily = dmSansFamily,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.60f),
                lineHeight = 18.sp
            )
        }
    }
}
