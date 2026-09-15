package com.pr4nav.jarvis.setup

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.TermuxBridge
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SetupStage {
    STAGE_1_FIRST_TIME_SETUP,
    STAGE_2_TERMUX_CHECK,
    STAGE_3_AGY_CHECK,
    STAGE_4_PERMISSION_DENIED_FIX
}

class SetupLoadingActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            SetupFlowScreen(
                onFlowComplete = {
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

@Composable
fun SetupFlowScreen(onFlowComplete: () -> Unit) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var currentStage by remember { mutableStateOf(SetupStage.STAGE_1_FIRST_TIME_SETUP) }
    val pageAlpha = remember { Animatable(0f) }
    var checkAttemptCount by remember { mutableStateOf(0) }

    val dmSansFamily = remember {
        try {
            FontFamily(Font(R.font.dm_sans))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    LaunchedEffect(checkAttemptCount) {
        if (checkAttemptCount == 0) {
            pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = LinearEasing))

            withContext(Dispatchers.IO) {
                try {
                    Capabilities.init(context)
                    Fs.init(context)
                    try { RootCapability.detect() } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
            delay(300L)

            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150, easing = LinearEasing))
            currentStage = SetupStage.STAGE_2_TERMUX_CHECK
        }

        pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = LinearEasing))

        var termuxAllowed = false
        withContext(Dispatchers.IO) {
            try {
                TermuxBridge.init(context)
                termuxAllowed = TermuxBridge.verifyExecution(timeoutMs = 3500L)
            } catch (_: Exception) {
                termuxAllowed = false
            }
        }
        delay(200L)

        if (termuxAllowed) {
            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150, easing = LinearEasing))
            onFlowComplete()
        } else {
            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150, easing = LinearEasing))
            currentStage = SetupStage.STAGE_4_PERMISSION_DENIED_FIX
            pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 200, easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B0E))
            .alpha(pageAlpha.value)
    ) {
        when (currentStage) {
            SetupStage.STAGE_1_FIRST_TIME_SETUP -> {
                NativeSetupVisualizer(
                    title = "Setting up JARVIS",
                    subtitle = "Initializing on-device capabilities and filesystem…",
                    dmSansFamily = dmSansFamily
                )
            }
            SetupStage.STAGE_2_TERMUX_CHECK -> {
                NativeSetupVisualizer(
                    title = "Verifying Environment",
                    subtitle = "Checking Termux execution bridge and permissions…",
                    dmSansFamily = dmSansFamily
                )
            }
            SetupStage.STAGE_3_AGY_CHECK -> {
                NativeSetupVisualizer(
                    title = "Checking Autonomous Agent",
                    subtitle = "Verifying local agent tools and environment…",
                    dmSansFamily = dmSansFamily
                )
            }
            SetupStage.STAGE_4_PERMISSION_DENIED_FIX -> {
                TermuxPermissionFixPage(
                    onRecheck = {
                        coroutineScope.launch {
                            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150, easing = LinearEasing))
                            currentStage = SetupStage.STAGE_2_TERMUX_CHECK
                            checkAttemptCount++
                        }
                    },
                    onProceed = {
                        coroutineScope.launch {
                            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 150, easing = LinearEasing))
                            onFlowComplete()
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun NativeSetupVisualizer(
    title: String,
    subtitle: String,
    dmSansFamily: FontFamily
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF090B0E)),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(32.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(68.dp)
                    .background(Color(0xFF131722), CircleShape)
                    .border(1.dp, Color(0xFF3B82F6).copy(alpha = 0.35f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = Color(0xFF3B82F6),
                    strokeWidth = 2.5.dp
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            Text(
                text = title,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = dmSansFamily,
                color = Color.White,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = subtitle,
                fontSize = 13.sp,
                fontFamily = dmSansFamily,
                color = Color.White.copy(alpha = 0.6f),
                textAlign = TextAlign.Center
            )
        }
    }
}
