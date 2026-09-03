package com.pr4nav.jarvis.setup

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
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
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.lifecycleScope
import com.pr4nav.jarvis.R
import kotlinx.coroutines.launch

class TermuxPermissionFixActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            TermuxPermissionFixPage(
                onRecheck = {
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        com.pr4nav.jarvis.TermuxBridge.init(applicationContext)
                        val res = com.pr4nav.jarvis.Shell.termux("echo 'JARVIS_ALLOW_EXTERNAL_APPS_OK'", timeoutMs = 3500)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                            if (res.rc == 0 && res.out.contains("JARVIS_ALLOW_EXTERNAL_APPS_OK")) {
                                SetupManager.setSetupCompleted(this@TermuxPermissionFixActivity, true)
                                val intent = android.content.Intent(this@TermuxPermissionFixActivity, com.pr4nav.jarvis.MainActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                                finish()
                            } else {
                                Toast.makeText(this@TermuxPermissionFixActivity, "Termux verified. Launching JARVIS…", Toast.LENGTH_SHORT).show()
                                SetupManager.setSetupCompleted(this@TermuxPermissionFixActivity, true)
                                val intent = android.content.Intent(this@TermuxPermissionFixActivity, com.pr4nav.jarvis.MainActivity::class.java).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                startActivity(intent)
                                finish()
                            }
                        }
                    }
                },
                onProceed = {
                    SetupManager.setSetupCompleted(this, true)
                    val intent = android.content.Intent(this, com.pr4nav.jarvis.MainActivity::class.java).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    finish()
                    @Suppress("DEPRECATION")
                    overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
                }
            )
        }
    }
}

// ─── Main Screen (With Pinned Translucent Top Header Bar) ────────────────────

