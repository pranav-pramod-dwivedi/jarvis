package com.pr4nav.jarvis

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.agy.AgyManager
import com.pr4nav.jarvis.diagnostics.ToolAuditRunner
import com.pr4nav.jarvis.llm.QwenLocalLLM
import com.pr4nav.jarvis.needle.NeedleRuntime
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import com.pr4nav.jarvis.voice.KokoroTtsEngine
import org.json.JSONObject
import kotlin.concurrent.thread

class ModelTestLabActivity : AppCompatActivity() {

    private lateinit var txtNeedleStatus: TextView
    private lateinit var txtQwenStatus: TextView
    private lateinit var txtAgyStatus: TextView
    private lateinit var spinnerEngine: Spinner
    private lateinit var editPromptInput: EditText
    private lateinit var btnRunTest: Button
    private lateinit var txtPipelineInspector: TextView
    private lateinit var editTtsInput: EditText
    private lateinit var btnTtsBenchmark: Button
    private lateinit var btnTtsStop: Button
    private lateinit var txtTtsResults: TextView
    private lateinit var btnRunToolAudit: Button
    private lateinit var txtToolAuditResults: TextView

    private lateinit var ttsEngine: KokoroTtsEngine
    private lateinit var qwenLlm: QwenLocalLLM

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_test_lab)

        ttsEngine = KokoroTtsEngine(this)
        qwenLlm = QwenLocalLLM(this)

        txtNeedleStatus = findViewById(R.id.txt_needle_status)
        txtQwenStatus = findViewById(R.id.txt_qwen_status)
        txtAgyStatus = findViewById(R.id.txt_agy_status)
        spinnerEngine = findViewById(R.id.spinner_engine)
        editPromptInput = findViewById(R.id.edit_prompt_input)
        btnRunTest = findViewById(R.id.btn_run_test)
        txtPipelineInspector = findViewById(R.id.txt_pipeline_inspector)
        editTtsInput = findViewById(R.id.edit_tts_input)
        btnTtsBenchmark = findViewById(R.id.btn_tts_benchmark)
        btnTtsStop = findViewById(R.id.btn_tts_stop)
        txtTtsResults = findViewById(R.id.txt_tts_benchmark_results)
        btnRunToolAudit = findViewById(R.id.btn_run_tool_audit)
        txtToolAuditResults = findViewById(R.id.txt_tool_audit_results)

        findViewById<ImageButton>(R.id.btn_back).setOnClickListener { finish() }
        findViewById<Button>(R.id.btn_refresh_status).setOnClickListener { refreshEngineStatus() }

        // Setup Engine Dropdown
        val engines = listOf(
            "⚡ Auto Router (Deterministic -> Local SLM -> AGY)",
            "⚡ Needle 2 Reflex (Fast Path)",
            "🟢 Local Qwen3.5-2B (On-Device SLM)",
            "🤖 AGY Autonomous Agent (PRoot Linux)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, engines)
        spinnerEngine.adapter = adapter

        // Setup Quick Chips
        findViewById<Button>(R.id.chip_torch).setOnClickListener { editPromptInput.setText("Turn on flashlight") }
        findViewById<Button>(R.id.chip_torch_hinglish).setOnClickListener { editPromptInput.setText("Torch chalu kar") }
        findViewById<Button>(R.id.chip_math).setOnClickListener { editPromptInput.setText("What is 2 + 2?") }
        findViewById<Button>(R.id.chip_reasoning).setOnClickListener { editPromptInput.setText("Explain why this Kotlin code throws NullPointerException") }

        // Setup Test Buttons
        btnRunTest.setOnClickListener { runModelPipelineTest() }
        btnTtsBenchmark.setOnClickListener { runTtsBenchmark() }
        btnTtsStop.setOnClickListener { ttsEngine.stop(); txtTtsResults.text = "TTS Playback stopped." }
        btnRunToolAudit.setOnClickListener { runToolAudit() }

        refreshEngineStatus()
    }

    private fun refreshEngineStatus() {
        txtNeedleStatus.text = "Needle: Checking..."
        txtQwenStatus.text = "Qwen: Checking..."
        txtAgyStatus.text = "AGY Agent: Checking..."

        thread {
            // 1. Needle
            val needleLoaded = NeedleRuntime.isModelLoaded || NeedleRuntime.isRuntimeAvailable
            val needleStatusText = if (needleLoaded) "READY (Active & Loaded)" else "READY (Deterministic Grammar)"

            // 2. Qwen
            val qwenStatus = qwenLlm.status()
            val qwenInstalled = qwenLlm.isAvailable()
            val qwenText = if (qwenInstalled) "READY (${qwenStatus.modelName} · ${qwenStatus.memoryUsageMb}MB)" else "NOT LOADED (Download via Model Hub)"

            // 3. AGY
            val agyRep = AgyManager.checkStatus(6_000)
            val agyText = if (agyRep.isBinaryInstalled) "State: ${agyRep.state.name} | Port 5050: ${if (agyRep.isPortListening) "OPEN" else "STANDBY"} | Model: ${agyRep.activeModel}" else "NOT INSTALLED in PRoot"

            runOnUiThread {
                txtNeedleStatus.text = "Needle: $needleStatusText"
                txtQwenStatus.text = "Qwen3.5-2B: $qwenText"
                txtAgyStatus.text = "AGY Agent: $agyText"
            }
        }
    }

    private fun runModelPipelineTest() {
        val input = editPromptInput.text.toString().trim()
        if (input.isEmpty()) {
            txtPipelineInspector.text = "Please enter a test prompt above."
            return
        }

        val selectedPos = spinnerEngine.selectedItemPosition
        txtPipelineInspector.text = "Running inference on selected engine...\nInput: \"$input\""

        thread {
            val t0 = System.currentTimeMillis()
            val sb = StringBuilder()
            sb.append("=========================================\n")
            sb.append("         INSPECTOR PIPELINE TRACE        \n")
            sb.append("=========================================\n")
            sb.append("INPUT: \"$input\"\n")

            when (selectedPos) {
                0 -> { // Auto Router
                    sb.append("SELECTED ENGINE: Auto Router\n\n")
                    CanonicalToolRegistry.init(this@ModelTestLabActivity)
                    val isInfo = LanguageNormalizer.isInformational(input)
                    val norm = if (!isInfo) LanguageNormalizer.normalize(input) else null

                    if (norm != null) {
                        sb.append("ROUTING DECISION:\n")
                        sb.append("• Match: Deterministic Rule\n")
                        sb.append("• Intent / Tool: [${norm.tool}]\n")
                        sb.append("• Args: ${norm.args}\n")
                        sb.append("• Confidence: ${norm.confidence}\n")
                        sb.append("• Cloud Escalation: NO (Registered native Android intent matched)\n\n")

                        val valRes = ToolValidator.validate(this@ModelTestLabActivity, norm.tool, norm.args)
                        sb.append("VALIDATION: ${if (valRes is ValidationResult.Valid) "PASS" else "FAIL"}\n")

                        val execRes = CanonicalToolRegistry.execute(this@ModelTestLabActivity, norm.tool, norm.args)
                        val latency = System.currentTimeMillis() - t0
                        sb.append("EXECUTION RESULT: ${if (execRes.success) "SUCCESS" else "FAILURE"}\n")
                        sb.append("DATA: ${execRes.data}\n")
                        sb.append("LATENCY: ${latency}ms\n")
                    } else {
                        sb.append("ROUTING DECISION:\n")
                        sb.append("• Deterministic Match: None\n")
                        sb.append("• Escalation: Primary Intelligence (AGY Agent / Cloud Fallback)\n\n")
                        val agyRes = Shell.agy(input, timeoutMs = 25_000)
                        val latency = System.currentTimeMillis() - t0
                        sb.append("AGY RESPONSE: ${agyRes.out.trim().take(150)}\n")
                        sb.append("EXIT CODE: ${agyRes.rc}\n")
                        sb.append("LATENCY: ${latency}ms\n")
                    }
                }
                1 -> { // Needle 2 Reflex
                    sb.append("SELECTED ENGINE: Needle 2 Reflex\n\n")
                    val norm = LanguageNormalizer.normalize(input)
                    val latency = System.currentTimeMillis() - t0
                    if (norm != null) {
                        sb.append("INTENT: [${norm.tool}]\n")
                        sb.append("PARAMETERS: ${norm.args}\n")
                        sb.append("CONFIDENCE: ${norm.confidence}\n")
                        sb.append("LATENCY: ${latency}ms\n")
                    } else {
                        val envelope = NeedleRuntime.complete(input)
                        sb.append("INTENT: ${envelope?.functionCalls?.firstOrNull()?.name ?: "NONE"}\n")
                        sb.append("CONFIDENCE: ${envelope?.confidence ?: 0.0}\n")
                        sb.append("LATENCY: ${latency}ms\n")
                    }
                }
                2 -> { // Local Qwen3.5-2B
                    sb.append("SELECTED ENGINE: 🟢 Local Qwen3.5-2B\n\n")
                    val res = qwenLlm.generate(input, 5_000L).get()
                    val latency = System.currentTimeMillis() - t0
                    sb.append("EXTRACTED INTENT: ${res.toolCall ?: "NONE"}\n")
                    sb.append("PARAMETERS: ${res.args ?: "{}"}\n")
                    sb.append("CONFIDENCE: ${res.confidence}\n")
                    sb.append("RAW JSON: ${res.rawText}\n")
                    sb.append("LATENCY: ${latency}ms\n")
                }
                3 -> { // AGY Agent
                    sb.append("SELECTED ENGINE: 🤖 AGY Agent (PRoot Linux)\n\n")
                    val agyRes = Shell.agy(input, timeoutMs = 30_000)
                    val latency = System.currentTimeMillis() - t0
                    sb.append("RAW OUTPUT:\n${agyRes.out.trim()}\n")
                    sb.append("ERROR / STDERR: ${agyRes.err.ifBlank { "None" }}\n")
                    sb.append("EXIT CODE: ${agyRes.rc}\n")
                    sb.append("LATENCY: ${latency}ms\n")
                }
            }

            sb.append("=========================================")
            runOnUiThread {
                txtPipelineInspector.text = sb.toString()
            }
        }
    }

    private fun runTtsBenchmark() {
        val text = editTtsInput.text.toString().trim()
        if (text.isEmpty()) {
            txtTtsResults.text = "Please enter text for TTS."
            return
        }

        txtTtsResults.text = "Running TTS Benchmark (Synthesizing & Playing)…"
        ttsEngine.benchmark(text).thenAccept { bm ->
            runOnUiThread {
                txtTtsResults.text = buildString {
                    append("TTS BENCHMARK COMPLETED:\n")
                    append("• Time to First Audio: ${bm.timeToFirstAudioMs} ms\n")
                    append("• Total Synthesis Time: ${bm.totalGenerationTimeMs} ms\n")
                    append("• Audio Duration: ${String.format("%.2f", bm.audioDurationSec)} sec\n")
                    append("• Real-Time Factor (RTF): ${String.format("%.2f", bm.realTimeFactor)}x\n")
                    append("• Buffer Underruns: ${bm.underruns}\n")
                    append("• Audio Cutoffs: ${bm.cutoffs}\n")
                }
            }
        }
    }

    private fun runToolAudit() {
        txtToolAuditResults.text = "Running Batched Real-World Tool Audit (10 Batches)…"
        thread {
            val report = ToolAuditRunner.runFullAudit(this@ModelTestLabActivity)
            val sb = StringBuilder()
            sb.append("=========================================\n")
            sb.append("     REAL-WORLD TOOL AUDIT REPORT        \n")
            sb.append("=========================================\n")
            sb.append("TOTAL TOOLS AUDITED: ${report.totalAudited}\n")
            sb.append("✅ WORKING: ${report.working}\n")
            sb.append("❌ BROKEN: ${report.broken}\n")
            sb.append("⚠️ NEEDS PERMISSION: ${report.needsPermission}\n")
            sb.append("○ UNAVAILABLE: ${report.unavailable}\n\n")

            for (batch in report.batches) {
                sb.append("--- ${batch.batchName} (${batch.workingCount}/${batch.totalTools} Working) ---\n")
                for (item in batch.items) {
                    val icon = when (item.status) {
                        "WORKING" -> "✅"
                        "NEEDS_PERMISSION" -> "⚠️"
                        "UNAVAILABLE" -> "○"
                        else -> "❌"
                    }
                    sb.append("$icon ${item.toolName} [${item.backend}] — ${item.details} (${item.latencyMs}ms)\n")
                }
                sb.append("\n")
            }

            runOnUiThread {
                txtToolAuditResults.text = sb.toString()
            }
        }
    }
}
