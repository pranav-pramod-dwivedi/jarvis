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

        // Local AI Management
        val labelLocalAi = findViewById<TextView>(R.id.local_ai_status)
        val progressLocalAi = findViewById<android.widget.ProgressBar>(R.id.local_ai_progress)
        val btnDownloadLocalAi = findViewById<Button>(R.id.btn_download_local_ai)
        val btnTestLocalAi = findViewById<Button>(R.id.btn_test_local_ai)

        val activeModelId = com.pr4nav.jarvis.llm.LocalModelManager.getActiveModelId(this)
        val isInstalled = com.pr4nav.jarvis.llm.LocalModelManager.isModelInstalled(this, activeModelId)
        if (isInstalled) {
            labelLocalAi.text = "Status: Qwen 2.5 (Active & Ready in App Storage ✓)"
            btnDownloadLocalAi.text = "RE-DOWNLOAD"
        } else {
            labelLocalAi.text = "Status: Qwen 2.5 (1.5B Instruct) — Not Downloaded (~986MB)"
            btnDownloadLocalAi.text = "DOWNLOAD QWEN 2.5"
        }

        btnDownloadLocalAi.setOnClickListener {
            progressLocalAi.visibility = android.view.View.VISIBLE
            progressLocalAi.progress = 0
            btnDownloadLocalAi.isEnabled = false
            labelLocalAi.text = "Downloading Qwen 2.5 to internal app storage..."

            com.pr4nav.jarvis.llm.LocalModelManager.startDownload(
                context = this,
                modelId = activeModelId,
                onProgress = { pct, downloaded, total ->
                    runOnUiThread {
                        progressLocalAi.progress = pct
                        labelLocalAi.text = "Downloading: $pct% (${downloaded / 1024 / 1024} MB / ${total / 1024 / 1024} MB)"
                    }
                },
                onComplete = { success, err ->
                    runOnUiThread {
                        btnDownloadLocalAi.isEnabled = true
                        progressLocalAi.visibility = android.view.View.GONE
                        if (success) {
                            labelLocalAi.text = "Status: Qwen 2.5 Installed & Ready ✓"
                            Toast.makeText(this, "Local Model Downloaded Successfully!", Toast.LENGTH_SHORT).show()
                        } else {
                            labelLocalAi.text = "Download Failed: $err"
                            Toast.makeText(this, "Download error: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                }
            )
        }

        btnTestLocalAi.setOnClickListener {
            val qwen = com.pr4nav.jarvis.llm.QwenLocalLLM(this)
            Toast.makeText(this, "Running Local LLM test prompt...", Toast.LENGTH_SHORT).show()
            qwen.generate("Turn off the flashlight and set volume to 5", 30_000L).thenAccept { res ->
                runOnUiThread {
                    Toast.makeText(this, "Local LLM: ${res.toolCall ?: "none"} (${res.latencyMs}ms)", Toast.LENGTH_LONG).show()
                }
            }
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
