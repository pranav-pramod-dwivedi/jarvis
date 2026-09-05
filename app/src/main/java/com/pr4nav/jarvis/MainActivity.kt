@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.media3.common.util.UnstableApi::class,
    androidx.compose.foundation.ExperimentalFoundationApi::class
)

package com.pr4nav.jarvis

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Shader
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.widget.EditText
import android.widget.Toast
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import com.pr4nav.jarvis.companion.CompanionManager
import com.pr4nav.jarvis.router.AgentExecutionMode
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.session.JarvisSession
import com.pr4nav.jarvis.session.JarvisSessionManager
import com.pr4nav.jarvis.session.SessionHistoryDialog
import com.pr4nav.jarvis.session.SessionMessage
import com.pr4nav.jarvis.session.SessionType
import com.pr4nav.jarvis.setup.SetupLoadingActivity
import com.pr4nav.jarvis.setup.SetupManager
import com.pr4nav.jarvis.voice.JarvisVoiceEngine
import com.pr4nav.jarvis.voice.JarvisVoiceService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Random
import kotlin.math.cos
import kotlin.math.sin

// ─── Constants & View States ──────────────────────────────────────────────────

private const val DRIBBBLE_ORB_VIDEO_URL =
    "https://cdn.dribbble.com/userupload/48175050/file/a2c40840cf95dde8784132e3897449b8.mp4"

enum class ViewState {
    EXPLORE,
    VOICE_ACTIVE,
    CONVERSATION
}

// ─── Retro CRT TV Analog Noise, Chunky Grain & Scanline Generator ───────────

object DynamicCrtTvEffects {
    private val grainFrameBrushes = mutableListOf<ShaderBrush>()
    private val scanlineBrush: ShaderBrush
    private const val TOTAL_FRAMES = 8
    private const val TILE_SIZE = 256
    private const val GRAIN_BLOCK_SIZE = 4 // 4x4 chunky pixels for retro CRT phosphor look

