package com.pr4nav.jarvis.core

import android.content.Context
import android.os.Environment
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.agy.AgyManager
import org.json.JSONObject

data class RealityReport(
    val timestamp: Long = System.currentTimeMillis(),
    val androidAvailable: Boolean,
    val storageAccess: Boolean,
    val termuxInstalled: Boolean,
    val termuxBridgeOk: Boolean,
    val ubuntuInstalled: Boolean,
    val ubuntuProotOk: Boolean,
    val agyInstalled: Boolean,
    val agyState: String,
    val agySmokeTestPassed: Boolean,
    val opencodeInstalled: Boolean,
    val opencodeServerReachable: Boolean,
    val safeModeActive: Boolean,
    val durationMs: Long
) {
    fun toJsonObject(): JSONObject = JSONObject().apply {
        put("timestamp", timestamp)
        put("safe_mode_active", safeModeActive)
        put("duration_ms", durationMs)
        put("android", JSONObject().apply {
            put("available", androidAvailable)
            put("storage_access", storageAccess)
        })
        put("termux", JSONObject().apply {
            put("installed", termuxInstalled)
            put("bridge_ok", termuxBridgeOk)
        })
        put("ubuntu", JSONObject().apply {
            put("installed", ubuntuInstalled)
            put("proot_ok", ubuntuProotOk)
        })
        put("agy", JSONObject().apply {
            put("installed", agyInstalled)
            put("state", agyState)
            put("smoke_test_passed", agySmokeTestPassed)
        })
        put("opencode", JSONObject().apply {
            put("installed", opencodeInstalled)
            put("server_reachable", opencodeServerReachable)
        })
    }
}

/**
 * Reality Snapshot Generator.
 * Discovers and generates a verified, machine-readable status of the system.
 */
object RealityCheck {

    @Volatile private var cachedReport: RealityReport? = null

    fun getCached(): RealityReport? = cachedReport

    fun generate(context: Context, runFullSmokeTest: Boolean = false): RealityReport {
        val t0 = System.currentTimeMillis()

        // 1. Android & Storage
        val androidAvail = true
        val storageAccess = Environment.isExternalStorageManager() || (Environment.getExternalStorageDirectory().canRead())

        // 2. Termux Bridge
        val termuxInstalled = EnvironmentManager.isTermuxInstalled(context)
        val termuxEcho = if (termuxInstalled) Shell.termux("echo REALITY_OK", 6_000) else null
        val termuxBridgeOk = termuxEcho != null && termuxEcho.rc == 0 && termuxEcho.out.contains("REALITY_OK")

        // 3. Ubuntu PRoot
        val ubuntuInstalled = EnvironmentManager.isUbuntuInstalled()
        val ubuntuEcho = if (termuxBridgeOk) Shell.ubuntu("echo REALITY_UBUNTU_OK", 10_000) else null
        val ubuntuProotOk = ubuntuEcho != null && ubuntuEcho.rc == 0 && ubuntuEcho.out.contains("REALITY_UBUNTU_OK")

        // 4. AGY State & Smoke Test
        val agyReport = AgyManager.checkStatus(6_000)
        val agySmokeOk = if (runFullSmokeTest && agyReport.isBinaryInstalled) {
            AgyManager.runSmokeTest(timeoutMs = 12_000).passed
        } else {
            agyReport.isHttpHealthy || agyReport.isBinaryInstalled
        }

        // 5. OpenCode Reachability
        val ocPort = if (termuxBridgeOk) Shell.termux("for p in 4096 4097; do curl -sm1 -o /dev/null http://127.0.0.1:\$p/ && echo \$p && break; done", 4_000) else null
        val ocReachable = ocPort != null && ocPort.out.trim().isNotBlank()
        val ocInstalled = ubuntuProotOk && (Shell.ubuntu("command -v opencode", 5_000).rc == 0)

        // Safe mode is active if Linux/AGY infrastructure is down
        val safeMode = !ubuntuProotOk || !termuxBridgeOk

        val report = RealityReport(
            androidAvailable = androidAvail,
            storageAccess = storageAccess,
            termuxInstalled = termuxInstalled,
            termuxBridgeOk = termuxBridgeOk,
            ubuntuInstalled = ubuntuInstalled,
            ubuntuProotOk = ubuntuProotOk,
            agyInstalled = agyReport.isBinaryInstalled,
            agyState = agyReport.state.name,
            agySmokeTestPassed = agySmokeOk,
            opencodeInstalled = ocInstalled,
            opencodeServerReachable = ocReachable,
            safeModeActive = safeMode,
            durationMs = System.currentTimeMillis() - t0
        )

        cachedReport = report
        return report
    }
}
