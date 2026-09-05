package com.pr4nav.jarvis.voice

import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.R

class ModelHubActivity : AppCompatActivity() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var voiceEngine: JarvisVoiceEngine? = null

    private lateinit var badgeWakeWord: TextView
    private lateinit var txtWakeWordSize: TextView
    private lateinit var progressWakeWord: ProgressBar
    private lateinit var btnDownloadWakeWord: Button
    private lateinit var btnTestWakeWord: Button

    private lateinit var badgeKokoro: TextView
    private lateinit var txtKokoroSize: TextView
    private lateinit var progressKokoro: ProgressBar
    private lateinit var btnDownloadKokoro: Button
    private lateinit var btnTestKokoro: Button

    private lateinit var badgeQwen: TextView
    private lateinit var txtQwenDetails: TextView
    private lateinit var progressQwen: ProgressBar
    private lateinit var btnDownloadQwen: Button
    private lateinit var btnVerifyQwen: Button
    private lateinit var btnDownloadAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_hub)

        voiceEngine = JarvisVoiceEngine.getInstance(this)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        badgeWakeWord = findViewById(R.id.badge_wakeword_status)
        txtWakeWordSize = findViewById(R.id.txt_wakeword_size)
        progressWakeWord = findViewById(R.id.progress_wakeword)
        btnDownloadWakeWord = findViewById(R.id.btn_download_wakeword)
        btnTestWakeWord = findViewById(R.id.btn_test_wakeword)

        badgeKokoro = findViewById(R.id.badge_kokoro_status)
        txtKokoroSize = findViewById(R.id.txt_kokoro_size)
        progressKokoro = findViewById(R.id.progress_kokoro)
        btnDownloadKokoro = findViewById(R.id.btn_download_kokoro)
        btnTestKokoro = findViewById(R.id.btn_test_kokoro)

        badgeQwen = findViewById(R.id.badge_qwen_status)
        txtQwenDetails = findViewById(R.id.txt_qwen_details)
        progressQwen = findViewById(R.id.progress_qwen)
        btnDownloadQwen = findViewById(R.id.btn_download_qwen)
        btnVerifyQwen = findViewById(R.id.btn_verify_qwen)

        btnDownloadAll = findViewById(R.id.btn_download_all)

        setupListeners()
        refreshStatus()
    }

    private fun setupListeners() {
        btnDownloadWakeWord.setOnClickListener {
            startDownload(ModelDownloadManager.MODEL_WAKEWORD)
        }

        btnDownloadKokoro.setOnClickListener {
            startDownload(ModelDownloadManager.MODEL_KOKORO_TTS)
        }

        btnDownloadQwen.setOnClickListener {
            val deleted = com.pr4nav.jarvis.llm.LocalModelManager.deleteAllLocalModels(this)
            Toast.makeText(this, "Purged $deleted local model files from storage.", Toast.LENGTH_SHORT).show()
            refreshStatus()
        }

        btnVerifyQwen.setOnClickListener {
            verifyAndTestCloud()
        }

        btnDownloadAll.setOnClickListener {
            startDownload(ModelDownloadManager.MODEL_WAKEWORD) {
                startDownload(ModelDownloadManager.MODEL_KOKORO_TTS)
            }
        }

        btnTestKokoro.setOnClickListener {
            voiceEngine?.speak("JARVIS neural speech synthesis online and fully operational, sir.", interrupt = true)
            Toast.makeText(this, "Testing Kokoro-82M TTS...", Toast.LENGTH_SHORT).show()
        }

        btnTestWakeWord.setOnClickListener {
            Toast.makeText(this, "Say 'Hey Jarvis' to test wake word detection", Toast.LENGTH_LONG).show()
        }
    }

    private fun startQwenDownload() {
        val activeModelId = com.pr4nav.jarvis.llm.LocalModelManager.getActiveModelId(this)
        progressQwen.visibility = View.VISIBLE
        progressQwen.progress = 0
        btnDownloadQwen.isEnabled = false
        badgeQwen.text = "DOWNLOADING…"
        badgeQwen.setTextColor(Color.parseColor("#F59E0B"))

        com.pr4nav.jarvis.llm.LocalModelManager.startDownload(
            this,
            activeModelId,
            onProgress = { pct, downloaded, total ->
                mainHandler.post {
                    progressQwen.progress = pct
                    badgeQwen.text = "$pct%"
                    txtQwenDetails.text = "Downloading: %.1f MB / %.1f MB".format(
                        downloaded / (1024.0 * 1024.0),
                        total / (1024.0 * 1024.0)
                    )
                }
            },
            onComplete = { success, error ->
                mainHandler.post {
                    progressQwen.visibility = View.GONE
                    btnDownloadQwen.isEnabled = true
                    refreshStatus()
                    if (success) {
                        Toast.makeText(this, "Qwen GGUF model downloaded and verified!", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(this, "Download failed: $error", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    private fun verifyAndTestCloud() {
        badgeQwen.text = "TESTING CLOUD…"
        badgeQwen.setTextColor(Color.parseColor("#38BDF8"))
        com.pr4nav.jarvis.llm.GeminiCloudLLM.generate(
            context = this,
            prompt = "What is the status of the system?",
            onSuccess = { res ->
                mainHandler.post {
                    badgeQwen.text = "ONLINE ✓"
                    badgeQwen.setTextColor(Color.parseColor("#10B981"))
                    txtQwenDetails.text = "Cloud Response:\n$res"
                    Toast.makeText(this, "Cloud LLM online and operational!", Toast.LENGTH_SHORT).show()
                }
            },
            onError = { err ->
                mainHandler.post {
                    badgeQwen.text = "STANDBY"
                    badgeQwen.setTextColor(Color.parseColor("#F59E0B"))
                    txtQwenDetails.text = "Autonomous Mode: $err"
                    Toast.makeText(this, "Cloud: $err", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun startDownload(modelId: String, onDone: (() -> Unit)? = null) {
        val progressBar = if (modelId == ModelDownloadManager.MODEL_WAKEWORD) progressWakeWord else progressKokoro
        val button = if (modelId == ModelDownloadManager.MODEL_WAKEWORD) btnDownloadWakeWord else btnDownloadKokoro
        val badge = if (modelId == ModelDownloadManager.MODEL_WAKEWORD) badgeWakeWord else badgeKokoro

        progressBar.visibility = View.VISIBLE
        progressBar.progress = 0
        button.isEnabled = false
        badge.text = "DOWNLOADING…"
        badge.setTextColor(Color.parseColor("#F59E0B"))

        ModelDownloadManager.downloadModel(this, modelId, object : ModelDownloadManager.DownloadListener {
            override fun onProgress(modelId: String, progressPercent: Int, bytesRead: Long, totalBytes: Long) {
                mainHandler.post {
                    if (progressPercent >= 0) {
                        progressBar.progress = progressPercent
                        badge.text = "$progressPercent%"
                    }
                }
            }

            override fun onSuccess(modelId: String) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    button.isEnabled = true
                    refreshStatus()
                    Toast.makeText(this@ModelHubActivity, "Model $modelId downloaded successfully!", Toast.LENGTH_SHORT).show()
                    onDone?.invoke()
                }
            }

            override fun onError(modelId: String, error: String) {
                mainHandler.post {
                    progressBar.visibility = View.GONE
                    button.isEnabled = true
                    refreshStatus()
                    Toast.makeText(this@ModelHubActivity, "Download failed: $error", Toast.LENGTH_LONG).show()
                }
            }
        })
    }

    private fun refreshStatus() {
        // Wake word status
        val wwInstalled = ModelDownloadManager.isWakeWordInstalled(this)
        val wwSize = ModelDownloadManager.getModelDiskSize(this, ModelDownloadManager.MODEL_WAKEWORD)
        if (wwInstalled) {
            badgeWakeWord.text = "INSTALLED ✓"
            badgeWakeWord.setTextColor(Color.parseColor("#10B981"))
            btnDownloadWakeWord.text = "Reinstall"
            txtWakeWordSize.text = "Installed: %.2f MB".format(wwSize / (1024.0 * 1024.0))
        } else {
            badgeWakeWord.text = "NEEDS DOWNLOAD"
            badgeWakeWord.setTextColor(Color.parseColor("#EF4444"))
            btnDownloadWakeWord.text = "Download"
            txtWakeWordSize.text = "Required size: ~3.5 MB"
        }

        // Kokoro status
        val kokoroInstalled = ModelDownloadManager.isKokoroTtsInstalled(this)
        val kokoroSize = ModelDownloadManager.getModelDiskSize(this, ModelDownloadManager.MODEL_KOKORO_TTS)
        if (kokoroInstalled) {
            badgeKokoro.text = "INSTALLED ✓"
            badgeKokoro.setTextColor(Color.parseColor("#10B981"))
            btnDownloadKokoro.text = "Reinstall"
            txtKokoroSize.text = "Installed: %.1f MB".format(kokoroSize / (1024.0 * 1024.0))
        } else {
            badgeKokoro.text = "NEEDS DOWNLOAD"
            badgeKokoro.setTextColor(Color.parseColor("#EF4444"))
            btnDownloadKokoro.text = "Download"
            txtKokoroSize.text = "Required size: ~98 MB"
        }

        // Cloud LLM & Storage status
        val cloudConfigured = com.pr4nav.jarvis.llm.GeminiCloudLLM.isConfigured(this)
        val cloudModel = com.pr4nav.jarvis.llm.GeminiCloudLLM.getModel(this)
        badgeQwen.text = if (cloudConfigured) "CONFIGURED ✓" else "CLOUD MANAGED"
        badgeQwen.setTextColor(Color.parseColor(if (cloudConfigured) "#10B981" else "#38BDF8"))
        btnDownloadQwen.text = "Delete Local Models"
        btnVerifyQwen.text = "Test Cloud"
        txtQwenDetails.text = "Cloud Model: $cloudModel · Direct Command Access: ENABLED"
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine?.destroy()
    }
}
