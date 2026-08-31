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

    private lateinit var btnDownloadAll: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_hub)

        voiceEngine = JarvisVoiceEngine(this)

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
    }

    override fun onDestroy() {
        super.onDestroy()
        voiceEngine?.destroy()
    }
}