    init {
        // 1. Generate multi-frame chunky CRT phosphor grain
        for (f in 0 until TOTAL_FRAMES) {
            val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(TILE_SIZE * TILE_SIZE)
            val rnd = Random(4040L + f * 997L)

            val blocksCount = TILE_SIZE / GRAIN_BLOCK_SIZE
            for (by in 0 until blocksCount) {
                for (bx in 0 until blocksCount) {
                    val noiseVal = (rnd.nextGaussian() * 68 + 128).toInt().coerceIn(0, 255)
                    // Refined chunky phosphor sparkle (alpha 28 to 68)
                    val alphaVal = rnd.nextInt(40) + 28
                    val color = android.graphics.Color.argb(alphaVal, noiseVal, noiseVal, noiseVal)

                    val startY = by * GRAIN_BLOCK_SIZE
                    val startX = bx * GRAIN_BLOCK_SIZE
                    for (dy in 0 until GRAIN_BLOCK_SIZE) {
                        val rowOffset = (startY + dy) * TILE_SIZE
                        for (dx in 0 until GRAIN_BLOCK_SIZE) {
                            pixels[rowOffset + startX + dx] = color
                        }
                    }
                }
            }
            bitmap.setPixels(pixels, 0, TILE_SIZE, 0, 0, TILE_SIZE, TILE_SIZE)
            val shader = BitmapShader(bitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
            grainFrameBrushes.add(ShaderBrush(shader))
        }

        // 2. Generate delicate CRT horizontal raster scanlines (repeating 5px vertical strip)
        val scanlineBitmap = Bitmap.createBitmap(1, 5, Bitmap.Config.ARGB_8888)
        scanlineBitmap.setPixel(0, 0, android.graphics.Color.argb(0, 0, 0, 0))  // Phosphor row 1
        scanlineBitmap.setPixel(0, 1, android.graphics.Color.argb(0, 0, 0, 0))  // Phosphor row 2
        scanlineBitmap.setPixel(0, 2, android.graphics.Color.argb(0, 0, 0, 0))  // Phosphor row 3
        scanlineBitmap.setPixel(0, 3, android.graphics.Color.argb(0, 0, 0, 0))  // Phosphor row 4
        scanlineBitmap.setPixel(0, 4, android.graphics.Color.argb(38, 0, 0, 0)) // Subtle soft raster shadow line
        val scanlineShader = BitmapShader(scanlineBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        scanlineBrush = ShaderBrush(scanlineShader)
    }

    fun getGrainBrush(frameIndex: Int): ShaderBrush {
        return grainFrameBrushes[frameIndex % TOTAL_FRAMES]
    }

    fun getScanlineBrush(): ShaderBrush {
        return scanlineBrush
    }
}

// ─── Main Activity Entry Point ────────────────────────────────────────────────

class MainActivity : ComponentActivity() {

    private var voiceEngine: JarvisVoiceEngine? = null
    private var initialIntentPrompt: String? = null
    private var targetSessionIdExtra by mutableStateOf<String?>(null)

    // Real-time Voice Service Observers for Hands-Free & Overlay HUD continuity
    private var onSpeechRecognizedCallback: ((String) -> Unit)? = null
    private var onResponseSynthesizedCallback: ((String, String) -> Unit)? = null
    private var onThinkingTraceCallback: ((String) -> Unit)? = null
    private var onWakeWordTriggeredCallback: (() -> Unit)? = null

    private val voiceCoreObserver = object : JarvisVoiceService.CoreObserver {
        override fun onStateChanged(state: JarvisVoiceService.VoiceState, detail: String) {
            runOnUiThread {
                if (state == JarvisVoiceService.VoiceState.WAKE_DETECTED || state == JarvisVoiceService.VoiceState.LISTENING) {
                    onWakeWordTriggeredCallback?.invoke()
                }
            }
        }
        override fun onSpeechRecognized(text: String) {
            runOnUiThread {
                if (text.isNotBlank()) {
                    onSpeechRecognizedCallback?.invoke(text)
                }
            }
        }
        override fun onResponseSynthesized(speechText: String, fullSummary: String) {
            runOnUiThread {
                if (fullSummary.isNotBlank()) {
                    onResponseSynthesizedCallback?.invoke(speechText, fullSummary)
                }
            }
        }
        override fun onThinkingTrace(trace: String) {
            runOnUiThread {
                if (trace.isNotBlank()) {
                    onThinkingTraceCallback?.invoke(trace)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }

        // 1. Core Initializations
        TermuxBridge.init(this)
        Fs.init(this)
        Capabilities.init(this)
        Thread { RootCapability.detect() }.start()

        // 2. Setup Verification
        if (!SetupManager.isSetupCompleted(this)) {
            startActivity(Intent(this, SetupLoadingActivity::class.java))
            finish()
            return
        }

        // 3. Voice Engine Initialization
        voiceEngine = JarvisVoiceEngine.getInstance(this)

        // 4. First Run Permissions Check
        checkFirstRunPermissions()

        // 5. Check incoming intent prompt & session
        initialIntentPrompt = extractPromptFromIntent(intent)
        targetSessionIdExtra = intent?.getStringExtra("open_session_id")

        // 6. Launch Compose UI
        setContent {
            JarvisMainApp(
                voiceEngine = voiceEngine,
                initialPrompt = initialIntentPrompt,
                targetSessionId = targetSessionIdExtra,
                onOpenDeveloperHub = { showDeveloperHubMenu() },
                onOpenSessionHistory = { onSelectSession, onNewSession ->
                    SessionHistoryDialog(
                        this,
                        filterType = SessionType.AGENT_CHAT,
                        onSessionSelected = { session -> onSelectSession(session) },
                        onNewSessionRequested = { onNewSession() }
                    ).show()
                },
                onToggleCompanionMode = { showCompanionModeDialog() },
                onConfigureQwenUrl = { showConfigureQwenUrlDialog() },
                onShowRegenerateDialog = { prompt, onSelectedMode ->
                    showRegenerateDialog(prompt, onSelectedMode)
                },
                onRegisterVoiceCallbacks = { onSpeech, onResponse, onThinking, onWake ->
                    onSpeechRecognizedCallback = onSpeech
                    onResponseSynthesizedCallback = onResponse
                    onThinkingTraceCallback = onThinking
                    onWakeWordTriggeredCallback = onWake
                }
            )

            if (isToolsDialogVisible) {
                JarvisToolsDialog(
                    onDismiss = { isToolsDialogVisible = false },
                    onOpenStandby = {
                        isToolsDialogVisible = false
                        isStandbyDialogVisible = true
                    },
                    onConfigureQwenUrl = {
                        isToolsDialogVisible = false
                        showConfigureQwenUrlDialog()
                    }
                )
            }
            if (isStandbyDialogVisible) {
                JarvisStandbyVoiceDialog(onDismiss = { isStandbyDialogVisible = false })
            }
        }
    }

    override fun onStart() {
        super.onStart()
        JarvisVoiceService.registerObserver(voiceCoreObserver)
    }

    override fun onStop() {
        super.onStop()
        JarvisVoiceService.unregisterObserver(voiceCoreObserver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("from_wake_word", false)) {
            onWakeWordTriggeredCallback?.invoke()
        }
        val sessId = intent.getStringExtra("open_session_id")
        if (!sessId.isNullOrBlank()) {
            targetSessionIdExtra = sessId
        }
        val p = extractPromptFromIntent(intent)
        if (!p.isNullOrBlank()) {
            initialIntentPrompt = p
        }
        if (!p.isNullOrBlank() || !sessId.isNullOrBlank()) {
            setContent {
                JarvisMainApp(
                    voiceEngine = voiceEngine,
                    initialPrompt = initialIntentPrompt,
                    targetSessionId = targetSessionIdExtra,
                    onOpenDeveloperHub = { showDeveloperHubMenu() },
                    onOpenSessionHistory = { onSelectSession, onNewSession ->
                        SessionHistoryDialog(
                            this,
                            filterType = SessionType.AGENT_CHAT,
                            onSessionSelected = { session -> onSelectSession(session) },
                            onNewSessionRequested = { onNewSession() }
                        ).show()
                    },
                    onToggleCompanionMode = { showCompanionModeDialog() },
                    onConfigureQwenUrl = { showConfigureQwenUrlDialog() },
                    onShowRegenerateDialog = { prompt, onSelectedMode ->
                        showRegenerateDialog(prompt, onSelectedMode)
                    },
                    onRegisterVoiceCallbacks = { onSpeech, onResponse, onThinking, onWake ->
                        onSpeechRecognizedCallback = onSpeech
                        onResponseSynthesizedCallback = onResponse
                        onThinkingTraceCallback = onThinking
                        onWakeWordTriggeredCallback = onWake
                    }
                )

                if (isToolsDialogVisible) {
                    JarvisToolsDialog(
                        onDismiss = { isToolsDialogVisible = false },
                        onOpenStandby = {
                            isToolsDialogVisible = false
                            isStandbyDialogVisible = true
                        },
                        onConfigureQwenUrl = {
                            isToolsDialogVisible = false
                            showConfigureQwenUrlDialog()
                        }
                    )
                }
                if (isStandbyDialogVisible) {
                    JarvisStandbyVoiceDialog(onDismiss = { isStandbyDialogVisible = false })
                }
            }
        }
    }

    private fun extractPromptFromIntent(intent: Intent?): String? {
        val p = intent?.getStringExtra("prompt") ?: intent?.getStringExtra("auto_submit")
        if (!p.isNullOrBlank()) {
            val decoded = try { java.net.URLDecoder.decode(p, "UTF-8") } catch (_: Exception) { p }
            return decoded.trim()
        }
        if (intent?.getBooleanExtra("from_wake_word", false) == true) {
            val wake = intent.getStringExtra("wake_word") ?: "Jarvis"
            return "Wake word detected: $wake"
        }
        return null
    }

    override fun onResume() {
        super.onResume()
        if (com.pr4nav.jarvis.voice.VoiceAssistantPreferences.isHandsFreeEnabled(this)) {
            val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasMic) {
                JarvisVoiceService.start(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        JarvisVoiceService.unregisterObserver(voiceCoreObserver)
        voiceEngine?.destroy()
    }

    private fun checkFirstRunPermissions() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (!prefs.getBoolean("perm_asked_v1", false)) {
            prefs.edit().putBoolean("perm_asked_v1", true).apply()
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private fun showConfigureQwenUrlDialog() {
        val currentKey = com.pr4nav.jarvis.llm.GroqClient.getApiKey(this)
        val currentModel = com.pr4nav.jarvis.llm.GroqClient.getModel(this)
        val metrics = com.pr4nav.jarvis.llm.GroqClient.getUsageMetrics(this)

        val layout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(50, 40, 50, 20)
        }

        val infoText = android.widget.TextView(this).apply {
            text = "⚡ Groq LPU powers fast reasoning and native shell execution.\n\n" +
                    "• Token Limit: Max 8,192 tokens/msg\n" +
                    "• Rate Quotas: ${metrics.rpdUsed}/${metrics.rpdLimit} RPD · ${metrics.currentTpm}/${metrics.tpmLimit} TPM\n" +
                    "• Escalation: Exceeded limits or complex coding tasks automatically route to AGY.\n" +
                    "• Active Model: $currentModel"
            textSize = 12.5f
            setTextColor(android.graphics.Color.parseColor("#94A3B8"))
        }
        layout.addView(infoText)

        val editKey = EditText(this).apply {
            setText(currentKey)
            hint = "Groq API Key (gsk_...)"
            setPadding(30, 25, 30, 25)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(editKey)

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("⚡ Configure Groq API Key & Quotas")
            .setView(layout)
            .setPositiveButton("Save") { _, _ ->
                val newKey = editKey.text.toString().trim()
                com.pr4nav.jarvis.llm.GroqClient.setApiKey(this, newKey)
                Toast.makeText(this, if (newKey.isNotEmpty()) "Groq API Key Saved!" else "Groq API Key Cleared", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Switch Model") { _, _ ->
                val models = arrayOf(
                    "⚡ openai/gpt-oss-120b (Default · Flagship OSS 120B)",
                    "🧠 groq/compound (Complex Multi-Tool Compound Agent)",
                    "⚡ groq/compound-mini (Ultra-Fast Compound Agent)",
                    "llama-3.3-70b-versatile (Flagship 70B)",
                    "llama-3.1-8b-instant (Fast 8B)",
                    "mixtral-8x7b-32768 (32k Context)",
                    "📥 Fetch Available Models from Groq API..."
                )
                androidx.appcompat.app.AlertDialog.Builder(this)
                    .setTitle("Select Groq Model")
                    .setItems(models) { _, which ->
                        when (which) {
                            0 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "openai/gpt-oss-120b")
                                Toast.makeText(this, "Model set to openai/gpt-oss-120b (Default)", Toast.LENGTH_SHORT).show()
                            }
                            1 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "groq/compound")
                                Toast.makeText(this, "Model set to groq/compound", Toast.LENGTH_SHORT).show()
                            }
                            2 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "groq/compound-mini")
                                Toast.makeText(this, "Model set to groq/compound-mini", Toast.LENGTH_SHORT).show()
                            }
                            3 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "llama-3.3-70b-versatile")
                                Toast.makeText(this, "Model set to llama-3.3-70b-versatile", Toast.LENGTH_SHORT).show()
                            }
                            4 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "llama-3.1-8b-instant")
                                Toast.makeText(this, "Model set to llama-3.1-8b-instant", Toast.LENGTH_SHORT).show()
                            }
                            5 -> {
                                com.pr4nav.jarvis.llm.GroqClient.setModel(this, "mixtral-8x7b-32768")
                                Toast.makeText(this, "Model set to mixtral-8x7b-32768", Toast.LENGTH_SHORT).show()
                            }
                            6 -> {
                                Toast.makeText(this, "Fetching models from Groq...", Toast.LENGTH_SHORT).show()
                                com.pr4nav.jarvis.llm.GroqClient.fetchAvailableModels(
                                    context = this,
                                    onSuccess = { fetched ->
                                        runOnUiThread {
                                            if (fetched.isEmpty()) {
                                                Toast.makeText(this, "No models returned by Groq", Toast.LENGTH_SHORT).show()
                                                return@runOnUiThread
                                            }
                                            androidx.appcompat.app.AlertDialog.Builder(this)
                                                .setTitle("Available Groq Models (${fetched.size})")
                                                .setItems(fetched.toTypedArray()) { _, fWhich ->
                                                    val chosen = fetched[fWhich]
                                                    com.pr4nav.jarvis.llm.GroqClient.setModel(this, chosen)
                                                    Toast.makeText(this, "Model set to $chosen", Toast.LENGTH_SHORT).show()
                                                }
                                                .setNegativeButton("Cancel", null)
                                                .show()
                                        }
                                    },
                                    onError = { err ->
                                        runOnUiThread {
                                            Toast.makeText(this, "Fetch error: $err", Toast.LENGTH_LONG).show()
                                        }
                                    }
                                )
                            }
                        }
                    }
                    .show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showRegenerateDialog(prompt: String, onSelectedMode: (AgentExecutionMode) -> Unit) {
        val modes = AgentExecutionMode.values()
        val items = modes.map { "${it.displayName}\n${it.description}" }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Regenerate with Engine:")
            .setItems(items) { _, which ->
                val targetMode = modes[which]
                UnifiedAssistantDispatcher.setAgentMode(this, targetMode)
                Toast.makeText(this, "Switched to ${targetMode.displayName}", Toast.LENGTH_SHORT).show()
                onSelectedMode(targetMode)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }


    private var isToolsDialogVisible by mutableStateOf(false)
    private var isStandbyDialogVisible by mutableStateOf(false)

    private fun showCompanionModeDialog() {
        isStandbyDialogVisible = true
    }

    private fun showHandsFreeSettingsDialog() {
        isStandbyDialogVisible = true
    }

    private fun showDeveloperHubMenu() {
        isToolsDialogVisible = true
    }
}

// ─── Master Application UI Composable ─────────────────────────────────────────

@Composable
fun JarvisMainApp(
    voiceEngine: JarvisVoiceEngine?,
    initialPrompt: String? = null,
    targetSessionId: String? = null,
    onOpenDeveloperHub: () -> Unit,
    onOpenSessionHistory: (onSelect: (JarvisSession) -> Unit, onNew: () -> Unit) -> Unit,
    onToggleCompanionMode: () -> Unit,
    onConfigureQwenUrl: () -> Unit,
    onShowRegenerateDialog: (prompt: String, onSelectedMode: (AgentExecutionMode) -> Unit) -> Unit,
    onRegisterVoiceCallbacks: (
        onSpeech: (String) -> Unit,
        onResponse: (String, String) -> Unit,
        onThinking: (String) -> Unit,
        onWake: () -> Unit
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var viewState by remember { mutableStateOf(ViewState.EXPLORE) }
    var isModelMenuOpen by remember { mutableStateOf(false) }

    // Execution & Streaming State
    var isWorking by remember { mutableStateOf(false) }
    var liveThinkingTitle by remember { mutableStateOf("Thinking…") }
    var liveStreamingText by remember { mutableStateOf("") }
    var liveThinkingSteps by remember { mutableStateOf<List<String>>(emptyList()) }
    var activeJob by remember { mutableStateOf<Job?>(null) }
    var lastSubmittedPrompt by remember { mutableStateOf("") }

    // Active execution mode
    var currentMode by remember {
        mutableStateOf(UnifiedAssistantDispatcher.getAgentMode(context))
    }

    // Active session & sessions list
    var currentSession by remember {
        val target = targetSessionId?.let { id ->
            JarvisSessionManager.listSessions(context).find { it.id == id }
        }
        mutableStateOf(target ?: JarvisSessionManager.getActiveSession(context, SessionType.AGENT_CHAT))
    }

    var sessionsList by remember {
        mutableStateOf(JarvisSessionManager.listSessions(context))
    }

    // Session messages list
    var sessionMessages by remember {
        mutableStateOf(currentSession.messages.toList())
    }

    LaunchedEffect(targetSessionId) {
        if (!targetSessionId.isNullOrBlank()) {
            val sess = JarvisSessionManager.listSessions(context).find { it.id == targetSessionId }
            if (sess != null) {
                currentSession = sess
                sessionMessages = sess.messages.toList()
                viewState = ViewState.CONVERSATION
            }
        }
    }

    var isSessionDrawerOpen by remember {
        mutableStateOf(false)
    }

    var isToolsDialogOpen by remember {
        mutableStateOf(false)
    }

    var isStandbyDialogOpen by remember {
        mutableStateOf(false)
    }

    // Space Grotesk for Titles & DM Sans for Body
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

    BackHandler(enabled = isSessionDrawerOpen || viewState != ViewState.EXPLORE) {
        if (isSessionDrawerOpen) {
            isSessionDrawerOpen = false
        } else {
            viewState = ViewState.EXPLORE
        }
    }

    fun copyToClipboard(text: String) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        cm?.setPrimaryClip(ClipData.newPlainText("JARVIS Output", text))
        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
    }

    fun handleNewSession() {
        val newSess = JarvisSessionManager.createSession(context, SessionType.AGENT_CHAT, workingDir = SessionState.dir)
        currentSession = newSess
        sessionMessages = emptyList()
        SessionState.dir = newSess.workingDir
        sessionsList = JarvisSessionManager.listSessions(context)
        isSessionDrawerOpen = false
        viewState = ViewState.CONVERSATION
        Toast.makeText(context, "Started new session", Toast.LENGTH_SHORT).show()
    }

    fun handleSelectSession(selected: JarvisSession) {
        currentSession = selected
        sessionMessages = selected.messages.toList()
        SessionState.dir = selected.workingDir
        sessionsList = JarvisSessionManager.listSessions(context)
        isSessionDrawerOpen = false
        viewState = ViewState.CONVERSATION
    }

    fun handleDeleteSession(sessionId: String) {
        JarvisSessionManager.deleteSession(context, sessionId)
        sessionsList = JarvisSessionManager.listSessions(context)
        if (currentSession.id == sessionId) {
            val fallback = sessionsList.firstOrNull() ?: JarvisSessionManager.createSession(context, SessionType.AGENT_CHAT)
            handleSelectSession(fallback)
        }
        Toast.makeText(context, "Session deleted", Toast.LENGTH_SHORT).show()
    }

    fun cancelActiveExecution() {
        activeJob?.cancel()
        activeJob = null
        isWorking = false
        voiceEngine?.stopSpeaking()
        val cancelMsg = SessionMessage(
            sender = "agent",
            text = "Task execution was cancelled.",
            steps = listOf("Operation stopped by user request"),
            isSuccess = false
        )
        JarvisSessionManager.appendMessage(context, currentSession, cancelMsg)
        sessionMessages = sessionMessages + cancelMsg
        Toast.makeText(context, "Task cancelled", Toast.LENGTH_SHORT).show()
    }

    fun raceWithAgy() {
        if (lastSubmittedPrompt.isBlank()) {
            Toast.makeText(context, "No active prompt to race", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = lastSubmittedPrompt
        isWorking = true
        liveThinkingTitle = "Racing with AGY in PRoot Linux…"
        liveStreamingText = "Executing parallel run via AGY in PRoot Linux…"
        liveThinkingSteps = listOf("Engine: AGY PRoot Linux", "Timeout: 60s")
        viewState = ViewState.CONVERSATION

        scope.launch(Dispatchers.IO) {
            try {
                val agyRes = Shell.agy(prompt, timeoutMs = 60_000)
                withContext(Dispatchers.Main) {
                    isWorking = false
                    val cleanOut = com.pr4nav.jarvis.response.UserResponseSanitizer.sanitize(agyRes.out, prompt)
                    val jarvisMsg = SessionMessage(
                        sender = "agent",
                        text = if (cleanOut.isNotBlank()) cleanOut else if (agyRes.err.isNotBlank()) agyRes.err else "(no output)",
                        steps = listOf(
                            "Parallel Engine: AGY PRoot Linux",
                            "Execution time: ${agyRes.ms}ms",
                            "Exit code: ${agyRes.rc}"
                        ),
                        isSuccess = agyRes.rc == 0
                    )
                    JarvisSessionManager.appendMessage(context, currentSession, jarvisMsg)
                    sessionMessages = sessionMessages + jarvisMsg

                    if (cleanOut.isNotBlank()) {
                        voiceEngine?.speak(cleanOut, interrupt = false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isWorking = false
                    Toast.makeText(context, "AGY race error: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun dispatchCommand(prompt: String, isFromVoice: Boolean = false) {
        val trimmed = prompt.trim()
        if (trimmed.isBlank()) return

        // ── AUTOMATIC NEW SESSION CREATION ──
        // When launching a prompt from Explore or Voice, if currentSession already has messages,
        // automatically create a brand new session so conversations are not lumped together!
        if ((viewState == ViewState.EXPLORE || viewState == ViewState.VOICE_ACTIVE) && currentSession.messages.isNotEmpty()) {
            val sessionType = if (isFromVoice) SessionType.VOICE_CHAT else SessionType.AGENT_CHAT
            val newSess = JarvisSessionManager.createSession(context, sessionType, workingDir = SessionState.dir)
            currentSession = newSess
            sessionMessages = emptyList()
            sessionsList = JarvisSessionManager.listSessions(context)
        }

        // Set human-readable title of session to the user prompt if initial message or default date title
        if (currentSession.messages.isEmpty() || currentSession.title.matches(Regex("^[0-9]{2} [A-Za-z]{3}.*"))) {
            currentSession.title = trimmed.take(38).replace("\n", " ")
            JarvisSessionManager.saveSession(context, currentSession)
            sessionsList = JarvisSessionManager.listSessions(context)
        }

        lastSubmittedPrompt = trimmed
        val userMsg = SessionMessage(
            sender = "user",
            text = trimmed
        )
        JarvisSessionManager.appendMessage(context, currentSession, userMsg)
        sessionMessages = sessionMessages + userMsg
        sessionsList = JarvisSessionManager.listSessions(context)
        viewState = ViewState.CONVERSATION

        val lower = trimmed.lowercase()
        val arg = trimmed.split(" ", limit = 2).getOrNull(1)?.trim() ?: ""

        // ── Built-in Developer Commands from AgentActivity ──
        when {
            lower.startsWith("/ui") || lower.startsWith("make an ui") || lower.startsWith("make a ui") ||
            lower.startsWith("create ui") || lower.startsWith("generate ui") -> {
                val uiPrompt = when {
                    lower.startsWith("/ui") -> trimmed.substring(3).trim()
                    lower.startsWith("make an ui") -> trimmed.substring(10).trim()
                    lower.startsWith("make a ui") -> trimmed.substring(9).trim()
                    lower.startsWith("create ui") -> trimmed.substring(9).trim()
                    lower.startsWith("generate ui") -> trimmed.substring(11).trim()
                    else -> trimmed
                }.ifBlank { "Futuristic Interactive Dashboard" }

                isWorking = true
                liveThinkingTitle = "Synthesizing UI with JarvisBrowser…"
                liveStreamingText = "Generating dynamic UI for: $uiPrompt"
                liveThinkingSteps = listOf("Target: JarvisBrowser", "Prompt: $uiPrompt", "Tier: Groq -> AGY PRoot Linux")

                scope.launch(Dispatchers.IO) {
                    val result = com.pr4nav.jarvis.browser.JarvisUiGenerator.generateAndLaunch(
                        context = context,
                        rawPrompt = uiPrompt,
                        onStatus = { st ->
                            scope.launch(Dispatchers.Main) {
                                liveThinkingTitle = st
                                liveThinkingSteps = liveThinkingSteps + st
                            }
                        }
                    )

                    withContext(Dispatchers.Main) {
                        isWorking = false
                        val msg = SessionMessage(
                            sender = "agent",
                            text = result,
                            steps = listOf("Engine: JarvisBrowser Dynamic UI Generator", "Fallback: AGY Autonomous Agent"),
                            isSuccess = true
                        )
                        JarvisSessionManager.appendMessage(context, currentSession, msg)
                        sessionMessages = sessionMessages + msg
                    }
                }
                return
            }

            lower == "help" -> {
                val msg = SessionMessage(
                    sender = "agent",
                    text = "Reference loaded.",
                    steps = listOf(
                        "Natural Voice & Chat: \"hi\", \"what is quantum computing\", \"take me home\"",
                        "Device Control: \"turn on flashlight\", \"set volume 80%\", \"take screenshot\"",
                        "Linux System: pwd, cd <path>, ls [path], run <cmd> (Termux shell)",
                        "Tools: tools (lists all 23+ canonical tools)"
                    ),
                    isSuccess = true
                )
                JarvisSessionManager.appendMessage(context, currentSession, msg)
                sessionMessages = sessionMessages + msg
                return
            }

            lower == "pwd" -> {
                val msg = SessionMessage(
                    sender = "agent",
                    text = "cwd: ${SessionState.dir}",
                    steps = listOf("Resolved from SessionState"),
                    isSuccess = true
                )
                JarvisSessionManager.appendMessage(context, currentSession, msg)
                sessionMessages = sessionMessages + msg
                return
            }

            lower.startsWith("cd ") -> {
                val targetPath = Fs.resolve(arg)
                val targetFile = File(targetPath)
                if (targetFile.exists() && targetFile.isDirectory) {
                    SessionState.dir = targetPath
                    val msg = SessionMessage(
                        sender = "agent",
                        text = "Working directory changed to: $targetPath",
                        steps = listOf("Resolved path: $targetPath"),
                        isSuccess = true
                    )
                    JarvisSessionManager.appendMessage(context, currentSession, msg)
                    sessionMessages = sessionMessages + msg
                } else {
                    val msg = SessionMessage(
                        sender = "agent",
                        text = "Directory not found: $targetPath",
                        steps = listOf("Path does not exist: $targetPath"),
                        isSuccess = false
                    )
                    JarvisSessionManager.appendMessage(context, currentSession, msg)
                    sessionMessages = sessionMessages + msg
                }
                return
            }

            lower.startsWith("ls") -> {
                val p = Fs.resolve(arg.ifBlank { "." })
                val list = Fs.list(p)
                val items = list.take(10).map { (if (it.isDir) "[dir] " else "[file] ") + it.name }
                val msg = SessionMessage(
                    sender = "agent",
                    text = "Found ${list.size} items in $p",
                    steps = items,
                    isSuccess = true
                )
                JarvisSessionManager.appendMessage(context, currentSession, msg)
                sessionMessages = sessionMessages + msg
                return
            }

            lower.startsWith("run ") -> {
                isWorking = true
                liveThinkingTitle = "Executing Termux Shell…"
                liveStreamingText = "Running command: $arg"
                liveThinkingSteps = listOf("Target: Termux PRoot Shell", "Command: $arg")

                scope.launch(Dispatchers.IO) {
                    val guardErr = CmdGuard.check(arg)
                    if (guardErr != null) {
                        withContext(Dispatchers.Main) {
                            isWorking = false
                            val msg = SessionMessage(
                                sender = "agent",
                                text = "Security guard prevented execution: $guardErr",
                                steps = listOf("Command Blocked: $guardErr"),
                                isSuccess = false
                            )
                            JarvisSessionManager.appendMessage(context, currentSession, msg)
                            sessionMessages = sessionMessages + msg
                        }
                        return@launch
                    }
                    val r = Shell.termux(arg, 60_000)
                    withContext(Dispatchers.Main) {
                        isWorking = false
                        val outText = if (r.out.isNotBlank()) r.out.take(1000) else if (r.err.isNotBlank()) r.err.take(500) else "(no output)"
                        val msg = SessionMessage(
                            sender = "agent",
                            text = outText,
                            steps = listOf("Exit code: ${r.rc}", "Execution time: ${r.ms}ms", "Via: ${r.via}"),
                            isSuccess = r.rc == 0
                        )
                        JarvisSessionManager.appendMessage(context, currentSession, msg)
                        sessionMessages = sessionMessages + msg
                    }
                }
                return
            }

            lower == "tools" -> {
                com.pr4nav.jarvis.tools.JarvisToolRegistry.registerAll(context)
                val cat = com.pr4nav.jarvis.tools.JarvisToolRegistry.catalog()
                val msg = SessionMessage(
                    sender = "agent",
                    text = "All canonical tools ready for autonomous execution.",
                    steps = cat.lines().take(12),
                    isSuccess = true
                )
                JarvisSessionManager.appendMessage(context, currentSession, msg)
                sessionMessages = sessionMessages + msg
                return
            }
        }

        // ── Autonomous Execution Pipeline with Real-time Streaming ──
        isWorking = true
        liveThinkingTitle = "Thinking…"
        liveStreamingText = ""
        liveThinkingSteps = listOf("Analyzing intent…")

        activeJob = scope.launch(Dispatchers.IO) {
            val accumulatedChunks = StringBuilder()
            val intermediateSteps = mutableListOf<String>()

            UnifiedAssistantDispatcher.execute(
                context = context,
                rawQuery = trimmed,
                onStatus = { status ->
                    scope.launch(Dispatchers.Main) {
                        liveThinkingTitle = status
                        intermediateSteps.add(status)
                        liveThinkingSteps = intermediateSteps.toList()
                    }
                },
                onChunk = { chunk ->
                    scope.launch(Dispatchers.Main) {
                        accumulatedChunks.append(chunk)
                        liveStreamingText = accumulatedChunks.toString()
                    }
                },
                onResult = { res ->
                    scope.launch(Dispatchers.Main) {
                        val replyText = if (res.jarvisResponse.text.isNotBlank()) {
                            res.jarvisResponse.text
                        } else if (accumulatedChunks.isNotBlank()) {
                            accumulatedChunks.toString()
                        } else {
                            "Action executed successfully."
                        }

                        val steps = mutableListOf<String>()
                        if (res.thinkingTrace.isNotBlank()) {
                            val traceLines = res.thinkingTrace
                                .replace("<think>", "")
                                .replace("</think>", "")
                                .trim()
                                .lines()
                                .map { it.trim() }
                                .filter { it.isNotEmpty() }
                            steps.addAll(traceLines)
                        } else {
                            steps.add("Engine: ${res.modelName}")
                            steps.add("Execution: ${res.source.badge}")
                            if (res.latencyMs > 0) steps.add("Latency: ${res.latencyMs}ms")
                        }

                        val jarvisMsg = SessionMessage(
                            sender = "agent",
                            text = replyText,
                            steps = steps,
                            isSuccess = res.handled
                        )
                        JarvisSessionManager.appendMessage(context, currentSession, jarvisMsg)
                        sessionMessages = sessionMessages + jarvisMsg

                        isWorking = false
                        liveStreamingText = ""
                        liveThinkingSteps = emptyList()

                        if (res.jarvisResponse.speechText.isNotBlank()) {
                            voiceEngine?.speak(res.jarvisResponse.speechText, interrupt = false)
                        }
                    }
                }
            )
        }
    }

    // Connect reactive voice service callbacks
    LaunchedEffect(Unit) {
        onRegisterVoiceCallbacks(
            { recognizedText ->
                val userMsg = SessionMessage(sender = "user", text = recognizedText)
                JarvisSessionManager.appendMessage(context, currentSession, userMsg)
                sessionMessages = sessionMessages + userMsg
            },
            { _, fullSummary ->
                val agentMsg = SessionMessage(sender = "agent", text = fullSummary, isSuccess = true)
                JarvisSessionManager.appendMessage(context, currentSession, agentMsg)
                sessionMessages = sessionMessages + agentMsg
                isWorking = false
            },
            { trace ->
                if (isWorking) {
                    liveThinkingTitle = trace
                }
            },
            {
                // In-app voice listening is only triggered when the user explicitly taps the microphone button
            }
        )
    }

    LaunchedEffect(initialPrompt) {
        if (!initialPrompt.isNullOrBlank()) {
            dispatchCommand(initialPrompt)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF000000))
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, dragAmount ->
                    if (dragAmount > 20 && change.position.x < 120) {
                        sessionsList = JarvisSessionManager.listSessions(context)
                        isSessionDrawerOpen = true
                    }
                }
            }
    ) {
        // Dynamic Dynamic Multi-Phase Atmosphere with Tactile Grain Noise
        LivingAtmosphereBackground(viewState = viewState)

        AnimatedContent(
            targetState = viewState,
            transitionSpec = {
                fadeIn(animationSpec = tween(450, easing = LinearOutSlowInEasing)) togetherWith
                        fadeOut(animationSpec = tween(350, easing = FastOutLinearInEasing))
            },
            label = "ScreenMotion"
        ) { state ->
            when (state) {
                ViewState.EXPLORE -> {
                    ExploreView(
                        currentMode = currentMode,
                        titleFontFamily = spaceGroteskFamily,
                        bodyFontFamily = dmSansFamily,
                        onOpenModelSelector = { isModelMenuOpen = true },
                        onStartVoice = { viewState = ViewState.VOICE_ACTIVE },
                        onOpenConversation = { viewState = ViewState.CONVERSATION },
                        onOpenDeveloperHub = { isToolsDialogOpen = true },
                        onOpenHistory = {
                            sessionsList = JarvisSessionManager.listSessions(context)
                            isSessionDrawerOpen = true
                        },
                        onToggleCompanionMode = { isStandbyDialogOpen = true },
                        onExecutePrompt = { prompt -> dispatchCommand(prompt) }
                    )
                }

                ViewState.VOICE_ACTIVE -> {
                    VoiceSessionView(
                        voiceEngine = voiceEngine,
                        titleFontFamily = spaceGroteskFamily,
                        bodyFontFamily = dmSansFamily,
                        onClose = { viewState = ViewState.EXPLORE },
                        onSendVoiceInput = { transcript -> dispatchCommand(transcript, isFromVoice = true) }
                    )
                }

                ViewState.CONVERSATION -> {
                    ConversationView(
                        currentSession = currentSession,
                        messages = sessionMessages,
                        isWorking = isWorking,
                        thinkingTitle = liveThinkingTitle,
                        streamingText = liveStreamingText,
                        thinkingSteps = liveThinkingSteps,
                        currentMode = currentMode,
                        titleFontFamily = spaceGroteskFamily,
                        bodyFontFamily = dmSansFamily,
                        onClose = { viewState = ViewState.EXPLORE },
                        onOpenVoice = { viewState = ViewState.VOICE_ACTIVE },
                        onOpenModelSelector = { isModelMenuOpen = true },
                        onOpenHistory = {
                            sessionsList = JarvisSessionManager.listSessions(context)
                            isSessionDrawerOpen = true
                        },
                        onNewSession = { handleNewSession() },
                        onSendMessage = { text -> dispatchCommand(text) },
                        onCopyText = { text -> copyToClipboard(text) },
                        onSpeakText = { text -> voiceEngine?.speak(text, interrupt = true) },
                        onStopSpeech = { voiceEngine?.stopSpeaking() },
                        onRegeneratePrompt = { prompt ->
                            onShowRegenerateDialog(prompt) { targetMode ->
                                currentMode = targetMode
                                dispatchCommand(prompt)
                            }
                        },
                        onCancelTask = { cancelActiveExecution() },
                        onRaceAgy = { raceWithAgy() },
                        onOpenDeveloperHub = { isToolsDialogOpen = true }
                    )
                }
            }
        }

        if (isModelMenuOpen) {
            ModelPickerSheet(
                currentMode = currentMode,
                titleFontFamily = spaceGroteskFamily,
                bodyFontFamily = dmSansFamily,
                onSelect = { selected ->
                    currentMode = selected
                    UnifiedAssistantDispatcher.setAgentMode(context, selected)
                    isModelMenuOpen = false
                },
                onConfigureQwenUrl = {
                    isModelMenuOpen = false
                    onConfigureQwenUrl()
                },
                onDismiss = { isModelMenuOpen = false }
            )
        }

        // ── Termux-Style Left-to-Right Slide Drawer ──
        TermuxSessionDrawer(
            isOpen = isSessionDrawerOpen,
            currentSessionId = currentSession.id,
            sessions = sessionsList,
            titleFontFamily = spaceGroteskFamily,
            bodyFontFamily = dmSansFamily,
            onClose = { isSessionDrawerOpen = false },
            onSelectSession = { handleSelectSession(it) },
            onNewSession = { handleNewSession() },
            onDeleteSession = { handleDeleteSession(it) },
            onOpenTools = { isToolsDialogOpen = true },
            onOpenControls = { isStandbyDialogOpen = true }
        )

        // ── Tools Dialog & Standby / Voice Intelligence Dialog ──
        if (isToolsDialogOpen) {
            JarvisToolsDialog(
                onDismiss = { isToolsDialogOpen = false },
                onOpenStandby = {
                    isToolsDialogOpen = false
                    isStandbyDialogOpen = true
                },
                onConfigureQwenUrl = {
                    isToolsDialogOpen = false
                    onConfigureQwenUrl()
                }
            )
        }
        if (isStandbyDialogOpen) {
            JarvisStandbyVoiceDialog(onDismiss = { isStandbyDialogOpen = false })
        }
    }
}

// ─── Continuous Fluid Ambient Atmosphere Background with Film Grain Noise ─────

@Composable
fun LivingAtmosphereBackground(viewState: ViewState) {
    val infiniteTransition = rememberInfiniteTransition(label = "AlwaysOnLivingAtmosphere")

    // Continuous 25fps retro CRT phosphor grain flicker
    val crtFrame by infiniteTransition.animateValue(
        initialValue = 0,
        targetValue = 7,
        typeConverter = Int.VectorConverter,
        animationSpec = infiniteRepeatable(
            animation = tween(190, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "crtFrame"
    )

    // Always-on continuous fluid wave drift (runs constantly even on static screen)
    val wavePhase1 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(7500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase1"
    )

    val wavePhase2 by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 6.28318f,
        animationSpec = infiniteRepeatable(
            animation = tween(11000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "wavePhase2"
    )

    val breathingPulse by infiniteTransition.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(3800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathingPulse"
    )

    // Smooth Interpolation Weights Across All 3 Distinct Phases
    val exploreWeight by animateFloatAsState(
        targetValue = if (viewState == ViewState.EXPLORE) 1f else 0f,
        animationSpec = tween(650, easing = EaseInOutCubic),
        label = "exploreWeight"
    )

    val voiceWeight by animateFloatAsState(
        targetValue = if (viewState == ViewState.VOICE_ACTIVE) 1f else 0f,
        animationSpec = tween(650, easing = EaseInOutCubic),
        label = "voiceWeight"
    )

    val chatWeight by animateFloatAsState(
        targetValue = if (viewState == ViewState.CONVERSATION) 1f else 0f,
        animationSpec = tween(650, easing = EaseInOutCubic),
        label = "chatWeight"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 1: EXPLORE (Always-On Undulating Fruity Molten Lava + Inverted U)
        // ═════════════════════════════════════════════════════════════════════
        if (exploreWeight > 0.005f) {
            // 1. Continuously undulating bright fruity lava orange field
            val startY = -h * 0.04f + h * 0.03f * sin(wavePhase1)
            val endY = h * (0.88f + 0.05f * cos(wavePhase2))
            drawRect(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFFFF7200).copy(alpha = exploreWeight), // Bright fruity magma
                        0.24f to Color(0xFFFF4D00).copy(alpha = exploreWeight), // Electric lava orange
                        0.44f to Color(0xFFE52200).copy(alpha = exploreWeight), // Vibrant crimson lava
                        0.64f to Color(0xFF8B0600).copy(alpha = exploreWeight), // Deep ruby core (no brown!)
                        0.84f to Color(0xFF000000).copy(alpha = exploreWeight), // Pure jet black base
                        1.00f to Color(0xFF000000).copy(alpha = exploreWeight)
                    ),
                    startY = startY,
                    endY = endY
                )
            )

            // 2. Continuous floating solar radiant hotspot in upper-right
            val hotX = w * (0.68f + 0.07f * sin(wavePhase1))
            val hotY = h * (0.16f + 0.04f * cos(wavePhase2))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF9500).copy(alpha = 0.80f * exploreWeight),
                        Color(0xFFFF3D00).copy(alpha = 0.55f * exploreWeight),
                        Color.Transparent
                    ),
                    center = Offset(hotX, hotY),
                    radius = w * 0.94f * breathingPulse
                ),
                radius = w * 0.94f * breathingPulse,
                center = Offset(hotX, hotY)
            )

            // 3. The Inverted-U Dark Volcanic Cavity breathing underneath headline
            val cavX = w * (0.35f + 0.04f * cos(wavePhase1))
            val cavY = h * (0.96f - 0.02f * sin(wavePhase2))
            val cavR = w * (1.12f + 0.05f * sin(wavePhase2))
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF000000).copy(alpha = exploreWeight),
                        0.48f to Color(0xFF000000).copy(alpha = exploreWeight),
                        0.72f to Color(0xFF140200).copy(alpha = 0.85f * exploreWeight),
                        0.88f to Color(0xFF380600).copy(alpha = 0.35f * exploreWeight),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(cavX, cavY),
                    radius = cavR
                ),
                radius = cavR,
                center = Offset(cavX, cavY)
            )

            // 4. Continuously flowing Molten Lava stream along right margin
            val flowRimX = w * (1.02f - 0.04f * sin(wavePhase2))
            val flowRimY = h * (0.58f + 0.06f * cos(wavePhase1))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF4D00).copy(alpha = 0.68f * exploreWeight),
                        Color(0xFFB01000).copy(alpha = 0.38f * exploreWeight),
                        Color.Transparent
                    ),
                    center = Offset(flowRimX, flowRimY),
                    radius = w * 0.80f * breathingPulse
                ),
                radius = w * 0.80f * breathingPulse,
                center = Offset(flowRimX, flowRimY)
            )
        }

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 2: VOICE ACTIVE (Always-On Undulating Lava Canopy & Rim Surges)
        // ═════════════════════════════════════════════════════════════════════
        if (voiceWeight > 0.005f) {
            // Base jet black floor
            drawRect(color = Color(0xFF000000).copy(alpha = voiceWeight))

            // 1. Arched molten lava canopy undulating at top
            val voiceTopY = -h * 0.04f + h * 0.03f * sin(wavePhase1)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFFFF9500).copy(alpha = 0.95f * voiceWeight),
                        0.28f to Color(0xFFFF4D00).copy(alpha = 0.85f * voiceWeight),
                        0.58f to Color(0xFFD51A00).copy(alpha = 0.55f * voiceWeight),
                        0.85f to Color(0xFF550400).copy(alpha = 0.20f * voiceWeight),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(w * 0.50f, voiceTopY),
                    radius = w * 1.15f * breathingPulse
                ),
                radius = w * 1.15f * breathingPulse,
                center = Offset(w * 0.50f, voiceTopY)
            )

            // 2. Left glowing molten rim surging
            val leftY = h * (0.42f + 0.05f * cos(wavePhase2))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF4D00).copy(alpha = 0.82f * voiceWeight),
                        Color(0xFF9E0B00).copy(alpha = 0.48f * voiceWeight),
                        Color.Transparent
                    ),
                    center = Offset(-w * 0.12f, leftY),
                    radius = w * 0.74f * breathingPulse
                ),
                radius = w * 0.74f * breathingPulse,
                center = Offset(-w * 0.12f, leftY)
            )

            // 3. Right glowing molten rim surging
            val rightY = h * (0.42f - 0.05f * sin(wavePhase2))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF4D00).copy(alpha = 0.82f * voiceWeight),
                        Color(0xFF9E0B00).copy(alpha = 0.48f * voiceWeight),
                        Color.Transparent
                    ),
                    center = Offset(w * 1.12f, rightY),
                    radius = w * 0.74f * breathingPulse
                ),
                radius = w * 0.74f * breathingPulse,
                center = Offset(w * 1.12f, rightY)
            )

            // 4. Center dark volcanic chamber (forming the inverted U arch)
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFF000000).copy(alpha = 0.99f * voiceWeight),
                        0.55f to Color(0xFF000000).copy(alpha = 0.95f * voiceWeight),
                        0.82f to Color(0xFF160200).copy(alpha = 0.50f * voiceWeight),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(w * 0.50f, h * 0.48f),
                    radius = w * 0.68f
                ),
                radius = w * 0.68f,
                center = Offset(w * 0.50f, h * 0.48f)
            )

            // 5. Bottom amber smolder behind mic controls
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF3D00).copy(alpha = 0.48f * voiceWeight),
                        Color(0xFF8B0600).copy(alpha = 0.22f * voiceWeight),
                        Color.Transparent
                    ),
                    center = Offset(w * 0.50f, h * 0.95f),
                    radius = w * 0.72f
                ),
                radius = w * 0.72f,
                center = Offset(w * 0.50f, h * 0.95f)
            )
        }

        // ═════════════════════════════════════════════════════════════════════
        // PHASE 3: CONVERSATION (Always-On Surging Right-Rim Lava & Top-Right Flame)
        // ═════════════════════════════════════════════════════════════════════
        if (chatWeight > 0.005f) {
            // Base jet black floor
            drawRect(color = Color(0xFF000000).copy(alpha = chatWeight))

            // 1. Top-right blazing molten corner surging
            val chatTopX = w * (1.06f - 0.04f * cos(wavePhase1))
            drawCircle(
                brush = Brush.radialGradient(
                    colorStops = arrayOf(
                        0.00f to Color(0xFFFF9500).copy(alpha = 0.92f * chatWeight),
                        0.35f to Color(0xFFFF4D00).copy(alpha = 0.82f * chatWeight),
                        0.68f to Color(0xFFD51A00).copy(alpha = 0.48f * chatWeight),
                        1.00f to Color.Transparent
                    ),
                    center = Offset(chatTopX, -h * 0.02f),
                    radius = w * 1.22f * breathingPulse
                ),
                radius = w * 1.22f * breathingPulse,
                center = Offset(chatTopX, -h * 0.02f)
            )

            // 2. Right vertical glowing rim sweep continuously flowing
            val chatRimY = h * (0.50f + 0.07f * sin(wavePhase1))
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF4D00).copy(alpha = 0.80f * chatWeight),
                        Color(0xFF9E0B00).copy(alpha = 0.42f * chatWeight),
                        Color.Transparent
                    ),
                    center = Offset(w * 1.12f, chatRimY),
                    radius = w * 0.92f * breathingPulse
                ),
                radius = w * 0.92f * breathingPulse,
                center = Offset(w * 1.12f, chatRimY)
            )
        }

        // ═════════════════════════════════════════════════════════════════════
        // RETRO CRT TV CHUNKY PHOSPHOR GRAIN & ANALOG SIZZLE (25 FPS)
        // ═════════════════════════════════════════════════════════════════════
        val crtGrainBrush = DynamicCrtTvEffects.getGrainBrush(crtFrame)
        drawRect(
            brush = crtGrainBrush,
            blendMode = BlendMode.Overlay
        )
        drawRect(
            brush = crtGrainBrush,
            alpha = 0.26f,
            blendMode = BlendMode.Screen
        )

        // ═════════════════════════════════════════════════════════════════════
        // CRT TELEVISION HORIZONTAL RASTER SCANLINES
        // ═════════════════════════════════════════════════════════════════════
        val scanlineBrush = DynamicCrtTvEffects.getScanlineBrush()
        drawRect(
            brush = scanlineBrush,
            alpha = 0.35f,
            blendMode = BlendMode.Darken
        )
    }
}

