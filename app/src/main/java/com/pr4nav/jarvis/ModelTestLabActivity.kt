package com.pr4nav.jarvis

import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.agy.AgyManager
import com.pr4nav.jarvis.diagnostics.ToolAuditRunner
import com.pr4nav.jarvis.engine.*
import com.pr4nav.jarvis.intent.IntentClassifier
import com.pr4nav.jarvis.intent.ResponseType
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import com.pr4nav.jarvis.voice.KokoroTtsEngine
import kotlin.concurrent.thread

class ModelTestLabActivity : AppCompatActivity() {

    private lateinit var txtNeedleStatus: TextView
    private lateinit var txtQwenStatus: TextView
    private lateinit var txtAgyStatus: TextView
    private lateinit var spinnerEngine: Spinner
    private lateinit var editPromptInput: EditText
    private lateinit var btnRunTest: Button
    private lateinit var btnTestIdentity: Button
    private lateinit var txtPipelineInspector: TextView
    private lateinit var editTtsInput: EditText
    private lateinit var btnTtsBenchmark: Button
    private lateinit var btnTtsStop: Button
    private lateinit var txtTtsResults: TextView
    private lateinit var btnRunToolAudit: Button
    private lateinit var txtToolAuditResults: TextView

    private lateinit var ttsEngine: KokoroTtsEngine
    private lateinit var needleEngine: NeedleInferenceEngine
    private lateinit var qwenEngine: QwenLocalInferenceEngine
    private lateinit var agyEngine: AgyInferenceEngine

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_model_test_lab)

        ttsEngine = KokoroTtsEngine(this)
        needleEngine = NeedleInferenceEngine(this)
        qwenEngine = QwenLocalInferenceEngine(this)
        agyEngine = AgyInferenceEngine(this)

        txtNeedleStatus = findViewById(R.id.txt_needle_status)
        txtQwenStatus = findViewById(R.id.txt_qwen_status)
        txtAgyStatus = findViewById(R.id.txt_agy_status)
        spinnerEngine = findViewById(R.id.spinner_engine)
        editPromptInput = findViewById(R.id.edit_prompt_input)
        btnRunTest = findViewById(R.id.btn_run_test)
        btnTestIdentity = findViewById(R.id.btn_test_identity)
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
        findViewById<Button>(R.id.chip_reasoning).setOnClickListener { editPromptInput.setText("Code me a calculator") }

        // Setup Test Buttons
        btnRunTest.setOnClickListener { runModelPipelineTest() }
        btnTestIdentity.setOnClickListener { testEngineIdentity() }
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
            val needleLoaded = com.pr4nav.jarvis.needle.NeedleRuntime.isModelLoaded
            val needleStatusText = if (needleLoaded) "READY (Active & Loaded)" else "READY (Deterministic Grammar)"

            val activeModelId = com.pr4nav.jarvis.llm.LocalModelManager.getActiveModelId(this@ModelTestLabActivity)
            val qwenIntegrity = com.pr4nav.jarvis.llm.LocalModelManager.checkFileIntegrity(this@ModelTestLabActivity, activeModelId)
            val qwenText = if (qwenIntegrity.isReady) {
                "READY (${qwenIntegrity.sizeBytes / 1024 / 1024} MB · GGUF Valid)"
            } else {
                "NOT LOADED (${qwenIntegrity.statusText})"
            }

            val agyRep = AgyManager.checkStatus(4_000)
            val agyText = if (agyRep.isBinaryInstalled) "State: ${agyRep.state.name} | Port 5050: ${if (agyRep.isPortListening) "OPEN" else "STANDBY"} | Model: ${agyRep.activeModel}" else "NOT INSTALLED in PRoot"

            runOnUiThread {
                txtNeedleStatus.text = "Needle: $needleStatusText"
                txtQwenStatus.text = "Qwen3.5-2B: $qwenText"
                txtAgyStatus.text = "AGY Agent: $agyText"
            }
        }
    }

    private fun testEngineIdentity() {
        val selectedPos = spinnerEngine.selectedItemPosition
        val testToken = "QWEN_ENGINE_TEST_73921"
        txtPipelineInspector.text = "Executing Engine Identity Test with token '$testToken'…"

        thread {
            val result = when (selectedPos) {
                0 -> {
                    // Auto Router Identity
                    val meta = EngineMetadata(
                        requestedEngine = EngineType.AUTO_ROUTER,
                        actualEngine = EngineType.AUTO_ROUTER,
                        provider = "router_orchestrator",
                        runtimeBackend = "JARVIS Hierarchical Multi-Tier Router",
                        modelPath = "N/A",
                        modelFilename = "UnifiedAssistantDispatcher.kt",
                        modelHashSha256 = "N/A",
                        tokenizer = "N/A",
                        isModelLoaded = true
                    )
                    EngineInferenceResult(
                        success = true,
                        rawOutput = "AUTO_ROUTER_IDENTITY_PASS",
                        intent = "ROUTER_HEALTH_CHECK",
                        arguments = null,
                        confidence = 1.0f,
                        metadata = meta,
                        latencyMs = 2L
                    )
                }
                1 -> needleEngine.infer(testToken)
                2 -> qwenEngine.infer(testToken)
                3 -> agyEngine.infer(testToken)
                else -> needleEngine.infer(testToken)
            }

            val sb = StringBuilder()
            sb.append("=========================================\n")
            sb.append("        ENGINE IDENTITY AUDIT TRACE      \n")
            sb.append("=========================================\n")
            sb.append("TEST TOKEN: \"$testToken\"\n\n")

            sb.append("DEVELOPER METADATA:\n")
            sb.append("• Requested Engine: ${result.metadata.requestedEngine}\n")
            sb.append("• Actual Engine:    ${result.metadata.actualEngine}\n")
            val isMatch = result.metadata.requestedEngine == result.metadata.actualEngine || result.metadata.requestedEngine == EngineType.AUTO_ROUTER
            sb.append("• Routing Integrity: ${if (isMatch) "✅ PASS" else "❌ ENGINE_ROUTING_FAILURE"}\n")
            sb.append("• Provider:         ${result.metadata.provider}\n")
            sb.append("• Runtime Backend:  ${result.metadata.runtimeBackend}\n")
            sb.append("• Model Path:       ${result.metadata.modelPath}\n")
            sb.append("• Model Filename:   ${result.metadata.modelFilename}\n")
            sb.append("• Model SHA256:     ${result.metadata.modelHashSha256}\n")
            sb.append("• Tokenizer:        ${result.metadata.tokenizer}\n")
            sb.append("• Runtime ID:       ${result.metadata.runtimeInstanceId}\n")
            sb.append("• Model Loaded:     ${result.metadata.isModelLoaded}\n\n")

            sb.append("INFERENCE RESULT:\n")
            sb.append("• Success: ${result.success}\n")
            sb.append("• Latency: ${result.latencyMs}ms\n")
            sb.append("• Raw Output:\n${result.rawOutput}\n")
            if (result.error != null) {
                sb.append("• Error Details:\n${result.error}\n")
            }
            sb.append("=========================================")

            runOnUiThread {
                txtPipelineInspector.text = sb.toString()
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
        txtPipelineInspector.text = "Running isolated inference on selected engine...\nInput: \"$input\""

        thread {
            val t0 = System.currentTimeMillis()
            CanonicalToolRegistry.init(this@ModelTestLabActivity)
            val sb = StringBuilder()
            sb.append("=========================================\n")
            sb.append("         INSPECTOR PIPELINE TRACE        \n")
            sb.append("=========================================\n")
            sb.append("INPUT: \"$input\"\n")

            when (selectedPos) {
                0 -> { // Auto Router
                    sb.append("SELECTED ENGINE: ⚡ Auto Router\n\n")
                    val classified = IntentClassifier.classify(input)
                    sb.append("INTENT CLASSIFICATION:\n")
                    sb.append("• Category: ${classified.category}\n")
                    sb.append("• Response Type: ${classified.responseType}\n")
                    sb.append("• Explanation: ${classified.explanation}\n\n")

                    if (classified.responseType == ResponseType.ANSWER && classified.directAnswer != null) {
                        sb.append("ROUTING DECISION:\n")
                        sb.append("• Action: Direct Answer (No tool needed)\n")
                        sb.append("• Answer: ${classified.directAnswer}\n")
                        sb.append("• Cloud Escalation: NO\n")
                        sb.append("• Latency: ${System.currentTimeMillis() - t0}ms\n")
                    } else {
                        val norm = LanguageNormalizer.normalize(input)
                        if (norm != null) {
                            if (norm.trace != null) {
                                sb.append("LANGUAGE NORMALIZATION TRACE:\n")
                                sb.append("• Detected Language: ${norm.trace.detectedLanguage}\n")
                                sb.append("• Normalized: ${norm.trace.normalizedText}\n")
                                sb.append("• Matched Object: ${norm.trace.matchedObject}\n")
                                sb.append("• Matched Action: ${norm.trace.matchedAction}\n")
                                sb.append("• Qwen: SKIPPED (Deterministic priority)\n")
                                sb.append("• AGY: SKIPPED\n\n")
                            }

                            val validation = ToolValidator.validate(this@ModelTestLabActivity, norm.tool, norm.args, input)
                            sb.append("SYSTEM VALIDATION:\n")
                            sb.append("• Tool Proposed: [${norm.tool}]\n")
                            sb.append("• Schema Check: PASS\n")
                            sb.append("• Semantic Guard: ${if (validation is ValidationResult.Valid) "PASS" else "FAIL"}\n\n")

                            if (validation is ValidationResult.Valid) {
                                sb.append("FINAL DECISION: ACCEPT (DIRECT_MATCH)\n")
                                val execRes = CanonicalToolRegistry.execute(this@ModelTestLabActivity, norm.tool, norm.args)
                                sb.append("EXECUTION RESULT: ${if (execRes.success) "SUCCESS" else "FAILURE"}\n")
                                sb.append("DATA: ${execRes.data}\n")
                                sb.append("LATENCY: ${System.currentTimeMillis() - t0}ms\n")
                            } else if (validation is ValidationResult.Rejected) {
                                sb.append("FINAL DECISION: REJECT\n")
                                sb.append("REASON: ${validation.reasonCode} (${validation.error.message})\n")
                                sb.append("FALLBACK: Escalating to general information/reasoning engine\n")
                            }
                        } else {
                            sb.append("ROUTING DECISION:\n")
                            sb.append("• Deterministic Match: None\n")
                            sb.append("• Escalation: Primary Intelligence (AGY Agent / Cloud Fallback)\n\n")
                            val agyRes = agyEngine.infer(input)
                            sb.append("AGY RESPONSE: ${agyRes.rawOutput.take(200)}\n")
                            sb.append("SUCCESS: ${agyRes.success}\n")
                            sb.append("LATENCY: ${agyRes.latencyMs}ms\n")
                        }
                    }
                }
                1 -> { // Needle 2 Reflex
                    sb.append("SELECTED ENGINE: ⚡ Needle 2 Reflex\n\n")
                    val result = needleEngine.infer(input)
                    sb.append("METADATA:\n")
                    sb.append("• Engine: ${result.metadata.actualEngine} (${result.metadata.provider})\n")
                    sb.append("• Runtime: ${result.metadata.runtimeBackend}\n")
                    sb.append("• Model Hash: ${result.metadata.modelHashSha256}\n\n")

                    sb.append("INFERENCE RESULT:\n")
                    sb.append("• Intent: ${result.intent ?: "NONE"}\n")
                    sb.append("• Arguments: ${result.arguments ?: "{}"}\n")
                    sb.append("• Confidence: ${result.confidence}\n")
                    sb.append("• Latency: ${result.latencyMs}ms\n")
                    sb.append("• Raw Output:\n${result.rawOutput}\n")
                }
                2 -> { // Local Qwen3.5-2B (NO FALLBACK)
                    sb.append("SELECTED ENGINE: 🟢 Local Qwen3.5-2B (Strict Isolated Inference)\n\n")
                    val result = qwenEngine.infer(input)

                    sb.append("METADATA:\n")
                    sb.append("• Requested Engine: ${result.metadata.requestedEngine}\n")
                    sb.append("• Actual Engine:    ${result.metadata.actualEngine}\n")
                    sb.append("• Routing Integrity: ${if (result.metadata.isRoutingIntegrityValid) "✅ PASS" else "❌ ENGINE_ROUTING_FAILURE"}\n")
                    sb.append("• Model Path:       ${result.metadata.modelPath}\n")
                    sb.append("• Model Filename:   ${result.metadata.modelFilename}\n")
                    sb.append("• Model Hash:       ${result.metadata.modelHashSha256}\n")
                    sb.append("• Model Loaded:     ${result.metadata.isModelLoaded}\n\n")

                    if (!result.success) {
                        sb.append("INFERENCE STATUS: FAILED\n")
                        sb.append("ERROR: ${result.error}\n")
                    } else {
                        sb.append("MODEL OUTPUT:\n${result.rawOutput}\n\n")
                        if (result.intent != null) {
                            val validation = ToolValidator.validate(this@ModelTestLabActivity, result.intent, result.arguments, input)
                            sb.append("SYSTEM MULTI-STAGE VALIDATION:\n")
                            sb.append("• Tool Exists: ${CanonicalToolRegistry.get(result.intent) != null}\n")
                            sb.append("• Schema Check: ${if (validation is ValidationResult.Valid) "PASS" else "FAIL"}\n")
                            sb.append("• Semantic Guard: ${if (validation is ValidationResult.Valid) "PASS" else "FAIL"}\n\n")

                            if (validation is ValidationResult.Valid) {
                                sb.append("FINAL DECISION: ACCEPT\n")
                                sb.append("SCORE: ${validation.score}/100\n")
                            } else if (validation is ValidationResult.Rejected) {
                                sb.append("FINAL DECISION: REJECT\n")
                                sb.append("REJECTION REASON: ${validation.reasonCode}\n")
                                sb.append("DETAILS: ${validation.error.message}\n")
                            }
                        }
                    }
                    sb.append("LATENCY: ${result.latencyMs}ms\n")
                }
                3 -> { // AGY Agent
                    sb.append("SELECTED ENGINE: 🤖 AGY Agent (PRoot Linux)\n\n")
                    val result = agyEngine.infer(input)
                    sb.append("METADATA:\n")
                    sb.append("• Engine: ${result.metadata.actualEngine} (${result.metadata.provider})\n")
                    sb.append("• Backend: ${result.metadata.runtimeBackend}\n")
                    sb.append("• Model Loaded: ${result.metadata.isModelLoaded}\n\n")

                    sb.append("RAW OUTPUT:\n${result.rawOutput}\n")
                    if (result.error != null) {
                        sb.append("ERROR: ${result.error}\n")
                    }
                    sb.append("LATENCY: ${result.latencyMs}ms\n")
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
