package com.pr4nav.jarvis.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.pr4nav.jarvis.R
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
import com.pr4nav.jarvis.MainActivity
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val VIDEO_URL =
    "https://cdn.dribbble.com/userupload/15363755/file/original-33c8fceaa8f1f12da02deb9ef182cafd.mp4"

private const val INTRODUCING = "Introducing"

/**
 * "Introducing J4rvis" Cinematic Reveal Page.
 * Displays luminous holographic sphere without stretching, letter-by-letter
 * "Introducing" reveal, and sweeping gradient "J4rvis" typography.
 */
class JarvisIntroActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    @Volatile private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val windowInsetsController = WindowCompat.getInsetsController(window, window.decorView)
        windowInsetsController.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())

        setContent {
            IntroLoadingScreen(
                onProceed = { proceedToMain() },
                bindPlayer = { player ->
                    exoPlayer = player
                    var loopCount = 0
                    player.addListener(object : Player.Listener {
                        override fun onPlaybackStateChanged(playbackState: Int) {
                            if (playbackState == Player.STATE_ENDED) {
                                loopCount++
                                if (loopCount < 2) {
                                    player.seekTo(0)
                                    player.play()
                                } else {
                                    proceedToMain()
                                }
                            }
                        }
                    })
                }
            )
        }
    }

    private fun proceedToMain() {
        if (isNavigating || isFinishing || isDestroyed) return
        isNavigating = true
        SetupManager.setSetupCompleted(this, true)
        val intent = Intent(this, UserNameSetupActivity::class.java).apply {
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
fun IntroLoadingScreen(
    onProceed: () -> Unit = {},
    bindPlayer: (ExoPlayer) -> Unit = {}
) {
    var typed by remember { mutableStateOf("") }
    var jarvisVisible by remember { mutableStateOf(false) }
    val screenAlpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    val spaceGroteskFamily = remember {
        try {
            FontFamily(Font(R.font.space_grotesk))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    val dmSansFamily = remember {
        try {
            FontFamily(Font(R.font.dm_sans))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
        INTRODUCING.forEachIndexed { index, _ ->
            delay(80)
            typed = INTRODUCING.substring(0, index + 1)
        }
        delay(180)
        jarvisVisible = true
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(screenAlpha.value)
            .clickable {
                coroutineScope.launch {
                    screenAlpha.animateTo(0f, animationSpec = tween(durationMillis = 250, easing = LinearEasing))
                    onProceed()
                }
            }
    ) {
        // Video rendered with FIT + uniform scale to strictly prevent video stretch
        IntroFullscreenVideo(
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
            // "Introducing" — letter by letter
            Text(
                text = typed + if (typed.length < INTRODUCING.length) "|" else "",
                fontSize = 15.sp,
                fontFamily = dmSansFamily,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.6f),
                letterSpacing = 0.05.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            // "J4rvis" — gradient sweep animation
            if (jarvisVisible) {
                IntroGradientText(
                    text = "J4rvis",
                    fontSize = 48.sp,
                    fontFamily = spaceGroteskFamily,
                    fontWeight = FontWeight.Bold
                )
            } else {
                // Reserve space so layout doesn't jump
                Spacer(modifier = Modifier.height(50.dp))
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Sub-text 1 — shimmer
            if (jarvisVisible) {
                IntroShimmerText(
                    text = "Waking up jarvis for the very first time...",
                    fontSize = 13.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Please wait, this will take a couple of seconds only",
                    fontSize = 10.5.sp,
                    fontFamily = dmSansFamily,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.38f),
                    letterSpacing = 0.1.sp,
                    lineHeight = 14.7.sp,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }
    }
}

// ─── Video player ────────────────────────────────────────────────────────────

@Composable
private fun IntroFullscreenVideo(
    url: String,
    modifier: Modifier = Modifier,
    onReady: (ExoPlayer) -> Unit = {}
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.parse(url)))
            repeatMode = ExoPlayer.REPEAT_MODE_OFF
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

// ─── Gradient sweep text (J4rvis) ────────────────────────────────────────────

@Composable
private fun IntroGradientText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily? = null
) {
    val gradientWidth = 700f
    val transition = rememberInfiniteTransition(label = "jarvisGradient")
    val offset by transition.animateFloat(
        initialValue = 0f,
        targetValue = gradientWidth,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "gradientOffset"
    )

    val brush = Brush.linearGradient(
        colors = listOf(
            Color.White,
            Color(0xFFA78BFA), // violet
            Color(0xFF38BDF8), // sky blue
            Color(0xFF818CF8), // indigo
            Color.White,
        ),
        start = Offset(offset, 0f),
        end = Offset(offset + gradientWidth, 0f),
        tileMode = TileMode.Repeated
    )

    Text(
        text = text,
        fontSize = fontSize,
        fontWeight = fontWeight,
        fontFamily = fontFamily,
        style = TextStyle(
            brush = brush,
            letterSpacing = (-1.5).sp
        )
    )
}

// ─── Shimmer text ─────────────────────────────────────────────────────────────

@Composable
private fun IntroShimmerText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    fontWeight: FontWeight,
    fontFamily: FontFamily? = null
) {
    val transition = rememberInfiniteTransition(label = "introShimmer")
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
        fontFamily = fontFamily,
        style = TextStyle(brush = brush, letterSpacing = (-0.2).sp)
    )
}