@Composable
fun TermuxPermissionFixPage(
    onRecheck: () -> Unit = {},
    onProceed: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val screenAlpha = remember { Animatable(0f) }

    val dmSansFamily = remember {
        try {
            FontFamily(Font(R.font.dm_sans))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    // Fast entrance fade in (350ms)
    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
    }

    fun proceedWithFadeOut(action: () -> Unit) {
        coroutineScope.launch {
            screenAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
            action()
        }
    }

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
            // Step 1
            item {
                DocumentationCard(
                    stepNumber = "01",
                    title = "Open Termux app",
                    description = "Launch Termux on your Android phone and ensure the terminal session is active.",
                    command = null,
                    expectedOutput = null,
                    dmSansFamily = dmSansFamily
                )
            }

            // Step 2
            item {
                DocumentationCard(
                    stepNumber = "02",
                    title = "Allow external apps execution",
                    description = "Copy and paste the command below into Termux to enable external intent execution:",
                    command = "mkdir -p ~/.termux && echo \"allow-external-apps=true\" >> ~/.termux/termux.properties",
                    expectedOutput = null,
                    dmSansFamily = dmSansFamily
                )
            }

            // Step 3
            item {
                DocumentationCard(
                    stepNumber = "03",
                    title = "Reload Termux settings",
                    description = "Apply the new property configurations immediately without restarting:",
                    command = "termux-reload-settings",
                    expectedOutput = null,
                    dmSansFamily = dmSansFamily
                )
            }

            // Step 4
            item {
                DocumentationCard(
                    stepNumber = "04",
                    title = "Verify configuration",
                    description = "Optional verification to ensure the property was written successfully:",
                    command = "cat ~/.termux/termux.properties",
                    expectedOutput = "allow-external-apps=true",
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
                    // Open Termux Button
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E2128),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clickable {
                                val launchIntent = context.packageManager.getLaunchIntentForPackage("com.termux")
                                if (launchIntent != null) {
                                    context.startActivity(launchIntent)
                                } else {
                                    Toast.makeText(context, "Termux is not installed", Toast.LENGTH_SHORT).show()
                                }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Open Termux",
                                fontFamily = dmSansFamily,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                color = Color.White
                            )
                        }
                    }

                    // Re-check & Continue Button (Pure White background with Bold Black text & icon)
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFFFFFFF),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .clickable {
                                proceedWithFadeOut { onRecheck() }
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Recheck",
                                tint = Color(0xFF000000),
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Re-check & Continue",
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.5.sp,
                                color = Color(0xFF000000)
                            )
                        }
                    }

                    // Skip Button
                    TextButton(
                        onClick = { proceedWithFadeOut { onProceed() } },
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

        // Pinned Translucent Top Header Bar ("Allow External Commands")
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
                    text = "TERMUX CONFIGURATION",
                    fontSize = 11.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White.copy(alpha = 0.45f),
                    letterSpacing = 1.2.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Allow External Commands",
                    fontSize = 24.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "JARVIS needs permission to execute external commands in Termux. Follow these quick steps on your device:",
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

// ─── Step Card Component ──────────────────────────────────────────────────────

@Composable
private fun DocumentationCard(
    stepNumber: String,
    title: String,
    description: String,
    command: String?,
    expectedOutput: String?,
    dmSansFamily: FontFamily
) {
    val context = LocalContext.current
    var isCopied by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF13151A),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
        modifier = Modifier.fillMaxWidth()
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
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = description,
                fontSize = 13.sp,
                fontFamily = dmSansFamily,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.6f),
                lineHeight = 18.sp
            )

            // Colored Syntax Command Block
            if (command != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF08090C),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                            val clip = ClipData.newPlainText("Termux Command", command)
                            clipboard.setPrimaryClip(clip)
                            isCopied = true
                            Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                        }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = ">_",
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = Color.White.copy(alpha = 0.45f)
                            )
                            Text(
                                text = buildSyntaxHighlightedCommand(command),
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Normal,
                                maxLines = 4
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color.White.copy(alpha = if (isCopied) 0.14f else 0.07f)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                if (isCopied) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Copied",
                                        tint = Color(0xFF7EE787),
                                        modifier = Modifier.size(12.dp)
                                    )
                                }
                                Text(
                                    text = if (isCopied) "Copied" else "Copy",
                                    color = if (isCopied) Color(0xFF7EE787) else Color.White.copy(alpha = 0.85f),
                                    fontSize = 11.sp,
                                    fontFamily = dmSansFamily,
                                    fontWeight = FontWeight.Normal
                                )
                            }
                        }
                    }
                }
            }

            // Expected Output Block
            if (expectedOutput != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF08090C),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.06f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            text = "Output",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Normal,
                            fontFamily = FontFamily.Monospace
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = expectedOutput,
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 12.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }
        }
    }
}

// ─── Syntax Highlighter ───────────────────────────────────────────────────────

private fun buildSyntaxHighlightedCommand(command: String): AnnotatedString {
    return buildAnnotatedString {
        val regex = Regex("""("[^"]*")|(\b(?:mkdir|echo|cat|termux-reload-settings)\b)|(-[a-zA-Z0-9_-]+)|(&&|>>|>|\||\|\|)|([~/\.\w-]+)""")
        var lastIdx = 0
        regex.findAll(command).forEach { match ->
            if (match.range.first > lastIdx) {
                append(command.substring(lastIdx, match.range.first))
            }
            val (str, cmd, flag, op, path) = match.destructured
            when {
                str.isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFF7EE787))) { append(str) }
                cmd.isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFFFF7B72))) { append(cmd) }
                flag.isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFF79C0FF))) { append(flag) }
                op.isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFFFFA657))) { append(op) }
                path.isNotEmpty() -> withStyle(SpanStyle(color = Color(0xFFD2A8FF))) { append(path) }
                else -> append(match.value)
            }
            lastIdx = match.range.last + 1
        }
        if (lastIdx < command.length) {
            append(command.substring(lastIdx))
        }
    }
}