// ─── Termux-Style Left-to-Right Sliding Session Drawer ───────────────────────

@Composable
fun TermuxSessionDrawer(
    isOpen: Boolean,
    currentSessionId: String,
    sessions: List<JarvisSession>,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onClose: () -> Unit,
    onSelectSession: (JarvisSession) -> Unit,
    onNewSession: () -> Unit,
    onDeleteSession: (String) -> Unit,
    onOpenTools: () -> Unit = {},
    onOpenControls: () -> Unit = {}
) {
    if (!isOpen) return

    BackHandler(enabled = true) {
        onClose()
    }

    var selectedFilter by remember { mutableStateOf<SessionType?>(null) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
    ) {
        // Scrim
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onClose() }
        )

        // Slide Drawer Content
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .width(310.dp)
                .background(Color(0xFF0C0301))
                .border(
                    BorderStroke(
                        1.dp,
                        Brush.verticalGradient(
                            listOf(
                                Color(0xFFFF7200).copy(alpha = 0.75f),
                                Color(0xFFE52200).copy(alpha = 0.40f),
                                Color.Transparent
                            )
                        )
                    )
                )
                .statusBarsPadding()
                .navigationBarsPadding()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        TerminalSvg(modifier = Modifier.size(18.dp), tint = Color(0xFFFF7200))
                        Column {
                            Text(
                                text = "TERMINAL SESSIONS",
                                color = Color.White,
                                fontSize = 14.sp,
                                fontFamily = titleFontFamily,
                                fontWeight = FontWeight.Bold,
                                letterSpacing = 0.5.sp
                            )
                            Text(
                                text = "JARVIS Workspace",
                                color = Color(0xFFFF7200).copy(alpha = 0.8f),
                                fontSize = 11.sp,
                                fontFamily = bodyFontFamily
                            )
                        }
                    }

                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        CloseSvg(modifier = Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.7f))
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // + New Session Glowing Button
                Surface(
                    onClick = {
                        onNewSession()
                        onClose()
                    },
                    shape = RoundedCornerShape(12.dp),
                    color = Color.Transparent,
                    border = BorderStroke(1.dp, Color(0xFFFF7200).copy(alpha = 0.75f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .shadow(8.dp, RoundedCornerShape(12.dp), ambientColor = Color(0xFFFF4D00), spotColor = Color(0xFFFF4D00))
                        .background(
                            brush = Brush.horizontalGradient(
                                listOf(
                                    Color(0xFFFF7200),
                                    Color(0xFFFF3D00),
                                    Color(0xFFC41C00)
                                )
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        PlusSvg(modifier = Modifier.size(14.dp), tint = Color.White)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "New Session",
                            color = Color.White,
                            fontSize = 13.5.sp,
                            fontFamily = titleFontFamily,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Filter Chips (All, Chat, Voice, AGY)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    listOf(
                        "ALL" to null,
                        "CHAT" to SessionType.AGENT_CHAT,
                        "VOICE" to SessionType.VOICE_CHAT,
                        "AGY" to SessionType.AGY_CODING
                    ).forEach { (label, type) ->
                        val isSelected = selectedFilter == type
                        Surface(
                            onClick = { selectedFilter = type },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFFFF4D00).copy(alpha = 0.25f) else Color.White.copy(alpha = 0.06f),
                            border = BorderStroke(1.dp, if (isSelected) Color(0xFFFF7200) else Color.White.copy(alpha = 0.12f))
                        ) {
                            Text(
                                text = label,
                                color = if (isSelected) Color(0xFFFF9500) else Color.White.copy(alpha = 0.65f),
                                fontSize = 10.5.sp,
                                fontFamily = titleFontFamily,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Sessions List
                val filtered = sessions.filter { selectedFilter == null || it.type == selectedFilter }
                if (filtered.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No sessions found",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 12.sp,
                            fontFamily = bodyFontFamily
                        )
                    }
                } else {
                    val timeFormat = remember { SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault()) }
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(filtered, key = { it.id }) { sess ->
                            val isActive = sess.id == currentSessionId
                            Surface(
                                onClick = {
                                    onSelectSession(sess)
                                    onClose()
                                },
                                shape = RoundedCornerShape(10.dp),
                                color = if (isActive) Color(0xFF1E0703) else Color(0xFF130502),
                                border = BorderStroke(
                                    1.dp,
                                    if (isActive) Color(0xFFFF7200).copy(alpha = 0.75f) else Color.White.copy(alpha = 0.10f)
                                ),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.weight(1f),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(9.dp)
                                    ) {
                                        when (sess.type) {
                                            SessionType.VOICE_CHAT -> MicSvg(modifier = Modifier.size(14.dp), tint = Color(0xFFFF9500))
                                            SessionType.AGY_CODING -> TerminalSvg(modifier = Modifier.size(14.dp), tint = Color(0xFF10B981))
                                            else -> ChatBubbleSvg(modifier = Modifier.size(14.dp), tint = Color(0xFFFF7200))
                                        }

                                        Column {
                                            Text(
                                                text = sess.title,
                                                color = if (isActive) Color.White else Color.White.copy(alpha = 0.88f),
                                                fontSize = 12.5.sp,
                                                fontFamily = bodyFontFamily,
                                                fontWeight = if (isActive) FontWeight.SemiBold else FontWeight.Normal,
                                                maxLines = 1
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = timeFormat.format(Date(sess.lastUsedMs)),
                                                    color = Color.White.copy(alpha = 0.45f),
                                                    fontSize = 10.sp,
                                                    fontFamily = bodyFontFamily
                                                )
                                                Text(
                                                    text = "•",
                                                    color = Color.White.copy(alpha = 0.3f),
                                                    fontSize = 10.sp
                                                )
                                                Text(
                                                    text = "${sess.messages.size} msgs",
                                                    color = Color(0xFFFF9500).copy(alpha = 0.75f),
                                                    fontSize = 10.sp,
                                                    fontFamily = bodyFontFamily
                                                )
                                            }
                                        }
                                    }

                                    IconButton(
                                        onClick = { onDeleteSession(sess.id) },
                                        modifier = Modifier.size(24.dp)
                                    ) {
                                        TrashSvg(
                                            modifier = Modifier.size(13.dp),
                                            tint = Color.White.copy(alpha = 0.4f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Controls & Tools Quick Access Buttons
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E0E08),
                        border = BorderStroke(1.dp, Color(0x55FF7200)),
                        onClick = {
                            onClose()
                            onOpenTools()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            ToolsSvg(modifier = Modifier.size(13.dp), tint = Color(0xFFFF8A00))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Tools",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Surface(
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFF1E0E08),
                        border = BorderStroke(1.dp, Color(0x55FF7200)),
                        onClick = {
                            onClose()
                            onOpenControls()
                        }
                    ) {
                        Row(
                            modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            SparkleSvg(modifier = Modifier.size(13.dp), tint = Color(0xFFFF8A00))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Controls",
                                color = Color.White,
                                fontSize = 11.5.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Footer with Workspace path
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        TerminalSvg(modifier = Modifier.size(11.dp), tint = Color(0xFFFF7200))
                        Text(
                            text = SessionState.dir,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 10.5.sp,
                            fontFamily = bodyFontFamily,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

// ─── View 1: Explore Dashboard ────────────────────────────────────────────────

@Composable
fun ExploreView(
    currentMode: AgentExecutionMode,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onOpenModelSelector: () -> Unit,
    onStartVoice: () -> Unit,
    onOpenConversation: () -> Unit,
    onOpenDeveloperHub: () -> Unit,
    onOpenHistory: () -> Unit,
    onToggleCompanionMode: () -> Unit,
    onExecutePrompt: (String) -> Unit
) {
    val context = LocalContext.current
    var textInput by remember { mutableStateOf("") }
    var isTermuxReady by remember { mutableStateOf(false) }
    var isCompanionOn by remember { mutableStateOf(CompanionManager.isEnabled(context)) }

    // Animated entrance transitions for texts
    var isHeaderVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        isHeaderVisible = true
        withContext(Dispatchers.IO) {
            isTermuxReady = Shell.termuxReachable()
        }
    }

    val headlineAlpha by animateFloatAsState(
        targetValue = if (isHeaderVisible) 1f else 0f,
        animationSpec = tween(600, easing = EaseOutCubic),
        label = "headlineAlpha"
    )
    val headlineOffsetY by animateFloatAsState(
        targetValue = if (isHeaderVisible) 0f else 28f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 320f),
        label = "headlineOffsetY"
    )

    val userName = remember { com.pr4nav.jarvis.setup.SetupManager.getUserName(context) }
    val avatarInitial = remember(userName) {
        if (userName.isNotBlank()) userName.first().uppercase() else "J"
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 22.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .background(Color.White.copy(alpha = 0.12f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.35f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = avatarInitial,
                        color = Color.White,
                        fontSize = 16.sp,
                        fontFamily = titleFontFamily,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column {
                    Text(
                        text = userName,
                        color = Color.White,
                        fontSize = 14.sp,
                        fontFamily = titleFontFamily,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .background(if (isTermuxReady) Color(0xFF10B981) else Color(0xFFE8580D), CircleShape)
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = if (isTermuxReady) "Linux Ready" else "Linux Standby",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 11.5.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Normal
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                // Companion mode toggle badge - GREYED OUT per user request
                Surface(
                    onClick = {
                        Toast.makeText(context, "Companion mode is greyed out / paused for now", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(14.dp),
                    color = Color.White.copy(alpha = 0.04f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.08f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SparkleSvg(
                            modifier = Modifier.size(11.dp),
                            tint = Color.White.copy(alpha = 0.35f)
                        )
                        Text(
                            text = "COMPANION (PAUSED)",
                            color = Color.White.copy(alpha = 0.35f),
                            fontSize = 10.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                IconButton(onClick = onOpenDeveloperHub, modifier = Modifier.size(36.dp)) {
                    ToolsSvg(
                        modifier = Modifier.size(20.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }

                IconButton(onClick = onOpenHistory, modifier = Modifier.size(36.dp)) {
                    HamburgerMenuSvg(
                        modifier = Modifier.size(20.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                }
            }
        }

        // Center Content Area with Animated Text
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .offset(y = headlineOffsetY.dp)
                .alpha(headlineAlpha)
        ) {
            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "•  YOUR ON-DEVICE AGENT",
                color = Color(0xFFFFB45A),
                fontSize = 11.5.sp,
                fontFamily = titleFontFamily,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.4.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "What can I do\nfor you today?",
                color = Color.White,
                fontSize = 35.sp,
                fontFamily = titleFontFamily,
                fontWeight = FontWeight.Bold,
                lineHeight = 41.sp,
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(24.dp))

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                item {
                    ExploreSuggestionCard(
                        badge = "SYSTEM",
                        title = "System\nDiagnostics",
                        titleFontFamily = titleFontFamily,
                        bodyFontFamily = bodyFontFamily,
                        onClick = { onExecutePrompt("Check device battery, memory, and storage status") }
                    )
                }
                item {
                    ExploreSuggestionCard(
                        badge = "QUICK",
                        title = "Toggle\nFlashlight",
                        titleFontFamily = titleFontFamily,
                        bodyFontFamily = bodyFontFamily,
                        onClick = { onExecutePrompt("Toggle flashlight") }
                    )
                }
                item {
                    ExploreSuggestionCard(
                        badge = "NETWORK",
                        title = "Scan Local\nNetwork",
                        titleFontFamily = titleFontFamily,
                        bodyFontFamily = bodyFontFamily,
                        onClick = { onExecutePrompt("Scan local WiFi network") }
                    )
                }
                item {
                    ExploreSuggestionCard(
                        badge = "LINUX",
                        title = "Check Linux\nEnvironment",
                        titleFontFamily = titleFontFamily,
                        bodyFontFamily = bodyFontFamily,
                        onClick = { onExecutePrompt("Check termux Linux status and environment") }
                    )
                }
            }
        }

        // Bottom Floating Composer with Noise Surface
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(bottom = 16.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(22.dp),
                color = Color(0xFF0A0200).copy(alpha = 0.94f),
                border = BorderStroke(1.dp, Color(0xFFFF4D00).copy(alpha = 0.22f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    BasicTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 15.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(Color(0xFFE46313)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                        keyboardActions = KeyboardActions(onGo = {
                            if (textInput.isNotBlank()) {
                                onExecutePrompt(textInput)
                                textInput = ""
                            }
                        }),
                        decorationBox = { innerTextField ->
                            if (textInput.isEmpty()) {
                                Text(
                                    text = "Ask JARVIS or give a command…",
                                    color = Color.White.copy(alpha = 0.45f),
                                    fontSize = 14.5.sp,
                                    fontFamily = bodyFontFamily,
                                    fontWeight = FontWeight.Light,
                                    letterSpacing = (-0.1).sp
                                )
                            }
                            innerTextField()
                        },
                        modifier = Modifier.fillMaxWidth()
                    )

                    Spacer(modifier = Modifier.height(20.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                onClick = onOpenConversation,
                                shape = CircleShape,
                                color = Color.White.copy(alpha = 0.1f),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    ChatBubbleSvg(
                                        modifier = Modifier.size(15.dp),
                                        tint = Color.White.copy(alpha = 0.85f)
                                    )
                                }
                            }

                            Surface(
                                onClick = onOpenModelSelector,
                                shape = RoundedCornerShape(16.dp),
                                color = Color.White.copy(alpha = 0.1f),
                                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Text(
                                        text = currentMode.displayName,
                                        color = Color.White,
                                        fontSize = 12.sp,
                                        fontFamily = bodyFontFamily,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Icon(
                                        imageVector = Icons.Outlined.KeyboardArrowDown,
                                        contentDescription = "Dropdown",
                                        tint = Color.White.copy(alpha = 0.75f),
                                        modifier = Modifier.size(14.dp)
                                    )
                                }
                            }
                        }

                        Surface(
                            onClick = {
                                if (textInput.isNotBlank()) {
                                    onExecutePrompt(textInput)
                                    textInput = ""
                                } else {
                                    onStartVoice()
                                }
                            },
                            shape = CircleShape,
                            color = Color.Transparent,
                            modifier = Modifier
                                .size(40.dp)
                                .shadow(
                                    elevation = 12.dp,
                                    shape = CircleShape,
                                    ambientColor = Color(0xFFFF4D00),
                                    spotColor = Color(0xFFFF4D00)
                                )
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(
                                        brush = Brush.radialGradient(
                                            colors = listOf(
                                                Color(0xFFFF8500),
                                                Color(0xFFFF3D00),
                                                Color(0xFFC41C00)
                                            )
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                if (textInput.isNotBlank()) {
                                    SendSvg(
                                        modifier = Modifier.size(18.dp),
                                        tint = Color.White
                                    )
                                } else {
                                    MicSvg(
                                        modifier = Modifier.size(19.dp),
                                        tint = Color.White
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ─── View 2: Voice Session View (Refined to match Dribbble Screen 2) ──────────

@Composable
fun InAppListeningOrbVideo(
    modifier: Modifier = Modifier,
    isListening: Boolean = true
) {
    val context = LocalContext.current
    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val uri = Uri.parse("asset:///voice/apple_siri_orb.mp4")
            setMediaItem(MediaItem.fromUri(uri))
            repeatMode = ExoPlayer.REPEAT_MODE_ALL
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }

    LaunchedEffect(isListening) {
        if (isListening) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    AndroidView(
        factory = { ctx ->
            val playerView = android.view.LayoutInflater.from(ctx)
                .inflate(R.layout.view_texture_player, null, false) as PlayerView
            playerView.player = exoPlayer
            playerView.setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
            playerView.resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            playerView
        },
        modifier = modifier
    )
}

@Composable
fun VoiceSessionView(
    voiceEngine: JarvisVoiceEngine?,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onClose: () -> Unit,
    onSendVoiceInput: (String) -> Unit
) {
    val context = LocalContext.current
    val activity = context as? ComponentActivity
    var isListening by remember { mutableStateOf(true) }
    var liveTranscript by remember { mutableStateOf("Listening for your command…") }

    LaunchedEffect(isListening) {
        if (isListening && activity != null) {
            voiceEngine?.startListening(
                activity = activity,
                onPartial = { partial ->
                    if (partial.isNotBlank()) {
                        liveTranscript = partial
                    }
                },
                onResult = { result ->
                    if (result.isNotBlank()) {
                        liveTranscript = result
                        onSendVoiceInput(result)
                    }
                },
                onError = { err ->
                    liveTranscript = "Tap mic to speak"
                    isListening = false
                }
            )
        } else {
            voiceEngine?.stopListening()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            voiceEngine?.stopListening()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Action Pill
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                onClick = onClose,
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E1713).copy(alpha = 0.88f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CloseSvg(
                        modifier = Modifier.size(12.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                    Text(
                        text = "Close voice",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontFamily = bodyFontFamily,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.1).sp
                    )
                }
            }
        }

        // Center 3D Glowing Orb Video & Animated Dynamic Voice Text (Matching Dribbble)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val infiniteTransition = rememberInfiniteTransition(label = "orbPulse")
            val pulseScale by infiniteTransition.animateFloat(
                initialValue = 0.98f,
                targetValue = 1.03f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2800, easing = EaseInOutSine),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseScale"
            )

            Box(
                modifier = Modifier
                    .size(270.dp)
                    .scale(if (isListening) pulseScale else 0.95f)
                    .alpha(if (isListening) 1.0f else 0.65f)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        drawRect(
                            brush = Brush.radialGradient(
                                colorStops = arrayOf(
                                    0.0f to Color.Black,
                                    0.50f to Color.Black,
                                    0.78f to Color.Black.copy(alpha = 0.45f),
                                    0.98f to Color.Transparent
                                ),
                                center = center,
                                radius = size.minDimension / 2f
                            ),
                            blendMode = BlendMode.DstIn
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                InAppListeningOrbVideo(
                    modifier = Modifier.fillMaxSize(),
                    isListening = isListening
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Text(
                text = if (isListening) "JARVIS is listening…" else "JARVIS is paused",
                color = Color.White.copy(alpha = 0.60f),
                fontSize = 13.5.sp,
                fontFamily = bodyFontFamily,
                fontWeight = FontWeight.Normal,
                letterSpacing = 0.2.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Dynamic Animated Multi-Tier Text (Dribbble Screen 2: White active tokens + Muted gray context)
            AnimatedVoiceTranscript(
                transcript = liveTranscript,
                titleFontFamily = titleFontFamily
            )
        }

        // Bottom Controls Bar (Pause, Central Glowing Mic, Send)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 32.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(36.dp)
            ) {
                IconButton(
                    onClick = { isListening = !isListening },
                    modifier = Modifier.size(40.dp)
                ) {
                    if (isListening) {
                        PauseSvg(modifier = Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.85f))
                    } else {
                        PlaySvg(modifier = Modifier.size(20.dp), tint = Color.White.copy(alpha = 0.85f))
                    }
                }

                Surface(
                    onClick = { isListening = !isListening },
                    shape = CircleShape,
                    color = Color.Transparent,
                    modifier = Modifier
                        .size(68.dp)
                        .shadow(
                            elevation = 16.dp,
                            shape = CircleShape,
                            ambientColor = Color(0xFFE8580D),
                            spotColor = Color(0xFFE8580D)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(
                                        Color(0xFFFF9636),
                                        Color(0xFFE8580D),
                                        Color(0xFFAC3700)
                                    )
                                )
                            )
                            .border(1.5.dp, Color.White.copy(alpha = 0.45f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        MicSvg(
                            modifier = Modifier.size(26.dp),
                            tint = Color.White
                        )
                    }
                }

                IconButton(
                    onClick = {
                        if (liveTranscript.isNotBlank() && !liveTranscript.startsWith("Listening")) {
                            onSendVoiceInput(liveTranscript)
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    SendSvg(
                        modifier = Modifier.size(22.dp),
                        tint = Color.White.copy(alpha = 0.85f)
                    )
                }
            }
        }
    }
}

// ─── Dynamic Animated Multi-Tier Voice Transcript Text ────────────────────────

@Composable
fun AnimatedVoiceTranscript(
    transcript: String,
    titleFontFamily: FontFamily
) {
    val words = remember(transcript) { transcript.split(" ").filter { it.isNotBlank() } }

    val annotated = remember(transcript) {
        if (words.size <= 4) {
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.SemiBold)) {
                    append(transcript)
                }
            }
        } else {
            // Split: first 60% active white, trailing 40% soft gray (as in Dribbble mock)
            val splitIdx = (words.size * 0.65f).toInt().coerceAtLeast(1)
            val leading = words.take(splitIdx).joinToString(" ")
            val trailing = words.drop(splitIdx).joinToString(" ")
            buildAnnotatedString {
                withStyle(SpanStyle(color = Color.White, fontWeight = FontWeight.Bold)) {
                    append("$leading ")
                }
                withStyle(SpanStyle(color = Color(0xFF94A3B8), fontWeight = FontWeight.Medium)) {
                    append(trailing)
                }
            }
        }
    }

    AnimatedContent(
        targetState = annotated,
        transitionSpec = {
            fadeIn(animationSpec = tween(250)) togetherWith fadeOut(animationSpec = tween(150))
        },
        label = "TranscriptMotion"
    ) { targetText ->
        Text(
            text = targetText,
            fontSize = 22.sp,
            fontFamily = titleFontFamily,
            lineHeight = 29.sp,
            letterSpacing = (-0.3).sp,
            textAlign = TextAlign.Center
        )
    }
}

// ─── View 3: Conversation View (Complete Parity with AgentActivity) ───────────

@Composable
fun ConversationView(
    currentSession: JarvisSession,
    messages: List<SessionMessage>,
    isWorking: Boolean,
    thinkingTitle: String,
    streamingText: String,
    thinkingSteps: List<String>,
    currentMode: AgentExecutionMode,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onClose: () -> Unit,
    onOpenVoice: () -> Unit,
    onOpenModelSelector: () -> Unit,
    onOpenHistory: () -> Unit,
    onNewSession: () -> Unit,
    onSendMessage: (String) -> Unit,
    onCopyText: (String) -> Unit,
    onSpeakText: (String) -> Unit,
    onStopSpeech: () -> Unit,
    onRegeneratePrompt: (String) -> Unit,
    onCancelTask: () -> Unit,
    onRaceAgy: () -> Unit,
    onOpenDeveloperHub: () -> Unit
) {
    val context = LocalContext.current
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size, isWorking, streamingText) {
        if (messages.isNotEmpty() || isWorking) {
            val target = if (isWorking) messages.size else messages.size - 1
            if (target >= 0) {
                listState.animateScrollToItem(target)
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding(),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Top Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Surface(
                onClick = onClose,
                shape = RoundedCornerShape(20.dp),
                color = Color(0xFF1E1713).copy(alpha = 0.88f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.18f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    CloseSvg(
                        modifier = Modifier.size(12.dp),
                        tint = Color.White.copy(alpha = 0.9f)
                    )
                    Text(
                        text = "Close chat",
                        color = Color.White,
                        fontSize = 12.5.sp,
                        fontFamily = bodyFontFamily,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = (-0.1).sp
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // New Session Button
                Surface(
                    onClick = onNewSession,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        PlusSvg(
                            modifier = Modifier.size(14.dp),
                            tint = Color.White
                        )
                    }
                }

                // History Button
                Surface(
                    onClick = onOpenHistory,
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.size(32.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        HamburgerMenuSvg(
                            modifier = Modifier.size(15.dp),
                            tint = Color.White
                        )
                    }
                }

                // Model Selector Pill
                Surface(
                    onClick = onOpenModelSelector,
                    shape = RoundedCornerShape(16.dp),
                    color = Color.White.copy(alpha = 0.1f),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f))
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = currentMode.displayName,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Medium
                        )
                        Icon(
                            imageVector = Icons.Outlined.KeyboardArrowDown,
                            contentDescription = "Dropdown",
                            tint = Color.White.copy(alpha = 0.75f),
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }

        // Quick Subpage Navigation Bar (Linked Services from AgentActivity)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                QuickNavChip(
                    label = "Files",
                    icon = { FolderSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, BrowserActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Commander",
                    icon = { CommanderSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, CommanderActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Terminal",
                    icon = { TerminalSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, TerminalActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Diagnostics",
                    icon = { DiagnosticsSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, DiagnosticsActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Model Hub",
                    icon = { ModelHubSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, com.pr4nav.jarvis.voice.ModelHubActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Antigravity",
                    icon = { AntigravitySvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, AgyActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Connected Services",
                    icon = { ConnectedServicesSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, ConnectedServicesActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Playground",
                    icon = { PlaygroundSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    context.startActivity(Intent(context, ToolPlaygroundActivity::class.java))
                }
            }
            item {
                QuickNavChip(
                    label = "Tools",
                    icon = { ToolsSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.85f)) }
                ) {
                    onOpenDeveloperHub()
                }
            }
        }

        // Messages List with Animated Entrances
        Box(modifier = Modifier.weight(1f)) {
            if (messages.isEmpty() && !isWorking) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        modifier = Modifier.padding(horizontal = 32.dp)
                    ) {
                        GlowingOrbAvatar(modifier = Modifier.size(44.dp))
                        Text(
                            text = "JARVIS Neural Agent Ready",
                            color = Color.White,
                            fontSize = 19.sp,
                            fontFamily = titleFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Session: ${currentSession.title}\nWorking Dir: ${SessionState.dir}\nDeterministic tool routing & autonomous execution active.",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 13.sp,
                            fontFamily = bodyFontFamily,
                            textAlign = TextAlign.Center,
                            lineHeight = 18.sp
                        )
                    }
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(messages, key = { it.id }) { msg ->
                        Box(modifier = Modifier.fillMaxWidth().animateItemPlacement()) {
                            if (msg.sender == "user") {
                                UserBubble(
                                    text = msg.text,
                                    timestampMs = msg.timestampMs,
                                    bodyFontFamily = bodyFontFamily,
                                    onCopy = { onCopyText(msg.text) }
                                )
                            } else {
                                JarvisBubble(
                                    text = msg.text,
                                    timestampMs = msg.timestampMs,
                                    steps = msg.steps,
                                    isSuccess = msg.isSuccess,
                                    titleFontFamily = titleFontFamily,
                                    bodyFontFamily = bodyFontFamily,
                                    onCopy = { onCopyText(msg.text) },
                                    onSpeak = { onSpeakText(msg.text) },
                                    onStopSpeech = onStopSpeech,
                                    onRegenerate = { onRegeneratePrompt(msg.text) }
                                )
                            }
                        }
                    }

                    // Live Interactive Streaming & Thinking Card with Animated Cursor
                    if (isWorking) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().animateItemPlacement()) {
                                StreamingExecutionCard(
                                    title = thinkingTitle,
                                    steps = thinkingSteps,
                                    streamingText = streamingText,
                                    titleFontFamily = titleFontFamily,
                                    bodyFontFamily = bodyFontFamily,
                                    onCancel = onCancelTask,
                                    onRaceAgy = onRaceAgy
                                )
                            }
                        }
                    }
                }
            }
        }

        // Horizontal Prompt Cards from AgentActivity (Fast Task Selection)
        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                PromptActionChip("✨ Make a UI") { onSendMessage("make an ui") }
            }
            item {
                PromptActionChip("Take Screenshot") { onSendMessage("Take a screenshot") }
            }
            item {
                PromptActionChip("Recent Downloads") { onSendMessage("Find recent downloads") }
            }
            item {
                PromptActionChip("Call Akhil") { onSendMessage("Call Akhil") }
            }
            item {
                PromptActionChip("Play Music") { onSendMessage("Play chill music") }
            }
            item {
                PromptActionChip("Take Me Home") { onSendMessage("Take me home") }
            }
            item {
                PromptActionChip("Battery Status") { onSendMessage("Check device battery status") }
            }
            item {
                PromptActionChip("Scan WiFi") { onSendMessage("Scan local WiFi network") }
            }
            item {
                PromptActionChip("Linux Packages") { onSendMessage("List installed python packages in proot") }
            }
            item {
                PromptActionChip("Storage Space") { onSendMessage("Check storage free space") }
            }
        }

        // Bottom Chat Bar
        ChatBar(
            inputText = inputText,
            bodyFontFamily = bodyFontFamily,
            onTextChange = { inputText = it },
            onSend = {
                if (inputText.isNotBlank()) {
                    onSendMessage(inputText)
                    inputText = ""
                }
            },
            onMicClick = onOpenVoice
        )
    }
}

// ─── Live Streaming & Thinking Execution Card with Animated Cursor ────────────

@Composable
fun StreamingExecutionCard(
    title: String,
    steps: List<String>,
    streamingText: String,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onCancel: () -> Unit,
    onRaceAgy: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "workingAlpha"
    )

    val cursorBlink by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorBlink"
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        GlowingOrbAvatar(modifier = Modifier.padding(top = 2.dp, end = 10.dp))

        Surface(
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 18.dp),
            color = Color(0xFF140D0A).copy(alpha = 0.96f),
            border = BorderStroke(1.dp, Color(0xFFFF9636).copy(alpha = 0.45f)),
            modifier = Modifier.widthIn(max = 310.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                // Live Header with Pulsing Title
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Canvas(modifier = Modifier.size(8.dp)) {
                            drawCircle(color = Color(0xFFFF9636).copy(alpha = alpha), radius = size.minDimension / 2f)
                        }
                        Text(
                            text = title,
                            color = Color(0xFFFF9636),
                            fontSize = 13.sp,
                            fontFamily = titleFontFamily,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // Intermediate steps list
                if (steps.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                    ) {
                        steps.forEach { step ->
                            Text(
                                text = "• $step",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp,
                                fontFamily = bodyFontFamily
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                }

                // Streaming Text Buffer with Animated Blinking Cursor
                if (streamingText.isNotBlank()) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = streamingText,
                            color = Color.White,
                            fontSize = 14.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Normal,
                            lineHeight = 20.sp,
                            modifier = Modifier.weight(1f, fill = false)
                        )
                        Box(
                            modifier = Modifier
                                .size(width = 2.dp, height = 15.dp)
                                .alpha(if (cursorBlink > 0.5f) 1f else 0f)
                                .background(Color(0xFFFF9636))
                        )
                    }
                    Spacer(modifier = Modifier.height(10.dp))
                }

                // Live Actions (Race AGY & Cancel)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Surface(
                        onClick = onRaceAgy,
                        shape = RoundedCornerShape(8.dp),
                        color = Color.White.copy(alpha = 0.1f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            LightningSvg(modifier = Modifier.size(10.dp), tint = Color(0xFF38BDF8))
                            Text(
                                text = "Race AGY",
                                color = Color(0xFF38BDF8),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(6.dp))

                    Surface(
                        onClick = onCancel,
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0x33EF4444)
                    ) {
                        Text(
                            text = "Cancel",
                            color = Color(0xFFEF4444),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

// ─── Quick Nav Chip ───────────────────────────────────────────────────────────

@Composable
fun QuickNavChip(label: String, icon: @Composable () -> Unit, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = Color(0xFF140D0A).copy(alpha = 0.88f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            icon()
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
fun PromptActionChip(label: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFF170F0C).copy(alpha = 0.92f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LightningSvg(modifier = Modifier.size(11.dp), tint = Color(0xFFFF9636))
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

// ─── Pure SVG Vector Icon Components (NO Emojis) ──────────────────────────────

@Composable
fun ChatBubbleSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.1f, h * 0.2f)
            lineTo(w * 0.9f, h * 0.2f)
            quadraticBezierTo(w * 0.98f, h * 0.2f, w * 0.98f, h * 0.35f)
            lineTo(w * 0.98f, h * 0.65f)
            quadraticBezierTo(w * 0.98f, h * 0.8f, w * 0.9f, h * 0.8f)
            lineTo(w * 0.4f, h * 0.8f)
            lineTo(w * 0.2f, h * 0.98f)
            lineTo(w * 0.2f, h * 0.8f)
            lineTo(w * 0.1f, h * 0.8f)
            quadraticBezierTo(w * 0.02f, h * 0.8f, w * 0.02f, h * 0.65f)
            lineTo(w * 0.02f, h * 0.35f)
            quadraticBezierTo(w * 0.02f, h * 0.2f, w * 0.1f, h * 0.2f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun HamburgerMenuSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawLine(color = tint, start = Offset(w * 0.15f, h * 0.25f), end = Offset(w * 0.85f, h * 0.25f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.15f, h * 0.50f), end = Offset(w * 0.85f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.15f, h * 0.75f), end = Offset(w * 0.85f, h * 0.75f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun SettingsSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val r = w * 0.22f
        val center = Offset(w / 2f, h / 2f)
        drawCircle(color = tint, radius = r, center = center, style = Stroke(width = w * 0.08f))
        for (i in 0 until 6) {
            val angle = (i * 60f) * (Math.PI / 180f).toFloat()
            val startX = center.x + (r * 1.0f) * cos(angle)
            val startY = center.y + (r * 1.0f) * sin(angle)
            val endX = center.x + (r * 1.55f) * cos(angle)
            val endY = center.y + (r * 1.55f) * sin(angle)
            drawLine(color = tint, start = Offset(startX, startY), end = Offset(endX, endY), strokeWidth = w * 0.10f, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun ToolsSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.085f

        // Toolbox outer box
        val left = w * 0.14f
        val right = w * 0.86f
        val top = h * 0.35f
        val bot = h * 0.86f
        val cr = w * 0.10f

        drawRoundRect(
            color = tint,
            topLeft = Offset(left, top),
            size = Size(right - left, bot - top),
            cornerRadius = CornerRadius(cr, cr),
            style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )

        // Top handle
        val handleLeft = w * 0.35f
        val handleRight = w * 0.65f
        val handleTop = h * 0.18f
        val handlePath = Path().apply {
            moveTo(handleLeft, top)
            lineTo(handleLeft, handleTop + cr)
            quadraticBezierTo(handleLeft, handleTop, handleLeft + cr, handleTop)
            lineTo(handleRight - cr, handleTop)
            quadraticBezierTo(handleRight, handleTop, handleRight, handleTop + cr)
            lineTo(handleRight, top)
        }
        drawPath(handlePath, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))

        // Center seam line
        drawLine(
            color = tint.copy(alpha = 0.5f),
            start = Offset(left, h * 0.58f),
            end = Offset(right, h * 0.58f),
            strokeWidth = stroke * 0.8f
        )

        // Center clasp
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.42f, h * 0.51f),
            size = Size(w * 0.16f, h * 0.14f),
            cornerRadius = CornerRadius(w * 0.03f, w * 0.03f)
        )
    }
}

@Composable
fun SparkleSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, 0f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, w, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, h)
            quadraticBezierTo(w * 0.5f, h * 0.5f, 0f, h * 0.5f)
            quadraticBezierTo(w * 0.5f, h * 0.5f, w * 0.5f, 0f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}

@Composable
fun CopySvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.08f
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.28f, h * 0.28f),
            size = Size(w * 0.60f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f),
            style = Stroke(width = stroke)
        )
        val path = Path().apply {
            moveTo(w * 0.70f, h * 0.15f)
            lineTo(w * 0.15f, h * 0.15f)
            quadraticBezierTo(w * 0.10f, h * 0.15f, w * 0.10f, h * 0.20f)
            lineTo(w * 0.10f, h * 0.70f)
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
    }
}

@Composable
fun SpeakerSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.08f
        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.35f)
            lineTo(w * 0.35f, h * 0.35f)
            lineTo(w * 0.60f, h * 0.15f)
            lineTo(w * 0.60f, h * 0.85f)
            lineTo(w * 0.35f, h * 0.65f)
            lineTo(w * 0.15f, h * 0.65f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
        drawArc(
            color = tint,
            startAngle = -45f,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = Offset(w * 0.50f, h * 0.30f),
            size = Size(w * 0.40f, h * 0.40f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
    }
}

@Composable
fun StopSpeechSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.20f, h * 0.20f),
            size = Size(w * 0.60f, h * 0.60f),
            cornerRadius = CornerRadius(w * 0.1f, w * 0.1f)
        )
    }
}

@Composable
fun RegenerateSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawArc(
            color = tint,
            startAngle = 45f,
            sweepAngle = 270f,
            useCenter = false,
            topLeft = Offset(w * 0.18f, h * 0.18f),
            size = Size(w * 0.64f, h * 0.64f),
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.05f)
            lineTo(w * 0.68f, h * 0.18f)
            lineTo(w * 0.50f, h * 0.31f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}

@Composable
fun FolderSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.10f, h * 0.25f)
            lineTo(w * 0.40f, h * 0.25f)
            lineTo(w * 0.50f, h * 0.38f)
            lineTo(w * 0.90f, h * 0.38f)
            quadraticBezierTo(w * 0.95f, h * 0.38f, w * 0.95f, h * 0.45f)
            lineTo(w * 0.95f, h * 0.80f)
            quadraticBezierTo(w * 0.95f, h * 0.85f, w * 0.90f, h * 0.85f)
            lineTo(w * 0.10f, h * 0.85f)
            quadraticBezierTo(w * 0.05f, h * 0.85f, w * 0.05f, h * 0.80f)
            lineTo(w * 0.05f, h * 0.30f)
            quadraticBezierTo(w * 0.05f, h * 0.25f, w * 0.10f, h * 0.25f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = w * 0.09f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun CommanderSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val center = Offset(w / 2f, h / 2f)
        val stroke = w * 0.08f
        drawCircle(color = tint, radius = w * 0.35f, center = center, style = Stroke(width = stroke))
        drawCircle(color = tint, radius = w * 0.12f, center = center, style = Fill)
        drawLine(color = tint, start = Offset(w * 0.50f, 0f), end = Offset(w * 0.50f, h * 0.20f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.50f, h * 0.80f), end = Offset(w * 0.50f, h), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(0f, h * 0.50f), end = Offset(w * 0.20f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.80f, h * 0.50f), end = Offset(w, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun TerminalSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawRoundRect(color = tint, topLeft = Offset(w * 0.05f, h * 0.12f), size = Size(w * 0.90f, h * 0.76f), cornerRadius = CornerRadius(w * 0.1f, w * 0.1f), style = Stroke(width = stroke))
        val path = Path().apply {
            moveTo(w * 0.25f, h * 0.38f)
            lineTo(w * 0.42f, h * 0.50f)
            lineTo(w * 0.25f, h * 0.62f)
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawLine(color = tint, start = Offset(w * 0.52f, h * 0.62f), end = Offset(w * 0.72f, h * 0.62f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun DiagnosticsSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        val path = Path().apply {
            moveTo(w * 0.05f, h * 0.50f)
            lineTo(w * 0.30f, h * 0.50f)
            lineTo(w * 0.42f, h * 0.20f)
            lineTo(w * 0.58f, h * 0.80f)
            lineTo(w * 0.70f, h * 0.50f)
            lineTo(w * 0.95f, h * 0.50f)
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun ModelHubSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.08f
        drawRoundRect(color = tint, topLeft = Offset(w * 0.22f, h * 0.22f), size = Size(w * 0.56f, h * 0.56f), cornerRadius = CornerRadius(w * 0.08f, w * 0.08f), style = Stroke(width = stroke))
        drawRect(color = tint, topLeft = Offset(w * 0.38f, h * 0.38f), size = Size(w * 0.24f, h * 0.24f))
        val pins = listOf(0.35f, 0.50f, 0.65f)
        for (p in pins) {
            drawLine(color = tint, start = Offset(w * p, 0f), end = Offset(w * p, h * 0.20f), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color = tint, start = Offset(w * p, h * 0.80f), end = Offset(w * p, h), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color = tint, start = Offset(0f, h * p), end = Offset(w * 0.20f, h * p), strokeWidth = stroke, cap = StrokeCap.Round)
            drawLine(color = tint, start = Offset(w * 0.80f, h * p), end = Offset(w, h * p), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun AntigravitySvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.50f, h * 0.05f)
            cubicTo(w * 0.75f, h * 0.20f, w * 0.85f, h * 0.55f, w * 0.70f, h * 0.80f)
            lineTo(w * 0.50f, h * 0.70f)
            lineTo(w * 0.30f, h * 0.80f)
            cubicTo(w * 0.15f, h * 0.55f, w * 0.25f, h * 0.20f, w * 0.50f, h * 0.05f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = w * 0.08f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawCircle(color = tint, radius = w * 0.10f, center = Offset(w * 0.50f, h * 0.35f))
    }
}

@Composable
fun ConnectedServicesSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.08f
        val path = Path().apply {
            moveTo(w * 0.25f, h * 0.70f)
            quadraticBezierTo(w * 0.08f, h * 0.70f, w * 0.10f, h * 0.50f)
            quadraticBezierTo(w * 0.10f, h * 0.32f, w * 0.30f, h * 0.32f)
            quadraticBezierTo(w * 0.38f, h * 0.15f, w * 0.60f, h * 0.18f)
            quadraticBezierTo(w * 0.78f, h * 0.18f, w * 0.82f, h * 0.35f)
            quadraticBezierTo(w * 0.95f, h * 0.40f, w * 0.92f, h * 0.58f)
            quadraticBezierTo(w * 0.92f, h * 0.70f, w * 0.75f, h * 0.70f)
            close()
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun PlaygroundSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        drawLine(color = tint, start = Offset(w * 0.20f, h * 0.80f), end = Offset(w * 0.65f, h * 0.35f), strokeWidth = stroke * 1.5f, cap = StrokeCap.Round)
        drawCircle(color = tint, radius = w * 0.18f, center = Offset(w * 0.75f, h * 0.25f), style = Stroke(width = stroke))
    }
}

@Composable
fun LightningSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.55f, 0f)
            lineTo(w * 0.20f, h * 0.55f)
            lineTo(w * 0.48f, h * 0.55f)
            lineTo(w * 0.40f, h)
            lineTo(w * 0.80f, h * 0.45f)
            lineTo(w * 0.52f, h * 0.45f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}

@Composable
fun CloseSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        drawLine(color = tint, start = Offset(w * 0.18f, h * 0.18f), end = Offset(w * 0.82f, h * 0.82f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.82f, h * 0.18f), end = Offset(w * 0.18f, h * 0.82f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun PlusSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        drawLine(color = tint, start = Offset(w * 0.50f, h * 0.15f), end = Offset(w * 0.50f, h * 0.85f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.15f, h * 0.50f), end = Offset(w * 0.85f, h * 0.50f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun CheckSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.12f
        val path = Path().apply {
            moveTo(w * 0.15f, h * 0.52f)
            lineTo(w * 0.42f, h * 0.78f)
            lineTo(w * 0.85f, h * 0.22f)
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
fun TrashSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.09f
        // Lid
        drawLine(color = tint, start = Offset(w * 0.2f, h * 0.25f), end = Offset(w * 0.8f, h * 0.25f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.4f, h * 0.15f), end = Offset(w * 0.6f, h * 0.15f), strokeWidth = stroke, cap = StrokeCap.Round)
        // Bin
        val path = Path().apply {
            moveTo(w * 0.26f, h * 0.25f)
            lineTo(w * 0.32f, h * 0.85f)
            lineTo(w * 0.68f, h * 0.85f)
            lineTo(w * 0.74f, h * 0.25f)
        }
        drawPath(path = path, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
        // Ribs
        drawLine(color = tint, start = Offset(w * 0.43f, h * 0.38f), end = Offset(w * 0.43f, h * 0.72f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.57f, h * 0.38f), end = Offset(w * 0.57f, h * 0.72f), strokeWidth = stroke * 0.8f, cap = StrokeCap.Round)
    }
}

@Composable
fun PlaySvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.28f, h * 0.18f)
            lineTo(w * 0.82f, h * 0.50f)
            lineTo(w * 0.28f, h * 0.82f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}

@Composable
fun PauseSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.18f
        drawLine(color = tint, start = Offset(w * 0.35f, h * 0.20f), end = Offset(w * 0.35f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
        drawLine(color = tint, start = Offset(w * 0.65f, h * 0.20f), end = Offset(w * 0.65f, h * 0.80f), strokeWidth = stroke, cap = StrokeCap.Round)
    }
}

@Composable
fun MicSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.33f, h * 0.12f),
            size = Size(w * 0.34f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.17f, w * 0.17f)
        )
        drawArc(
            color = tint,
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(w * 0.20f, h * 0.26f),
            size = Size(w * 0.60f, h * 0.44f),
            style = Stroke(width = w * 0.09f, cap = StrokeCap.Round)
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.50f, h * 0.70f),
            end = Offset(w * 0.50f, h * 0.86f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round
        )
        drawLine(
            color = tint,
            start = Offset(w * 0.30f, h * 0.86f),
            end = Offset(w * 0.70f, h * 0.86f),
            strokeWidth = w * 0.09f,
            cap = StrokeCap.Round
        )
    }
}

@Composable
fun SendSvg(modifier: Modifier = Modifier, tint: Color = Color.White) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.12f, h * 0.12f)
            lineTo(w * 0.88f, h * 0.50f)
            lineTo(w * 0.12f, h * 0.88f)
            lineTo(w * 0.30f, h * 0.50f)
            close()
        }
        drawPath(path = path, color = tint, style = Fill)
    }
}

// ─── Video & Visual Components ────────────────────────────────────────────────

@Composable
fun SeamlessVideoOrb(
    videoUrl: String,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    val exoPlayer = remember {
        ExoPlayer.Builder(context).build().apply {
            val mediaItem = MediaItem.fromUri(Uri.parse(videoUrl))
            setMediaItem(mediaItem)
            repeatMode = Player.REPEAT_MODE_ALL
            volume = 0f
            prepare()
            playWhenReady = true
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            exoPlayer.release()
        }
    }

    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFF9D42).copy(alpha = 0.85f),
                        Color(0xFFE8580D).copy(alpha = 0.45f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = r * 1.35f
                ),
                radius = r * 1.35f,
                center = center
            )

            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFE3A0),
                        Color(0xFFF19126),
                        Color(0xFFB54507),
                        Color(0xFF2C0E00)
                    ),
                    center = Offset(center.x - r * 0.28f, center.y - r * 0.32f),
                    radius = r * 1.15f
                ),
                radius = r,
                center = center
            )
        }

        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    player = exoPlayer
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape)
        )
    }
}

@Composable
fun FrostedGrainyGlassContainer(
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 24.dp,
    content: @Composable BoxScope.() -> Unit
) {
    val shape = RoundedCornerShape(cornerRadius)

    val noiseShaderBrush = remember {
        val size = 128
        val pixels = IntArray(size * size) {
            val alpha = kotlin.random.Random.nextInt(15, 45)
            android.graphics.Color.argb(alpha, 255, 255, 255)
        }
        val noiseBitmap = Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
        val shader = BitmapShader(noiseBitmap, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT)
        ShaderBrush(shader)
    }

    Box(
        modifier = modifier
            .clip(shape)
            .blur(radius = 24.dp)
            .background(Color(0xFF0F141C).copy(alpha = 0.75f))
            .drawBehind {
                drawRect(brush = noiseShaderBrush)
            }
            .border(
                BorderStroke(
                    width = 1.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = 0.22f),
                            Color.White.copy(alpha = 0.05f),
                            Color.Transparent
                        )
                    )
                ),
                shape = shape
            )
            .padding(14.dp)
    ) {
        content()
    }
}

