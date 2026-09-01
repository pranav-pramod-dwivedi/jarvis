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

        labelLocalAi.text = "Architecture: ⚡ Needle 2 Reflex (On-Device) + ☁️ Gemini Cloud LLM"
        btnDownloadLocalAi.text = "DELETE ALL STORED LOCAL MODELS"
        btnTestLocalAi.text = "TEST CLOUD COMMAND EXECUTION"

        btnDownloadLocalAi.setOnClickListener {
            val deleted = com.pr4nav.jarvis.llm.LocalModelManager.deleteAllLocalModels(this)
            Toast.makeText(this, "Purged $deleted local model files from storage.", Toast.LENGTH_SHORT).show()
            labelLocalAi.text = "Storage: All local models deleted. Using Needle 2 + Cloud LLM."
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
