package com.pr4nav.jarvis.capabilities

import android.content.pm.PackageManager
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.TermuxBridge
import com.pr4nav.jarvis.tools.ToolDef
import org.json.JSONObject

object TermuxCapability : Capability {

    override val name = "termux"

    fun installed(): Boolean = try {
        Capabilities.require().packageManager.getPackageInfo(TermuxBridge.TERMUX_PKG, 0)
        true
    } catch (_: Exception) { false }

    fun reachable(): Boolean = if (installed() && permittedBridge()) Shell.termuxReachable() else false

    private fun permittedBridge(): Boolean = TermuxBridge.hasPermission()

    fun run(command: String, timeoutMs: Long): CapabilityResult {
        if (!reachable())
            return CapabilityResult.fail(
                "Termux bridge unreachable (installed=${installed()} permitted=${permitted()})"
            )
        val r = Shell.termux(command, timeoutMs)
        return CapabilityResult.ok(
            JSONObject().put("rc", r.rc ?: -1)
                .put("stdout", r.out.take(30_000))
                .put("stderr", r.err.take(5_000)).toString(),
            "ms" to r.ms.toString(), "via" to r.via
        )
    }

    override fun available(): Boolean = installed()
    override fun permitted(): Boolean = reachable()

    override fun status(): String = when {
        !installed() -> "✗ Termux — not installed"
        !TermuxBridge.hasPermission() -> "△ Termux — RUN_COMMAND permission missing"
        !Shell.termuxReachable() -> "△ Termux — installed but bridge did not answer"
        else -> "✓ Termux — bridge live"
    }

    override fun tools() = listOf(
        ToolDef("termux.status", "Termux install/permission/bridge state", "{}", null,
            {
                CapabilityResult.ok(
                    JSONObject().put("installed", installed())
                        .put("permitted", permitted())
                        .put("bridgeReachable", reachable()).toString()
                ).envelope()
            }),
        ToolDef("termux.run", "Run one shell command inside Termux (real Linux env)", """{"command":"uname -a","timeoutMs":30000}""",
            { if (Capabilities.app != null && !reachable()) "Termux bridge unreachable (installed=${installed()} permitted=${permitted()})" else null },
            { a ->
                if (!reachable()) CapabilityResult.fail("Termux bridge unreachable").envelope()
                else run(a.getString("command"), a.optLong("timeoutMs", 30_000).coerceIn(1_000, 300_000)).envelope()
            })
    )
}

object OpenCodeCapability : Capability {

    override val name = "opencode"

    // Legacy CLI fallback (Termux proot) — kept for when the API facade is not yet ready
    private const val PROOT = "proot-distro login ubuntu -- /bin/bash -lc"

    private fun oc(): com.pr4nav.jarvis.opencode.OpenCode? = try {
        if (com.pr4nav.jarvis.opencode.OpenCode.isInitialized()) com.pr4nav.jarvis.opencode.OpenCode.get()
        else {
            val ctx = Capabilities.app
            if (ctx != null) {
                val kv = try { com.pr4nav.jarvis.opencode.PrefsKvStore(ctx) } catch (_: Exception) { com.pr4nav.jarvis.opencode.InMemoryKvStore() }
                com.pr4nav.jarvis.opencode.OpenCode.init(ctx, kv)
            } else null
        }
    } catch (_: Exception) { null }

    fun serverPort(): String? {
        oc()?.process?.current?.port?.let { return it.toString() }
        if (!TermuxCapability.reachable()) return null
        val r = Shell.termux(
            "for p in 4096 4097 4098 4099; do curl -sm1 -o /dev/null http://127.0.0.1:\${'$'}p/ && echo \${'$'}p && break; done",
            15_000
        )
        return r.out.trim().ifBlank { null }
    }

    fun installed(): Boolean = try {
        oc()?.let { return true }
        TermuxCapability.reachable() && Shell.termux("$PROOT 'command -v opencode' ", 20_000).out.contains("opencode")
    } catch (_: Exception) { false }

    // --- facade helpers that are the single OpenCode brain used by GUI + agent ---
    private fun ensureOc(): com.pr4nav.jarvis.opencode.OpenCode =
        oc() ?: throw IllegalStateException("OpenCode not initialized — open the native GUI once or call opencode.start")

