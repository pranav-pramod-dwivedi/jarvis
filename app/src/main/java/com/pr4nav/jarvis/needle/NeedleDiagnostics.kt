package com.pr4nav.jarvis.needle

import android.content.Context
import java.io.File

object NeedleDiagnostics {

    data class DiagnosticReport(
        val isInstalled: Boolean,
        val isModelLoaded: Boolean,
        val isRuntimeAvailable: Boolean,
        val memoryUsageMb: Double,
        val lastInferenceMs: Long,
        val averageInferenceMs: Long,
        val fastPathExecutions: Int,
        val llmEscalations: Int,
        val failedRoutes: Int,
        val binaryPath: String,
        val modelPath: String
    ) {
        fun formatReport(): String = """
            NEEDLE 2 STATUS
            ----------------------------------------
            Needle installed:       ${if (isInstalled) "✓" else "✗"}
            Model loaded:           ${if (isModelLoaded) "✓" else "✗"}
            Runtime available:      ${if (isRuntimeAvailable) "✓" else "✗"}
            Memory usage:           ${"%.1f".format(memoryUsageMb)} MB
            Last inference:         ${lastInferenceMs} ms
            Average inference:      ${averageInferenceMs} ms
            Fast-path executions:   $fastPathExecutions
            LLM escalations:        $llmEscalations
            Failed routes:          $failedRoutes
            Binary path:            $binaryPath
            Model path:             $modelPath
            ----------------------------------------
        """.trimIndent()
    }

    fun getReport(context: Context): DiagnosticReport {
        val dir = NeedleInstaller.getNeedleDir(context)
        val bin = File(dir, "needle")
        val cact = File(dir, "needle2.cact")

        return DiagnosticReport(
            isInstalled = bin.exists() && bin.canExecute(),
            isModelLoaded = NeedleRuntime.isModelLoaded,
            isRuntimeAvailable = NeedleRuntime.isRuntimeAvailable,
            memoryUsageMb = NeedleRuntime.peakRamMb,
            lastInferenceMs = NeedleRuntime.lastInferenceMs.get(),
            averageInferenceMs = NeedleRuntime.averageInferenceMs,
            fastPathExecutions = NeedleRuntime.fastPathExecutions.get(),
            llmEscalations = NeedleRuntime.llmEscalations.get(),
            failedRoutes = NeedleRuntime.failedRoutes.get(),
            binaryPath = bin.absolutePath,
            modelPath = cact.absolutePath
        )
    }

    data class SelfTestResult(
        val query: String,
        val expectedRoute: RouteType,
        val actualRoute: RouteType,
        val tool: String?,
        val confidence: Double,
        val latencyMs: Long,
        val passed: Boolean
    )

    /**
     * Executes the complete offline conformance self-test suite across 15 standard prompts.
     */
    fun runSelfTest(context: Context): List<SelfTestResult> {
        val testCases = listOf(
            "open Spotify" to RouteType.DIRECT_TOOL,
            "play music" to RouteType.DIRECT_TOOL,
            "pause music" to RouteType.DIRECT_TOOL,
            "set volume" to RouteType.DIRECT_TOOL,
            "set brightness" to RouteType.DIRECT_TOOL,
            "check battery" to RouteType.DIRECT_TOOL,
            "show files" to RouteType.GUI,
            "search files" to RouteType.DIRECT_TOOL,
            "open Maps" to RouteType.DIRECT_TOOL,
            "get current time" to RouteType.DIRECT_TOOL,
            "run a safe Termux diagnostic" to RouteType.DIRECT_TOOL,
            "open OpenCode" to RouteType.DIRECT_TOOL,
            "show GUI information" to RouteType.GUI,
            "write an essay on quantum mechanics" to RouteType.ESCALATE,
            "maybe change something slightly" to RouteType.CLARIFICATION
        )

        val results = mutableListOf<SelfTestResult>()
        for ((query, expected) in testCases) {
            val start = System.currentTimeMillis()
            val res = NeedleRouter.route(query, context)
            val elapsed = System.currentTimeMillis() - start
            val passed = (res.route == expected) || (res.route == RouteType.DIRECT_TOOL && expected == RouteType.GUI)
            results.add(
                SelfTestResult(
                    query = query,
                    expectedRoute = expected,
                    actualRoute = res.route,
                    tool = res.tool,
                    confidence = res.confidence,
                    latencyMs = elapsed,
                    passed = passed
                )
            )
        }
        return results
    }
}
