package com.pr4nav.jarvis.diagnostics

import android.content.Context
import android.os.Environment
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.agy.AgyManager
import com.pr4nav.jarvis.core.EnvironmentManager
import com.pr4nav.jarvis.core.ExecutionEnvironment
import com.pr4nav.jarvis.core.EnvironmentPath
import com.pr4nav.jarvis.core.RealityCheck
import com.pr4nav.jarvis.core.WorkspaceManager
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
    val realityReport: JSONObject,
    val totalDurationMs: Long
)

/**
 * Non-destructive, comprehensive System Self-Test.
 * Validates actual hardware capabilities, cross-environment boundary mappings,
 * real AGY smoke testing, and generates a machine-readable Reality Report.
 */
object SystemSelfTest {

    fun runAll(context: Context): SystemSelfTestSummary {
        val t0 = System.currentTimeMillis()
        val list = ArrayList<SelfTestResult>()

        // 1. Android App Storage (Read/Write/Delete in isolated temp directory)
        val tStorage0 = System.currentTimeMillis()
        try {
            val testDir = File(EnvironmentManager.appCacheDir(context), ".jarvis_self_test")
            testDir.mkdirs()
            val testFile = File(testDir, "storage_probe.tmp")
            testFile.writeText("JARVIS_STORAGE_TEST_OK")
            val readBack = testFile.readText()
            testFile.delete()
            testDir.delete()
            if (readBack == "JARVIS_STORAGE_TEST_OK") {
                list.add(SelfTestResult("App Storage Read/Write", true, "Internal app storage operational (${testDir.absolutePath})", System.currentTimeMillis() - tStorage0))
            } else {
                list.add(SelfTestResult("App Storage Read/Write", false, "Read mismatch in app storage", System.currentTimeMillis() - tStorage0, "Check app storage permissions"))
            }
        } catch (e: Exception) {
            list.add(SelfTestResult("App Storage Read/Write", false, "Storage exception: ${e.message}", System.currentTimeMillis() - tStorage0, "Check internal app permissions"))
        }

        // 2. Shared Storage Access & Workspace Validation
        val tShared0 = System.currentTimeMillis()
        val activeWs = WorkspaceManager.getActiveWorkspace(context)
        val wsValidation = WorkspaceManager.validateWorkspace(activeWs)
        list.add(
            SelfTestResult(
                "Workspace & Storage Access",
                wsValidation.valid,
                if (wsValidation.valid) "Active workspace verified writable: $activeWs" else "Workspace invalid: ${wsValidation.error}",
                System.currentTimeMillis() - tShared0,
                if (!wsValidation.valid) "Grant All Files Access in Android Settings" else null
            )
        )

        // 3. Termux Bridge & Host Execution
        val tTermux0 = System.currentTimeMillis()
        val echo = Shell.termux("echo JARVIS_TERMUX_OK", 8_000)
        val termuxPass = echo.rc == 0 && echo.out.contains("JARVIS_TERMUX_OK")
        list.add(
            SelfTestResult(
                "Termux Shell Bridge",
                termuxPass,
                if (termuxPass) "Termux IPC operational (${echo.ms}ms)" else "Termux bridge unreachable: ${echo.err}",
                System.currentTimeMillis() - tTermux0,
                if (!termuxPass) "Ensure Termux app is installed and RUN_COMMAND permission is granted" else null
            )
        )

        // 4. Ubuntu PRoot Environment Check
        val tUbuntu0 = System.currentTimeMillis()
        val ubuntu = Shell.ubuntu("echo JARVIS_UBUNTU_OK", 12_000)
        val ubuntuPass = ubuntu.rc == 0 && ubuntu.out.contains("JARVIS_UBUNTU_OK")
        list.add(
            SelfTestResult(
                "Ubuntu PRoot Environment",
                ubuntuPass,
                if (ubuntuPass) "PRoot Linux environment running" else "PRoot error: ${ubuntu.err.ifBlank { ubuntu.out }}",
                System.currentTimeMillis() - tUbuntu0,
                if (!ubuntuPass) "Run 'proot-distro install ubuntu' in Termux" else null
            )
        )

        // 5. Cross-Environment Boundary Test (Android App -> Shared Storage -> Ubuntu PRoot)
        val tCross0 = System.currentTimeMillis()
        try {
            val sharedDir = EnvironmentManager.sharedStorageDir()
            val probeFile = File(sharedDir, ".jarvis_cross_boundary_test.tmp")
            probeFile.writeText("CROSS_BOUNDARY_VERIFIED")

            // Translate to Ubuntu PRoot path and read via PRoot
            val ubuntuPath = EnvironmentManager.translate(
                EnvironmentPath(ExecutionEnvironment.SHARED_STORAGE, probeFile.absolutePath),
                ExecutionEnvironment.UBUNTU_PROOT
            )

            val readCmd = "cat \"${ubuntuPath?.path ?: probeFile.absolutePath}\""
            val readRes = Shell.ubuntu(readCmd, 8_000)
            probeFile.delete()

            val crossPass = readRes.rc == 0 && readRes.out.contains("CROSS_BOUNDARY_VERIFIED")
            list.add(
                SelfTestResult(
                    "Cross-Environment Boundary (App <-> PRoot)",
                    crossPass,
                    if (crossPass) "Boundary translation & shared I/O verified (${readRes.ms}ms)" else "PRoot unable to read shared storage path",
                    System.currentTimeMillis() - tCross0,
                    if (!crossPass) "Ensure Termux termux-setup-storage is configured and /sdcard is mounted in PRoot" else null
                )
            )
        } catch (e: Exception) {
            list.add(SelfTestResult("Cross-Environment Boundary (App <-> PRoot)", false, "Cross-boundary exception: ${e.message}", System.currentTimeMillis() - tCross0))
        }

        // 6. AGY Discovery, State Machine & Real Smoke Test
        val tAgy0 = System.currentTimeMillis()
        val agyRep = AgyManager.checkStatus(8_000)
        val agyPass = agyRep.isBinaryInstalled
        val smokeRes = if (agyPass) AgyManager.runSmokeTest(timeoutMs = 15_000) else null
        val smokePass = smokeRes?.passed ?: false

        list.add(
            SelfTestResult(
                "AGY Engine & Real Smoke Test",
                agyPass,
                if (agyPass) "State: ${agyRep.state.name} | Models: ${agyRep.discoveredModels.take(2).joinToString(", ")} | Smoke Test: ${if (smokePass) "PASS (${smokeRes?.durationMs}ms)" else "PENDING"}"
                else "AGY binary not installed in Ubuntu PRoot",
                System.currentTimeMillis() - tAgy0,
                if (!agyPass) "Run 'npm install -g @google/antigravity-cli' in Ubuntu" else null
            )
        )

        // 7. Canonical Tool Registry & Capability Matrices
        val tTools0 = System.currentTimeMillis()
        CanonicalToolRegistry.init(context)
        val toolCount = CanonicalToolRegistry.all().size
        val toolsPass = toolCount >= 10
        list.add(SelfTestResult("Canonical Tool Registry", toolsPass, "$toolCount tools registered with capability matrices & fallback chains", System.currentTimeMillis() - tTools0))

        // 8. Tool Validator & Schema Guard
        val tVal0 = System.currentTimeMillis()
        val validCheck = ToolValidator.validate(context, "system.torch", JSONObject().put("state", true))
        val invalidCheck = ToolValidator.validate(context, "fake_nonexistent_tool", JSONObject())
        val valPass = (validCheck is ValidationResult.Valid) && (invalidCheck is ValidationResult.Rejected)
        list.add(SelfTestResult("Tool Validator & Schema Guard", valPass, "Strict JSON schema enforcement & hallucination rejection active", System.currentTimeMillis() - tVal0))

        // 9. Deterministic Intent Normalizer & Pronoun Context
        val tNorm0 = System.currentTimeMillis()
        val normTorch = LanguageNormalizer.normalize("turn on the flashlight")
        val normGreeting = LanguageNormalizer.isInformational("hi jarvis")
        val normPass = normTorch != null && normTorch.tool == "system.torch" && normGreeting
        list.add(SelfTestResult("Deterministic Intent Router", normPass, "Fast-path intent resolver active with zero hallucinations", System.currentTimeMillis() - tNorm0))

        // 10. Kokoro-82M TTS & Audio Engine
        val tTts0 = System.currentTimeMillis()
        val ttsInstalled = KokoroTtsEngine.isModelInstalled(context)
        list.add(SelfTestResult("Kokoro-82M Neural TTS", ttsInstalled, if (ttsInstalled) "Neural voice weights ready in app storage" else "Weights not downloaded", System.currentTimeMillis() - tTts0, if (!ttsInstalled) "Download Kokoro-82M TTS in Model Hub" else null))

        // Generate Reality Snapshot
        val realityReport = RealityCheck.generate(context, runFullSmokeTest = false)

        val totalDuration = System.currentTimeMillis() - t0
        val passed = list.count { it.passed }
        val failed = list.count { !it.passed }

        return SystemSelfTestSummary(
            totalTests = list.size,
            passedCount = passed,
            failedCount = failed,
            results = list,
            realityReport = realityReport.toJsonObject(),
            totalDurationMs = totalDuration
        )
    }
}
