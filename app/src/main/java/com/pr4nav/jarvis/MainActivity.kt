package com.pr4nav.jarvis

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.capabilities.Capabilities
import com.pr4nav.jarvis.capabilities.RootCapability
import com.pr4nav.jarvis.companion.CompanionManager
import com.pr4nav.jarvis.router.JarvisIntentRouter
import com.pr4nav.jarvis.voice.JarvisVoiceEngine

/**
 * JARVIS Assistant Home (Clean, Ultra-Premium UI/UX)
 *
 * Conforms strictly to guidelines:
 * - Simple, friendly, un-cluttered home view (no technical architecture exposed on home)
 * - Glowing Orb representing states: IDLE, LISTENING, THINKING, EXECUTING, FINISHED, ERROR
 * - Two main triggers: "Speak" and "Type"
 * - Opt-in Companion Mode toggle (Default: OFF)
 * - Developer Mode sheet (hidden/inspectable behind the top-right button)
 * - Full linking to all pages without hiding anything
 */
class MainActivity : AppCompatActivity() {

    private lateinit var txtSystemStatus: TextView
    private lateinit var txtMainPrompt: TextView
    private lateinit var txtActionSubtext: TextView
    private lateinit var orbRing: View
    private lateinit var orbCore: View
    private lateinit var orbContainer: FrameLayout
    private lateinit var btnSpeak: View
    private lateinit var btnType: View
    private lateinit var txtCompanionToggle: TextView
    private lateinit var devModeSheet: LinearLayout
    private lateinit var btnDevToggle: ImageButton

    private var voiceEngine: JarvisVoiceEngine? = null
    private var orbPulseAnimator: ObjectAnimator? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    enum class OrbState {
        IDLE, LISTENING, THINKING, EXECUTING, FINISHED, ERROR
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        TermuxBridge.init(this)
        Fs.init(this)
        Capabilities.init(this)
        Thread { RootCapability.detect() }.start()

        txtSystemStatus = findViewById(R.id.txt_system_status)
        txtMainPrompt = findViewById(R.id.txt_main_prompt)
        txtActionSubtext = findViewById(R.id.txt_action_subtext)
        orbRing = findViewById(R.id.orb_ring)
        orbCore = findViewById(R.id.orb_core)
        orbContainer = findViewById(R.id.orb_container)
        btnSpeak = findViewById(R.id.btn_home_speak)
        btnType = findViewById(R.id.btn_home_type)
        txtCompanionToggle = findViewById(R.id.txt_companion_toggle)
        devModeSheet = findViewById(R.id.developer_mode_sheet)
        btnDevToggle = findViewById(R.id.btn_dev_mode_toggle)

        voiceEngine = JarvisVoiceEngine(this)

        startOrbIdleAnimation()
        setupListeners()
        updateCompanionBadge()
        checkFirstRunPermissions()
    }

