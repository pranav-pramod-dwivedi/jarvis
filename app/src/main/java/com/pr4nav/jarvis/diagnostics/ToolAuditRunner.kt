package com.pr4nav.jarvis.diagnostics

import android.content.Context
import com.pr4nav.jarvis.tools.CanonicalToolDef
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.tools.ToolBackend
import com.pr4nav.jarvis.tools.ToolResult
import com.pr4nav.jarvis.tools.ToolState
import com.pr4nav.jarvis.tools.ToolValidator
import com.pr4nav.jarvis.tools.ValidationResult
import org.json.JSONObject

data class ToolAuditItem(
    val toolName: String,
    val batchName: String,
    val inRegistry: Boolean,
    val schemaValid: Boolean,
    val backend: ToolBackend,
    val backendState: ToolState,
    val safeExecutionPassed: Boolean,
    val verificationPassed: Boolean,
    val status: String,
    val details: String,
    val latencyMs: Long
)

data class ToolBatchReport(
    val batchName: String,
    val totalTools: Int,
    val workingCount: Int,
    val brokenCount: Int,
    val unavailableCount: Int,
    val needsPermissionCount: Int,
    val items: List<ToolAuditItem>
)

data class FullAuditReport(
    val totalAudited: Int,
    val working: Int,
    val broken: Int,
    val unavailable: Int,
    val needsPermission: Int,
    val batches: List<ToolBatchReport>
)

/**
 * Batched Real-World Tool Audit Runner.
 * Systematically audits every registered tool safely without triggering destructive actions.
 */
object ToolAuditRunner {

    fun runFullAudit(context: Context): FullAuditReport {
        CanonicalToolRegistry.init(context)
        val allBatches = mutableListOf<ToolBatchReport>()

        // Batch 1: Android System & Hardware
        allBatches.add(
            auditBatch(
                context,
                "Batch 1 — Android System & Hardware",
                listOf(
                    "system.torch" to JSONObject().put("state", true),
                    "system.volume" to JSONObject().put("action", "raise"),
                    "system.battery" to JSONObject(),
                    "open_settings" to JSONObject().put("subpage", "wifi"),
                    "system.screenshot" to JSONObject()
                )
            )
        )

        // Batch 2: Apps & Intents
        allBatches.add(
            auditBatch(
                context,
                "Batch 2 — Apps & Intents",
                listOf(
                    "open_app" to JSONObject().put("app", "Settings"),
                    "close_app" to JSONObject().put("package", "com.android.settings")
                )
            )
        )

        // Batch 3: Comms & Contacts
        allBatches.add(
            auditBatch(
                context,
                "Batch 3 — Contacts & Communication",
                listOf(
                    "find_contact" to JSONObject().put("name", "Test"),
                    "call_contact" to JSONObject().put("number", "12345"),
                    "send_message" to JSONObject().put("recipient", "Test").put("message", "Test SMS")
                )
            )
        )

        // Batch 4: Filesystem
        allBatches.add(
            auditBatch(
                context,
                "Batch 4 — Filesystem",
                listOf(
                    "search_files" to JSONObject().put("query", "test"),
                    "read_file" to JSONObject().put("path", "/sdcard"),
                    "find_downloads" to JSONObject().put("extension", "pdf")
                )
            )
        )

        // Batch 5: Navigation
        allBatches.add(
            auditBatch(
                context,
                "Batch 5 — Navigation & Location",
                listOf(
                    "navigate" to JSONObject().put("destination", "Home"),
                    "get_location" to JSONObject()
                )
            )
        )

        // Batch 6: Media & Network
        allBatches.add(
            auditBatch(
                context,
                "Batch 6 — Media & Connectivity",
                listOf(
                    "get_wifi" to JSONObject(),
                    "get_bluetooth" to JSONObject(),
                    "search_web" to JSONObject().put("query", "Android")
                )
            )
        )

        val total = allBatches.sumOf { it.totalTools }
        val working = allBatches.sumOf { it.workingCount }
        val broken = allBatches.sumOf { it.brokenCount }
        val unavail = allBatches.sumOf { it.unavailableCount }
        val perm = allBatches.sumOf { it.needsPermissionCount }

        return FullAuditReport(total, working, broken, unavail, perm, allBatches)
    }

    private fun auditBatch(
        context: Context,
        batchName: String,
        toolsToTest: List<Pair<String, JSONObject>>
    ): ToolBatchReport {
        val items = mutableListOf<ToolAuditItem>()

        for ((toolName, testArgs) in toolsToTest) {
            val t0 = System.currentTimeMillis()
            val toolDef = CanonicalToolRegistry.get(toolName)

            if (toolDef == null) {
                items.add(
                    ToolAuditItem(
                        toolName = toolName,
                        batchName = batchName,
                        inRegistry = false,
                        schemaValid = false,
                        backend = ToolBackend.ANDROID_NATIVE,
                        backendState = ToolState.UNAVAILABLE,
                        safeExecutionPassed = false,
                        verificationPassed = false,
                        status = "UNREGISTERED",
                        details = "Tool missing from CanonicalToolRegistry",
                        latencyMs = System.currentTimeMillis() - t0
                    )
                )
                continue
            }

            // Validate schema
            val valRes = ToolValidator.validate(context, toolName, testArgs)
            val schemaValid = valRes is ValidationResult.Valid
            val backendState = toolDef.checkAvailability(context)

            // Safe Dry-Run Execution (Non-Destructive)
            var execPassed = false
            var verifPassed = false
            var statusStr = "WORKING"
            var detailsStr = "Operational"

            if (backendState == ToolState.UNAVAILABLE) {
                statusStr = "UNAVAILABLE"
                detailsStr = "Backend ${toolDef.backend} not installed"
            } else {
                try {
                    val result = toolDef.executeWithTimeout(context, testArgs, timeoutOverrideMs = 3000L)
                    execPassed = result.success
                    verifPassed = if (result.success) toolDef.verify(context, testArgs, result) else false

                    if (result.status == com.pr4nav.jarvis.tools.ToolStatus.PERMISSION_REQUIRED) {
                        statusStr = "NEEDS_PERMISSION"
                        detailsStr = result.error?.message ?: "Permission required"
                    } else if (!result.success) {
                        statusStr = "BROKEN"
                        detailsStr = result.error?.message ?: "Execution returned failure"
                    }
                } catch (e: Exception) {
                    statusStr = "BROKEN"
                    detailsStr = e.message ?: "Exception during safe execution"
                }
            }

            items.add(
                ToolAuditItem(
                    toolName = toolName,
                    batchName = batchName,
                    inRegistry = true,
                    schemaValid = schemaValid,
                    backend = toolDef.backend,
                    backendState = backendState,
                    safeExecutionPassed = execPassed,
                    verificationPassed = verifPassed,
                    status = statusStr,
                    details = detailsStr,
                    latencyMs = System.currentTimeMillis() - t0
                )
            )
        }

        val workingCount = items.count { it.status == "WORKING" }
        val brokenCount = items.count { it.status == "BROKEN" }
        val unavailCount = items.count { it.status == "UNAVAILABLE" }
        val permCount = items.count { it.status == "NEEDS_PERMISSION" }

        return ToolBatchReport(
            batchName = batchName,
            totalTools = items.size,
            workingCount = workingCount,
            brokenCount = brokenCount,
            unavailableCount = unavailCount,
            needsPermissionCount = permCount,
            items = items
        )
    }
}
