package com.pr4nav.jarvis.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.TermuxBridge
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val VIDEO_URL =
    "https://cdn.dribbble.com/userupload/45546543/file/51b6e93b5e57cc0ab3ae175603b73076.mp4"

/**
 * Cinematic First-Time Wake Loading Screen.
 * Plays 2 loops minimum - 4 loops maximum while running real background setups.
 * Automatically transitions to MainActivity (chat screen) when ready.
 */
class JarvisWakeLoadingActivity : ComponentActivity() {

    @Volatile private var isSetupDone = false
    @Volatile private var isNavigating = false
    private var exoPlayer: ExoPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Run real background setup during the 2-4 video loops
        CoroutineScope(Dispatchers.IO).launch {
            try {
                Capabilities.init(applicationContext)
                Fs.init(applicationContext)
                try { RootCapability.detect() } catch (_: Exception) {}
                TermuxBridge.init(applicationContext)
                try {
                    Shell.termux("echo 'JARVIS_WAKE_SETUP_OK'", timeoutMs = 3500)
                } catch (_: Exception) {}
                try {
                    Shell.ubuntu("which agy || test -x /usr/local/bin/agy", timeoutMs = 3500)
                } catch (_: Exception) {}
                SetupManager.setSetupCompleted(applicationContext, true)
                SetupManager.setAgyCheckCompleted(applicationContext, true)
            } catch (_: Exception) {
            } finally {
                isSetupDone = true
            }
        }

        setContent {
            LoadingScreen(
                onProceed = {
                    proceedToMain()
                },
                bindPlayer = { player ->
                    exoPlayer = player
                    setupLoopController(player)
                }
            )
        }
    }

    private fun setupLoopController(player: ExoPlayer) {
        var loopCount = 0
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    loopCount++
                    // Requirement: 2 loops minimum - 4 loops maximum
                    if (loopCount < 2) {
                        player.seekTo(0)
                        player.play()
                    } else if (loopCount < 4 && !isSetupDone) {
                        player.seekTo(0)
                        player.play()
                    } else {
                        // 2 loops done + setup complete, or hit 4 loops max
                        proceedToMain()
                    }
                }
            }
        })
    }

    private fun proceedToMain() {
        if (isNavigating || isFinishing || isDestroyed) return
        isNavigating = true
        SetupManager.setSetupCompleted(this, true)
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            exoPlayer?.release()
        } catch (_: Exception) {}
        exoPlayer = null
    }
}

// ─── Screen ──────────────────────────────────────────────────────────────────

@Composable
fun LoadingScreen(
    onProceed: () -> Unit = {},
    bindPlayer: (ExoPlayer) -> Unit = {}
) {
    val screenAlpha = remember { Animatable(0f) }

    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, animationSpec = tween(durationMillis = 400, easing = LinearEasing))
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(screenAlpha.value)
    ) {
        FullscreenVideo(
            url = VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                },
            onReady = bindPlayer
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 32.dp, bottom = 48.dp)
        ) {
            ShimmerText(
                text = "Waking up jarvis for the very first time...",
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(modifier = Modifier.height(5.dp))
            Text(
                text = "Please wait, this will take a couple of seconds only",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.45f),
                letterSpacing = 0.1.sp,
                lineHeight = 14.7.sp,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

// ─── Video player ─────────────────────────────────────────────────────────────

@Composable
private fun FullscreenVideo(
    url: String,
    modifier: Modifier = Modifier,
    onReady: (ExoPlayer) -> Unit = {}
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            repeatMode = ExoPlayer.REPEAT_MODE_OFF // Controlled loop count (2 min - 4 max)
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(exoPlayer) {
        onReady(exoPlayer)
    }

    DisposableEffect(Unit) {
        onDispose { exoPlayer.release() }
    }

    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = exoPlayer
                useController = false
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
        modifier = modifier
    )
}

// ─── Shimmer text ─────────────────────────────────────────────────────────────

@Composable
private fun ShimmerText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2400, easing = LinearEasing),
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
        start = Offset(offset * 800f, 0f),
        end = Offset((offset + 0.6f) * 800f, 0f)
    )

    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        style = TextStyle(brush = brush, letterSpacing = (-0.3).sp)
    )
}
