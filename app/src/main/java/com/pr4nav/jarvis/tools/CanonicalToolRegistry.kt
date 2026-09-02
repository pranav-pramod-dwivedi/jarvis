package com.pr4nav.jarvis.tools

import android.content.Context
import com.pr4nav.jarvis.capabilities.*
import com.pr4nav.jarvis.registry.CapabilityRegistry
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

object CanonicalToolRegistry {

    private val tools = ConcurrentHashMap<String, CanonicalToolDef>()
    @Volatile private var initialized = false

    @Synchronized
    fun init(context: Context? = null) {
        if (initialized) return
        registerDefaults()
        initialized = true
    }

    fun register(tool: CanonicalToolDef) {
        tools[tool.name] = tool
    }

    fun get(name: String): CanonicalToolDef? = tools[name]

    fun all(): List<CanonicalToolDef> = tools.values.toList()

    fun names(): List<String> = tools.keys.toList()

    fun schemaJson(): JSONArray {
        val arr = JSONArray()
        for (t in tools.values) {
            val obj = JSONObject().apply {
                put("name", t.name)
                put("description", t.description)
                put("parameters", t.argumentSchema)
                put("permissions", JSONArray(t.requiredPermissions))
                put("timeoutMs", t.defaultTimeoutMs)
            }
            arr.put(obj)
        }
        return arr
    }

    fun execute(context: Context, name: String, args: JSONObject, timeoutMs: Long? = null): ToolResult {
        val tool = tools[name] ?: return ToolResult.failure("TOOL_NOT_FOUND", "No canonical tool registered with name: $name")
        return tool.executeWithTimeout(context, args, timeoutMs)
    }

