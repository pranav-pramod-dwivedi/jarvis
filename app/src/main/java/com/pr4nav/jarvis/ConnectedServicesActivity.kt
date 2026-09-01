package com.pr4nav.jarvis

import android.content.Context
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.router.JarvisIntentRouter

class ConnectedServicesActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_connected_services)

        findViewById<android.view.View>(R.id.btn_back)?.setOnClickListener { finish() }

        val prefs = getSharedPreferences("jarvis_cloud_prefs", Context.MODE_PRIVATE)
        val inputProject = findViewById<EditText>(R.id.input_firebase_project)
        val labelStatus = findViewById<TextView>(R.id.firebase_status)
        val btnSave = findViewById<Button>(R.id.btn_save_firebase)

        val savedProject = prefs.getString("firebase_project_id", "jarvis-cloud-sync") ?: "jarvis-cloud-sync"
        inputProject.setText(savedProject)
        labelStatus.text = "Firebase Project: $savedProject (Cloud sync active)"

        btnSave.setOnClickListener {
            val proj = inputProject.text.toString().trim()
            if (proj.isNotEmpty()) {
                prefs.edit().putString("firebase_project_id", proj).apply()
                labelStatus.text = "Firebase Project: $proj (Connected ✓)"
                Toast.makeText(this, "Firebase Cloud Project Saved: $proj", Toast.LENGTH_SHORT).show()
            }
        }

        // Gemini Cloud LLM Setup
        val labelGeminiStatus = findViewById<TextView>(R.id.gemini_cloud_status)
        val inputGeminiKey = findViewById<EditText>(R.id.input_gemini_api_key)
        val btnSaveGemini = findViewById<Button>(R.id.btn_save_gemini)
        val btnTestGemini = findViewById<Button>(R.id.btn_test_gemini)

        val savedGeminiKey = com.pr4nav.jarvis.llm.GeminiCloudLLM.getApiKey(this)
        if (savedGeminiKey.isNotEmpty()) {
            inputGeminiKey.setText(savedGeminiKey)
            labelGeminiStatus.text = "Status: Direct Gemini API Configured (Ready ✓)"
            labelGeminiStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
        } else {
            labelGeminiStatus.text = "Status: Autonomous Mode (AGY / Needle / Local SLM Active ✓)"
            labelGeminiStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
        }

        btnSaveGemini.setOnClickListener {
            val key = inputGeminiKey.text.toString().trim()
            com.pr4nav.jarvis.llm.GeminiCloudLLM.setApiKey(this, key)
            if (key.isNotEmpty()) {
                labelGeminiStatus.text = "Status: Direct Gemini API Configured (Ready ✓)"
                labelGeminiStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
                Toast.makeText(this, "Gemini API Key Saved!", Toast.LENGTH_SHORT).show()
            } else {
                labelGeminiStatus.text = "Status: Autonomous Mode (AGY / Needle / Local SLM Active ✓)"
                labelGeminiStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
                Toast.makeText(this, "Gemini API Key Cleared — using AGY Autonomous Mode", Toast.LENGTH_SHORT).show()
            }
        }

        btnTestGemini.setOnClickListener {
            Toast.makeText(this, "Querying Cloud LLM...", Toast.LENGTH_SHORT).show()
            com.pr4nav.jarvis.llm.GeminiCloudLLM.generate(
                context = this,
                prompt = "Hello JARVIS, explain quantum entanglement in two short sentences.",
                onSuccess = { response ->
                    runOnUiThread {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("☁️ Cloud AI Response")
                            .setMessage(response)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("⚠️ Cloud Connection Error")
                            .setMessage(err)
                            .setPositiveButton("OK", null)
                            .show()
                    }
                }
            )
        }

        // On-Device Architecture & Storage Management
        val labelLocalAi = findViewById<TextView>(R.id.local_ai_status)
        val progressLocalAi = findViewById<android.widget.ProgressBar>(R.id.local_ai_progress)
        val btnDownloadLocalAi = findViewById<Button>(R.id.btn_download_local_ai)
        val btnTestLocalAi = findViewById<Button>(R.id.btn_test_local_ai)

        val activeMode = com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.getAgentMode(this)
        labelLocalAi.text = "Active Mode: ${activeMode.displayName} (Tap to change)"
        btnDownloadLocalAi.text = "SWITCH ROUTER MODE"
        btnTestLocalAi.text = "TEST CLOUD COMMAND EXECUTION"

        fun refreshModeLabel() {
            val m = com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.getAgentMode(this)
            labelLocalAi.text = "Active Mode: ${m.displayName}\n${m.description}"
        }
        refreshModeLabel()

        btnDownloadLocalAi.setOnClickListener {
            val modes = com.pr4nav.jarvis.router.AgentExecutionMode.values()
            val items = modes.map { "${it.displayName}\n${it.description}" }.toTypedArray()

            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Intelligence & Router Mode")
                .setItems(items) { _, which ->
                    val chosen = modes[which]
                    com.pr4nav.jarvis.router.UnifiedAssistantDispatcher.setAgentMode(this, chosen)
                    refreshModeLabel()
                    Toast.makeText(this, "Active Mode: ${chosen.displayName}", Toast.LENGTH_SHORT).show()
                }
                .setNeutralButton("Agent URL") { _, _ ->
                    val currentUrl = com.pr4nav.jarvis.llm.QwenAgentClient.getAgentUrl(this)
                    val edit = EditText(this).apply {
                        setText(currentUrl)
                        hint = "http://127.0.0.1:8081"
                        setPadding(40, 30, 40, 30)
                    }
                    androidx.appcompat.app.AlertDialog.Builder(this)
                        .setTitle("Qwen Python Agent Server URL")
                        .setMessage("Endpoint running `python3 server/agent.py` (Default: http://127.0.0.1:8081)")
                        .setView(edit)
                        .setPositiveButton("Save") { _, _ ->
                            val newUrl = edit.text.toString().trim()
                            if (newUrl.isNotBlank()) {
                                com.pr4nav.jarvis.llm.QwenAgentClient.setAgentUrl(this, newUrl)
                                Toast.makeText(this, "Saved: $newUrl", Toast.LENGTH_SHORT).show()
                            }
                        }
                        .setNegativeButton("Cancel", null)
                        .show()
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnTestLocalAi.setOnClickListener {
            Toast.makeText(this, "Testing Cloud LLM Command Execution...", Toast.LENGTH_SHORT).show()
            com.pr4nav.jarvis.llm.GeminiCloudLLM.generate(
                context = this,
                prompt = "Turn on the flashlight and tell me the status",
                onSuccess = { res ->
                    runOnUiThread {
                        Toast.makeText(this, "Cloud: $res", Toast.LENGTH_LONG).show()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        Toast.makeText(this, "Error: $err", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        // Test buttons
        findViewById<Button>(R.id.btn_test_compound).setOnClickListener {
            JarvisIntentRouter.routeAndExecute(this, "Play that YouTube video while navigating home") { res ->
                Toast.makeText(this, res.executionSummary, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_test_music).setOnClickListener {
            JarvisIntentRouter.routeAndExecute(this, "Play something chill") { res ->
                Toast.makeText(this, res.executionSummary, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_test_nav).setOnClickListener {
            JarvisIntentRouter.routeAndExecute(this, "Take me to school") { res ->
                Toast.makeText(this, res.executionSummary, Toast.LENGTH_LONG).show()
            }
        }

        findViewById<Button>(R.id.btn_test_files).setOnClickListener {
            JarvisIntentRouter.routeAndExecute(this, "Find my JEE notes") { res ->
                Toast.makeText(this, res.executionSummary, Toast.LENGTH_LONG).show()
            }
        }
    }
}
