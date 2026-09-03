package com.pr4nav.jarvis.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import com.pr4nav.jarvis.tools.CanonicalToolRegistry
import com.pr4nav.jarvis.voice.JarvisVoiceEngine
import org.json.JSONObject

/**
 * Controlled JavaScript interface providing safe, audited bridge access between
 * the generated web-app in JarvisBrowser and JARVIS capabilities.
 */
class JarvisBrowserBridge(
    private val context: Context,
    private val webView: WebView,
    private val currentApp: JarvisBrowserApp,
    private val voiceEngine: JarvisVoiceEngine? = null,
    private val onSaveRequested: ((title: String, desc: String) -> Unit)? = null,
    private val onCloseRequested: (() -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "JarvisBrowserBridge"

        // Safe tool whitelist permitted from inside sandboxed web-apps
        private val ALLOWED_BRIDGE_TOOLS = setOf(
            "device_info",
            "battery_status",
            "get_current_time",
            "calculate",
            "timer_set",
            "alarm_set",
            "search_web",
            "scrape_web_content",
            "read_clipboard",
            "write_clipboard",
            "get_installed_apps",
            "get_network_status",
            "media_control",
            "volume_set",
            "flashlight_toggle"
        )
    }

    @JavascriptInterface
    fun speak(text: String?) {
        if (text.isNullOrBlank()) return
        Log.i(TAG, "Web app requested speech: $text")
        mainHandler.post {
            try {
                voiceEngine?.speak(text, interrupt = false)
            } catch (e: Exception) {
                Log.w(TAG, "Failed speaking text from web app: ${e.message}")
            }
        }
    }

    @JavascriptInterface
    fun toast(message: String?) {
        if (message.isNullOrBlank()) return
        mainHandler.post {
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun close() {
        mainHandler.post {
            onCloseRequested?.invoke()
        }
    }

    @JavascriptInterface
    fun saveCurrentApp(title: String?, desc: String?) {
        mainHandler.post {
            val t = title?.takeIf { it.isNotBlank() } ?: currentApp.title
            val d = desc?.takeIf { it.isNotBlank() } ?: currentApp.description
            JarvisBrowserAppManager.saveTemporaryApp(context, currentApp.id, t, d)
            onSaveRequested?.invoke(t, d)
            Toast.makeText(context, "Saved to JarvisBrowser Apps: $t", Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    fun callTool(toolName: String?, jsonArgs: String?, callbackId: String?) {
        val tName = toolName?.trim() ?: ""
        val cbId = callbackId?.trim() ?: ""
        if (tName.isEmpty() || cbId.isEmpty()) return

        // Security Guard: Check whitelist
        if (!ALLOWED_BRIDGE_TOOLS.contains(tName.lowercase())) {
            val err = "Security Violation: Tool '$tName' is not permitted from JarvisBrowser sandbox."
            Log.w(TAG, err)
            sendCallback(cbId, null, err)
            return
        }

        val args = try {
            if (!jsonArgs.isNullOrBlank()) JSONObject(jsonArgs) else JSONObject()
        } catch (e: Exception) {
            sendCallback(cbId, null, "Invalid JSON arguments: ${e.message}")
            return
        }

        // Execute via Canonical Tool Registry safely off the UI thread
        Thread {
            try {
                val result = CanonicalToolRegistry.execute(context, tName, args)
                val jsonStr = result.toJsonObject().toString()
                mainHandler.post {
                    sendCallback(cbId, jsonStr, if (result.success) null else result.error?.message)
                }
            } catch (e: Exception) {
                mainHandler.post {
                    sendCallback(cbId, null, "Tool execution failed: ${e.message}")
                }
            }
        }.start()
    }

    private fun sendCallback(callbackId: String, resultJson: String?, errorMsg: String?) {
        val resLiteral = if (resultJson != null) "'${escapeJs(resultJson)}'" else "null"
        val errLiteral = if (errorMsg != null) "'${escapeJs(errorMsg)}'" else "null"
        val js = "window.__jarvisOnToolResult('$callbackId', $resLiteral, $errLiteral);"
        mainHandler.post {
            webView.evaluateJavascript(js, null)
        }
    }

    private fun escapeJs(s: String): String =
        s.replace("\\", "\\\\")
            .replace("'", "\\'")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
}