    private fun registerDefaults() {
        SiriAssistantToolCatalog.registerAll(::register)

        // system.torch (and aliases: torch, flashlight_on, flashlight_off, set_flashlight)
        val torchDef = CanonicalToolDef(
            name = "system.torch",
            description = "Turns the device flashlight/torch on or off.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("state", JSONObject().put("type", "boolean").put("description", "true to turn on, false to turn off"))
                })
                put("required", JSONArray().put("state"))
            },
            backend = ToolBackend.ANDROID_NATIVE,
            supportedBackends = setOf(ToolBackend.ANDROID_NATIVE),
            defaultTimeoutMs = 3_000L,
            verify = { _, args, res ->
                res.success
            },
            execute = { _, args ->
                val state = args.optBoolean("state", true)
                val capRes = com.pr4nav.jarvis.capabilities.DeviceCapability.torch(state)
                if (capRes.success) {
                    ToolResult.ok(
                        JSONObject().apply {
                            put("action", "TORCH_TOGGLED")
                            put("state", if (state) "on" else "off")
                            put("message", if (state) "Flashlight turned on." else "Flashlight turned off.")
                        }
                    )
                } else {
                    ToolResult.failure("TORCH_ERROR", capRes.error ?: "Torch failed")
                }
            }
        )
        register(torchDef)
        register(torchDef.copy(name = "torch"))
        register(torchDef.copy(name = "set_flashlight"))
        register(torchDef.copy(name = "flashlight_on", execute = { ctx, _ -> torchDef.execute(ctx, JSONObject().put("state", true)) }))
        register(torchDef.copy(name = "flashlight_off", execute = { ctx, _ -> torchDef.execute(ctx, JSONObject().put("state", false)) }))

        // system.volume
        val volumeDef = CanonicalToolDef(
            name = "system.volume",
            description = "Adjusts device volume (raise, lower, mute, unmute).",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().put("type", "string").put("description", "raise, lower, or mute"))
                })
            },
            backend = ToolBackend.ANDROID_NATIVE,
            defaultTimeoutMs = 3_000L,
            execute = { ctx, args ->
                val action = args.optString("action", "raise").lowercase()
                val am = ctx.getSystemService(android.content.Context.AUDIO_SERVICE) as android.media.AudioManager
                when (action) {
                    "raise", "up" -> {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                        ToolResult.ok(JSONObject().put("action", "VOLUME_RAISED"))
                    }
                    "lower", "down" -> {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_LOWER, android.media.AudioManager.FLAG_SHOW_UI)
                        ToolResult.ok(JSONObject().put("action", "VOLUME_LOWERED"))
                    }
                    "mute" -> {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_MUTE, android.media.AudioManager.FLAG_SHOW_UI)
                        ToolResult.ok(JSONObject().put("action", "VOLUME_MUTED"))
                    }
                    else -> {
                        am.adjustStreamVolume(android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.ADJUST_RAISE, android.media.AudioManager.FLAG_SHOW_UI)
                        ToolResult.ok(JSONObject().put("action", "VOLUME_ADJUSTED"))
                    }
                }
            }
        )
        register(volumeDef)
        register(volumeDef.copy(name = "volume"))

        // system.battery & get_battery
        val batteryDef = CanonicalToolDef(
            name = "system.battery",
            description = "Checks device battery level and charging state.",
            argumentSchema = JSONObject().apply { put("type", "object") },
            backend = ToolBackend.ANDROID_NATIVE,
            defaultTimeoutMs = 2_000L,
            execute = { _, _ ->
                val (pct, charging) = com.pr4nav.jarvis.capabilities.DeviceCapability.battery()
                ToolResult.ok(
                    JSONObject().apply {
                        put("level", pct)
                        put("charging", charging)
                        put("message", "Battery is at $pct% (${if (charging) "charging" else "discharging"}).")
                    }
                )
            }
        )
        register(batteryDef)
        register(batteryDef.copy(name = "get_battery"))

        // system.screenshot & take_screenshot
        val screenshotDef = CanonicalToolDef(
            name = "system.screenshot",
            description = "Captures a device screenshot.",
            argumentSchema = JSONObject().apply { put("type", "object") },
            backend = ToolBackend.ANDROID_NATIVE,
            defaultTimeoutMs = 5_000L,
            execute = { ctx, _ ->
                ToolResult.ok(JSONObject().put("action", "SCREENSHOT_REQUESTED"))
            }
        )
        register(screenshotDef)
        register(screenshotDef.copy(name = "take_screenshot"))

        // media.play
        val mediaPlayDef = CanonicalToolDef(
            name = "media.play",
            description = "Plays requested music track, song, or artist in default media player.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Song or artist name to play"))
                })
                put("required", JSONArray().put("query"))
            },
            backend = ToolBackend.ANDROID_NATIVE,
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val q = args.optString("query").trim()
                ToolResult.ok(JSONObject().put("action", "PLAYING_MEDIA").put("query", q))
            }
        )
        register(mediaPlayDef)

        // open_app
        register(CanonicalToolDef(
            name = "open_app",
            description = "Opens or launches an installed application by package name or app name.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("app", JSONObject().put("type", "string").put("description", "Name or package of the app"))
                })
                put("required", JSONArray().put("app"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val appQuery = args.optString("app").trim()
                if (appQuery.isEmpty()) {
                    return@CanonicalToolDef ToolResult.invalidArguments("No app name or package specified")
                }

                val target = com.pr4nav.jarvis.capabilities.AppCapability.find(appQuery)
                val targetPkg = target?.pkg ?: appQuery

                val pm = ctx.packageManager
                val intent = pm.getLaunchIntentForPackage(targetPkg)
                if (intent != null) {
                    try {
                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(intent)
                        val friendlyLabel = target?.label ?: com.pr4nav.jarvis.response.AnswerSynthesizer.cleanFriendlyAppName(targetPkg)
                        ToolResult.ok(
                            JSONObject().apply {
                                put("action", "APP_LAUNCHED")
                                put("label", friendlyLabel)
                                put("package", targetPkg)
                                put("message", "Opening $friendlyLabel.")
                            }
                        )
                    } catch (e: Exception) {
                        ToolResult.failure("LAUNCH_ERROR", e.message ?: "Failed to start activity")
                    }
                } else {
                    ToolResult.notFound("App '$appQuery' (no launchable package found on device)")
                }
            }
        ))

        // close_app
        register(CanonicalToolDef(
            name = "close_app",
            description = "Closes or stops a running application.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("package", JSONObject().put("type", "string").put("description", "Target application package name or app name"))
                })
                put("required", JSONArray().put("package"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val query = args.optString("package").trim()
                if (query.isEmpty()) {
                    return@CanonicalToolDef ToolResult.invalidArguments("No app name or package specified to close")
                }

                val target = com.pr4nav.jarvis.capabilities.AppCapability.find(query)
                val pkg = target?.pkg ?: query

                try {
                    val am = ctx.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    am.killBackgroundProcesses(pkg)
                    ToolResult.ok(
                        JSONObject().apply {
                            put("action", "BACKGROUND_PROCESSES_KILLED")
                            put("package", pkg)
                            put("note", "Killed background processes for $pkg. Complete force-stop requires system/root access.")
                        }
                    )
                } catch (e: Exception) {
                    ToolResult.failure("CLOSE_ERROR", e.message ?: "Failed to close app")
                }
            }
        ))

        // navigate
        register(CanonicalToolDef(
            name = "navigate",
            description = "Starts navigation or directions to a destination address or place.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("destination", JSONObject().put("type", "string").put("description", "Destination address, place, or coordinates"))
                })
                put("required", JSONArray().put("destination"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val destRaw = args.optString("destination").trim()
                if (destRaw.isEmpty()) {
                    return@CanonicalToolDef ToolResult.invalidArguments("No destination specified for navigation")
                }

                // Check Jarvis memory store for stored locations (e.g., "home", "work", "office")
                val memoryLocations = com.pr4nav.jarvis.memory.JarvisMemoryStore.recall(ctx, destRaw)
                val resolvedDest = memoryLocations.firstOrNull()?.value ?: destRaw

                val uri = android.net.Uri.parse("google.navigation:q=${android.net.Uri.encode(resolvedDest)}")
                val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, uri).apply {
                    setPackage("com.google.android.apps.maps")
                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                }

                try {
                    ctx.startActivity(intent)
                    ToolResult.ok(
                        JSONObject().apply {
                            put("action", "NAVIGATION_STARTED")
                            put("destination", resolvedDest)
                            put("resolvedFromMemory", resolvedDest != destRaw)
                        }
                    )
                } catch (e: Exception) {
                    // Fallback to web maps turn-by-turn url
                    try {
                        val web = android.content.Intent(
                            android.content.Intent.ACTION_VIEW,
                            android.net.Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${android.net.Uri.encode(resolvedDest)}")
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(web)
                        ToolResult.ok(
                            JSONObject().apply {
                                put("action", "MAPS_WEB_NAVIGATION_STARTED")
                                put("destination", resolvedDest)
                            }
                        )
                    } catch (e2: Exception) {
                        ToolResult.failure("NAVIGATION_ERROR", e2.message ?: "Failed to open navigation")
                    }
                }
            }
        ))

        // call_contact
        register(CanonicalToolDef(
            name = "call_contact",
            description = "Initiates a phone call or opens dialer for a phone number or contact.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("number", JSONObject().put("type", "string").put("description", "Phone number or contact name"))
                })
                put("required", JSONArray().put("number"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val target = args.optString("number").trim()
                if (target.isEmpty()) {
                    return@CanonicalToolDef ToolResult.invalidArguments("No contact name or phone number specified")
                }

                // Resolve contact
                val resolution = com.pr4nav.jarvis.capabilities.ContactResolver.resolve(ctx, target)
                val resolvedNumber: String
                val resolvedName: String
                when (resolution) {
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Single -> {
                        resolvedNumber = resolution.contact.number
                        resolvedName = resolution.contact.name
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Ambiguous -> {
                        val names = resolution.matches.map { "${it.name} (${it.number})" }
                        val candidates = resolution.matches.mapIndexed { idx, c ->
                            com.pr4nav.jarvis.context.CandidateItem(idx + 1, c.name, c.number)
                        }
                        com.pr4nav.jarvis.context.ContextManager.setCandidateList("call_contact", candidates)
                        return@CanonicalToolDef ToolResult.ambiguous(
                            "Multiple contacts found for '$target'",
                            names
                        )
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.NotFound -> {
                        return@CanonicalToolDef ToolResult.notFound("Contact '$target'")
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.PermissionRequired -> {
                        // Fallback: If contacts cannot be read, check if target is a raw phone number
                        val digits = target.count { it.isDigit() }
                        if (digits >= 7) {
                            resolvedNumber = target.replace(Regex("[^0-9+]"), "")
                            resolvedName = target
                        } else {
                            return@CanonicalToolDef ToolResult.permissionDenied(android.Manifest.permission.READ_CONTACTS)
                        }
                    }
                }

                // Check CALL_PHONE permission for direct call initiation
                val hasCallPermission = try {
                    ctx.checkCallingOrSelfPermission(android.Manifest.permission.CALL_PHONE) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) { false }

                if (hasCallPermission) {
                    try {
                        val callIntent = android.content.Intent(android.content.Intent.ACTION_CALL, android.net.Uri.parse("tel:$resolvedNumber")).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(callIntent)
                        ToolResult.ok(
                            JSONObject().apply {
                                put("action", "CALL_INITIATED")
                                put("contact", resolvedName)
                                put("number", resolvedNumber)
                            }
                        )
                    } catch (e: Exception) {
                        ToolResult.failure("CALL_ERROR", e.message ?: "Failed to initiate call")
                    }
                } else {
                    // ACTION_DIAL opens the dialer prefilled without placing the call automatically
                    try {
                        val dialIntent = android.content.Intent(android.content.Intent.ACTION_DIAL, android.net.Uri.parse("tel:$resolvedNumber")).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(dialIntent)
                        ToolResult.requiresUser(
                            "DIALER_OPENED",
                            "Opened dialer for $resolvedName ($resolvedNumber). Direct call requires CALL_PHONE permission."
                        )
                    } catch (e: Exception) {
                        ToolResult.failure("DIAL_ERROR", e.message ?: "Failed to open dialer")
                    }
                }
            }
        ))

        // send_message
        register(CanonicalToolDef(
            name = "send_message",
            description = "Sends an SMS or chat message to a recipient.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("recipient", JSONObject().put("type", "string").put("description", "Phone number or contact name"))
                    put("message", JSONObject().put("type", "string").put("description", "Message text body"))
                })
                put("required", JSONArray().put("recipient").put("message"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val rec = args.optString("recipient").trim()
                val msg = args.optString("message").trim()
                if (rec.isEmpty() || msg.isEmpty()) {
                    return@CanonicalToolDef ToolResult.invalidArguments("Recipient and message cannot be empty")
                }

                // Resolve contact
                val resolution = com.pr4nav.jarvis.capabilities.ContactResolver.resolve(ctx, rec)
                val targetNumber: String
                val targetName: String
                when (resolution) {
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Single -> {
                        targetNumber = resolution.contact.number
                        targetName = resolution.contact.name
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Ambiguous -> {
                        val names = resolution.matches.map { "${it.name} (${it.number})" }
                        return@CanonicalToolDef ToolResult.ambiguous("Multiple recipients match '$rec'", names)
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.NotFound -> {
                        val digits = rec.count { it.isDigit() }
                        if (digits >= 7) {
                            targetNumber = rec.replace(Regex("[^0-9+]"), "")
                            targetName = rec
                        } else {
                            return@CanonicalToolDef ToolResult.notFound("Recipient '$rec'")
                        }
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.PermissionRequired -> {
                        val digits = rec.count { it.isDigit() }
                        if (digits >= 7) {
                            targetNumber = rec.replace(Regex("[^0-9+]"), "")
                            targetName = rec
                        } else {
                            return@CanonicalToolDef ToolResult.permissionDenied(android.Manifest.permission.READ_CONTACTS)
                        }
                    }
                }

                // Check SEND_SMS permission
                val hasSmsPermission = try {
                    ctx.checkCallingOrSelfPermission(android.Manifest.permission.SEND_SMS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED
                } catch (_: Exception) { false }

                if (hasSmsPermission) {
                    try {
                        val smsManager = if (android.os.Build.VERSION.SDK_INT >= 31) {
                            ctx.getSystemService(android.telephony.SmsManager::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            android.telephony.SmsManager.getDefault()
                        }
                        smsManager.sendTextMessage(targetNumber, null, msg, null, null)
                        ToolResult.ok(
                            JSONObject().apply {
                                put("action", "SMS_SENT")
                                put("recipient", targetName)
                                put("number", targetNumber)
                                put("message", msg)
                            }
                        )
                    } catch (e: Exception) {
                        ToolResult.failure("SMS_SEND_ERROR", e.message ?: "Failed to send SMS")
                    }
                } else {
                    // Fallback to composer Intent
                    try {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            data = android.net.Uri.parse("sms:$targetNumber")
                            putExtra("sms_body", msg)
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        ToolResult.requiresUser(
                            "SMS_COMPOSER_OPENED",
                            "Opened SMS composer for $targetName ($targetNumber). Direct background send requires SEND_SMS permission."
                        )
                    } catch (e: Exception) {
                        ToolResult.failure("SMS_COMPOSER_ERROR", e.message ?: "Failed to open SMS composer")
                    }
                }
            }
        ))

        // read_file
        register(CanonicalToolDef(
            name = "read_file",
            description = "Reads content from a local file path.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().put("type", "string").put("description", "Absolute or relative file path"))
                })
                put("required", JSONArray().put("path"))
            },
            defaultTimeoutMs = 10_000L,
            execute = { _, args ->
                val path = args.optString("path").trim()
                if (path.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Path cannot be empty")
                try {
                    val content = com.pr4nav.jarvis.Fs.read(path)
                    ToolResult.ok(
                        JSONObject().apply {
                            put("path", path)
                            put("content", content)
                            put("length", content.length)
                        }
                    )
                } catch (e: Exception) {
                    if (e.message?.contains("not found", ignoreCase = true) == true || !java.io.File(path).exists()) {
                        ToolResult.notFound("File '$path'")
                    } else {
                        ToolResult.failure("READ_FILE_ERROR", e.message ?: "Failed to read file")
                    }
                }
            }
        ))

        // write_file
        register(CanonicalToolDef(
            name = "write_file",
            description = "Writes text content to a local file path.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().put("type", "string").put("description", "Target file path"))
                    put("content", JSONObject().put("type", "string").put("description", "Text content to write"))
                })
                put("required", JSONArray().put("path").put("content"))
            },
            defaultTimeoutMs = 10_000L,
            execute = { _, args ->
                val path = args.optString("path").trim()
                val content = args.optString("content")
                if (path.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Path cannot be empty")
                try {
                    com.pr4nav.jarvis.Fs.write(path, content)
                    ToolResult.ok(
                        JSONObject().apply {
                            put("action", "FILE_WRITTEN")
                            put("path", path)
                            put("bytesWritten", content.toByteArray().size)
                        }
                    )
                } catch (e: Exception) {
                    ToolResult.failure("WRITE_FILE_ERROR", e.message ?: "Failed to write file")
                }
            }
        ))

        // search_files
        register(CanonicalToolDef(
            name = "search_files",
            description = "Searches for files matching a name query.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Search query or file pattern"))
                    put("path", JSONObject().put("type", "string").put("description", "Base directory to search in (optional)"))
                })
                put("required", JSONArray().put("query"))
            },
            defaultTimeoutMs = 15_000L,
            execute = { _, args ->
                val q = args.optString("query").trim()
                val base = args.optString("path", "/storage/emulated/0")
                if (q.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Search query cannot be empty")
                try {
                    val entries = com.pr4nav.jarvis.Fs.search(base, q, 30)
                    if (entries.isEmpty()) {
                        ToolResult.notFound("Files matching query '$q' under $base")
                    } else {
                        val arr = JSONArray()
                        for (e in entries) {
                            arr.put(
                                JSONObject().apply {
                                    put("name", e.name)
                                    put("path", e.path)
                                    put("isDir", e.isDir)
                                    put("size", e.size)
                                    put("modified", e.modified)
                                }
                            )
                        }
                        ToolResult.ok(
                            JSONObject().apply {
                                put("query", q)
                                put("count", entries.size)
                                put("results", arr)
                            }
                        )
                    }
                } catch (e: Exception) {
                    ToolResult.failure("SEARCH_ERROR", e.message ?: "Failed searching files")
                }
            }
        ))

        // run_command
        register(CanonicalToolDef(
            name = "run_command",
            description = "Executes a shell command in Termux, Ubuntu PRoot, or local Android shell environment.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("command", JSONObject().put("type", "string").put("description", "Shell command line string"))
                    put("inUbuntu", JSONObject().put("type", "boolean").put("description", "Whether to run inside Ubuntu PRoot (default true)"))
                })
                put("required", JSONArray().put("command"))
            },
            defaultTimeoutMs = 30_000L,
            execute = { _, args ->
                val cmd = args.optString("command").trim()
                if (cmd.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Command cannot be empty")
                val guardErr = com.pr4nav.jarvis.CmdGuard.check(cmd)
                if (guardErr != null) {
                    return@CanonicalToolDef ToolResult.failure("FORBIDDEN", "Command blocked by safety policy: $guardErr")
                }
                val inUbuntu = args.optBoolean("inUbuntu", true)

                // Try Termux/Ubuntu execution first
                var res = com.pr4nav.jarvis.Shell.termux(cmd, 30_000L, inUbuntu = inUbuntu)
                // If Termux bridge is unavailable and we didn't require PRoot-specific packages, fallback to local Android shell
                if (res.timedOut && res.err.contains("bridge unavailable")) {
                    res = com.pr4nav.jarvis.Shell.local(cmd, 15_000L)
                }

                if (res.timedOut) {
                    ToolResult.timeout(res.ms)
                } else if (res.rc == 0) {
                    ToolResult.ok(
                        JSONObject().apply {
                            put("stdout", res.out)
                            put("stderr", res.err)
                            put("exitCode", res.rc)
                            put("backend", res.via)
                            put("durationMs", res.ms)
                        }
                    )
                } else {
                    ToolResult.failure(
                        "COMMAND_FAILED",
                        "Command exited with code ${res.rc}: ${if (res.err.isNotBlank()) res.err else res.out}"
                    )
                }
            }
        ))

        // get_location
        register(CanonicalToolDef(
            name = "get_location",
            description = "Returns current device geographical location (latitude, longitude).",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 15_000L,
            execute = { _, _ ->
                val cap = com.pr4nav.jarvis.capabilities.LocationCapability.current(10_000)
                if (cap.success) {
                    val d = cap.data ?: "{}"
                    ToolResult.ok(JSONObject(d))
                } else if (cap.error?.contains("permission", ignoreCase = true) == true) {
                    ToolResult.permissionDenied("ACCESS_FINE_LOCATION")
                } else {
                    ToolResult.failure("LOCATION_FAILED", cap.error ?: "Location unavailable")
                }
            }
        ))

        // get_battery
        register(CanonicalToolDef(
            name = "get_battery",
            description = "Gets battery level percentage and charging state.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 3_000L,
            execute = { ctx, _ ->
                val res = CapabilityRegistry.execute("system.battery", emptyMap(), ctx)
                if (res.success) ToolResult.ok(res.data ?: res.summary) else ToolResult.failure("BATTERY_FAILED", res.summary)
            }
        ))

        // get_wifi
        register(CanonicalToolDef(
            name = "get_wifi",
            description = "Gets current Wi-Fi connection status and SSID.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 3_000L,
            execute = { ctx, _ ->
                val res = CapabilityRegistry.execute("system.wifi.state", emptyMap(), ctx)
                if (res.success) ToolResult.ok(res.data ?: res.summary) else ToolResult.failure("WIFI_FAILED", res.summary)
            }
        ))

        // system.bluetooth & set_bluetooth
        val bluetoothDef = CanonicalToolDef(
            name = "system.bluetooth",
            description = "Turns the device Bluetooth on or off.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("state", JSONObject().put("type", "boolean").put("description", "true to enable Bluetooth, false to disable"))
                })
                put("required", JSONArray().put("state"))
            },
            backend = ToolBackend.ANDROID_NATIVE,
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val state = args.optBoolean("state", true)
                try {
                    val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    val adapter = bm?.adapter
                    if (adapter == null) {
                        return@CanonicalToolDef ToolResult.notSupported("Bluetooth", "Device does not have Bluetooth hardware")
                    }

                    var success = false
                    try {
                        @Suppress("DEPRECATION")
                        success = if (state) adapter.enable() else adapter.disable()
                    } catch (_: SecurityException) {}

                    if (!success) {
                        val shRes = com.pr4nav.jarvis.Shell.root("cmd bluetooth ${if (state) "enable" else "disable"}")
                        if (shRes.rc == 0) {
                            success = true
                        }
                    }

                    if (success) {
                        ToolResult.ok(
                            JSONObject().apply {
                                put("action", if (state) "BLUETOOTH_ENABLED" else "BLUETOOTH_DISABLED")
                                put("state", if (state) "ON" else "OFF")
                                put("message", if (state) "Bluetooth enabled." else "Bluetooth disabled.")
                            }
                        )
                    } else {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        ToolResult.requiresUser("BLUETOOTH_SETTINGS_OPENED", "Opened Bluetooth settings to toggle state.")
                    }
                } catch (e: Exception) {
                    ToolResult.failure("BT_ERROR", e.message ?: "Failed controlling Bluetooth")
                }
            }
        )
        register(bluetoothDef)
        register(bluetoothDef.copy(name = "set_bluetooth"))

        // get_bluetooth
        register(CanonicalToolDef(
            name = "get_bluetooth",
            description = "Gets Bluetooth enabled status and bonded devices.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 3_000L,
            execute = { ctx, _ ->
                try {
                    val bm = ctx.getSystemService(android.bluetooth.BluetoothManager::class.java)
                    val adapter = bm?.adapter
                    if (adapter == null) {
                        ToolResult.notSupported("Bluetooth", "Device does not have Bluetooth hardware")
                    } else {
                        val isEnabled = adapter.isEnabled
                        ToolResult.ok(
                            JSONObject().apply {
                                put("enabled", isEnabled)
                                put("state", if (isEnabled) "ON" else "OFF")
                            }
                        )
                    }
                } catch (e: SecurityException) {
                    ToolResult.permissionDenied("android.permission.BLUETOOTH_CONNECT")
                } catch (e: Exception) {
                    ToolResult.failure("BT_ERROR", e.message ?: "Failed to get Bluetooth status")
                }
            }
        ))

        // open_settings
        register(CanonicalToolDef(
            name = "open_settings",
            description = "Opens the device Settings page or a specific sub-setting.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("subpage", JSONObject().put("type", "string").put("description", "Specific subpage such as wifi, bluetooth, display, sound, apps, accessibility, developer, permissions"))
                })
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val subpage = args.optString("subpage").lowercase().trim()
                val (intentAction, requiresAppUri) = when (subpage) {
                    "wifi" -> android.provider.Settings.ACTION_WIFI_SETTINGS to false
                    "bluetooth" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS to false
                    "display" -> android.provider.Settings.ACTION_DISPLAY_SETTINGS to false
                    "sound", "audio" -> android.provider.Settings.ACTION_SOUND_SETTINGS to false
                    "apps", "applications" -> android.provider.Settings.ACTION_APPLICATION_SETTINGS to false
                    "accessibility" -> android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS to false
                    "developer", "development" -> android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS to false
                    "permissions", "permission" -> android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS to true
                    else -> android.provider.Settings.ACTION_SETTINGS to false
                }

                try {
                    val intent = android.content.Intent(intentAction).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (requiresAppUri) {
                            data = android.net.Uri.parse("package:${ctx.packageName}")
                        }
                    }
                    ctx.startActivity(intent)
                    ToolResult.ok(
                        JSONObject().apply {
                            put("opened", if (subpage.isNotEmpty()) "$subpage settings" else "Settings")
                            put("action", intentAction)
                            put("level", if (subpage.isNotEmpty()) "DIRECT_SUBPAGE" else "GENERIC_SETTINGS")
                        }
                    )
                } catch (e: Exception) {
                    // Fallback to general settings
                    try {
                        val fallback = android.content.Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(fallback)
                        ToolResult.requiresUser(
                            "SETTINGS_FALLBACK",
                            "Subpage '$subpage' not directly supported by this Android ROM; opened main Settings instead."
                        )
                    } catch (e2: Exception) {
                        ToolResult.failure("SETTINGS_ERROR", e2.message ?: "Failed to launch Settings")
                    }
                }
            }
        ))

        // open_system_page
        register(CanonicalToolDef(
            name = "open_system_page",
            description = "Opens an Android system settings or details page.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("action", JSONObject().put("type", "string").put("description", "Settings action name or page"))
                })
                put("required", JSONArray().put("action"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val action = args.optString("action")
                val res = CapabilityRegistry.execute("device.settings", mapOf("action" to action), ctx)
                if (res.success) ToolResult.ok(res.summary) else ToolResult.failure("PAGE_FAILED", res.summary)
            }
        ))

        // jarvis_environment
        register(CanonicalToolDef(
            name = "jarvis_environment",
            description = "Discovers real system environment, storage mounts, and toolchain state.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 10_000L,
            execute = { ctx, _ ->
                val snap = com.pr4nav.jarvis.environment.JarvisEnvironment.getSnapshot(ctx, forceRefresh = true)
                ToolResult.ok(snap.toJson())
            }
        ))

        // take_screenshot
        register(CanonicalToolDef(
            name = "take_screenshot",
            description = "Captures the current screen display.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 15_000L,
            execute = { _, _ ->
                val cap = com.pr4nav.jarvis.capabilities.ScreenshotCapability.capture()
                if (cap.success) {
                    val d = cap.data ?: "{}"
                    ToolResult.ok(JSONObject(d))
                } else if (cap.error?.contains("consent") == true) {
                    ToolResult.requiresUser("CONSENT_REQUIRED", cap.error)
                } else {
                    ToolResult.failure("SCREENSHOT_FAILED", cap.error ?: "Screenshot failed")
                }
            }
        ))

        // screen_info
        register(CanonicalToolDef(
            name = "screen_info",
            description = "Reads current screen UI node tree hierarchy via Accessibility.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, _ ->
                if (!com.pr4nav.jarvis.capabilities.AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef ToolResult.permissionDenied("Accessibility Service not enabled in Android Settings")
                }
                val inspect = com.pr4nav.jarvis.capabilities.AccessibilityCapability.inspect()
                if (inspect.success) {
                    val d = inspect.data ?: "[]"
                    ToolResult.ok(JSONArray(d))
                } else {
                    ToolResult.failure("SCREEN_INFO_FAILED", inspect.error ?: "Failed to read screen nodes")
                }
            }
        ))

        // click
        register(CanonicalToolDef(
            name = "click",
            description = "Performs a tap or click at screen coordinates (x, y) or text label.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("x", JSONObject().put("type", "integer").put("description", "X coordinate"))
                    put("y", JSONObject().put("type", "integer").put("description", "Y coordinate"))
                    put("text", JSONObject().put("type", "string").put("description", "Visible text label to click"))
                })
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, args ->
                if (!com.pr4nav.jarvis.capabilities.AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef ToolResult.permissionDenied("Accessibility Service not enabled in Android Settings")
                }
                val text = args.optString("text").trim()
                val x = args.optInt("x", -1)
                val y = args.optInt("y", -1)

                // 1. Semantic click takes highest priority
                if (text.isNotEmpty()) {
                    val res = com.pr4nav.jarvis.capabilities.AccessibilityCapability.clickByText(text)
                    if (res.success) {
                        return@CanonicalToolDef ToolResult.ok(JSONObject().put("action", "CLICKED_BY_TEXT").put("target", text))
                    } else {
                        return@CanonicalToolDef ToolResult.notFound("Element with text '$text' on current screen")
                    }
                }

                // 2. Coordinate fallback
                if (x >= 0 && y >= 0) {
                    val res = com.pr4nav.jarvis.capabilities.AccessibilityCapability.tap(x, y)
                    if (res.success) {
                        return@CanonicalToolDef ToolResult.ok(JSONObject().put("action", "TAPPED_COORDINATES").put("x", x).put("y", y))
                    } else {
                        return@CanonicalToolDef ToolResult.failure("TAP_FAILED", "Failed to dispatch gesture tap at ($x, $y)")
                    }
                }

                ToolResult.invalidArguments("Must specify either 'text' or coordinates ('x', 'y') to click")
            }
        ))

        // scroll
        register(CanonicalToolDef(
            name = "scroll",
            description = "Performs a scroll gesture forward or backward.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("direction", JSONObject().put("type", "string").put("description", "forward, backward, up, down"))
                })
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, args ->
                if (!com.pr4nav.jarvis.capabilities.AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef ToolResult.permissionDenied("Accessibility Service not enabled in Android Settings")
                }
                val dir = args.optString("direction", "forward").lowercase()
                val forward = (dir == "forward" || dir == "down")
                val res = com.pr4nav.jarvis.capabilities.AccessibilityCapability.scroll(forward)
                if (res.success) ToolResult.ok(JSONObject().put("action", "SCROLLED").put("direction", dir))
                else ToolResult.failure("SCROLL_FAILED", "No scrollable container found on current screen")
            }
        ))

        // type_text
        register(CanonicalToolDef(
            name = "type_text",
            description = "Types text into the currently focused input field.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().put("type", "string").put("description", "Text string to input"))
                })
                put("required", JSONArray().put("text"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, args ->
                if (!com.pr4nav.jarvis.capabilities.AccessibilityCapability.enabled()) {
                    return@CanonicalToolDef ToolResult.permissionDenied("Accessibility Service not enabled in Android Settings")
                }
                val text = args.optString("text")
                val res = com.pr4nav.jarvis.capabilities.AccessibilityCapability.type(text)
                if (res.success) ToolResult.ok(JSONObject().put("action", "TYPED").put("text", text))
                else ToolResult.failure("TYPE_FAILED", "No focused editable text field found")
            }
        ))

        // clipboard_get
        register(CanonicalToolDef(
            name = "clipboard_get",
            description = "Reads current text from system clipboard.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject())
            },
            defaultTimeoutMs = 3_000L,
            execute = { ctx, _ ->
                val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                val text = cb.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
                ToolResult.ok(JSONObject().put("clipboard", text).put("length", text.length))
            }
        ))

        // clipboard_set
        register(CanonicalToolDef(
            name = "clipboard_set",
            description = "Sets or copies text to the system clipboard.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("text", JSONObject().put("type", "string").put("description", "Text to place on clipboard"))
                })
                put("required", JSONArray().put("text"))
            },
            defaultTimeoutMs = 3_000L,
            execute = { ctx, args ->
                val text = args.optString("text")
                val cb = ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cb.setPrimaryClip(android.content.ClipData.newPlainText("JARVIS", text))
                ToolResult.ok(JSONObject().put("action", "COPIED").put("length", text.length))
            }
        ))

        // --- DEEP SYSTEM CAPABILITIES ---

        // 1. PHONE: call_history
        register(CanonicalToolDef(
            name = "call_history",
            description = "Reads recent calls from the Android call log.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("limit", JSONObject().put("type", "integer").put("description", "Number of recent calls to retrieve (default 10)"))
                })
            },
            requiredPermissions = listOf(android.Manifest.permission.READ_CALL_LOG),
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                if (ctx.checkCallingOrSelfPermission(android.Manifest.permission.READ_CALL_LOG) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    return@CanonicalToolDef ToolResult.permissionDenied(android.Manifest.permission.READ_CALL_LOG)
                }
                val limit = args.optInt("limit", 10).coerceIn(1, 50)
                val calls = JSONArray()
                try {
                    val cursor = ctx.contentResolver.query(
                        android.provider.CallLog.Calls.CONTENT_URI,
                        arrayOf(
                            android.provider.CallLog.Calls.NUMBER,
                            android.provider.CallLog.Calls.CACHED_NAME,
                            android.provider.CallLog.Calls.TYPE,
                            android.provider.CallLog.Calls.DATE,
                            android.provider.CallLog.Calls.DURATION
                        ),
                        null, null,
                        "${android.provider.CallLog.Calls.DATE} DESC LIMIT $limit"
                    )
                    cursor?.use { c ->
                        val numCol = c.getColumnIndex(android.provider.CallLog.Calls.NUMBER)
                        val nameCol = c.getColumnIndex(android.provider.CallLog.Calls.CACHED_NAME)
                        val typeCol = c.getColumnIndex(android.provider.CallLog.Calls.TYPE)
                        val dateCol = c.getColumnIndex(android.provider.CallLog.Calls.DATE)
                        val durCol = c.getColumnIndex(android.provider.CallLog.Calls.DURATION)
                        while (c.moveToNext()) {
                            calls.put(JSONObject().apply {
                                put("number", if (numCol >= 0) c.getString(numCol) ?: "" else "")
                                put("name", if (nameCol >= 0) c.getString(nameCol) ?: "" else "")
                                val typeInt = if (typeCol >= 0) c.getInt(typeCol) else 0
                                put("type", when (typeInt) {
                                    android.provider.CallLog.Calls.INCOMING_TYPE -> "INCOMING"
                                    android.provider.CallLog.Calls.OUTGOING_TYPE -> "OUTGOING"
                                    android.provider.CallLog.Calls.MISSED_TYPE -> "MISSED"
                                    android.provider.CallLog.Calls.REJECTED_TYPE -> "REJECTED"
                                    else -> "UNKNOWN"
                                })
                                put("timestamp", if (dateCol >= 0) c.getLong(dateCol) else 0L)
                                put("durationSeconds", if (durCol >= 0) c.getLong(durCol) else 0L)
                            })
                        }
                    }
                    ToolResult.ok(JSONObject().put("count", calls.length()).put("calls", calls))
                } catch (e: Exception) {
                    ToolResult.failure("CALL_LOG_ERROR", e.message ?: "Failed reading call log")
                }
            }
        ))

        // 2. CONTACTS: find_contact
        register(CanonicalToolDef(
            name = "find_contact",
            description = "Finds contact details and phone number for a person name.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("name", JSONObject().put("type", "string").put("description", "Person contact name"))
                })
                put("required", JSONArray().put("name"))
            },
            requiredPermissions = listOf(android.Manifest.permission.READ_CONTACTS),
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val name = args.optString("name").trim()
                if (name.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Name query cannot be empty")
                when (val res = com.pr4nav.jarvis.capabilities.ContactResolver.resolve(ctx, name)) {
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Single -> {
                        ToolResult.ok(JSONObject().apply {
                            put("id", res.contact.id)
                            put("name", res.contact.name)
                            put("number", res.contact.number)
                            put("type", res.contact.typeLabel)
                        })
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Ambiguous -> {
                        val arr = JSONArray()
                        res.matches.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
                        ToolResult.ambiguous("Found multiple contacts matching '$name'", res.matches.map { "${it.name} (${it.number})" })
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.NotFound -> {
                        ToolResult.notFound("Contact '$name'")
                    }
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.PermissionRequired -> {
                        ToolResult.permissionDenied(android.Manifest.permission.READ_CONTACTS)
                    }
                }
            }
        ))

        // 3. MESSAGES: draft_message
        register(CanonicalToolDef(
            name = "draft_message",
            description = "Opens the default SMS app with pre-filled recipient and message without sending directly.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("recipient", JSONObject().put("type", "string").put("description", "Recipient phone number or name"))
                    put("message", JSONObject().put("type", "string").put("description", "Pre-filled text content"))
                })
                put("required", JSONArray().put("recipient").put("message"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val rec = args.optString("recipient").trim()
                val msg = args.optString("message").trim()
                val resolvedNum = when (val r = com.pr4nav.jarvis.capabilities.ContactResolver.resolve(ctx, rec)) {
                    is com.pr4nav.jarvis.capabilities.ContactResolutionResult.Single -> r.contact.number
                    else -> rec
                }
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                        data = android.net.Uri.parse("sms:${android.net.Uri.encode(resolvedNum)}")
                        putExtra("sms_body", msg)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    ToolResult.requiresUser("DRAFT_OPENED", "Opened SMS composer for $resolvedNum with drafted message.")
                } catch (e: Exception) {
                    ToolResult.failure("DRAFT_ERROR", e.message ?: "Failed opening SMS draft")
                }
            }
        ))

        // 4. SETTINGS: Structured Subpages
        val settingsSubpages = listOf(
            "wifi" to android.provider.Settings.ACTION_WIFI_SETTINGS,
            "bluetooth" to android.provider.Settings.ACTION_BLUETOOTH_SETTINGS,
            "display" to android.provider.Settings.ACTION_DISPLAY_SETTINGS,
            "sound" to android.provider.Settings.ACTION_SOUND_SETTINGS,
            "battery" to (if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP_MR1) android.provider.Settings.ACTION_BATTERY_SAVER_SETTINGS else android.provider.Settings.ACTION_SETTINGS),
            "accessibility" to android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS,
            "developer" to android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS,
            "notification" to android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            "storage" to android.provider.Settings.ACTION_INTERNAL_STORAGE_SETTINGS,
            "security" to android.provider.Settings.ACTION_SECURITY_SETTINGS,
            "location" to android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS,
            "date_time" to android.provider.Settings.ACTION_DATE_SETTINGS
        )
        for ((subName, intentAction) in settingsSubpages) {
            register(CanonicalToolDef(
                name = "open_${subName}_settings",
                description = "Opens the Android $subName settings page directly.",
                argumentSchema = JSONObject().apply {
                    put("type", "object")
                    put("properties", JSONObject())
                },
                defaultTimeoutMs = 5_000L,
                execute = { ctx, _ ->
                    try {
                        val intent = android.content.Intent(intentAction).apply {
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        ToolResult.ok(JSONObject().put("opened", "$subName settings").put("action", intentAction))
                    } catch (e: Exception) {
                        try {
                            ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                            ToolResult.requiresUser("SETTINGS_FALLBACK", "Opened main Settings (specific $subName page not supported by ROM)")
                        } catch (e2: Exception) {
                            ToolResult.failure("SETTINGS_ERROR", e2.message ?: "Failed to open settings")
                        }
                    }
                }
            ))
        }

        // 5. FILES / STORAGE: Deep file operations
        // delete_file
        register(CanonicalToolDef(
            name = "delete_file",
            description = "Deletes a local file or directory.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().put("type", "string").put("description", "File path to delete"))
                })
                put("required", JSONArray().put("path"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, args ->
                val path = args.optString("path").trim()
                if (path.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Path cannot be empty")
                try {
                    val f = java.io.File(path)
                    if (!f.exists()) return@CanonicalToolDef ToolResult.notFound("File '$path'")
                    com.pr4nav.jarvis.Fs.delete(path)
                    ToolResult.ok(JSONObject().put("action", "FILE_DELETED").put("path", path))
                } catch (e: Exception) {
                    ToolResult.failure("DELETE_ERROR", e.message ?: "Failed deleting file")
                }
            }
        ))

        // rename_file
        register(CanonicalToolDef(
            name = "rename_file",
            description = "Renames or moves a local file.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("from", JSONObject().put("type", "string").put("description", "Source path"))
                    put("to", JSONObject().put("type", "string").put("description", "Destination path"))
                })
                put("required", JSONArray().put("from").put("to"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, args ->
                val from = args.optString("from").trim()
                val to = args.optString("to").trim()
                if (from.isEmpty() || to.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Source and target paths cannot be empty")
                try {
                    if (!java.io.File(from).exists()) return@CanonicalToolDef ToolResult.notFound("File '$from'")
                    com.pr4nav.jarvis.Fs.rename(from, to)
                    ToolResult.ok(JSONObject().put("action", "FILE_RENAMED").put("from", from).put("to", to))
                } catch (e: Exception) {
                    ToolResult.failure("RENAME_ERROR", e.message ?: "Failed renaming file")
                }
            }
        ))

        // copy_file
        register(CanonicalToolDef(
            name = "copy_file",
            description = "Copies a file from source to destination path.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("src", JSONObject().put("type", "string").put("description", "Source file path"))
                    put("dst", JSONObject().put("type", "string").put("description", "Destination file path"))
                })
                put("required", JSONArray().put("src").put("dst"))
            },
            defaultTimeoutMs = 10_000L,
            execute = { _, args ->
                val src = args.optString("src").trim()
                val dst = args.optString("dst").trim()
                if (src.isEmpty() || dst.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Source and destination cannot be empty")
                try {
                    if (!java.io.File(src).exists()) return@CanonicalToolDef ToolResult.notFound("File '$src'")
                    com.pr4nav.jarvis.Fs.copy(src, dst)
                    ToolResult.ok(JSONObject().put("action", "FILE_COPIED").put("src", src).put("dst", dst))
                } catch (e: Exception) {
                    ToolResult.failure("COPY_ERROR", e.message ?: "Failed copying file")
                }
            }
        ))

        // create_folder
        register(CanonicalToolDef(
            name = "create_folder",
            description = "Creates a directory/folder at the specified path.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("path", JSONObject().put("type", "string").put("description", "Folder path to create"))
                })
                put("required", JSONArray().put("path"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { _, args ->
                val path = args.optString("path").trim()
                if (path.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Path cannot be empty")
                try {
                    com.pr4nav.jarvis.Fs.mkdir(path)
                    ToolResult.ok(JSONObject().put("action", "FOLDER_CREATED").put("path", path))
                } catch (e: Exception) {
                    ToolResult.failure("MKDIR_ERROR", e.message ?: "Failed creating folder")
                }
            }
        ))

        // find_downloads
        register(CanonicalToolDef(
            name = "find_downloads",
            description = "Finds recent files in the Downloads folder.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("extension", JSONObject().put("type", "string").put("description", "Optional extension filter, e.g. pdf"))
                })
            },
            defaultTimeoutMs = 10_000L,
            execute = { _, args ->
                val ext = args.optString("extension").trim().lowercase().removePrefix(".")
                try {
                    val pubDir = android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS)
                    val downloadsDir = pubDir ?: java.io.File("/sdcard/Download")
                    if (!downloadsDir.exists() || !downloadsDir.canRead()) {
                        return@CanonicalToolDef ToolResult.ok(JSONObject().put("count", 0).put("downloads", JSONArray()).put("note", "Downloads directory not accessible or empty"))
                    }
                    val files = downloadsDir.listFiles()?.filter { f ->
                        f.isFile && (ext.isEmpty() || f.name.lowercase().endsWith(".$ext"))
                    }?.sortedByDescending { it.lastModified() }?.take(15) ?: emptyList()

                    val arr = JSONArray()
                    files.forEach { f ->
                        arr.put(JSONObject().apply {
                            put("name", f.name)
                            put("path", f.absolutePath)
                            put("size", f.length())
                            put("modified", f.lastModified())
                        })
                    }
                    ToolResult.ok(JSONObject().put("count", files.size).put("downloads", arr))
                } catch (e: SecurityException) {
                    ToolResult.permissionDenied("Storage permission required to read downloads")
                } catch (e: Exception) {
                    ToolResult.failure("DOWNLOADS_ERROR", e.message ?: "Failed reading downloads directory")
                }
            }
        ))

        // 6. BROWSER: open_url & search_web
        register(CanonicalToolDef(
            name = "open_url",
            description = "Opens a web URL in the default browser.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("url", JSONObject().put("type", "string").put("description", "Web URL to open"))
                })
                put("required", JSONArray().put("url"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                var url = args.optString("url").trim()
                if (url.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("URL cannot be empty")
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    url = "https://$url"
                }
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    ToolResult.ok(JSONObject().put("action", "URL_OPENED").put("url", url))
                } catch (e: Exception) {
                    ToolResult.failure("BROWSER_ERROR", e.message ?: "Failed opening URL")
                }
            }
        ))

        register(CanonicalToolDef(
            name = "search_web",
            description = "Performs a web search in the default browser or search provider.",
            argumentSchema = JSONObject().apply {
                put("type", "object")
                put("properties", JSONObject().apply {
                    put("query", JSONObject().put("type", "string").put("description", "Search query string"))
                })
                put("required", JSONArray().put("query"))
            },
            defaultTimeoutMs = 5_000L,
            execute = { ctx, args ->
                val query = args.optString("query").trim()
                if (query.isEmpty()) return@CanonicalToolDef ToolResult.invalidArguments("Search query cannot be empty")
                val searchUrl = "https://www.google.com/search?q=${android.net.Uri.encode(query)}"
                try {
                    val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(searchUrl)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    ctx.startActivity(intent)
                    ToolResult.ok(JSONObject().put("action", "WEB_SEARCH_OPENED").put("query", query).put("url", searchUrl))
                } catch (e: Exception) {
                    ToolResult.failure("WEB_SEARCH_ERROR", e.message ?: "Failed performing web search")
                }
            }
        ))
    }
}