    private fun jsonOk(block: () -> JSONObject): JSONObject = try {
        val data = block()
        CapabilityResult.ok(data.toString()).envelope()
    } catch (e: Exception) {
        CapabilityResult.fail(e.message ?: e.javaClass.simpleName).envelope()
    }

    override fun available(): Boolean = try { installed() } catch (_: Exception) { false }
    override fun permitted(): Boolean = true

    override fun status(): String {
        val facade = oc()
        val cur = facade?.process?.current
        return when {
            facade != null && cur != null -> "✓ OpenCode — ${cur.ownership} · ${cur.baseUrl} · ${facade.sessions.list().size} sessions"
            TermuxCapability.reachable() && cur == null -> "△ OpenCode — bridge up, server not yet started (call opencode.start)"
            TermuxCapability.reachable() && !available() -> "○ OpenCode — CLI not found in Ubuntu proot"
            !TermuxCapability.reachable() -> "○ OpenCode — needs Termux bridge (GUI still works via 127.0.0.1)"
            else -> "○ OpenCode — idle"
        }
    }

    override fun tools() = listOf(
        ToolDef("opencode.start", "Start (or adopt) the headless opencode server (web/serve auto)", """{"port":4096}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val port = a.optInt("port", 0).takeIf { it in 1024..65535 }
                val res = if (port != null && port != 0) {
                    val pass = facade.let { it.kv.getString(com.pr4nav.jarvis.opencode.OpenCodeSessionStore.KEY_SERVER_PASSWORD) ?: "" }
                    facade.process.startOwned(port, "jarvis", pass.ifBlank { "jarvis-pass" })
                } else facade.ensureServerStarted()
                val st = when (res) {
                    is com.pr4nav.jarvis.opencode.OcResult.Ok<*> -> (res as com.pr4nav.jarvis.opencode.OcResult.Ok<com.pr4nav.jarvis.opencode.OpenCodeProcessManager.ServerState>).value
                    is com.pr4nav.jarvis.opencode.OcResult.Err -> facade.process.current
                        ?: throw IllegalStateException(res.error.message ?: "start failed")
                    else -> throw IllegalStateException("no server")
                } ?: throw IllegalStateException("no server")
                JSONObject().put("baseUrl", st.baseUrl).put("ownership", st.ownership.name).put("port", st.port ?: JSONObject.NULL)
            }
        }),
        ToolDef("opencode.connect", "Connect to an existing opencode server at baseUrl", """{"baseUrl":"http://127.0.0.1:4096"}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.connect(a.getString("baseUrl"))
                if (!r.isSuccess) throw IllegalStateException(r.error?.message ?: "connect failed")
                JSONObject().put("baseUrl", r.state!!.baseUrl).put("ownership", r.state.ownership.name)
            }
        }),
        ToolDef("opencode.status", "Full OpenCode status: health, server, sessions, permissions, tools", "{}", null, { _ ->
            jsonOk {
                val facade = oc()
                if (facade == null) return@jsonOk JSONObject().put("initialized", false).put("termuxUp", TermuxCapability.reachable())
                val health = facade.client.health()
                JSONObject()
                    .put("initialized", true)
                    .put("baseUrl", facade.process.current?.baseUrl ?: facade.config.baseUrl)
                    .put("ownership", facade.process.current?.ownership?.name ?: "NONE")
                    .put("healthy", (health as? com.pr4nav.jarvis.opencode.OcResult.Ok)?.value?.healthy ?: false)
                    .put("version", (health as? com.pr4nav.jarvis.opencode.OcResult.Ok)?.value?.version ?: JSONObject.NULL)
                    .put("sessions", facade.sessions.list().size)
                    .put("currentSession", facade.sessions.currentSessionId ?: JSONObject.NULL)
                    .put("pendingPermissions", facade.permissions.permissionsSnapshot().size)
                    .put("pendingQuestions", facade.permissions.questionsSnapshot().size)
                    .put("termuxUp", TermuxCapability.reachable())
            }
        }),
        ToolDef("opencode.projects", "List opencode projects (worktrees)", "{}", null, { _ ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.projects.projects()
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                val arr = org.json.JSONArray()
                (r as com.pr4nav.jarvis.opencode.OcResult.Ok).value.forEach { p ->
                    arr.put(JSONObject().put("id", p.id).put("worktree", p.worktree ?: JSONObject.NULL).put("name", p.name ?: JSONObject.NULL))
                }
                JSONObject().put("projects", arr)
            }
        }),
        ToolDef("opencode.sessions", "List sessions (optional directory filter)", """{"directory":"/sdcard/...","includeArchived":false}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val dir = a.optString("directory", "").ifBlank { null }
                if (dir != null) facade.sessions.refreshFromServer(dir)
                val list = facade.sessions.list(a.optBoolean("includeArchived", false))
                val arr = org.json.JSONArray()
                list.forEach { e ->
                    arr.put(JSONObject().put("sessionId", e.sessionId).put("directory", e.directory)
                        .put("title", e.title ?: JSONObject.NULL).put("busy", e.busy).put("unread", e.unread).put("archived", e.archived))
                }
                JSONObject().put("sessions", arr).put("current", facade.sessions.currentSessionId ?: JSONObject.NULL)
            }
        }),
        ToolDef("opencode.session.create", "Create a new session in directory", """{"directory":"/data/data/com.termux/files/home","title":"my task"}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.sessions.createSession(a.getString("directory"), a.optString("title", "").ifBlank { null })
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                val e = (r as com.pr4nav.jarvis.opencode.OcResult.Ok).value
                JSONObject().put("sessionId", e.sessionId).put("directory", e.directory).put("title", e.title ?: JSONObject.NULL)
            }
        }),
        ToolDef("opencode.session.send", "Send a prompt to a session (sessionId optional = current)", """{"prompt":"fix the bug","sessionId":"ses_...","agent":"build","model":"provider/model"}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val sid = a.optString("sessionId", "").ifBlank { facade.sessions.currentSessionId }
                    ?: throw IllegalStateException("No sessionId and no current session")
                val prompt = a.getString("prompt")
                val parts = listOf(com.pr4nav.jarvis.opencode.OpenCodeClient.PromptPart.Text(prompt))
                val agent = a.optString("agent", "").ifBlank { null } ?: facade.agents.current
                val model = a.optString("model", "").ifBlank { null }?.let { m ->
                    val p = m.substringBefore('/'); val id = m.substringAfter('/', "")
                    if (id.isBlank()) null else com.pr4nav.jarvis.opencode.json.OcModelRef(p, id)
                } ?: facade.models.current
                when (val res = facade.sessions.sendPrompt(sid, parts, agent, model)) {
                    is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Started ->
                        JSONObject().put("sessionId", sid).put("status", "started").put("response", res.response ?: JSONObject.NULL)
                    is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Queued ->
                        JSONObject().put("sessionId", sid).put("status", "queued").put("position", res.position)
                    is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Failed ->
                        throw res.error
                }
            }
        }),
        ToolDef("opencode.session.abort", "Abort a running session", """{"sessionId":"ses_..."}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.sessions.abort(a.getString("sessionId"))
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                JSONObject().put("aborted", (r as com.pr4nav.jarvis.opencode.OcResult.Ok).value)
            }
        }),
        ToolDef("opencode.session.fork", "Fork a session", """{"sessionId":"ses_..."}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.sessions.fork(a.getString("sessionId"))
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                val e = (r as com.pr4nav.jarvis.opencode.OcResult.Ok).value
                JSONObject().put("sessionId", e.sessionId)
            }
        }),
        ToolDef("opencode.session.rename", "Rename a session", """{"sessionId":"ses_...","title":"new title"}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.sessions.rename(a.getString("sessionId"), a.getString("title"))
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                JSONObject().put("ok", true)
            }
        }),
        ToolDef("opencode.session.delete", "Delete/archive a session", """{"sessionId":"ses_..."}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val r = facade.sessions.archive(a.getString("sessionId"))
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                JSONObject().put("ok", true)
            }
        }),
        ToolDef("opencode.permission.respond", "Respond to a permission request", """{"requestId":"...","decision":"once|always|reject","directory":"/path"}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val dec = when (a.getString("decision")) {
                    "always" -> com.pr4nav.jarvis.opencode.OpenCodeClient.PermissionDecision.ALWAYS
                    "reject" -> com.pr4nav.jarvis.opencode.OpenCodeClient.PermissionDecision.REJECT
                    else -> com.pr4nav.jarvis.opencode.OpenCodeClient.PermissionDecision.ONCE
                }
                val r = facade.permissions.respondToPermission(a.getString("requestId"), dec, a.optString("directory", "").ifBlank { null })
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                JSONObject().put("ok", true)
            }
        }),
        ToolDef("opencode.question.respond", "Answer a question request", """{"requestId":"...","answers":[["yes"]],"directory":"/path"}""", null, { a ->
            jsonOk {
                val facade = ensureOc()
                val arr = a.getJSONArray("answers")
                val answers = List(arr.length()) { i -> List(arr.getJSONArray(i).length()) { j -> arr.getJSONArray(i).getString(j) } }
                val r = facade.permissions.respondToQuestion(a.getString("requestId"), answers, a.optString("directory", "").ifBlank { null })
                if (r is com.pr4nav.jarvis.opencode.OcResult.Err) throw r.error
                JSONObject().put("ok", true)
            }
        }),
        ToolDef("opencode.events", "Snapshot of pending events (permissions, questions, busy sessions)", "{}", null, { _ ->
            jsonOk {
                val facade = oc()
                if (facade == null) return@jsonOk JSONObject().put("error", "not initialized")
                val perms = org.json.JSONArray().apply { facade.permissions.permissionsSnapshot().forEach { put(JSONObject().put("requestId", it.requestId).put("sessionId", it.sessionId ?: JSONObject.NULL).put("type", it.type ?: JSONObject.NULL)) } }
                val qs = org.json.JSONArray().apply { facade.permissions.questionsSnapshot().forEach { put(JSONObject().put("requestId", it.requestId)) } }
                JSONObject().put("pendingPermissions", perms).put("pendingQuestions", qs)
                    .put("busySessions", org.json.JSONArray().apply { facade.sessions.list().filter { it.busy }.forEach { put(it.sessionId) } })
            }
        }),
        // legacy aliases keep old prompts working
        ToolDef("opencode.prompt", "Legacy: send prompt via CLI (prefer opencode.session.send)", """{"prompt":"...","timeoutMs":300000}""", null, { a ->
            try {
                val facade = oc()
                if (facade?.sessions?.currentSessionId != null) {
                    val sid = facade.sessions.currentSessionId!!
                    val parts = listOf(com.pr4nav.jarvis.opencode.OpenCodeClient.PromptPart.Text(a.getString("prompt")))
                    when (val r = facade.sessions.sendPrompt(sid, parts, facade.agents.current, facade.models.current)) {
                        is com.pr4nav.jarvis.opencode.OpenCodeSessionManager.PromptSubmit.Started ->
                            return@ToolDef CapabilityResult.ok(JSONObject().put("via", "facade").put("sessionId", sid).toString()).envelope()
                        else -> {}
                    }
                }
            } catch (_: Exception) {}
            // fallback to CLI
            val quoted = "'" + a.getString("prompt").replace("'", "'\\''") + "'"
            val r = Shell.termux("$PROOT 'opencode run $quoted' 2>&1", a.optLong("timeoutMs", 300_000))
            if (r.rc == 0) CapabilityResult.ok(JSONObject().put("response", r.out.take(40_000)).toString()).envelope()
            else CapabilityResult.fail(r.out.ifBlank { r.err }.take(2_000)).envelope()
        }),
        ToolDef("opencode.cli", "Run raw opencode CLI args (Termux proot)", """{"args":"session list"}""", null, { a ->
            val safe = a.optString("args", "--help").replace("'", "")
            val r = Shell.termux("$PROOT 'opencode $safe' 2>&1", a.optLong("timeoutMs", 60_000))
            CapabilityResult.ok(JSONObject().put("rc", r.rc ?: -1).put("output", r.out.take(30_000)).toString()).envelope()
        })
    )
}