@Composable
fun UserBubble(
    text: String,
    timestampMs: Long,
    bodyFontFamily: FontFamily,
    onCopy: () -> Unit
) {
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val timeStr = remember(timestampMs) { timeFormat.format(Date(timestampMs)) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.End
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 295.dp)
                .shadow(
                    elevation = 10.dp,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp),
                    ambientColor = Color(0xFFDF5A10).copy(alpha = 0.45f),
                    spotColor = Color(0xFFDF5A10).copy(alpha = 0.55f)
                )
                .background(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color(0xFFE46313),
                            Color(0xFFBD4301)
                        )
                    ),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp)
                )
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.25f),
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 5.dp)
                )
                .clickable { onCopy() }
                .padding(horizontal = 18.dp, vertical = 12.dp)
        ) {
            Text(
                text = text,
                color = Color.White,
                fontSize = 14.5.sp,
                fontFamily = bodyFontFamily,
                fontWeight = FontWeight.Normal,
                lineHeight = 21.sp,
                letterSpacing = (-0.1).sp
            )
        }

        // Timestamp situated below the bubble on the right (matching Dribbble)
        Text(
            text = timeStr,
            color = Color.White.copy(alpha = 0.45f),
            fontSize = 10.5.sp,
            fontFamily = bodyFontFamily,
            modifier = Modifier.padding(top = 4.dp, end = 4.dp)
        )
    }
}

