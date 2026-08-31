package com.pr4nav.jarvis

import android.os.Bundle
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.router.CanonicalRouter
import com.pr4nav.jarvis.router.LanguageNormalizer
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import org.json.JSONObject
import kotlin.concurrent.thread

/**
 * Developer Tool Playground & Debug Surface.
 * Exposes the canonical tools for interactive testing and prompt normalization.
 */
class ToolPlaygroundActivity : AppCompatActivity() {

    private lateinit var inputQuery: EditText
    private lateinit var btnNormalize: Button
    private lateinit var spinnerTools: Spinner
    private lateinit var inputArgs: EditText
    private lateinit var btnExecute: Button
    private lateinit var btnClear: Button
    private lateinit var tvOutput: TextView
    private lateinit var scroller: ScrollView

    private val router = CanonicalRouter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tool_playground)

        CanonicalToolRegistry.init(this)

        inputQuery = findViewById(R.id.input_nl_query)
        btnNormalize = findViewById(R.id.btn_normalize)
        spinnerTools = findViewById(R.id.spinner_tools)
        inputArgs = findViewById(R.id.input_args_json)
        btnExecute = findViewById(R.id.btn_execute_tool)
        btnClear = findViewById(R.id.btn_clear_log)
        tvOutput = findViewById(R.id.tv_tool_output)
        scroller = findViewById(R.id.log_scroller)

        findViewById<View>(R.id.btn_back).setOnClickListener { finish() }

        // Populate tools in spinner
        val toolNames = CanonicalToolRegistry.names().sorted()
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, toolNames)
        spinnerTools.adapter = adapter

        // Update default JSON schema template when tool is selected
        spinnerTools.setOnItemSelectedListener(object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                val selectedTool = toolNames[position]
                val def = CanonicalToolRegistry.get(selectedTool)
                if (def != null) {
                    val sample = JSONObject()
                    val required = def.argumentSchema.optJSONArray("required")
                    if (required != null) {
                        for (i in 0 until required.length()) {
                            sample.put(required.optString(i), "")
                        }
                    }
                    inputArgs.setText(sample.toString(2))
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        })

        // Normalize Natural Language input
        btnNormalize.setOnClickListener {
            val query = inputQuery.text.toString().trim()
            if (query.isEmpty()) return@setOnClickListener

            log("────────────────────────────────────────────────")
            log("🔎 INPUT: \"$query\"")
            val decision = router.route(this, query)
            log("⚡ TIER: ${decision.tier}")
            log("🎯 CANONICAL TOOL: ${decision.tool ?: "NONE"}")
            log("📊 CONFIDENCE: ${String.format("%.2f", decision.confidence)}")
            log("📝 REASON: ${decision.reason}")
            if (decision.arguments != null) {
                log("📦 ARGS:\n${decision.arguments.toString(2)}")
                // Set into executor
                val idx = toolNames.indexOf(decision.tool)
                if (idx >= 0) {
                    spinnerTools.setSelection(idx)
                    inputArgs.setText(decision.arguments.toString(2))
                }
            }
            if (decision.executionResult != null) {
                val r = decision.executionResult
                log("🏁 EXEC STATUS: ${r.status} (success=${r.success}, latency=${r.latencyMs}ms)")
                if (r.data != null) log("📄 DATA: ${r.data}")
                if (r.error != null) log("⚠️ ERROR: [${r.error.code}] ${r.error.message}")
            }
        }

        // Direct Execution
        btnExecute.setOnClickListener {
            val toolName = spinnerTools.selectedItem?.toString() ?: return@setOnClickListener
            val argsStr = inputArgs.text.toString().trim()
            val argsJson = try {
                if (argsStr.isNotEmpty()) JSONObject(argsStr) else JSONObject()
            } catch (e: Exception) {
                log("❌ Invalid JSON Arguments: ${e.message}")
                return@setOnClickListener
            }

            val toolDef = CanonicalToolRegistry.get(toolName)
            if (toolDef == null) {
                log("❌ Tool '$toolName' not found")
                return@setOnClickListener
            }

            log("────────────────────────────────────────────────")
            log("▶ EXECUTING: $toolName")
            log("📦 ARGS: $argsJson")

            thread {
                val t0 = System.currentTimeMillis()
                val result = toolDef.executeWithTimeout(this, argsJson)
                val duration = System.currentTimeMillis() - t0

                runOnUiThread {
                    log("🏁 STATUS: ${result.status} (latency=${duration}ms)")
                    log("✔ SUCCESS: ${result.success}")
                    if (result.data != null) log("📄 DATA:\n${result.data}")
                    if (result.error != null) log("⚠️ ERROR: [${result.error.code}] ${result.error.message}")
                }
            }
        }

        btnClear.setOnClickListener {
            tvOutput.text = "Ready.\n"
        }
    }

    private fun log(text: String) {
        tvOutput.append("\n$text")
        scroller.post { scroller.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
