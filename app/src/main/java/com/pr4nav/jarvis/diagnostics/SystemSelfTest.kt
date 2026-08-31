package com.pr4nav.jarvis.diagnostics

import android.content.Context
import android.os.Environment
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.agy.AgyManager
import com.pr4nav.jarvis.core.EnvironmentManager
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import com.pr4nav.jarvis.voice.KokoroTtsEngine
import org.json.JSONObject
import java.io.File

data class SelfTestResult(
    val name: String,
    val passed: Boolean,
    val message: String,
    val latencyMs: Long = 0L,
    val suggestedFix: String? = null
)

data class SystemSelfTestSummary(
    val totalTests: Int,
    val passedCount: Int,
    val failedCount: Int,
    val results: List<SelfTestResult>,
    val totalDurationMs: Long
)

/**
 * Single Automated System Self-Test.
 * Runs 10 isolated, safe diagnostic checks covering the complete JARVIS stack.
 */
object SystemSelfTest {

    fun runAll(context: Context): SystemSelfTestSummary {
        val t0 = System.currentTimeMillis()
        val list = ArrayList<SelfTestResult>()

        // 1. Android App Storage (Read/Write/Delete)
        val tStorage0 = System.currentTimeMillis()
        try {
            val dir = EnvironmentManager.appFilesDir(context)
            val testFile = File(dir, ".self_test_storage.tmp")
            testFile.writeText("JARVIS_STORAGE_TEST_OK")
            val readBack = testFile.readText()
            testFile.delete()
            if (readBack == "JARVIS_STORAGE_TEST_OK") {
                list.add(SelfTestResult("App Storage Read/Write", true, "Internal app storage operational (${dir.absolutePath})", System.currentTimeMillis() - tStorage0))
            } else {
                list.add(SelfTestResult("App Storage Read/Write", false, "Read mismatch in app storage", System.currentTimeMillis() - tStorage0, "Check app storage permissions"))
            }
        } catch (e: Exception) {
            list.add(SelfTestResult("App Storage Read/Write", false, "Storage exception: ${e.message}", System.currentTimeMillis() - tStorage0, "Check internal app permissions"))
        }

        // 2. Shared Storage Access (/sdcard)
        val tShared0 = System.currentTimeMillis()
        try {
            val sd = EnvironmentManager.sharedStorageDir()
            val canRead = sd.exists() && sd.canRead()
            list.add(SelfTestResult("Shared Storage (/sdcard)", canRead, if (canRead) "Accessible: ${sd.absolutePath}" else "Limited access", System.currentTimeMillis() - tShared0, if (!canRead) "Grant All Files Access in Android Settings" else null))
        } catch (e: Exception) {
            list.add(SelfTestResult("Shared Storage (/sdcard)", false, e.message ?: "Access error", System.currentTimeMillis() - tShared0))
        }

        // 3. Termux Bridge & Host Execution
        val tTermux0 = System.currentTimeMillis()
        val echo = Shell.termux("echo JARVIS_TERMUX_OK", 10_000)
        val termuxPass = echo.rc == 0 && echo.out.contains("JARVIS_TERMUX_OK")
        list.add(SelfTestResult("Termux Shell Bridge", termuxPass, if (termuxPass) "Termux IPC operational (${echo.ms}ms)" else "Termux bridge unreachable: ${echo.err}", System.currentTimeMillis() - tTermux0, if (!termuxPass) "Ensure Termux app is installed and RUN_COMMAND permission is granted" else null))

        // 4. Ubuntu PRoot Environment Check
        val tUbuntu0 = System.currentTimeMillis()
        val ubuntu = Shell.ubuntu("echo JARVIS_UBUNTU_OK", 15_000)
        val ubuntuPass = ubuntu.rc == 0 && ubuntu.out.contains("JARVIS_UBUNTU_OK")
        list.add(SelfTestResult("Ubuntu PRoot Environment", ubuntuPass, if (ubuntuPass) "PRoot Linux environment running" else "PRoot error: ${ubuntu.err.ifBlank { ubuntu.out }}", System.currentTimeMillis() - tUbuntu0, if (!ubuntuPass) "Run 'proot-distro install ubuntu' in Termux" else null))

        // 5. AGY Binary Discovery & Port 5050 Health
        val tAgy0 = System.currentTimeMillis()
        val agyRep = AgyManager.checkStatus(8_000)
        val agyPass = agyRep.isBinaryInstalled
        list.add(SelfTestResult("AGY Engine Discovery", agyPass, if (agyPass) "AGY ${agyRep.version} at ${agyRep.binaryPath} (Port 5050: ${if (agyRep.isPortListening) "LISTENING" else "STANDBY"})" else "AGY binary not found in Ubuntu PRoot", System.currentTimeMillis() - tAgy0, if (!agyPass) "Run 'npm install -g @google/antigravity-cli' in Ubuntu" else null))

        // 6. OpenCode Workspace & Connectivity
        val tOc0 = System.currentTimeMillis()
        val oc = Shell.termux("proot-distro login ubuntu -- /bin/bash -lc 'command -v opencode' 2>&1", 10_000)
        val ocPass = oc.rc == 0 && oc.out.contains("opencode")
        list.add(SelfTestResult("OpenCode Agent", ocPass, if (ocPass) "OpenCode installed at ${oc.out.trim()}" else "OpenCode binary not present in PRoot", System.currentTimeMillis() - tOc0, if (!ocPass) "Install OpenCode in Ubuntu PRoot if coding agent is desired" else null))

        // 7. Canonical Tool Registry Integrity
        val tTools0 = System.currentTimeMillis()
        CanonicalToolRegistry.init(context)
        val toolCount = CanonicalToolRegistry.all().size
        val toolsPass = toolCount >= 10
        list.add(SelfTestResult("Canonical Tool Registry", toolsPass, "$toolCount canonical tools registered and validated", System.currentTimeMillis() - tTools0))

        // 8. Tool Validator & Schema Guard
        val tVal0 = System.currentTimeMillis()
        val validCheck = ToolValidator.validate(context, "system.torch", JSONObject().put("state", true))
        val invalidCheck = ToolValidator.validate(context, "fake_nonexistent_tool", JSONObject())
        val valPass = (validCheck is ValidationResult.Valid) && (invalidCheck is ValidationResult.Rejected)
        list.add(SelfTestResult("Tool Validator & Schema Guard", valPass, "Schema enforcement & hallucination rejection active", System.currentTimeMillis() - tVal0))

        // 9. Deterministic Intent Normalizer
        val tNorm0 = System.currentTimeMillis()
        val normTorch = LanguageNormalizer.normalize("turn on the flashlight")
        val normGreeting = LanguageNormalizer.isInformational("hi jarvis")
        val normPass = normTorch != null && normTorch.tool == "system.torch" && normGreeting
        list.add(SelfTestResult("Deterministic Intent Router", normPass, "Fast-path intent resolver & greeting guard active", System.currentTimeMillis() - tNorm0))

        // 10. Kokoro-82M TTS & Audio Engine
        val tTts0 = System.currentTimeMillis()
        val ttsInstalled = KokoroTtsEngine.isModelInstalled(context)
        list.add(SelfTestResult("Kokoro-82M Neural TTS", ttsInstalled, if (ttsInstalled) "Model weights verified in app storage" else "Model weights not downloaded (~82MB)", System.currentTimeMillis() - tTts0, if (!ttsInstalled) "Download Kokoro-82M TTS in Model Hub" else null))

        val totalDuration = System.currentTimeMillis() - t0
        val passed = list.count { it.passed }
        val failed = list.count { !it.passed }

        return SystemSelfTestSummary(
            totalTests = list.size,
            passedCount = passed,
            failedCount = failed,
            results = list,
            totalDurationMs = totalDuration
        )
    }
}