@Composable
fun JarvisBubble(
    text: String,
    timestampMs: Long,
    steps: List<String>,
    isSuccess: Boolean,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onCopy: () -> Unit,
    onSpeak: () -> Unit,
    onStopSpeech: () -> Unit,
    onRegenerate: () -> Unit
) {
    var areStepsExpanded by remember { mutableStateOf(false) }
    val timeFormat = remember { SimpleDateFormat("hh:mm a", Locale.getDefault()) }
    val timeStr = remember(timestampMs) { timeFormat.format(Date(timestampMs)) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        // Metallic Glowing 3D Orb Avatar (from Dribbble Screen 3)
        GlowingOrbAvatar(modifier = Modifier.padding(top = 4.dp, end = 10.dp))

        Column {
            FrostedGrainyGlassContainer(
                modifier = Modifier.widthIn(max = 300.dp),
                cornerRadius = 20.dp
            ) {
                Column {
                    // Steps dropdown (if available)
                    if (steps.isNotEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { areStepsExpanded = !areStepsExpanded }
                                .padding(bottom = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                CheckSvg(
                                    modifier = Modifier.size(11.dp),
                                    tint = if (isSuccess) Color(0xFF10B981) else Color(0xFFF59E0B)
                                )
                                Text(
                                    text = steps.firstOrNull() ?: "Executed",
                                    color = if (isSuccess) Color(0xFF10B981) else Color(0xFFF59E0B),
                                    fontSize = 11.sp,
                                    fontFamily = bodyFontFamily,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                            Icon(
                                imageVector = if (areStepsExpanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                                contentDescription = "Expand Steps",
                                tint = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.size(14.dp)
                            )
                        }

                        if (areStepsExpanded) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color.White.copy(alpha = 0.05f), RoundedCornerShape(8.dp))
                                    .padding(8.dp)
                            ) {
                                steps.forEach { step ->
                                    Text(
                                        text = "• $step",
                                        color = Color.White.copy(alpha = 0.7f),
                                        fontSize = 10.5.sp,
                                        fontFamily = bodyFontFamily
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }

                    // Main message content with Space Grotesk/DM Sans typography
                    Text(
                        text = text,
                        color = Color.White,
                        fontSize = 14.5.sp,
                        fontFamily = bodyFontFamily,
                        fontWeight = FontWeight.Normal,
                        lineHeight = 21.sp,
                        letterSpacing = (-0.1).sp
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    // Action Bar: Copy, Regenerate, Listen, Stop Speaking
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onCopy, modifier = Modifier.size(24.dp)) {
                            CopySvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.75f))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onRegenerate, modifier = Modifier.size(24.dp)) {
                            RegenerateSvg(modifier = Modifier.size(13.dp), tint = Color.White.copy(alpha = 0.75f))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onSpeak, modifier = Modifier.size(24.dp)) {
                            SpeakerSvg(modifier = Modifier.size(14.dp), tint = Color.White.copy(alpha = 0.75f))
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                        IconButton(onClick = onStopSpeech, modifier = Modifier.size(24.dp)) {
                            StopSpeechSvg(modifier = Modifier.size(13.dp), tint = Color(0xFFEF4444).copy(alpha = 0.85f))
                        }
                    }
                }
            }

            // Timestamp situated below the bubble on the left (matching Dribbble)
            Text(
                text = timeStr,
                color = Color.White.copy(alpha = 0.45f),
                fontSize = 10.5.sp,
                fontFamily = bodyFontFamily,
                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
            )
        }
    }
}

@Composable
fun GlowingOrbAvatar(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.size(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val r = size.minDimension / 2f
            val center = Offset(size.width / 2f, size.height / 2f)

            // Outer golden-orange aura
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFB05A).copy(alpha = 0.75f),
                        Color(0xFFE8580D).copy(alpha = 0.35f),
                        Color.Transparent
                    ),
                    center = center,
                    radius = r * 1.35f
                ),
                radius = r * 1.35f,
                center = center
            )

            // 3D metallic sphere shading
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        Color(0xFFFFE8B2),
                        Color(0xFFF0932B),
                        Color(0xFFB54507),
                        Color(0xFF331000)
                    ),
                    center = Offset(center.x - r * 0.32f, center.y - r * 0.35f),
                    radius = r * 1.15f
                ),
                radius = r,
                center = center
            )
        }
    }
}

