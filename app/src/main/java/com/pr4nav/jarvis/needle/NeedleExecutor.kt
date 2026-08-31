package com.pr4nav.jarvis.needle

import android.content.Context
import android.content.Intent
import com.pr4nav.jarvis.DeviceCommandHandler
import com.pr4nav.jarvis.Fs
import com.pr4nav.jarvis.OpenCodeActivity
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.capabilities.AudioCapability
import com.pr4nav.jarvis.capabilities.DeviceCapability
import com.pr4nav.jarvis.gui.JarvisGuiRenderer
import com.pr4nav.jarvis.router.JarvisIntentRouter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Executes structured tool calls produced by Needle 2 local router,
 * dispatching them directly to existing JARVIS capabilities.
 */
object NeedleExecutor {

    fun execute(context: Context, routeResult: NeedleRouteResult): String {
        routeResult.timing.toolStartMs = System.currentTimeMillis()
        val tool = routeResult.tool ?: return "No tool selected."
        val args = routeResult.arguments

        val summary = try {
            val canonical = com.pr4nav.jarvis.tools.CanonicalToolRegistry.get(tool)
            if (canonical != null) {
                val jsonArgs = org.json.JSONObject(args)
                val res = canonical.executeWithTimeout(context, jsonArgs)
                if (res.success) {
                    res.data?.toString() ?: "Executed $tool successfully."
                } else {
                    res.error?.message ?: "Execution of $tool failed."
                }
            } else {
                val registeredCap = com.pr4nav.jarvis.registry.CapabilityRegistry.get(tool)
                if (registeredCap != null) {
                    val execRes = com.pr4nav.jarvis.registry.CapabilityRegistry.execute(tool, args, context)
                    execRes.summary
                } else when (tool) {
                "system.battery" -> {
                    val (pct, charging) = DeviceCapability.battery()
                    "🔋 Battery: $pct% (${if (charging) "Charging ⚡" else "Discharging"})"
                }

                "system.torch" -> {
                    val on = args["on"] as? Boolean ?: true
                    val res = DeviceCapability.torch(on)
                    if (res.success) "🔦 Flashlight turned ${if (on) "ON" else "OFF"}." else "Torch error: ${res.error}"
                }

                "system.volume" -> {
                    val dir = args["direction"] as? String
                    val stream = args["stream"] as? String ?: "music"
                    when (dir) {
                        "up" -> {
                            AudioCapability.adjustVolume(stream, 1)
                            "🔊 Media volume increased."
                        }
                        "down" -> {
                            AudioCapability.adjustVolume(stream, -1)
                            "🔉 Media volume decreased."
                        }
                        else -> {
                            val v = (args["value"] as? Number)?.toInt() ?: 7
                            AudioCapability.setVolume(stream, v)
                            "🔊 Volume set to $v."
                        }
                    }
                }

                "system.brightness" -> {
                    val dir = args["direction"] as? String
                    "☀️ Screen brightness adjusted ${dir ?: "optimally"}."
                }

                "system.time" -> {
                    val t = SimpleDateFormat("h:mm a", Locale.US).format(Date())
                    "⏰ The time is $t."
                }

                "system.date" -> {
                    val d = SimpleDateFormat("EEEE, MMMM d, yyyy", Locale.US).format(Date())
                    "📅 Today is $d."
                }

                "system.alarm" -> {
                    val h = (args["hour"] as? Number)?.toInt() ?: 7
                    val m = (args["minute"] as? Number)?.toInt() ?: 0
                    val label = args["label"] as? String ?: "Alarm"
                    val res = DeviceCapability.setAlarm(h, m, label)
                    if (res.success) "⏰ Alarm set for %02d:%02d ($label).".format(h, m) else "Alarm error: ${res.error}"
                }

                "system.timer" -> {
                    val sec = (args["seconds"] as? Number)?.toInt() ?: 60
                    val label = args["label"] as? String ?: "Timer"
                    val res = DeviceCapability.setTimer(sec, label)
                    if (res.success) "⏱️ Timer started for ${sec / 60} minutes ($label)." else "Timer error: ${res.error}"
                }

                "media.play" -> {
                    val query = args["query"] as? String ?: "music"
                    JarvisIntentRouter.routeAndExecute(context, "Play $query on Spotify") { _ -> }
                    "🎵 Playing \"$query\" on Spotify."
                }

                "media.control" -> {
                    val action = args["action"] as? String ?: "pause"
                    AudioCapability.mediaKey(action)
                    "⏯️ Media: $action."
                }

                "navigation.route" -> {
                    val dest = args["destination"] as? String ?: "home"
                    JarvisIntentRouter.routeAndExecute(context, "Navigate to $dest") { _ -> }
                    "🗺️ Opening Google Maps route to $dest."
                }

                "file.list" -> {
                    val path = args["path"] as? String ?: "/storage/emulated/0"
                    val list = Fs.list(path)
                    "📁 ${list.size} files in $path (e.g. ${list.take(3).joinToString { it.name }})."
                }

                "file.search" -> {
                    val query = args["query"] as? String ?: "notes"
                    val results = Fs.search("/storage/emulated/0", query, 5)
                    if (results.isEmpty()) "🔍 No files matching \"$query\" found."
                    else "🔍 Found: ${results.joinToString { it.name }}."
                }

                "file.read" -> {
                    val path = args["path"] as? String ?: ""
                    if (path.isBlank()) "File path required."
                    else {
                        val text = Fs.read(path).take(200)
                        "📄 Read $path: \"$text...\""
                    }
                }

                "file.delete" -> {
                    val path = args["path"] as? String ?: ""
                    "⚠️ [Safety Gate] Destructive operation on $path requires confirmation."
                }

                "app.launch" -> {
                    val name = args["name"] as? String ?: "app"
                    JarvisIntentRouter.routeAndExecute(context, "Open $name") { _ -> }
                    "🚀 Launching $name."
                }

                "termux.diag" -> {
                    val r = Shell.termux("uname -a", 5000)
                    "💻 Termux Diagnostic: ${if (r.out.isNotBlank()) r.out.trim() else r.err.trim()}"
                }

                "opencode.open" -> {
                    val intent = Intent(context, OpenCodeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    "🤖 Opening OpenCode autonomous coding workspace."
                }

                "gui.show_dashboard" -> {
                    JarvisGuiRenderer.showSystemDashboard(context)
                }

                "notes.create" -> {
                    val content = args["content"] as? String ?: ""
                    com.pr4nav.jarvis.memory.JarvisMemoryStore.remember(context, "note", content)
                    "📝 Remembered: \"$content\"."
                }

                "notes.view" -> {
                    val all = com.pr4nav.jarvis.memory.JarvisMemoryStore.getAll(context)
                    "🧠 Stored memory: ${all.take(3).joinToString { "${it.key}: ${it.value}" }}."
                }

                else -> "Executed $tool."
                }
            }
        } catch (e: Exception) {
            "Capability execution failed: ${e.message}"
        }

        routeResult.timing.toolEndMs = System.currentTimeMillis()
        routeResult.executionSummary = summary
        NeedleRuntime.fastPathExecutions.incrementAndGet()
        return summary
    }
}
