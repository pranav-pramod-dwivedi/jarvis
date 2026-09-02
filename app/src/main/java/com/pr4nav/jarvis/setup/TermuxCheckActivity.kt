package com.pr4nav.jarvis.setup

import android.net.Uri
import android.os.Bundle
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
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pr4nav.jarvis.R
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.TermuxBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TERMUX_CHECK_VIDEO_URL =
    "https://cdn.dribbble.com/userupload/13830057/file/original-881cd859857eb90cd1819c08f52d9e22.mp4"

// ─── 2nd Setup Screen: Termux allow-external-apps Verification ──────────────

class TermuxCheckActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // Immersive whole-screen edge-to-edge
        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        // Run Termux background execution check
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                TermuxBridge.init(applicationContext)
                Shell.termux("echo 'JARVIS_ALLOW_EXTERNAL_APPS_OK'", timeoutMs = 4000)
            } catch (_: Exception) {}
        }

        setContent { TermuxCheckScreen() }
    }
}

// ─── Screen Composable ────────────────────────────────────────────────────────

@Composable
fun TermuxCheckScreen() {
    val dmSansFamily = remember {
        FontFamily(
            Font(R.font.dm_sans, FontWeight.Normal),
            Font(R.font.dm_sans, FontWeight.SemiBold),
            Font(R.font.dm_sans, FontWeight.Bold)
        )
    }

    // Smooth entrance fade-in for all elements
    val fadeInAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        fadeInAlpha.animateTo(
            targetValue = 1f,
            animationSpec = tween(durationMillis = 800, easing = EaseInOut)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(fadeInAlpha.value)
    ) {
        // Video centered & unstretched
        FullscreenVideo(
            url = TERMUX_CHECK_VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                }
        )

        // Bottom Left Typography Block
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 28.dp, bottom = 36.dp)
        ) {
            // Main text with clean two-line clause break
            ShimmerText(
                text = "I'm checking if I can do things here...\nI'm still learning!",
                fontSize = 21.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = dmSansFamily
            )

            Spacer(modifier = Modifier.height(3.dp))

            // Secondary row: </> icon + "executing command"
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Small </> code icon
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

// ─── Video player ─────────────────────────────────────────────────────────────

@OptIn(UnstableApi::class)
@Composable
private fun FullscreenVideo(url: String, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }
    DisposableEffect(Unit) { onDispose { exoPlayer.release() } }

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
    fontWeight: FontWeight,
    fontFamily: FontFamily
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val offset by transition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2600, easing = LinearEasing),
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
