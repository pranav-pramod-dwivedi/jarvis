package com.pr4nav.jarvis.voice

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.pr4nav.jarvis.R

class VoiceSettingsActivity : AppCompatActivity() {

    private lateinit var switchHandsFree: SwitchCompat
    private lateinit var switchConversation: SwitchCompat
    private lateinit var switchBargeIn: SwitchCompat
    private lateinit var switchStartOnBoot: SwitchCompat
    private lateinit var txtBatteryOptStatus: TextView
    private lateinit var btnRequestBatteryExemption: Button
    private lateinit var btnTestVoice: Button

    private var voiceEngine: JarvisVoiceEngine? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_voice_settings)

        voiceEngine = JarvisVoiceEngine.getInstance(this)

        findViewById<View>(R.id.btn_back)?.setOnClickListener { finish() }

        switchHandsFree = findViewById(R.id.switch_hands_free)
        switchConversation = findViewById(R.id.switch_conversation_mode)
        switchBargeIn = findViewById(R.id.switch_barge_in)
        switchStartOnBoot = findViewById(R.id.switch_start_on_boot)
        txtBatteryOptStatus = findViewById(R.id.txt_battery_opt_status)
        btnRequestBatteryExemption = findViewById(R.id.btn_request_battery_exemption)
        btnTestVoice = findViewById(R.id.btn_test_voice)

        setupPreferences()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshBatteryStatus()
        switchHandsFree.isChecked = VoiceAssistantPreferences.isHandsFreeEnabled(this)
    }

    override fun onDestroy() {
        voiceEngine?.destroy()
        super.onDestroy()
    }

    private fun setupPreferences() {
        switchHandsFree.isChecked = VoiceAssistantPreferences.isHandsFreeEnabled(this)
        switchConversation.isChecked = VoiceAssistantPreferences.isConversationMode(this)
        switchBargeIn.isChecked = VoiceAssistantPreferences.isBargeInEnabled(this)
        switchStartOnBoot.isChecked = VoiceAssistantPreferences.isStartOnBoot(this)
        refreshBatteryStatus()
    }

    private fun setupListeners() {
        switchHandsFree.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                // Verify audio permission
                val hasMic = androidx.core.content.ContextCompat.checkSelfPermission(
                    this,
                    android.Manifest.permission.RECORD_AUDIO
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED
                if (!hasMic) {
                    switchHandsFree.isChecked = false
                    Toast.makeText(this, "Microphone permission required for Hands-Free mode", Toast.LENGTH_LONG).show()
                    requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 901)
                    return@setOnCheckedChangeListener
                }
            }
            VoiceAssistantPreferences.setHandsFreeEnabled(this, isChecked)
            val stateText = if (isChecked) "ENABLED (Service Running)" else "DISABLED"
            Toast.makeText(this, "Hands-Free Mode: $stateText", Toast.LENGTH_SHORT).show()
        }

        switchConversation.setOnCheckedChangeListener { _, isChecked ->
            VoiceAssistantPreferences.setConversationMode(this, isChecked)
        }

        switchBargeIn.setOnCheckedChangeListener { _, isChecked ->
            VoiceAssistantPreferences.getPrefs(this).edit().putBoolean(VoiceAssistantPreferences.KEY_BARGE_IN_ENABLED, isChecked).apply()
        }

        switchStartOnBoot.setOnCheckedChangeListener { _, isChecked ->
            VoiceAssistantPreferences.setStartOnBoot(this, isChecked)
        }

        btnRequestBatteryExemption.setOnClickListener {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                try {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (_: Exception) {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    startActivity(intent)
                }
            }
        }

        btnTestVoice.setOnClickListener {
            voiceEngine?.speak("Hello! JARVIS Voice Assistant is active and ready to perform actions.", interrupt = true)
        }
    }

    private fun refreshBatteryStatus() {
        val ignored = VoiceAssistantPreferences.isBatteryOptimizationsIgnored(this)
        if (ignored) {
            txtBatteryOptStatus.text = "Status: EXEMPT (Unrestricted background execution granted ✓)"
            txtBatteryOptStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
            btnRequestBatteryExemption.visibility = View.GONE
        } else {
            txtBatteryOptStatus.text = "Status: OPTIMIZED (OS may throttle background microphone)"
            txtBatteryOptStatus.setTextColor(android.graphics.Color.parseColor("#F59E0B"))
            btnRequestBatteryExemption.visibility = View.VISIBLE
        }
    }
}
