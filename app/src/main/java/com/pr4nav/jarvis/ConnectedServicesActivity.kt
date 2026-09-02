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
        // Groq Cloud LPU & Shell Agent Setup
        val labelGroqStatus = findViewById<TextView>(R.id.groq_status)
        val labelGroqQuota = findViewById<TextView>(R.id.groq_quota_status)
        val inputGroqKey = findViewById<EditText>(R.id.input_groq_api_key)
        val btnSaveGroq = findViewById<Button>(R.id.btn_save_groq)
        val btnSelectGroqModel = findViewById<Button>(R.id.btn_select_groq_model)
        val btnTestGroq = findViewById<Button>(R.id.btn_test_groq)

        fun refreshGroqStatus() {
            val key = com.pr4nav.jarvis.llm.GroqClient.getApiKey(this)
            val model = com.pr4nav.jarvis.llm.GroqClient.getModel(this)
            val metrics = com.pr4nav.jarvis.llm.GroqClient.getUsageMetrics(this)

            btnSelectGroqModel.text = if (model.contains("8b")) "MODEL: 8B" else "MODEL: 70B"
            if (key.isNotBlank()) {
                inputGroqKey.setText(key)
                labelGroqStatus.text = "Status: Groq LPU Configured ($model) ✓"
                labelGroqStatus.setTextColor(android.graphics.Color.parseColor("#10B981"))
            } else {
                labelGroqStatus.text = "Status: Groq API Key Not Set (Using AGY Autonomous Mode)"
                labelGroqStatus.setTextColor(android.graphics.Color.parseColor("#94A3B8"))
            }

            labelGroqQuota.text = "Quotas: ${metrics.rpdUsed}/${metrics.rpdLimit} RPD · ${metrics.currentTpm}/${metrics.tpmLimit} TPM · Max 8,192 tokens/msg"
        }
        refreshGroqStatus()

        btnSaveGroq.setOnClickListener {
            val key = inputGroqKey.text.toString().trim()
            com.pr4nav.jarvis.llm.GroqClient.setApiKey(this, key)
            refreshGroqStatus()
            Toast.makeText(this, if (key.isNotEmpty()) "Groq API Key Saved!" else "Groq API Key Cleared", Toast.LENGTH_SHORT).show()
        }

        btnSelectGroqModel.setOnClickListener {
            val defaultModels = arrayOf(
                "⚡ groq/compound-mini (Default · Ultra-Fast Compound Agent)",
                "🧠 groq/compound (Complex Multi-Tool Compound Agent)",
                "llama-3.3-70b-versatile (Flagship 70B)",
                "llama-3.1-8b-instant (Fast 8B)",
                "mixtral-8x7b-32768 (32k Context)",
                "📥 Fetch Available Models from Groq API..."
            )
            androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle("Select Groq Model")
                .setItems(defaultModels) { _, which ->
                    when (which) {
                        0 -> {
                            com.pr4nav.jarvis.llm.GroqClient.setModel(this, "groq/compound-mini")
                            refreshGroqStatus()
                            Toast.makeText(this, "Model: groq/compound-mini (Default)", Toast.LENGTH_SHORT).show()
                        }
                        1 -> {
                            com.pr4nav.jarvis.llm.GroqClient.setModel(this, "groq/compound")
                            refreshGroqStatus()
                            Toast.makeText(this, "Model: groq/compound", Toast.LENGTH_SHORT).show()
                        }
                        2 -> {
                            com.pr4nav.jarvis.llm.GroqClient.setModel(this, "llama-3.3-70b-versatile")
                            refreshGroqStatus()
                            Toast.makeText(this, "Model: llama-3.3-70b-versatile", Toast.LENGTH_SHORT).show()
                        }
                        3 -> {
                            com.pr4nav.jarvis.llm.GroqClient.setModel(this, "llama-3.1-8b-instant")
                            refreshGroqStatus()
                            Toast.makeText(this, "Model: llama-3.1-8b-instant", Toast.LENGTH_SHORT).show()
                        }
                        4 -> {
                            com.pr4nav.jarvis.llm.GroqClient.setModel(this, "mixtral-8x7b-32768")
                            refreshGroqStatus()
                            Toast.makeText(this, "Model: mixtral-8x7b-32768", Toast.LENGTH_SHORT).show()
                        }
                        5 -> {
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
                                                refreshGroqStatus()
                                                Toast.makeText(this, "Model: $chosen", Toast.LENGTH_SHORT).show()
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
                .setNegativeButton("Cancel", null)
                .show()
        }

        btnTestGroq.setOnClickListener {
            Toast.makeText(this, "Querying Groq + executing shell command test...", Toast.LENGTH_SHORT).show()
            com.pr4nav.jarvis.llm.GroqClient.query(
                context = this,
                prompt = "Run `uname -a` and tell me what kernel this device is using.",
                forceShellCapability = true,
                onSuccess = { res ->
                    runOnUiThread {
                        refreshGroqStatus()
                        val toolsInfo = if (res.toolCallsExecuted.isNotEmpty()) {
                            "Shell Executed: ${res.toolCallsExecuted.map { it.command }.joinToString(", ")}\nOutput: ${res.toolCallsExecuted.firstOrNull()?.output}\n\n"
                        } else ""
                        android.app.AlertDialog.Builder(this)
                            .setTitle("⚡ Groq LPU Response (${res.latencyMs}ms)")
                            .setMessage("$toolsInfo${res.response}\n\n[Tokens: ${res.totalTokens} | Prompt: ${res.promptTokens} | Completion: ${res.completionTokens}]")
                            .setPositiveButton("OK", null)
                            .show()
                    }
                },
                onError = { err ->
                    runOnUiThread {
                        android.app.AlertDialog.Builder(this)
                            .setTitle("⚠️ Groq Connection Error")
                            .setMessage(err)
                            .setPositiveButton("OK", null)
                            .show()
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
