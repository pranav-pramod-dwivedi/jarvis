package com.pr4nav.jarvis.setup

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pr4nav.jarvis.MainActivity
import com.pr4nav.jarvis.R
import kotlinx.coroutines.launch

private const val VIDEO_URL =
    "https://cdn.dribbble.com/userupload/14870403/file/original-d4592d4cddfa59e3f760c477b88d86d7.mp4"

/**
 * First-time User Profile & Name Setup Screen.
 * Uses official SF Pro typography, dynamic IME keyboard adaptation
 * (robot face glides up and scales smoothly when keyboard opens),
 * and golden ratio spacing.
 */
class UserNameSetupActivity : ComponentActivity() {

    private var exoPlayer: ExoPlayer? = null
    @Volatile private var isNavigating = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Enable edge to edge with transparent status/nav bars to preserve IME insets
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NameSetupScreen(
    onSubmit: (String) -> Unit = {},
    bindPlayer: (ExoPlayer) -> Unit = {}
) {
    var name by remember { mutableStateOf("") }
    val screenAlpha = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }

    // Official SF Pro typography
    val sfProFamily = remember {
        try {
            FontFamily(Font(R.font.sf_pro))
        } catch (_: Exception) {
            FontFamily.SansSerif
        }
    }

    LaunchedEffect(Unit) {
        screenAlpha.animateTo(1f, animationSpec = tween(durationMillis = 350, easing = LinearEasing))
    }

    fun submit() {
        coroutineScope.launch {
            screenAlpha.animateTo(0f, animationSpec = tween(durationMillis = 250, easing = LinearEasing))
            onSubmit(name)
        }
    }

    // Dynamic keyboard avoidance: smoothly move robot face up and scale when keyboard opens
    val isImeOpen = WindowInsets.isImeVisible
    val density = LocalDensity.current
    val targetOffsetPx = with(density) { if (isImeOpen) (-140).dp.toPx() else 0f }

    val videoOffsetY by animateFloatAsState(
        targetValue = targetOffsetPx,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 320f),
        label = "videoOffsetY"
    )
    val videoScale by animateFloatAsState(
        targetValue = if (isImeOpen) 0.95f else 1.25f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 320f),
        label = "videoScale"
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .alpha(screenAlpha.value)
    ) {
        // Video rendered with FIT + uniform scale (No stretch, horizontal robot eyes)
        // Dynamically adapts position when keyboard opens so face is never covered!
        NameFullscreenVideo(
            url = VIDEO_URL,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = videoScale
                    scaleY = videoScale
                    translationY = videoOffsetY
                },
            onReady = bindPlayer
        )

        // Bottom scrim gradient for readability
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

        // Content pinned to bottom with golden ratio vertical rhythm & IME avoidance
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .imePadding()
                .navigationBarsPadding()
                .padding(start = 34.dp, end = 34.dp, bottom = 28.dp)
        ) {
            // Greeting — 34sp SF Pro headline with gradient
            Text(
                text = "Hi, I am Jarvis",
                fontSize = 34.sp,
                fontFamily = sfProFamily,
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

            // Subtitle — 17sp SF Pro
            Text(
                text = "What should I call you?",
                fontSize = 17.sp,
                fontFamily = sfProFamily,
                fontWeight = FontWeight.Normal,
                color = Color.White.copy(alpha = 0.55f),
                letterSpacing = (-0.1).sp
            )

            // Fibonacci spacing: 34dp (or 24dp when keyboard open)
            val separatorSpacing by animateDpAsState(
                targetValue = if (isImeOpen) 20.dp else 34.dp,
                label = "separatorSpacing"
            )
            Spacer(modifier = Modifier.height(separatorSpacing))

            // Separator line with subtle golden ratio opacity
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Color.White.copy(alpha = 0.12f))
            )

            // Fibonacci spacing: 21dp (or 16dp when keyboard open)
            val inputSpacing by animateDpAsState(
                targetValue = if (isImeOpen) 16.dp else 21.dp,
                label = "inputSpacing"
            )
            Spacer(modifier = Modifier.height(inputSpacing))

            // Input row — 44dp height touch targets
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(13.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        try { focusRequester.requestFocus() } catch (_: Exception) {}
                    }
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
                    Text(
                        "+",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 22.sp,
                        fontFamily = sfProFamily
                    )
                }

                // Transparent text field — 20sp SF Pro font
                BasicTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 20.sp,
                        fontFamily = sfProFamily,
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
                                fontSize = 20.sp,
                                fontFamily = sfProFamily,
                                fontWeight = FontWeight.Normal
                            )
                        }
                        inner()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .focusRequester(focusRequester)
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
                        Text(
                            "↑",
                            color = Color.White,
                            fontSize = 21.sp,
                            fontFamily = sfProFamily,
                            fontWeight = FontWeight.Bold
                        )
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