    override fun onResume() {
        super.onResume()
        updateCompanionBadge()
        val termuxState = if (Shell.termuxReachable()) "Ready" else "Standby"
        txtSystemStatus.text = "Linux execution · $termuxState"

        if (com.pr4nav.jarvis.voice.VoiceAssistantPreferences.isHandsFreeEnabled(this)) {
            val hasMic = checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasMic && !com.pr4nav.jarvis.voice.JarvisVoiceService.isRunning) {
                com.pr4nav.jarvis.voice.JarvisVoiceService.start(this)
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        orbPulseAnimator?.cancel()
        voiceEngine?.destroy()
    }

    private fun startOrbIdleAnimation() {
        orbPulseAnimator?.cancel()
        orbPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
            orbRing,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.15f, 1.0f),
            PropertyValuesHolder.ofFloat(View.ALPHA, 0.4f, 0.9f, 0.4f)
        ).apply {
            duration = 2400
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun setOrbState(state: OrbState, customPrompt: String? = null, customSubtext: String? = null) {
        runOnUiThread {
            when (state) {
                OrbState.IDLE -> {
                    txtMainPrompt.text = customPrompt ?: "What do you need?"
                    txtActionSubtext.text = customSubtext ?: "Ready for natural voice or text command"
                    orbCore.setBackgroundResource(R.drawable.bg_orb_idle)
                    startOrbIdleAnimation()
                }
                OrbState.LISTENING -> {
                    txtMainPrompt.text = customPrompt ?: "Listening…"
                    txtActionSubtext.text = customSubtext ?: "Speak clearly into microphone"
                    orbCore.setBackgroundResource(R.drawable.bg_btn_send_orange)
                    orbPulseAnimator?.cancel()
                    orbPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                        orbRing,
                        PropertyValuesHolder.ofFloat(View.SCALE_X, 1.0f, 1.35f, 1.0f),
                        PropertyValuesHolder.ofFloat(View.SCALE_Y, 1.0f, 1.35f, 1.0f),
                        PropertyValuesHolder.ofFloat(View.ALPHA, 0.6f, 1.0f, 0.6f)
                    ).apply {
                        duration = 1000
                        repeatCount = ValueAnimator.INFINITE
                        start()
                    }
                }
                OrbState.THINKING -> {
                    txtMainPrompt.text = customPrompt ?: "Thinking…"
                    txtActionSubtext.text = customSubtext ?: "Analyzing natural language & capabilities"
                    orbPulseAnimator?.cancel()
                    orbPulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                        orbCore,
                        PropertyValuesHolder.ofFloat(View.ROTATION, 0f, 360f)
                    ).apply {
                        duration = 1200
                        repeatCount = ValueAnimator.INFINITE
                        start()
                    }
                }
                OrbState.EXECUTING -> {
                    txtMainPrompt.text = customPrompt ?: "Executing action…"
                    txtActionSubtext.text = customSubtext ?: "Performing requested operation"
                }
                OrbState.FINISHED -> {
                    txtMainPrompt.text = customPrompt ?: "Done ✓"
                    txtActionSubtext.text = customSubtext ?: "Action completed successfully"
                    mainHandler.postDelayed({
                        setOrbState(OrbState.IDLE)
                    }, 4000)
                }
                OrbState.ERROR -> {
                    txtMainPrompt.text = customPrompt ?: "Notice"
                    txtActionSubtext.text = customSubtext ?: "Could not complete operation"
                    mainHandler.postDelayed({
                        setOrbState(OrbState.IDLE)
                    }, 4000)
                }
            }
        }
    }

    private fun setupListeners() {
        // Speak button
        btnSpeak.setOnClickListener {
            startVoiceFlow()
        }

        // Orb tap also triggers voice
        orbContainer.setOnClickListener {
            startVoiceFlow()
        }

        // Type button opens direct dialog or navigates to Agent
        btnType.setOnClickListener {
            showTypePromptDialog()
        }

        // Companion Mode toggle configure
        txtCompanionToggle.setOnClickListener {
            showCompanionModeDialog()
        }

        // Developer Mode Hub Drawer Toggle
        btnDevToggle.setOnClickListener {
            showDeveloperHubMenu()
        }

        // Dev Mode Sheet Buttons
        findViewById<View>(R.id.btn_close_dev_mode)?.setOnClickListener {
            devModeSheet.visibility = View.GONE
        }
        findViewById<View>(R.id.btn_dev_agent)?.setOnClickListener {
            startActivity(Intent(this, AgentActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_playground)?.setOnClickListener {
            startActivity(Intent(this, ToolPlaygroundActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_services)?.setOnClickListener {
            startActivity(Intent(this, ConnectedServicesActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_files)?.setOnClickListener {
            startActivity(Intent(this, BrowserActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_commander)?.setOnClickListener {
            startActivity(Intent(this, CommanderActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_terminal)?.setOnClickListener {
            startActivity(Intent(this, TerminalActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_diagnostics)?.setOnClickListener {
            startActivity(Intent(this, DiagnosticsActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_agy)?.setOnClickListener {
            startActivity(Intent(this, AgyActivity::class.java))
        }
        findViewById<View>(R.id.btn_dev_permissions)?.setOnClickListener {
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }

    private fun startVoiceFlow() {
        setOrbState(OrbState.LISTENING)
        voiceEngine?.startListening(
            activity = this,
            onPartial = { partial ->
                setOrbState(OrbState.LISTENING, "Listening…", partial)
            },
            onResult = { text ->
                setOrbState(OrbState.THINKING, "Understanding…", "\"$text\"")
                executeAssistantCommand(text)
            },
            onError = { err ->
                setOrbState(OrbState.ERROR, "Voice Cancelled", err)
            }
        )
    }

    private fun showTypePromptDialog() {
        val inputEdit = EditText(this).apply {
            hint = "Ask JARVIS or give a command…"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#64748B"))
            setPadding(40, 30, 40, 30)
            setBackgroundColor(Color.parseColor("#141A26"))
        }

        AlertDialog.Builder(this)
            .setTitle("Enter Command")
            .setView(inputEdit)
            .setPositiveButton("Execute") { _, _ ->
                val q = inputEdit.text.toString().trim()
                if (q.isNotEmpty()) {
                    setOrbState(OrbState.THINKING, "Processing…", "\"$q\"")
                    executeAssistantCommand(q)
                }
            }
            .setNeutralButton("Open Chat Stream") { _, _ ->
                startActivity(Intent(this, AgentActivity::class.java))
            }
            .setNegativeButton("Cancel") { _, _ ->
                setOrbState(OrbState.IDLE)
            }
            .show()
    }

    private fun executeAssistantCommand(query: String) {
        Thread {
            setOrbState(OrbState.EXECUTING, "Executing…", query)
            com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.execute(this, query) { res ->
                runOnUiThread {
                    if (res.handled) {
                        setOrbState(OrbState.FINISHED, "Completed ✓", res.speechResponse)
                    } else {
                        setOrbState(OrbState.FINISHED, "JARVIS", res.speechResponse)
                    }
                    voiceEngine?.speak(res.speechResponse, interrupt = false)
                }
            }
        }.start()
    }

    private fun updateCompanionBadge() {
        val enabled = CompanionManager.isEnabled(this)
        txtCompanionToggle.text = if (enabled) {
            "Companion Mode: ON (Proactive) · Tap to configure"
        } else {
            "Companion Mode: OFF · Tap to configure"
        }
        txtCompanionToggle.setTextColor(if (enabled) Color.parseColor("#10B981") else Color.parseColor("#475569"))
    }

    private fun showCompanionModeDialog() {
        val enabled = CompanionManager.isEnabled(this)
        val options = arrayOf(
            if (enabled) "Disable Companion Mode" else "Enable Companion Mode (Proactive)",
            "Pause Companion Mode temporarily",
            "Open Agent Chat Stream"
        )

        AlertDialog.Builder(this)
            .setTitle("Companion Mode Settings")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> {
                        CompanionManager.setEnabled(this, !enabled)
                        updateCompanionBadge()
                        Toast.makeText(this, "Companion Mode: ${if (!enabled) "ENABLED" else "DISABLED"}", Toast.LENGTH_SHORT).show()
                    }
                    1 -> {
                        CompanionManager.setPaused(this, true)
                        Toast.makeText(this, "Companion Mode Paused", Toast.LENGTH_SHORT).show()
                    }
                    2 -> startActivity(Intent(this, AgentActivity::class.java))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showDeveloperHubMenu() {
        val options = arrayOf(
            "Toggle Developer Inspect Drawer",
            "🎙️ Voice Assistant Settings (Hands-Free)",
            "Agent Chat Stream (Dribbble UI)",
            "Canonical Tool Playground",
            "Connected Services & Local AI",
            "File Manager",
            "Commander Console",
            "Linux Terminal",
            "System Diagnostics",
            "Antigravity Console",
            "Permissions Manager"
        )

        AlertDialog.Builder(this)
            .setTitle("JARVIS Pages & Developer Hub")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> devModeSheet.visibility = if (devModeSheet.visibility == View.VISIBLE) View.GONE else View.VISIBLE
                    1 -> startActivity(Intent(this, com.pr4nav.jarvis.voice.VoiceSettingsActivity::class.java))
                    2 -> startActivity(Intent(this, AgentActivity::class.java))
                    3 -> startActivity(Intent(this, ToolPlaygroundActivity::class.java))
                    4 -> startActivity(Intent(this, ConnectedServicesActivity::class.java))
                    5 -> startActivity(Intent(this, BrowserActivity::class.java))
                    6 -> startActivity(Intent(this, CommanderActivity::class.java))
                    7 -> startActivity(Intent(this, TerminalActivity::class.java))
                    8 -> startActivity(Intent(this, DiagnosticsActivity::class.java))
                    9 -> startActivity(Intent(this, AgyActivity::class.java))
                    10 -> startActivity(Intent(this, PermissionsActivity::class.java))
                }
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun checkFirstRunPermissions() {
        val prefs = getPreferences(MODE_PRIVATE)
        if (!prefs.getBoolean("perm_asked_v1", false)) {
            prefs.edit().putBoolean("perm_asked_v1", true).apply()
            startActivity(Intent(this, PermissionsActivity::class.java))
        }
    }
}
