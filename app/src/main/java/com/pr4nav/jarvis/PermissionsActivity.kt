package com.pr4nav.jarvis

import android.Manifest
import android.app.AlarmManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import kotlin.concurrent.thread

class PermissionsActivity : ComponentActivity() {

    private var rootOk: Boolean? = null
    private val queue = ArrayDeque<Array<String>>()
    private val refreshTrigger = mutableStateOf(0)

    companion object {
        private const val RC = 7001
    }

    data class PermRow(
        val title: String,
        val why: String,
        val tag: String,
        val optional: Boolean,
        val special: Boolean,
        val isGranted: Boolean,
        val onGrant: (() -> Unit)? = null,
        val extraActionLabel: String? = null,
        val onExtraAction: (() -> Unit)? = null
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
            val rows = remember(trigger) { getPermRows() }
            val capabilities = remember(trigger) { Capabilities.all().map { safeStatus(it) } }

            PermissionsScreen(
                rows = rows,
                capabilities = capabilities,
                onBack = { finish() },
                onGrantAll = { grantAll() },
                onProceedToChat = {
                    val intent = Intent(this, MainActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    finish()
                }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        refreshTrigger.value++
    }

    private fun getPermRows(): List<PermRow> = listOf(
        PermRow(
            title = "Files & Storage",
            why = "Read/write project files, documents, scripts, and downloads.",
            tag = "FS",
            optional = false,
            special = true,
            isGranted = hasAllFiles(),
            onGrant = { requestFiles() }
        ),
        PermRow(
            title = "Microphone",
            why = "Real-time hands-free voice conversation, wake-word, and audio VAD.",
            tag = "MIC",
            optional = false,
            special = false,
            isGranted = granted(Manifest.permission.RECORD_AUDIO),
            onGrant = { requestRuntime(Manifest.permission.RECORD_AUDIO) }
        ),
        PermRow(
            title = "Display Over Other Apps",
            why = "Holographic Siri-style floating HUD companion overlay.",
            tag = "HUD",
            optional = false,
            special = true,
            isGranted = Settings.canDrawOverlays(this),
            onGrant = {
                openSettings(
                    { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION) }
                )
            }
        ),
        PermRow(
            title = "Accessibility Service",
            why = "Zero-latency text screen reading, coordinates extraction, and virtual tap.",
            tag = "A11Y",
            optional = false,
            special = true,
            isGranted = accessibilityEnabled(),
            onGrant = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        ),
        PermRow(
            title = "Notifications",
            why = "Foreground service persistence, task completion alerts, and responses.",
            tag = "NOTIF",
            optional = false,
            special = false,
            isGranted = Build.VERSION.SDK_INT < 33 || granted(Manifest.permission.POST_NOTIFICATIONS),
            onGrant = { if (Build.VERSION.SDK_INT >= 33) requestRuntime(Manifest.permission.POST_NOTIFICATIONS) }
        ),
        PermRow(
            title = "Battery Optimization Exemption",
            why = "Prevents Android OS from freezing the assistant in background or sleep.",
            tag = "PWR",
            optional = false,
            special = true,
            isGranted = (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName),
            onGrant = {
                openSettings(
                    { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS) }
                )
            }
        ),
        PermRow(
            title = "Termux Command Bridge",
            why = "Execute shell commands inside Termux and PRoot Ubuntu Linux.",
            tag = "SH",
            optional = false,
            special = false,
            isGranted = TermuxBridge.hasPermission(),
            onGrant = { requestRuntime("com.termux.permission.RUN_COMMAND") }
        ),
        PermRow(
            title = "Alarms & Reminders",
            why = "Schedule precise system alarms, timers, and scheduled tasks.",
            tag = "ALARM",
            optional = false,
            special = true,
            isGranted = Build.VERSION.SDK_INT < 31 || (getSystemService(Context.ALARM_SERVICE) as AlarmManager).canScheduleExactAlarms(),
            onGrant = {
                if (Build.VERSION.SDK_INT >= 31) openSettings(
                    { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName")) },
                    { Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM) }
                )
            }
        ),
        PermRow(
            title = "Root (Superuser)",
            why = "Advanced low-level automation via su (/product/bin/su) with safety gating.",
            tag = "ROOT",
            optional = true,
            special = false,
            isGranted = RootCapability.state == RootCapability.State.AVAILABLE,
            onGrant = null,
            extraActionLabel = if (RootCapability.available()) {
                if (RootCapability.toolsEnabled()) "DISABLE ROOT TOOLS" else "ENABLE ROOT TOOLS"
            } else null,
            onExtraAction = {
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
            val ok = RootCapability.detect()
            rootOk = ok
            runOnUiThread { refreshTrigger.value++ }
        }
    }

    private fun safeStatus(c: com.pr4nav.jarvis.capabilities.Capability): String =
        try { c.status() } catch (e: Exception) { "? ${c.name} — ${e.message}" }
}

// ─── Jetpack Compose Dark Screen UI (matching TermuxCheck & Allow page) ──────

@Composable
fun PermissionsScreen(
    rows: List<PermissionsActivity.PermRow>,
    capabilities: List<String>,
    onBack: () -> Unit,
    onGrantAll: () -> Unit,
    onProceedToChat: () -> Unit
) {
    val context = LocalContext.current
    val dmSansFamily = remember {
        try {
            FontFamily(Font(R.font.dm_sans))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    val reqOk = rows.count { !it.optional && it.isGranted }
    val reqAll = rows.count { !it.optional }

    val fadeInAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeInAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(fadeInAlpha.value)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
        ) {
            // Header Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 20.dp, top = 20.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack, modifier = Modifier.size(38.dp)) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White
                    )
                }

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    PermShimmerText(
                        text = "JARVIS PERMISSIONS",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = dmSansFamily
                    )
                    Text(
                        text = "Essential capabilities for autonomous execution",
                        color = Color.White.copy(alpha = 0.5f),
                        fontFamily = dmSansFamily,
                        fontSize = 12.sp
                    )
                }

