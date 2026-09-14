package com.pr4nav.jarvis.llm

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

/**
 * Shared Kira model picker dialog: built-in catalog (incl. Auto router),
 * live platform models, and the solo/cascade toggle.
 * Used by the agent chat pill and the route harness alike.
 */
object KiraModelPicker {

    fun show(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val context: Context = activity
        val current = KiraClient.getModel(context)
        val builtin = KiraClient.BUILTIN_MODELS

        val items = builtin.map { m ->
            buildString {
                append(m.label)
                append("  ·  ")
                append(m.contextWindow)
                if (m.tier == "REASONING" || m.tier == "FLAGSHIP") append("  ·  high reasoning")
                append("\n")
                append(m.description)
            }
        }.toMutableList()

        val soloOn = KiraClient.isSoloModel(context)
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Switch Kira Model  ·  current: ${KiraClient.modelLabel(current)}${if (soloOn) " (SOLO)" else ""}")
            .setItems(items.toTypedArray()) { _, which ->
                val chosen = builtin[which].id
                if (chosen == current) {
                    Toast.makeText(context, "Already using ${builtin[which].label}", Toast.LENGTH_SHORT).show()
                    return@setItems
                }
                KiraClient.setModel(context, chosen)
                onChanged()
                Toast.makeText(
                    context,
                    "${KiraClient.modelLabel(chosen)} active" +
                        if (KiraClient.isHighReasoning(chosen)) " · high reasoning" else "",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setNeutralButton(if (soloOn) "Solo: ON" else "Solo: OFF") { _, _ ->
                val next = !KiraClient.isSoloModel(context)
                KiraClient.setSoloModel(context, next)
                onChanged()
                Toast.makeText(
                    context,
                    if (next) "Solo model: cascade disabled, selected model only"
                    else "Cascade enabled: auto fallback across free models",
                    Toast.LENGTH_SHORT
                ).show()
            }
            .setPositiveButton("All Models (live)") { _, _ ->
                Toast.makeText(context, "Fetching live models from Kira…", Toast.LENGTH_SHORT).show()
                KiraClient.fetchAvailableModels(
                    context = context,
                    onSuccess = { fetched: List<String> ->
                        activity.runOnUiThread {
                            if (fetched.isEmpty()) {
                                Toast.makeText(context, "No models returned by Kira", Toast.LENGTH_SHORT).show()
                                return@runOnUiThread
                            }
                            val display = fetched.map { id: String ->
                                val known = builtin.firstOrNull { it.id == id }
                                if (known != null) "${known.label}  ·  ${known.contextWindow}"
                                else id + if (KiraClient.isHighReasoning(id)) "  ·  high reasoning" else ""
                            }.toTypedArray()
                            androidx.appcompat.app.AlertDialog.Builder(activity)
                                .setTitle("All Kira Models (${fetched.size})")
                                .setItems(display) { _, fWhich: Int ->
                                    val chosen = fetched[fWhich]
                                    KiraClient.setModel(context, chosen)
                                    onChanged()
                                    Toast.makeText(context, "Model: $chosen", Toast.LENGTH_SHORT).show()
                                }
                                .setNegativeButton("Close", null)
                                .show()
                        }
                    },
                    onError = { err: String ->
                        activity.runOnUiThread {
                            Toast.makeText(context, "Fetch error: $err", Toast.LENGTH_LONG).show()
                        }
                    }
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }
}
