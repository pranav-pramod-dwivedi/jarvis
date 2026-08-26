package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.json.OcEvent

class OpenCode private constructor(
    val kv: OcKvStore,
    internal val configHolder: ConfigHolder,
    val client: OpenCodeClient,
    val process: OpenCodeProcessManager,
    val events: OpenCodeEventManager,
    val sessions: OpenCodeSessionManager,
    val projects: OpenCodeProjectManager,
    val models: OpenCodeModelManager,
    val agents: OpenCodeAgentManager,
    val permissions: OpenCodePermissionManager,
    val tools: OpenCodeToolTracker
) {

    class ConfigHolder(@Volatile var current: OpenCodeConfig)

    interface EventListener : OpenCodeEventManager.Listener

    @Volatile
    var externalListener: EventListener? = null

    @Volatile
    var autoStartServer: Boolean = true

    init {
        wireInternalRouting()
        autoStartServer = (kv.getString(OpenCodeSessionStore.KEY_AUTO_START) ?: "true") == "true"
        Thread({ bootstrap() }, "opencode-bootstrap").apply {
            isDaemon = true
            start()
        }
    }

    private fun bootstrap() {
        val savedBase = kv.getString(OpenCodeSessionStore.KEY_SERVER_BASE_URL)
        val candidates = listOf(config.baseUrl) +
            OpenCodeProcessManager.defaultCandidates(listOfNotNull(savedBase))
        when (val r = process.detectAndConnect(candidates.distinct(), readOwnedCreds())) {
            is OcResult.Ok -> Unit
            is OcResult.Err ->
                OpenCodeLogger.w(TAG, "no server detected at startup: ${r.error.message}")
        }
        if (process.current == null && autoStartServer) {
            ensureServerStarted()
        }
        events.subscribe(Router())
        events.start()
        sessions.reconcileWithStatusMap()
        OpenCodeLogger.i(TAG, "bootstrapped; server=${process.current?.baseUrl ?: "none"}")
    }

    fun awaitReady(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (process.current != null) return true
            try {
                Thread.sleep(100)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    val config: OpenCodeConfig get() = configHolder.current

    fun connect(baseUrl: String, credentials: Pair<String, String>? = null): ConnectResult =
        when (val r = process.detectAndConnect(listOf(baseUrl), credentials)) {
            is OcResult.Ok -> {
                applyConfigFor(r.value.baseUrl, credentials)
                restartEvents()
                ConnectResult(r.value, null)
            }
            is OcResult.Err -> ConnectResult(null, r.error)
        }

    fun ensureServerStarted(): ConnectResult {
        process.current?.let { return ConnectResult(it, null) }
        val port = 4096 + (0..3).random()
        val password = newRandomPassword()
        return when (val r = process.startOwned(port, username = "jarvis", password = password)) {
            is OcResult.Ok -> {
                events.start()
                ConnectResult(r.value, null)
            }
            is OcResult.Err -> ConnectResult(null, r.error)
        }
    }

    fun stopOwnedServer(): Boolean = process.stopOwned()

    fun restartEvents() {
        events.stop()
        events.start()
    }

    fun setEventListener(listener: EventListener?) {
        externalListener = listener
    }

    fun sendPromptToCurrent(parts: List<OpenCodeClient.PromptPart>): OpenCodeSessionManager.PromptSubmit {
        val sid = sessions.currentSessionId
            ?: throw OpenCodeException(
                OpenCodeException.Code.NOT_FOUND,
                "No current session — create or select one first"
            )
        return sessions.sendPrompt(sid, parts, agents.current, models.current)
    }

    class ConnectResult(
        val state: OpenCodeProcessManager.ServerState?,
        val error: OpenCodeException?
    ) {
        val isSuccess: Boolean get() = error == null
    }

    private fun applyConfigFor(baseUrl: String, creds: Pair<String, String>?) {
        val base = config.withBaseUrl(baseUrl)
        configHolder.current = if (creds != null)
            base.copy(username = creds.first, password = creds.second)
        else base
    }

    private fun readOwnedCreds(): Pair<String, String>? {
        val u = kv.getString(OpenCodeSessionStore.KEY_SERVER_USERNAME)?.takeIf { it.isNotBlank() } ?: return null
        val p = kv.getString(OpenCodeSessionStore.KEY_SERVER_PASSWORD)?.takeIf { it.isNotBlank() } ?: return null
        return u to p
    }

    private fun newRandomPassword(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(24)
        repeat(24) { sb.append(alphabet[java.security.SecureRandom().nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun wireInternalRouting() {
        events.onReconnected = {
            try {
                sessions.reconcileWithStatusMap()
            } catch (_: Exception) {
            }
        }
        process.onUnavailable = { reason ->
            OpenCodeLogger.w(TAG, "server unavailable: $reason")
        }
    }

    private inner class Router : OpenCodeEventManager.Listener {
        override fun onEvent(event: OcEvent) {
            try {
                sessions.handleEvent(event)
            } catch (_: Exception) {
            }
            try {
                permissions.handleEvent(event)
            } catch (_: Exception) {
            }
            when (event) {
                is OcEvent.PartUpdated ->
                    try {
                        tools.ingestPart(event.part)
                    } catch (_: Exception) {
                    }
                is OcEvent.SessionDiff ->
                    try {
                        tools.ingestDiffEvent(event)
                    } catch (_: Exception) {
                    }
                else -> Unit
            }
            try {
                externalListener?.onEvent(event)
            } catch (_: Exception) {
            }
        }
    }

    companion object {
        const val TAG = "OpenCode"

        @Volatile
        private var instance: OpenCode? = null

        @JvmStatic
        fun get(): OpenCode = instance
            ?: throw IllegalStateException("OpenCode.init(context, kv) was not called")

        fun isInitialized(): Boolean = instance != null

        fun init(
            context: android.content.Context?,
            kv: OcKvStore,
            config: OpenCodeConfig = OpenCodeConfig(),
            launcher: OpenCodeProcessManager.Launcher = OpenCodeProcessManager.Launcher.SHELL_DEFAULT
        ): OpenCode = synchronized(this) {
            instance?.let { return it }
            build(kv, config, launcher).also { instance = it }
        }

        fun initForTesting(kv: OcKvStore, config: OpenCodeConfig): OpenCode =
            synchronized(this) {
                instance?.let { return it }
                build(kv, config, NoopLauncher).also { instance = it }
            }

        fun shutdownTesting() {
            synchronized(this) {
                instance?.events?.stop()
                instance = null
            }
        }

        internal val NoopLauncher = object : OpenCodeProcessManager.Launcher {
            override fun launch(spec: OpenCodeProcessManager.Launcher.LaunchSpec): OpenCodeProcessManager.Launcher.LaunchResult {
                throw OpenCodeException(
                    OpenCodeException.Code.PROCESS,
                    "Server launching is not available in this environment"
                )
            }
        }

        private fun build(
            kv: OcKvStore,
            initialConfig: OpenCodeConfig,
            launcher: OpenCodeProcessManager.Launcher
        ): OpenCode {
            val holder = ConfigHolder(initialConfig)
            val client = OpenCodeClient { holder.current }
            val processManager = OpenCodeProcessManager(client, kv, { holder.current }, launcher)
            processManager.configSwapHook = { holder.current = it }
            val events = OpenCodeEventManager({ holder.current }, client)
            val sessions = OpenCodeSessionManager(client, kv)
            val projects = OpenCodeProjectManager(client, kv)
            val models = OpenCodeModelManager(client, kv)
            val agents = OpenCodeAgentManager(client, kv)
            val permissions = OpenCodePermissionManager(client)
            val tools = OpenCodeToolTracker()
            return OpenCode(
                kv, holder, client, processManager, events, sessions,
                projects, models, agents, permissions, tools
            )
        }
    }
}
