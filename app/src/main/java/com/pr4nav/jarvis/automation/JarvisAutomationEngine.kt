package com.pr4nav.jarvis.automation

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.util.Log
import com.pr4nav.jarvis.router.JarvisIntentRouter
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import java.util.regex.Pattern

/**
 * JARVIS Proactive Event & Automation Engine
 * Listens to OS events (Battery, Bluetooth/Headset, Charging, Network)
 * and executes deterministic routines locally (EVENT → CONDITION → ACTION)
 * without LLM battery or latency overhead.
 */
object JarvisAutomationEngine {

    private const val TAG = "JarvisAutomationEngine"
    private const val PREFS_NAME = "jarvis_automation_prefs"
    private const val KEY_RULES = "automation_rules_json"

    data class Rule(
        val id: String,
        val eventType: String, // "HEADSET_CONNECTED", "BATTERY_LEVEL", "POWER_CONNECTED", "POWER_DISCONNECTED"
        val condition: String, // e.g. "<= 20" or "*"
        val actionCommand: String
    )

    private var receiverRegistered = false

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun init(context: Context) {
        if (receiverRegistered) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_BATTERY_CHANGED)
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_HEADSET_PLUG)
        }
        try {
            androidx.core.content.ContextCompat.registerReceiver(
                context.applicationContext,
                object : BroadcastReceiver() {
                    private var lastPlugged = -1
                    private var lastBatteryLevel = -1

                    override fun onReceive(ctx: Context, intent: Intent) {
                        when (intent.action) {
                            Intent.ACTION_POWER_CONNECTED -> onEvent(ctx, "POWER_CONNECTED", "true")
                            Intent.ACTION_POWER_DISCONNECTED -> onEvent(ctx, "POWER_DISCONNECTED", "false")
                            Intent.ACTION_HEADSET_PLUG -> {
                                val state = intent.getIntExtra("state", -1)
                                if (state == 1) onEvent(ctx, "HEADSET_CONNECTED", "plugged")
                                else if (state == 0) onEvent(ctx, "HEADSET_DISCONNECTED", "unplugged")
                            }
                            Intent.ACTION_BATTERY_CHANGED -> {
                                val level = intent.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                                if (level != lastBatteryLevel) {
                                    lastBatteryLevel = level
                                    if (level in listOf(15, 20, 50, 100)) {
                                        onEvent(ctx, "BATTERY_LEVEL", level.toString())
                                    }
                                }
                            }
                        }
                    }
                },
                filter,
                androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
            Log.i(TAG, "Jarvis Automation Engine active.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register automation receiver: ${e.message}")
        }
    }

    fun onEvent(context: Context, eventType: String, eventValue: String) {
        val rules = getRules(context)
        for (rule in rules) {
            if (rule.eventType.equals(eventType, ignoreCase = true)) {
                var matches = false
                if (rule.condition == "*" || rule.condition.isBlank()) {
                    matches = true
                } else if (rule.condition.startsWith("<=")) {
                    val target = rule.condition.removePrefix("<=").trim().toIntOrNull() ?: 20
                    val current = eventValue.toIntOrNull() ?: 100
                    if (current <= target) matches = true
                } else if (rule.condition.equals(eventValue, ignoreCase = true)) {
                    matches = true
                }

                if (matches) {
                    Log.i(TAG, "⚡ Automation Triggered: [${rule.eventType}] → Executing: ${rule.actionCommand}")
                    JarvisIntentRouter.routeAndExecute(context, rule.actionCommand) {}
                }
            }
        }
    }

    fun addRule(context: Context, eventType: String, condition: String, actionCommand: String): Rule {
        val rules = getRules(context).toMutableList()
        val rule = Rule(
            id = "rule_${System.currentTimeMillis()}",
            eventType = eventType.uppercase(Locale.US),
            condition = condition,
            actionCommand = actionCommand
        )
        rules.add(rule)
        saveRules(context, rules)
        return rule
    }

    fun getRules(context: Context): List<Rule> {
        val raw = getPrefs(context).getString(KEY_RULES, null) ?: return defaultRules()
        val list = mutableListOf<Rule>()
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    Rule(
                        id = obj.optString("id"),
                        eventType = obj.optString("eventType"),
                        condition = obj.optString("condition"),
                        actionCommand = obj.optString("actionCommand")
                    )
                )
            }
        } catch (_: Exception) {}
        return list
    }

    private fun defaultRules(): List<Rule> {
        return listOf(
            Rule("r1", "HEADSET_CONNECTED", "*", "Play some music"),
            Rule("r2", "BATTERY_LEVEL", "<= 20", "What's my battery percentage?"),
            Rule("r3", "POWER_CONNECTED", "*", "What's my battery percentage?")
        )
    }

    private fun saveRules(context: Context, rules: List<Rule>) {
        val array = JSONArray()
        for (r in rules) {
            val obj = JSONObject()
                .put("id", r.id)
                .put("eventType", r.eventType)
                .put("condition", r.condition)
                .put("actionCommand", r.actionCommand)
            array.put(obj)
        }
        getPrefs(context).edit().putString(KEY_RULES, array.toString()).apply()
    }

    /**
     * Parse natural language automation creation:
     * e.g. "When I connect my headphones, open Spotify"
     * e.g. "When battery hits 20%, tell me"
     */
    fun tryCreateAutomation(context: Context, input: String): String? {
        val raw = input.trim()
        val p = Pattern.compile("^(?:when|if) (.*?), (.*)$", Pattern.CASE_INSENSITIVE).matcher(raw)
        if (p.find()) {
            val cond = p.group(1)?.lowercase(Locale.US)?.trim() ?: ""
            val action = p.group(2)?.trim() ?: ""

            val (eventType, conditionVal) = when {
                cond.contains("headphone") || cond.contains("earbud") -> "HEADSET_CONNECTED" to "*"
                cond.contains("battery") && cond.contains("20") -> "BATTERY_LEVEL" to "<= 20"
                cond.contains("battery") -> "BATTERY_LEVEL" to "<= 20"
                cond.contains("charg") || cond.contains("plug") -> "POWER_CONNECTED" to "*"
                else -> return null
            }

            addRule(context, eventType, conditionVal, action)
            return "⚙️ Automation Registered:\nTrigger: When [$eventType]\nAction: $action\n✓ Will run deterministically with zero latency."
        }
        return null
    }
}
