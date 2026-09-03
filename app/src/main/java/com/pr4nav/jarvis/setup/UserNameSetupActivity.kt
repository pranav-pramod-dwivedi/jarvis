package com.pr4nav.jarvis.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pr4nav.jarvis.MainActivity
import kotlinx.coroutines.launch

private const val VIDEO_URL =
    "https://cdn.dribbble.com/userupload/14870403/file/original-d4592d4cddfa59e3f760c477b88d86d7.mp4"

/**
 * First-time User Profile & Name Setup Screen.
 * Uses golden ratio spacing (Fibonacci: 8, 13, 21, 34, 55),
 * non-stretched circular robot visual, and seamless text input.
 */
class UserNameSetupActivity : ComponentActivity() {

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
            NameSetupScreen(
                onSubmit = { enteredName ->
                    proceedToMain(enteredName)
                },
                bindPlayer = { player -> exoPlayer = player }
            )
        }
    }

    private fun proceedToMain(enteredName: String) {
        if (isNavigating || isFinishing || isDestroyed) return
        isNavigating = true

        val trimmed = enteredName.trim()
        if (trimmed.isNotBlank()) {
            SetupManager.setUserName(this, trimmed)
        }
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
fun NameSetupScreen(
    onSubmit: (String) -> Unit = {},
    bindPlayer: (ExoPlayer) -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    val screenAlpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
    }

    fun submit() {
        coroutineScope.launch {
            screenAlpha.animateTo(0f, animationSpec = tween(durationMillis = 250, easing = LinearEasing))
            onSubmit(name)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(screenAlpha.value)
    ) {
        // Video rendered with FIT + uniform scale (No stretch, preserves robot eyes geometry)
        NameFullscreenVideo(
            url = VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.25f
                    scaleY = 1.25f
                },
            onReady = bindPlayer
        )

        // Bottom scrim gradient for readability (Golden ratio gradient progression)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.38f to Color(0x20000000),
                        0.62f to Color(0x99000000),
                        1f to Color(0xFA000000)
                    )
                )
        )

        // Content pinned to bottom with golden ratio vertical rhythm (8, 13, 21, 34, 55 dp)
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .navigationBarsPadding()
                .imePadding()
                .padding(start = 34.dp, end = 34.dp, bottom = 28.dp)
        ) {
            // Greeting — 34sp Fibonacci headline with gradient
            Text(
                text = "Hi, I am Jarvis",
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.8).sp,
                style = TextStyle(
                    brush = Brush.linearGradient(
                        colors = listOf(
                            Color.White,
                            Color(0xFFC4B5FD), // soft lavender
                            Color(0xFF7DD3FC)  // radiant sky
                        )
                    )
                )
            )

            // Fibonacci spacing: 8dp
            Spacer(modifier = Modifier.height(8.dp))

            // Subtitle — 17sp (half of 34sp)
            Text(
                text = "What should I call you?",
                fontSize = 17.sp,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.55f),
                letterSpacing = (-0.1).sp
            )

            // Fibonacci spacing: 34dp
            Spacer(modifier = Modifier.height(34.dp))

            // Separator line with subtle golden ratio opacity
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            )

            // Fibonacci spacing: 21dp
            Spacer(modifier = Modifier.height(21.dp))

            // Input row — 44dp height touch targets
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                // + button (44dp target)
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.08f))
                        .border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                        .clickable { submit() }
                ) {
                    Text("+", color = Color.White.copy(alpha = 0.8f), fontSize = 22.sp)
                }

                // Transparent text field — 21sp Fibonacci font size
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Normal,
                        letterSpacing = (-0.2).sp
                    ),
                    cursorBrush = SolidColor(Color(0xFF38BDF8)),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { submit() }
                    ),
                    decorationBox = { inner ->
                        if (name.isEmpty()) {
                            Text(
                                "Your name...",
                                color = Color.White.copy(alpha = 0.28f),
                                fontSize = 21.sp
                            )
                        }
                        inner()
                    },
                    modifier = Modifier.weight(1f)
                )

                // Send / Mic button (44dp target)
                val hasText = name.isNotBlank()
                val bgBrush = if (hasText)
                    Brush.linearGradient(listOf(Color(0xFFA78BFA), Color(0xFF38BDF8)))
                else
                    Brush.linearGradient(listOf(Color.White.copy(0.08f), Color.White.copy(0.08f)))

                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(bgBrush)
                        .then(
                            if (!hasText) Modifier.border(1.dp, Color.White.copy(alpha = 0.18f), CircleShape)
                            else Modifier
                        )
                        .clickable { submit() }
                ) {
                    if (hasText) {
                        Text("↑", color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text("🎙", fontSize = 18.sp)
                    }
                }
            }
        }
    }
}

// ─── Video player ─────────────────────────────────────────────────────────────

@Composable
private fun NameFullscreenVideo(
    url: String,
    modifier: Modifier = Modifier,
    onReady: (ExoPlayer) -> Unit = {}
) {
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
