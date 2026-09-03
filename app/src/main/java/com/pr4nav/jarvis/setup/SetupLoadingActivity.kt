package com.pr4nav.jarvis.setup

import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.annotation.OptIn
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.TermuxBridge
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val STAGE_1_VIDEO_URL =
    "https://cdn.dribbble.com/userupload/31110973/file/original-c00ce340ffea6186dc2a6499c1ef59e1.mp4"

private const val STAGE_2_VIDEO_URL =
    "https://cdn.dribbble.com/userupload/13830057/file/original-881cd859857eb90cd1819c08f52d9e22.mp4"

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

        // Immersive whole-screen edge-to-edge
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

// ─── Setup Flow Composable (3 Stages with Redirection Support) ───────────────

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

    // Sequence controller
    LaunchedEffect(checkAttemptCount) {
        if (checkAttemptCount == 0) {
            // --- 1. Fast Fade In Page 1 (350ms) ---
            pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))

            // Background Initial Setup
            val stage1StartTime = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                try {
                    Capabilities.init(context)
                    Fs.init(context)
                    try { RootCapability.detect() } catch (_: Exception) {}
                } catch (_: Exception) {}
            }
            val stage1Elapsed = System.currentTimeMillis() - stage1StartTime
            if (stage1Elapsed < 3200L) {
                delay(3200L - stage1Elapsed)
            }

            // --- 2. Fast Fade Out Page 1 (300ms) ---
            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
            currentStage = SetupStage.STAGE_2_TERMUX_CHECK
        }

        // --- 3. Fast Fade In Page 2 (350ms) ---
        pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))

        // Background Termux Check
        val stage2StartTime = System.currentTimeMillis()
        var termuxAllowed = false
        withContext(Dispatchers.IO) {
            try {
                TermuxBridge.init(context)
                val res = Shell.termux("echo 'JARVIS_ALLOW_EXTERNAL_APPS_OK'", timeoutMs = 3500)
                termuxAllowed = res.rc == 0 && res.out.contains("JARVIS_ALLOW_EXTERNAL_APPS_OK")
            } catch (_: Exception) {
                termuxAllowed = false
            }
        }
        val stage2Elapsed = System.currentTimeMillis() - stage2StartTime
        if (stage2Elapsed < 3200L) {
            delay(3200L - stage2Elapsed)
        }

        if (termuxAllowed) {
            val needsAgyCheck = !SetupManager.isAgyCheckCompleted(context)
            if (needsAgyCheck) {
                // Transition to AGY Check stage (first time only)
                pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
                currentStage = SetupStage.STAGE_3_AGY_CHECK
                pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))

                val agyStartTime = System.currentTimeMillis()
                withContext(Dispatchers.IO) {
                    try {
                        val res = Shell.ubuntu("which agy || test -x /usr/local/bin/agy", timeoutMs = 4000)
                        android.util.Log.i("SetupFlow", "AGY check result: ${res.rc}")
                    } catch (_: Exception) {}
                    SetupManager.setAgyCheckCompleted(context, true)
                }
                val agyElapsed = System.currentTimeMillis() - agyStartTime
                if (agyElapsed < 2600L) {
                    delay(2600L - agyElapsed)
                }
                pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
                onFlowComplete()
            } else {
                // Success -> Fast Fade Out Page 2 (300ms) & Complete
                pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
                onFlowComplete()
            }
        } else {
            // Denied -> Transition into Termux Permission Fix Screen (Stage 4)
            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
            currentStage = SetupStage.STAGE_4_PERMISSION_DENIED_FIX
            pageAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(pageAlpha.value)
    ) {
        when (currentStage) {
            SetupStage.STAGE_1_FIRST_TIME_SETUP -> {
                StageOneContent(dmSansFamily = dmSansFamily)
            }
            SetupStage.STAGE_2_TERMUX_CHECK -> {
                StageTwoContent(dmSansFamily = dmSansFamily)
            }
            SetupStage.STAGE_3_AGY_CHECK -> {
                StageAgyContent(dmSansFamily = dmSansFamily)
            }
            SetupStage.STAGE_4_PERMISSION_DENIED_FIX -> {
                TermuxPermissionFixPage(
                    onRecheck = {
                        // Fast fade out and redirect back to Stage 2 check screen
                        coroutineScope.launch {
                            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
                            currentStage = SetupStage.STAGE_2_TERMUX_CHECK
                            checkAttemptCount++
                        }
                    },
                    onProceed = {
                        coroutineScope.launch {
                            pageAlpha.animateTo(0f, animationSpec = tween(durationMillis = 300, easing = LinearEasing))
                            onFlowComplete()
                        }
                    }
                )
            }
        }
    }
}

// ─── Stage 1: Setting up for the first time ──────────────────────────────────

@Composable
private fun StageOneContent(dmSansFamily: FontFamily) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        FullscreenVideo(
            url = STAGE_1_VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 36.dp)
        ) {
            ShimmerText(
                text = "Setting up for the first time...",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = dmSansFamily,
                shimmerDuration = 2400
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "Please wait, this will take a couple of seconds only",
                fontSize = 11.sp,
                fontFamily = dmSansFamily,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.45f),
                letterSpacing = 0.1.sp,
                lineHeight = 15.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ─── Stage 2: Termux allow-external-apps verification ────────────────────────

@Composable
private fun StageTwoContent(dmSansFamily: FontFamily) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        FullscreenVideo(
            url = STAGE_2_VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 36.dp)
        ) {
            ShimmerText(
                text = "I'm checking if I can do things here...\nI'm still learning!",
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = dmSansFamily,
                shimmerDuration = 2600
            )
            Spacer(modifier = Modifier.height(3.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "</>",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f),
                    letterSpacing = (-0.5).sp
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "executing command",
                    fontSize = 11.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.45f),
                    letterSpacing = 0.1.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// ─── TextureView-Backed Video Player (Full Alpha Blending Support) ──────────

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember(url) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(url) { onDispose { exoPlayer.release() } }

    AndroidView(
        factory = { ctx ->
            val playerView = LayoutInflater.from(ctx)
                .inflate(R.layout.view_texture_player, null, false) as PlayerView
            playerView.player = exoPlayer
            playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            playerView
        },
        modifier = modifier
    )
}

// ─── Shimmer Text ────────────────────────────────────────────────────────────

@Composable
private fun ShimmerText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily,
    shimmerDuration: Int
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = shimmerDuration, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerOffset"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = 0.45f),
            Color.White,
            Color.White.copy(alpha = 0.45f),
        ),
        start = Offset(offset * 900f, 0f),
        end = Offset((offset + 0.6f) * 900f, 0f)
    )

    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        style = TextStyle(
            brush = brush,
            letterSpacing = (-0.3).sp,
            lineHeight = 27.sp
        )
    )
}

// ─── Stage: Checking AGY Autonomous Agent (First Time Only) ──────────────────

@Composable
private fun StageAgyContent(dmSansFamily: FontFamily) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        FullscreenVideo(
            url = STAGE_2_VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                }
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 36.dp)
        ) {
            ShimmerText(
                text = "Checking AGY Autonomous Agent…\nReady to solve complex tasks.",
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = dmSansFamily,
                shimmerDuration = 2600
            )

            Spacer(modifier = Modifier.height(3.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "</>",
                    fontSize = 11.5.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                    color = Color.White.copy(alpha = 0.55f),
                    letterSpacing = (-0.5).sp
                )

                Spacer(modifier = Modifier.width(6.dp))

                Text(
                    text = "verifying antigravity in proot linux",
                    fontSize = 11.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.45f),
                    letterSpacing = 0.1.sp,
                    lineHeight = 15.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}