@Composable
fun ChatBar(
    inputText: String,
    bodyFontFamily: FontFamily,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onMicClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = 18.dp, vertical = 12.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(26.dp),
            color = Color(0xFF140D0A).copy(alpha = 0.95f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = 0.16f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = CircleShape,
                    color = Color.White.copy(alpha = 0.1f),
                    modifier = Modifier.size(30.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = "@",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }

                Spacer(modifier = Modifier.width(10.dp))

                Box(
                    modifier = Modifier.weight(1f),
                    contentAlignment = Alignment.CenterStart
                ) {
                    if (inputText.isEmpty()) {
                        Text(
                            text = "Ask JARVIS a question…",
                            color = Color.White.copy(alpha = 0.45f),
                            fontSize = 14.5.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Light,
                            letterSpacing = (-0.1).sp
                        )
                    }

                    BasicTextField(
                        value = inputText,
                        onValueChange = onTextChange,
                        singleLine = true,
                        textStyle = TextStyle(
                            color = Color.White,
                            fontSize = 14.5.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Normal
                        ),
                        cursorBrush = SolidColor(Color(0xFFE46313)),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { onSend() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.width(10.dp))

                if (inputText.isNotBlank()) {
                    IconButton(
                        onClick = onSend,
                        modifier = Modifier.size(32.dp)
                    ) {
                        SendSvg(
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFE46313)
                        )
                    }
                } else {
                    IconButton(
                        onClick = onMicClick,
                        modifier = Modifier.size(32.dp)
                    ) {
                        MicSvg(
                            modifier = Modifier.size(19.dp),
                            tint = Color.White.copy(alpha = 0.85f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun ExploreSuggestionCard(
    badge: String,
    title: String,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        color = Color(0xFF140200).copy(alpha = 0.96f),
        border = BorderStroke(1.dp, Color(0xFFFF4D00).copy(alpha = 0.32f)),
        modifier = Modifier.size(width = 158.dp, height = 145.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            SparkleSvg(
                modifier = Modifier.size(16.dp),
                tint = Color(0xFFFF5722)
            )

            Text(
                text = title,
                color = Color.White,
                fontSize = 14.sp,
                fontFamily = bodyFontFamily,
                fontWeight = FontWeight.Normal,
                lineHeight = 19.sp,
                letterSpacing = (-0.1).sp
            )
        }
    }
}

@Composable
fun ModelPickerSheet(
    currentMode: AgentExecutionMode,
    titleFontFamily: FontFamily,
    bodyFontFamily: FontFamily,
    onSelect: (AgentExecutionMode) -> Unit,
    onConfigureQwenUrl: () -> Unit,
    onDismiss: () -> Unit
) {
    val modes = AgentExecutionMode.values().toList()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = Color(0xFF15100D),
        contentColor = Color.White,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp)
                .padding(bottom = 36.dp)
        ) {
            Text(
                text = "Select Intelligence Engine",
                color = Color.White,
                fontSize = 18.sp,
                fontFamily = titleFontFamily,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(vertical = 12.dp)
            )

            modes.forEach { mode ->
                val isSelected = currentMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .clickable { onSelect(mode) }
                        .padding(vertical = 12.dp, horizontal = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = mode.displayName,
                            color = if (isSelected) Color(0xFFFF9636) else Color.White,
                            fontSize = 15.sp,
                            fontFamily = titleFontFamily,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = mode.description,
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            fontFamily = bodyFontFamily,
                            fontWeight = FontWeight.Light
                        )
                    }

                    if (isSelected) {
                        CheckSvg(
                            modifier = Modifier.size(18.dp),
                            tint = Color(0xFFFF9636)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            Surface(
                onClick = onConfigureQwenUrl,
                shape = RoundedCornerShape(12.dp),
                color = Color.White.copy(alpha = 0.08f),
                border = BorderStroke(1.dp, Color.White.copy(alpha = 0.15f)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(vertical = 12.dp, horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    SettingsSvg(modifier = Modifier.size(16.dp), tint = Color(0xFFFFB45A))
                    Text(
                        text = "Configure Groq API Key & Quotas",
                        color = Color(0xFFFFB45A),
                        fontSize = 12.5.sp,
                        fontFamily = bodyFontFamily,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }
}

// ─── Standby, Voice Intelligence & Tools Dialogs ───────────────────────────────

@Composable
fun JarvisSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    activeColor: Color = Color(0xFFFF8A00)
) {
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 4.dp,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "thumbOffset"
    )
    val trackColor by animateColorAsState(
        targetValue = if (checked) activeColor.copy(alpha = 0.35f) else Color(0xFF23140F),
        label = "trackColor"
    )
    val borderColor by animateColorAsState(
        targetValue = if (checked) activeColor.copy(alpha = 0.85f) else Color.White.copy(alpha = 0.18f),
        label = "borderColor"
    )

    Box(
        modifier = modifier
            .width(52.dp)
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(trackColor)
            .border(1.2.dp, borderColor, RoundedCornerShape(15.dp))
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .offset(x = thumbOffset)
                .size(22.dp)
                .clip(CircleShape)
                .background(
                    if (checked) Brush.radialGradient(
                        listOf(Color.White, activeColor)
                    ) else Brush.radialGradient(
                        listOf(Color(0xFFD4D4D4), Color(0xFF737373))
                    )
                )
                .shadow(if (checked) 6.dp else 2.dp, CircleShape)
        )
    }
}

@Composable
fun JarvisStandbyVoiceDialog(
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    var handsFreeOn by remember { mutableStateOf(com.pr4nav.jarvis.voice.VoiceAssistantPreferences.isHandsFreeEnabled(context)) }
    var companionOn by remember { mutableStateOf(CompanionManager.isEnabled(context)) }
    var currentThreshold by remember { mutableStateOf(com.pr4nav.jarvis.voice.VoiceAssistantPreferences.getWakeConfidenceThreshold(context)) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0F0503),
                border = BorderStroke(
                    1.5.dp,
                    Brush.verticalGradient(
                        listOf(Color(0xFFFF8A00).copy(alpha = 0.85f), Color(0xFFFF3D00).copy(alpha = 0.35f))
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(22.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x33FF8A00))
                                    .border(1.dp, Color(0xFFFF8A00).copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                SparkleSvg(modifier = Modifier.size(20.dp), tint = Color(0xFFFF8A00))
                            }
                            Column {
                                Text(
                                    text = "STANDBY & VOICE",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Hands-free wake & proactive assistant",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    // Divider
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))

                    // Toggle 1: Hands-Free Wake Word
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF160905),
                        border = BorderStroke(1.dp, if (handsFreeOn) Color(0x66FF8A00) else Color.White.copy(alpha = 0.08f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Hands-Free ('Hey Jarvis')",
                                    color = Color.White,
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                                Text(
                                    text = if (handsFreeOn) "Active: listening on local ONNX model" else "Disabled: mic is turned off",
                                    color = if (handsFreeOn) Color(0xFFFFB45A) else Color.White.copy(alpha = 0.5f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            JarvisSwitch(
                                checked = handsFreeOn,
                                onCheckedChange = { newState ->
                                    handsFreeOn = newState
                                    com.pr4nav.jarvis.voice.VoiceAssistantPreferences.setHandsFreeEnabled(context, newState)
                                },
                                activeColor = Color(0xFFFF8A00)
                            )
                        }
                    }

                    // Toggle 2: Proactive Companion Mode (Greyed out for now)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFF160905).copy(alpha = 0.6f),
                        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                                Text(
                                    text = "Proactive Companion Mode (Paused)",
                                    color = Color.White.copy(alpha = 0.4f),
                                    fontWeight = FontWeight.SemiBold,
                                    fontSize = 13.5.sp
                                )
                                Text(
                                    text = "Temporarily disabled for optimization",
                                    color = Color.White.copy(alpha = 0.3f),
                                    fontSize = 11.sp,
                                    modifier = Modifier.padding(top = 2.dp)
                                )
                            }
                            JarvisSwitch(
                                checked = false,
                                onCheckedChange = {
                                    Toast.makeText(context, "Companion mode is greyed out for now", Toast.LENGTH_SHORT).show()
                                },
                                activeColor = Color.Gray
                            )
                        }
                    }

                    // Sensitivity Presets Section
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "WAKE CONFIDENCE SENSITIVITY",
                            color = Color(0xFFFFB45A),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.8.sp
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            val presets = listOf(
                                Triple(0.20f, "Sensitive", "0.20"),
                                Triple(0.35f, "Balanced", "0.35"),
                                Triple(0.60f, "Strict", "0.60")
                            )
                            presets.forEach { (thresh, name, valueStr) ->
                                val isSelected = kotlin.math.abs(currentThreshold - thresh) < 0.06f
                                Surface(
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (isSelected) Color(0x33FF8A00) else Color(0xFF160905),
                                    border = BorderStroke(
                                        1.2.dp,
                                        if (isSelected) Color(0xFFFF8A00) else Color.White.copy(alpha = 0.12f)
                                    ),
                                    onClick = {
                                        currentThreshold = thresh
                                        com.pr4nav.jarvis.voice.VoiceAssistantPreferences.setWakeConfidenceThreshold(context, thresh)
                                        Toast.makeText(context, "Sensitivity set to $name ($valueStr)", Toast.LENGTH_SHORT).show()
                                    }
                                ) {
                                    Column(
                                        modifier = Modifier.padding(vertical = 10.dp, horizontal = 6.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Text(
                                            text = if (isSelected) "✓ $name" else name,
                                            color = if (isSelected) Color(0xFFFFB45A) else Color.White,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            fontSize = 11.5.sp
                                        )
                                        Text(
                                            text = valueStr,
                                            color = Color.White.copy(alpha = 0.5f),
                                            fontSize = 10.sp
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Quick Actions
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F0D07),
                            border = BorderStroke(1.dp, Color(0x44FF8A00)),
                            onClick = {
                                com.pr4nav.jarvis.companion.JarvisOverlayService.showHud(context)
                                onDismiss()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SparkleSvg(modifier = Modifier.size(13.dp), tint = Color(0xFFFF8A00))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("HUD Overlay", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }

                        Surface(
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(12.dp),
                            color = Color(0xFF1F0D07),
                            border = BorderStroke(1.dp, Color(0x44FF8A00)),
                            onClick = {
                                context.startActivity(Intent(context, com.pr4nav.jarvis.voice.VoiceSettingsActivity::class.java))
                                onDismiss()
                            }
                        ) {
                            Row(
                                modifier = Modifier.padding(vertical = 10.dp, horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                SettingsSvg(modifier = Modifier.size(13.dp), tint = Color(0xFFFF8A00))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Voice Settings", color = Color.White, fontSize = 11.5.sp, fontWeight = FontWeight.Medium)
                            }
                        }
                    }
                }
            }
        }
    }
}

data class JarvisToolItem(
    val title: String,
    val description: String,
    val iconEmoji: String,
    val onClick: () -> Unit
)

@Composable
fun JarvisToolsDialog(
    onDismiss: () -> Unit,
    onOpenStandby: () -> Unit = {},
    onConfigureQwenUrl: () -> Unit = {}
) {
    val context = LocalContext.current
    val tools = listOf(
        JarvisToolItem("File Manager & Storage", "Explore & manage internal files and directories", "📁") {
            context.startActivity(Intent(context, BrowserActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Web Browser & Search", "Open web browser and search Google", "🌐") {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(context, "Could not launch browser: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            onDismiss()
        },
        JarvisToolItem("Companion & Voice Controls", "Hands-free mic, proactive mode & sensitivity", "🎙") {
            onDismiss()
            onOpenStandby()
        },
        JarvisToolItem("Floating HUD Companion", "Siri-style screen overlay with live holographic waveforms", "🔮") {
            com.pr4nav.jarvis.companion.JarvisOverlayService.showHud(context)
            onDismiss()
        },
        JarvisToolItem("Linux Terminal (Termux)", "Full bash shell, apt packages & command execution", "💻") {
            context.startActivity(Intent(context, TerminalActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Commander Console", "Deep inspection, intent pipeline & system orchestrator", "🕹") {
            context.startActivity(Intent(context, CommanderActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("AI Model Hub & Local TTS", "Manage openWakeWord weights and Kokoro 82M offline TTS", "🧠") {
            context.startActivity(Intent(context, com.pr4nav.jarvis.voice.ModelHubActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("System Diagnostics", "All-Files, mic, Termux, battery optimization status", "📊") {
            context.startActivity(Intent(context, DiagnosticsActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Canonical Tool Playground", "Live sandbox for system commands and capability testing", "⚡") {
            context.startActivity(Intent(context, ToolPlaygroundActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Connected Services & AI", "Groq LPU, Gemini cloud, and router settings", "🛰") {
            context.startActivity(Intent(context, ConnectedServicesActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Configure Groq API Key & Quotas", "Set Groq key, select model, and monitor 245 RPD / 65k TPM limits", "⚡") {
            onDismiss()
            onConfigureQwenUrl()
        },
        JarvisToolItem("Antigravity PRoot Console", "Full Linux container environment with agy CLI tools", "🚀") {
            context.startActivity(Intent(context, AgyActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Voice Assistant Settings", "Language, speech rate, and hands-free preferences", "🔊") {
            context.startActivity(Intent(context, com.pr4nav.jarvis.voice.VoiceSettingsActivity::class.java))
            onDismiss()
        },
        JarvisToolItem("Permissions Manager", "Review storage, mic, notification & overlay rights", "🔒") {
            context.startActivity(Intent(context, PermissionsActivity::class.java))
            onDismiss()
        }
    )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.75f))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.82f)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                color = Color(0xFF0F0503),
                border = BorderStroke(
                    1.5.dp,
                    Brush.verticalGradient(
                        listOf(Color(0xFFFF8A00).copy(alpha = 0.85f), Color(0xFFFF3D00).copy(alpha = 0.35f))
                    )
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(20.dp)
                ) {
                    // Header
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color(0x33FF8A00))
                                    .border(1.dp, Color(0xFFFF8A00).copy(alpha = 0.6f), RoundedCornerShape(12.dp)),
                                contentAlignment = Alignment.Center
                            ) {
                                ToolsSvg(modifier = Modifier.size(20.dp), tint = Color(0xFFFF8A00))
                            }
                            Column {
                                Text(
                                    text = "JARVIS TOOLS",
                                    color = Color.White,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    letterSpacing = 1.sp
                                )
                                Text(
                                    text = "Utilities, capabilities & developer consoles",
                                    color = Color.White.copy(alpha = 0.6f),
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(32.dp)
                        ) {
                            Text("✕", color = Color.White.copy(alpha = 0.7f), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.08f)))
                    Spacer(modifier = Modifier.height(12.dp))

                    // Scrollable List of Tools
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(tools.size) { index ->
                            val tool = tools[index]
                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = Color(0xFF160905),
                                border = BorderStroke(1.dp, Color(0x33FF8A00)),
                                onClick = tool.onClick
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp, vertical = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(36.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Color(0xFF22100A))
                                            .border(1.dp, Color(0x44FF8A00), RoundedCornerShape(10.dp)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(text = tool.iconEmoji, fontSize = 18.sp)
                                    }
                                    Spacer(modifier = Modifier.width(12.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tool.title,
                                            color = Color.White,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 13.5.sp
                                        )
                                        Text(
                                            text = tool.description,
                                            color = Color.White.copy(alpha = 0.55f),
                                            fontSize = 11.sp,
                                            lineHeight = 14.sp
                                        )
                                    }
                                    Text(
                                        text = "›",
                                        color = Color(0xFFFFB45A),
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.padding(start = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
