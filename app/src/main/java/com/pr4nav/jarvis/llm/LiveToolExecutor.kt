package com.pr4nav.jarvis.llm

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Shared on-device tool execution for Live turns (route + playground).
 * Pure dispatch: no UI, no threads — callers own threading.
 */
object LiveToolExecutor {

    fun execute(name: String, argsJson: String, context: Context?): JSONObject {
        val args = try {
            JSONObject(argsJson)
        } catch (_: Exception) {
            JSONObject()
        }
        val result = JSONObject()
        try {
            when (name) {
                "run_shell" -> {
                    val cmd = args.optString("command", "").trim()
                    if (cmd.isBlank()) {
                        result.put("ok", false).put("note", "empty command")
                    } else {
                        val r = try {
                            com.pr4nav.jarvis.Shell.termux(cmd, 30_000)
                        } catch (e: Exception) {
                            return JSONObject().put("ok", false)
                                .put("note", e.message ?: "shell failed")
                        }
                        val out = (if (r.out.isNotBlank()) r.out else r.err).take(2000)
                        result.put("ok", (r.rc ?: -1) == 0)
                            .put("exit_code", r.rc ?: -1)
                            .put("output", out)
                    }
                }
                "read_file" -> {
                    val path = args.optString("path", "").trim()
                    if (path.isBlank()) {
                        result.put("ok", false).put("note", "empty path")
                    } else {
                        try {
                            result.put("ok", true)
                                .put("content", com.pr4nav.jarvis.Fs.read(path).take(4000))
                        } catch (e: Exception) {
                            result.put("ok", false).put("note", e.message ?: "read failed")
                        }
                    }
                }
                "get_time" -> {
                    result.put("ok", true).put(
                        "time",
                        SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
                    )
                }
                "take_screenshot" -> {
                    val shot = captureScreenshot(context)
                    if (shot != null) {
                        result.put("ok", true)
                            .put("note", "Screenshot captured (${shot.size} bytes PNG).")
                            .put("bytes", shot.size)
                    } else {
                        result.put("ok", false)
                            .put("note", "Screenshot not permitted from this sandbox.")
                    }
                }
                "execute_device_tool" -> {
                    val inner = args.optString("tool_name", "").trim()
                    val ctx = context
                    if (inner.isBlank()) {
                        result.put("ok", false).put("note", "empty tool_name")
                    } else if (ctx == null) {
                        result.put("ok", false).put("note", "no android context")
                    } else {
                        val innerArgs = args.optJSONObject("parameters") ?: JSONObject()
                        try {
                            val toolRes = com.pr4nav.jarvis.tools.CanonicalToolRegistry.execute(
                                ctx, inner, innerArgs
                            )
                            val out = (toolRes.data?.toString()
                                ?: toolRes.error?.message
                                ?: toolRes.status.name).take(2000)
                            result.put("ok", toolRes.success).put("output", out)
                        } catch (e: Exception) {
                            result.put("ok", false).put("note", e.message ?: "device tool failed")
                        }
                    }
                }
                else -> result.put("ok", false).put("note", "unknown tool: $name")
            }
        } catch (e: Exception) {
            try {
                result.put("ok", false).put("note", e.message ?: "failed")
            } catch (_: Exception) { }
        }
        return result
    }

    /**
     * Best-effort screencap. Returns PNG bytes or null (needs shell/root).
     * Callers display the bytes themselves when non-null.
     */
    fun captureScreenshot(context: Context?): ByteArray? {
        return try {
            val dir = try {
                context?.cacheDir ?: java.io.File("/tmp")
            } catch (_: Exception) {
                java.io.File("/tmp")
            }
            val f = java.io.File(dir, "live_shot_${System.currentTimeMillis()}.png")
            val r = com.pr4nav.jarvis.Shell.local("screencap -p ${f.absolutePath}", 10_000)
            if (r.rc == 0 && f.exists() && f.length() > 1000) {
                val bytes = f.readBytes()
                try {
                    f.delete()
                } catch (_: Exception) { }
                bytes
            } else null
        } catch (_: Exception) {
            null
        }
    }
}
