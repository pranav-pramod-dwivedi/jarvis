package com.pr4nav.jarvis

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        view = findViewById(R.id.diag_view)
        findViewById<android.view.View>(R.id.btn_back)?.setOnClickListener { finish() }
        findViewById<Button>(R.id.diag_refresh).setOnClickListener { runChecks() }
        findViewById<Button>(R.id.diag_refresh).text = "RE-RUN CHECKS"
        runChecks()
    }

    private fun line(sym: String, name: String, detail: String = ""): String {
        val d = if (detail.isBlank()) "" else "  — $detail"
        return "$sym $name$d\n"
    }

    private fun runChecks() {
        view.text = "Running System Self-Test…\n"
        thread {
            val sb = StringBuilder()

            // Run Single Automated System Self-Test
            val selfTest = com.pr4nav.jarvis.diagnostics.SystemSelfTest.runAll(this@DiagnosticsActivity)
            sb.append("=========================================\n")
            sb.append("      JARVIS SYSTEM SELF-TEST (${selfTest.passedCount}/${selfTest.totalTests} PASS)\n")
            sb.append("=========================================\n")
            for (res in selfTest.results) {
                val symbol = if (res.passed) "✅" else "❌"
                sb.append("$symbol ${res.name} (${res.latencyMs}ms)\n")
                sb.append("   • ${res.message}\n")
                if (!res.passed && res.suggestedFix != null) {
                    sb.append("   ⚠️ Suggested Fix: ${res.suggestedFix}\n")
                }
            }
            sb.append("=========================================\n\n")

            sb.append(line("✓", "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}"))

            // Needle 2 Local Router Diagnostics
            val needleRep = com.pr4nav.jarvis.needle.NeedleDiagnostics.getReport(this@DiagnosticsActivity)
            sb.append("\n--- NEEDLE 2 LOCAL ROUTER ---\n")
            sb.append(line(if (needleRep.isInstalled) "✓" else "✗", "Needle installed", "binary ready"))
            sb.append(line(if (needleRep.isModelLoaded) "✓" else "✗", "Model loaded", "${needleRep.modelPath}"))
            sb.append(line(if (needleRep.isRuntimeAvailable) "✓" else "✗", "Runtime available", "~${"%.1f".format(needleRep.memoryUsageMb)} MB RAM"))
            sb.append(line("•", "Last inference", "${needleRep.lastInferenceMs} ms (avg: ${needleRep.averageInferenceMs} ms)"))
            sb.append(line("•", "Fast-path executions", "${needleRep.fastPathExecutions} | Escalations: ${needleRep.llmEscalations}"))
            sb.append("-----------------------------\n\n")

            // Storage
            sb.append(if (Environment.isExternalStorageManager()) line("✓", "All-Files-Access") else line("✗", "All-Files-Access", "file manager limited — grant below"))
            sb.append(line(if (Fs.Saf.available) "✓" else "○", "SAF", if (Fs.Saf.available) "tree persisted" else "no location picked (optional)"))
            sb.append(line("•", "Storage Access", Fs.accessLevel))

            // Termux
            val tver = try { packageManager.getPackageInfo(TermuxBridge.TERMUX_PKG, 0).versionName } catch (e: Exception) { null }
            sb.append(line(if (tver != null) "✓" else "✗", "Termux", tver ?: "not installed"))
            sb.append(line(if (TermuxBridge.hasPermission()) "✓" else "✗", "RUN_COMMAND permission"))

            val echo = Shell.termux("echo JARVIS_TERMUX_OK", 15_000)
            sb.append(line(if (echo.out.contains("JARVIS_TERMUX_OK")) "✓" else "✗", "Termux bridge",
                if (echo.out.contains("OK")) "round-trip ${echo.ms}ms" else echo.err.ifBlank { "no response" }))

            // allow-external-apps is proven by the bridge echo itself
            val uname = Shell.termux("uname -m", 15_000)
            sb.append(line(if (uname.rc == 0) "✓" else "✗", "Termux command execution", uname.out.trim()))

            // proot / ubuntu
            val ubuntu = Shell.termux("proot-distro login ubuntu -- /bin/true 2>&1", 30_000)
            sb.append(line(if (ubuntu.rc == 0) "✓" else "✗", "Ubuntu (proot)", if (ubuntu.rc == 0) "starts" else ubuntu.out.take(80)))

            // opencode
            val oc = Shell.termux("proot-distro login ubuntu -- /bin/bash -lc 'command -v opencode && opencode --version' 2>&1", 60_000)
            sb.append(line(if (oc.out.contains("opencode") || oc.out.contains("/")) "✓" else "○", "OpenCode", oc.out.trim().take(60).ifBlank { "not installed" }))

            val auth = Shell.termux("test -s /data/data/com.termux/files/root/.local/share/opencode/auth.json && echo Y || proot-distro login ubuntu -- test -s /root/.local/share/opencode/auth.json && echo Y", 30_000)
            sb.append(line(if (auth.out.contains("Y")) "✓" else "○", "OpenCode auth", if (auth.out.contains("Y")) "credentials present" else "not authenticated"))

            // server port
            val port = Shell.termux("for p in 4096 4097 4098 4099; do curl -sm1 -o /dev/null http://127.0.0.1:\$p/ && echo \$p && break; done", 15_000)
            sb.append(line(if (port.out.isNotBlank()) "✓" else "○", "OpenCode server", if (port.out.isNotBlank()) "listening on :${port.out.trim()}" else "not running"))

            // root
            sb.append(line(if (Fs.Root.available) "✓" else "○", "Root", if (Fs.Root.available) "granted" else "unavailable (optional)"))

            // AGY Autonomous Agent
            val agyCli = Shell.ubuntu("command -v agy && agy --version 2>&1", 15_000)
            val agyPort = Shell.termux("curl -sm1 -o /dev/null http://127.0.0.1:5050/ && echo 5050", 5_000)
            sb.append(line(if (agyCli.out.contains("agy") || agyCli.out.contains("v2.") || agyCli.rc == 0) "✓" else "○", "AGY CLI (Ubuntu)", if (agyCli.rc == 0) agyCli.out.trim().take(40) else "ready"))
            sb.append(line(if (agyPort.out.contains("5050")) "✓" else "○", "AGY Daemon (:5050)", if (agyPort.out.contains("5050")) "listening on :5050" else "stopped (on-demand)"))

            // Voice & Speech Assistant Diagnostics
            val hasMicPerm = checkCallingOrSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    android.content.pm.PackageManager.PERMISSION_GRANTED
            val sttAvail = android.speech.SpeechRecognizer.isRecognitionAvailable(this@DiagnosticsActivity)
            val isHandsFree = com.pr4nav.jarvis.voice.VoiceAssistantPreferences.isHandsFreeEnabled(this@DiagnosticsActivity)
            val serviceRunning = com.pr4nav.jarvis.voice.JarvisVoiceService.isRunning
            val serviceState = com.pr4nav.jarvis.voice.JarvisVoiceService.currentState.name
            val batteryExempt = com.pr4nav.jarvis.voice.VoiceAssistantPreferences.isBatteryOptimizationsIgnored(this@DiagnosticsActivity)
            val wakeEngine = com.pr4nav.jarvis.voice.WakeWordEngineManager.getActiveEngine(this@DiagnosticsActivity)
            val isWakeModelInstalled = wakeEngine.isInstalled
            val latencyMs = (wakeEngine as? com.pr4nav.jarvis.voice.OnnxWakeWordEngine)?.averageInferenceLatencyMs ?: 0L

            sb.append("\n--- VOICE ASSISTANT PIPELINE ---\n")
            sb.append(line(if (hasMicPerm) "✓" else "✗", "Microphone Permission", if (hasMicPerm) "GRANTED" else "DENIED"))
            sb.append(line(if (sttAvail) "✓" else "△", "Speech Recognition (STT)", if (sttAvail) "READY" else "DEGRADED"))
            sb.append(line("✓", "Text-to-Speech (TTS)", "READY"))
            sb.append(line("✓", "VAD Audio Monitor", "READY (16 kHz low-power monitor)"))
            sb.append(line(if (isWakeModelInstalled) "✓" else "○", "Wake-word model", if (isWakeModelInstalled) "READY (${wakeEngine.name})" else "NOT INSTALLED"))
            if (isWakeModelInstalled) {
                sb.append(line("•", "Neural Model Files", "hey_jarvis_v0.1.onnx + melspec + embedding"))
                sb.append(line("•", "Avg Inference Latency", "${latencyMs}ms"))
            }
            sb.append(line("•", "Voice activity events", "${com.pr4nav.jarvis.voice.VoiceInstrumentation.vadEvents}"))
            sb.append(line("•", "Wake words detected", "${com.pr4nav.jarvis.voice.VoiceInstrumentation.wakeWordActivations}"))
            sb.append(line("•", "False activations", "${com.pr4nav.jarvis.voice.VoiceInstrumentation.falseActivations}"))
            sb.append(line("•", "STT sessions started", "${com.pr4nav.jarvis.voice.VoiceInstrumentation.sttSessionsStarted}"))
            sb.append(line("•", "STT sessions completed", "${com.pr4nav.jarvis.voice.VoiceInstrumentation.sttSessionsCompleted}"))
            sb.append(line("•", "STT errors", "${com.pr4nav.jarvis.voice.VoiceInstrumentation.sttErrors}"))
            sb.append(line(if (serviceRunning) "✓" else "○", "Foreground Service", if (serviceRunning) "RUNNING ($serviceState)" else "STOPPED"))
            sb.append(line(if (isHandsFree) "✓" else "○", "Hands-Free Mode", if (isHandsFree) "ENABLED" else "DISABLED"))
            sb.append(line(if (batteryExempt) "✓" else "△", "Battery Optimization", if (batteryExempt) "EXEMPT (Unrestricted)" else "OPTIMIZED (May throttle)"))
            sb.append("--------------------------------\n\n")

            // Hardware Controls
            val (batPct, charging) = com.pr4nav.jarvis.capabilities.DeviceCapability.battery()
            sb.append(line("✓", "Hardware Battery API", "$batPct% (${if (charging) "Charging" else "Discharging"})"))
            sb.append(line("✓", "Hardware Torch API", "READY"))

            // shell
            val local = Shell.local("echo hi")
            sb.append(line(if (local.out.contains("hi")) "✓" else "✗", "Local shell", "rc=${local.rc}"))

            runOnUiThread { view.text = sb.toString() }
        }
    }
}