                // Status Badge
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (reqOk == reqAll) Color(0x3310B981) else Color(0x33FFB45A),
                    border = BorderStroke(1.dp, if (reqOk == reqAll) Color(0x6610B981) else Color(0x66FFB45A))
                ) {
                    Text(
                        text = "$reqOk / $reqAll",
                        color = if (reqOk == reqAll) Color(0xFF10B981) else Color(0xFFFFB45A),
                        fontFamily = dmSansFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            // Scrollable List of Permission Cards
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(rows) { row ->
                    PermissionCard(row = row, dmSansFamily = dmSansFamily)
                }

                // Capabilities Terminal Section
                item {
                    Spacer(modifier = Modifier.height(14.dp))
                    Text(
                        text = "LIVE CAPABILITY MATRIX",
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color.White.copy(alpha = 0.4f),
                        letterSpacing = 1.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))

                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFF0D1117),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            capabilities.forEach { cap ->
                                Text(
                                    text = cap,
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                    color = if (cap.startsWith("✓")) Color(0xFF10B981) else Color.White.copy(alpha = 0.55f)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }

            // Bottom Actions Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                if (reqOk < reqAll) {
                    // Grant All Essentials
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color.White,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .clickable { onGrantAll() }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "GRANT ALL ESSENTIALS",
                                fontFamily = dmSansFamily,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.5.sp,
                                color = Color.Black
                            )
                        }
                    }
                }

                // Proceed to Chat
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = Color(0xFF10B981),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .clickable { onProceedToChat() }
                ) {
                    Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = Color.Black,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "PROCEED TO CHAT",
                            fontFamily = dmSansFamily,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.5.sp,
                            color = Color.Black
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PermissionCard(row: PermissionsActivity.PermRow, dmSansFamily: FontFamily) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF121620),
        border = BorderStroke(1.dp, if (row.isGranted) Color(0x3310B981) else Color.White.copy(alpha = 0.08f)),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = (!row.isGranted && row.onGrant != null) || row.onExtraAction != null) {
                if (!row.isGranted) row.onGrant?.invoke()
                else row.onExtraAction?.invoke()
            }
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Tag icon badge
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = if (row.isGranted) Color(0x2610B981) else Color(0x1AFFFFFF),
                modifier = Modifier.size(40.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = row.tag,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = if (row.isGranted) Color(0xFF10B981) else Color.White.copy(alpha = 0.7f)
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = row.title,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = Color.White
                )
                Text(
                    text = row.why,
                    fontFamily = dmSansFamily,
                    fontSize = 11.5.sp,
                    color = Color.White.copy(alpha = 0.55f),
                    modifier = Modifier.padding(top = 2.dp)
                )
                if (row.extraActionLabel != null) {
                    Text(
                        text = row.extraActionLabel,
                        fontFamily = dmSansFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = Color(0xFFFFB45A),
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            // State Pill
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = if (row.isGranted) Color(0x3310B981) else Color(0x1AFFFFFF),
                border = BorderStroke(1.dp, if (row.isGranted) Color(0x6610B981) else Color.White.copy(alpha = 0.15f))
            ) {
                Text(
                    text = if (row.isGranted) "✓ GRANTED" else if (row.onGrant != null) "GRANT" else "INFO",
                    color = if (row.isGranted) Color(0xFF10B981) else Color.White,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.5.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                )
            }
        }
    }
}

@Composable
fun PermShimmerText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily
) {
    val transition = rememberInfiniteTransition(label = "permShimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.6f),
            Color.White,
            Color.White.copy(alpha = 0.6f),
        ),
        start = Offset(offset * 800f, 0f),
        end = Offset((offset + 0.6f) * 800f, 0f)
    )

    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        style = TextStyle(
            brush = brush,
            letterSpacing = (-0.3).sp
        )
    )
}
