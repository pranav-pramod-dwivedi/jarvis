package com.pr4nav.jarvis.llm

import android.content.Context
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.pr4nav.jarvis.RouteHarnessActivity
import com.pr4nav.jarvis.router.UnifiedAssistantDispatcher

/**
 * Provider-first model picker: lists providers only, then fetches that
 * provider's live models. Nothing hardcoded, no cascade UI here —
 * fallback lives in Settings (route harness).
 */
object KiraModelPicker {

    fun show(activity: AppCompatActivity, onChanged: () -> Unit = {}) {
        val context: Context = activity
        val items = AIProvider.values().map { p ->
            val keyMark = if (p.hasKey(context)) "" else "  ·  no key"
            "${p.title}$keyMark\n${p.hint} · current: ${p.currentModel(context)}"
        }.toTypedArray()

        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("Select provider")
            .setItems(items) { _, which ->
                val provider = AIProvider.values()[which]
                showProviderModels(activity, provider, onChanged)
            }
            .setNeutralButton("Keys") { _, _ ->
                activity.startActivity(
                    android.content.Intent(activity, com.pr4nav.jarvis.ProviderKeysActivity::class.java)
                )
            }
            .setPositiveButton("Harness") { _, _ ->
                activity.startActivity(
                    android.content.Intent(activity, RouteHarnessActivity::class.java)
                )
            }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showProviderModels(
        activity: AppCompatActivity,
        provider: AIProvider,
        onChanged: () -> Unit
    ) {
        val context: Context = activity
        if (!provider.hasKey(context)) {
            Toast.makeText(context, "Add the ${provider.title} key first (Keys button)", Toast.LENGTH_LONG).show()
        }
        Toast.makeText(context, "Fetching ${provider.title} models…", Toast.LENGTH_SHORT).show()
        provider.fetchModels(
            context,
            onSuccess = { fetched: List<String> ->
                activity.runOnUiThread {
                    if (fetched.isEmpty()) {
                        Toast.makeText(context, "No models returned", Toast.LENGTH_SHORT).show()
                        return@runOnUiThread
                    }
                    val currentId = provider.currentModelId(context)
                    val display = fetched.map { id ->
                        if (id == currentId) "$id  ·  current" else id
                    }.toTypedArray()
                    androidx.appcompat.app.AlertDialog.Builder(activity)
                        .setTitle("${provider.title} models (${fetched.size})")
                        .setItems(display) { _, fWhich: Int ->
                            val chosen = fetched[fWhich]
                            provider.setModel(context, chosen)
                            // Solo engine: this provider alone, no fallback chain.
                            UnifiedAssistantDispatcher.setRoute(
                                context,
                                listOf(provider.engine)
                            )
                            onChanged()
                            Toast.makeText(
                                context,
                                "${provider.title}: $chosen",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        .setNegativeButton("Back", null)
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
}
