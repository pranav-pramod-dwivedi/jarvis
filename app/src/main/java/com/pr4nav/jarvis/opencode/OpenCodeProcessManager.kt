package com.pr4nav.jarvis.opencode

import com.pr4nav.jarvis.opencode.transport.OpenCodeHttp
import java.util.concurrent.atomic.AtomicBoolean

class OpenCodeProcessManager(
    private val client: OpenCodeClient,
    private val store: OcKvStore,
    private val configSupplier: () -> OpenCodeConfig,
    private val launcher: Launcher = Launcher.SHELL_DEFAULT
) {

    fun interface Launcher {
        data class LaunchSpec(
            val port: Int,
            val username: String,
            val password: String,
            val logPath: String?
        )

        fun launch(spec: LaunchSpec): LaunchResult

        data class LaunchResult(val pidMarkerPath: String?, val detail: String?)

        companion object {
            val SHELL_DEFAULT = Launcher { spec ->
                val envPrefix = buildString {
                    append("OPENCODE_SERVER_USERNAME=")
                    append(shellQuote(spec.username))
                    append(" OPENCODE_SERVER_PASSWORD=")
                    append(shellQuote(spec.password))
                    append(" ")
                }
                // Prefer `web` (serves HTML at /) then fallback to `serve`; both expose same API.
                // --cors needed so file:///android_asset/opencode.html can fetch via JS
                val base = "opencode web --hostname 127.0.0.1 --port ${spec.port} --cors \"*\" --cors \"file://*\""
                val fallback = "opencode serve --hostname 127.0.0.1 --port ${spec.port} --cors \"*\" --cors \"file://*\""
                val cmd = "nohup $envPrefix sh -c '$base 2>${spec.logPath ?: "/dev/null"} || $fallback 2>${spec.logPath ?: "/dev/null"}' " +
                    (spec.logPath?.let { ">>$it 2>&1" } ?: "") + " & echo \$!"
                val res = ShellBridge.LOCAL.run(cmd)
                if (res.rc == 0 && res.out.isNotBlank()) {
                    LaunchResult(pidMarkerPath = null, detail = res.out.trim().lines().last())
                } else {
                    LaunchResult(null, res.err.ifBlank { res.out })
                }
            }

            fun shellQuote(s: String): String =
                "'" + s.replace("'", "'\\''") + "'"
        }
    }

    interface ShellBridge {
        data class Res(val out: String, val err: String, val rc: Int)

        fun run(command: String): Res

        companion object {
            val LOCAL: ShellBridge = object : ShellBridge {
                override fun run(command: String): Res = try {
                    val p = ProcessBuilder("sh", "-c", command)
                        .redirectErrorStream(false)
                        .start()
                    val out = OpenCodeHttp.readAll(p.inputStream)
                    val err = OpenCodeHttp.readAll(p.errorStream)
                    val rc = try {
                        p.waitFor()
                    } catch (_: InterruptedException) {
                        p.destroy()
                        -1
                    }
                    Res(out, err, rc)
                } catch (e: Exception) {
                    Res("", e.message ?: "spawn failed", -1)
                }
            }
        }
    }

    enum class Ownership { NONE, EXTERNAL, JARVIS }

    data class ServerState(
        val baseUrl: String,
        val ownership: Ownership,
        val version: String?,
        val port: Int?
    )

    @Volatile
    var current: ServerState? = null
        private set

    private val procLock = Any()
    private val monitorRunning = AtomicBoolean(false)
    private var ownedUsername: String? = null
    private var ownedPassword: String? = null

    var onUnavailable: ((reason: String) -> Unit)? = null
    var onReady: ((state: ServerState) -> Unit)? = null

    fun probe(baseUrl: String): Pair<Boolean, String?> {
        return try {
            tryProbeWithCreds(baseUrl, null) ?: false to null
        } catch (_: Exception) {
            false to null
        }
    }

    internal var configSwapHook: (OpenCodeConfig) -> Unit = {}

    fun detectAndConnect(
        candidates: List<String>,
        credentials: Pair<String, String>? = null
    ): OcResult<ServerState> = synchronized(procLock) {
        for (base in candidates) {
            val healthy = tryProbeWithCreds(base, credentials)
            if (healthy != null && healthy.first) {
                val state = ServerState(
                    baseUrl = base,
                    ownership = Ownership.EXTERNAL,
                    version = healthy.second,
                    port = base.substringAfterLast(':').toIntOrNull()
                )
                current = state
                OpenCodeLogger.i(TAG, "adopted external server at $base (v${healthy.second})")
                onReady?.invoke(state)
                return@synchronized OcResult.ok(state)
            }
        }
        OcResult.Err(OpenCodeException.unavailable("No OpenCode server found on ${candidates.joinToString()}"))
    }

    private fun tryProbeWithCreds(base: String, credentials: Pair<String, String>?): Pair<Boolean, String?>? {
        val orig = configSupplier()
        return try {
            val withCreds = if (credentials != null)
                orig.withBaseUrl(base).copy(username = credentials.first, password = credentials.second)
            else orig.withBaseUrl(base)
            configSwapHook(withCreds)
            when (val health = client.health()) {
                is OcResult.Ok -> health.value.healthy to health.value.version
                is OcResult.Err -> false to null
            }
        } catch (_: Exception) {
            false to null
        } finally {
            try {
                configSwapHook(orig)
            } catch (_: Exception) {
            }
        }
    }

    fun startOwned(port: Int, username: String = "jarvis", password: String): OcResult<ServerState> =
        synchronized(procLock) {
            if (current?.ownership == Ownership.JARVIS) {
                return@synchronized OcResult.ok(current!!)
            }
            val base = "http://127.0.0.1:$port"
            probe(base).first.let { healthy ->
                if (healthy) {
                    return@synchronized OcResult.Err(
                        OpenCodeException(OpenCodeException.Code.PROCESS, "Port $port already serves an OpenCode instance")
                    )
                }
            }
            val launch = launcher.launch(Launcher.LaunchSpec(port, username, password, null))
            val deadline = System.currentTimeMillis() + 20_000
            var healthyNow = false
            while (System.currentTimeMillis() < deadline) {
                Thread.sleep(500)
                val ok = tryProbeWithCreds(base, username to password)
                if (ok?.first == true) {
                    healthyNow = true
                    break
                }
            }
            if (!healthyNow) {
                return@synchronized OcResult.Err(
                    OpenCodeException(OpenCodeException.Code.PROCESS, "Spawned server did not become healthy in 20s; ${launch.detail ?: "no output"}")
                )
            }
            val pid = launch.detail?.trim()?.takeIf { it.matches(Regex("\\d+")) }
            if (pid != null) store.putString(KEY_PID_MARKER, pid)
            ownedUsername = username
            ownedPassword = password
            store.putString(OpenCodeSessionStore.KEY_SERVER_BASE_URL, base)
            store.putString(OpenCodeSessionStore.KEY_SERVER_OWNED, "true")
            store.putString(OpenCodeSessionStore.KEY_SERVER_PORT, port.toString())
            store.putString(OpenCodeSessionStore.KEY_SERVER_USERNAME, username)
            store.putString(OpenCodeSessionStore.KEY_SERVER_PASSWORD, password)
            val state = ServerState(base, Ownership.JARVIS, okVersion(base), port)
            current = state
            configSwapHook(configSupplier().withBaseUrl(base).copy(username = username, password = password))
            OpenCodeLogger.i(TAG, "spawned owned server at $base")
            onReady?.invoke(state)
            OcResult.ok(state)
        }

    private fun okVersion(base: String): String? =
        tryProbeWithCreds(base, (ownedUsername ?: "jarvis") to (ownedPassword ?: ""))?.second

    fun stopOwned(graceMs: Long = 3_000): Boolean = synchronized(procLock) {
        val state = current ?: return@synchronized false
        if (state.ownership != Ownership.JARVIS) return@synchronized false
        val pidMarker = store.getString(KEY_PID_MARKER)
        var killed = false
        if (pidMarker != null && pidMarker.isNotBlank()) {
            ShellBridge.LOCAL.run("kill $pidMarker")
            val deadline = System.currentTimeMillis() + graceMs
            while (System.currentTimeMillis() < deadline) {
                if (probe(state.baseUrl).first.not()) {
                    killed = true
                    break
                }
                Thread.sleep(200)
            }
            if (!killed) {
                ShellBridge.LOCAL.run("kill -9 $pidMarker")
                killed = !probe(state.baseUrl).first
            }
            store.remove(KEY_PID_MARKER)
        }
        clearOwnedServerRecord()
        current = null
        OpenCodeLogger.i(TAG, "owned server stop attempted (killed=$killed)")
        killed
    }

    fun restart(): OcResult<ServerState> = synchronized(procLock) {
        val prev = current
        if (prev?.ownership == Ownership.JARVIS) stopOwned()
        val port = prev?.port
            ?: store.getString(OpenCodeSessionStore.KEY_SERVER_PORT)?.toIntOrNull()
            ?: 4096
        val pass = ownedPassword
            ?: store.getString(OpenCodeSessionStore.KEY_SERVER_PASSWORD)
            ?: newRandomPassword().also { ownedPassword = it }
        startOwned(port, ownedUsername ?: "jarvis", pass)
    }

    private fun newRandomPassword(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyzABCDEFGHJKMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(24)
        val rnd = java.security.SecureRandom()
        repeat(24) { sb.append(alphabet[rnd.nextInt(alphabet.length)]) }
        return sb.toString()
    }

    private fun clearOwnedServerRecord() {
        store.remove(OpenCodeSessionStore.KEY_SERVER_OWNED)
        store.remove(OpenCodeSessionStore.KEY_SERVER_PORT)
        store.remove(OpenCodeSessionStore.KEY_SERVER_USERNAME)
        store.remove(OpenCodeSessionStore.KEY_SERVER_PASSWORD)
    }

    fun startHealthMonitor(intervalMs: Long = 60_000): Boolean {
        if (!monitorRunning.compareAndSet(false, true)) return false
        val t = Thread({
            while (monitorRunning.get()) {
                try {
                    Thread.sleep(intervalMs)
                    val st = current ?: continue
                    val healthy = tryProbeWithCreds(st.baseUrl, ownedCreds())?.first ?: false
                    if (!healthy) {
                        OpenCodeLogger.w(TAG, "health check failed for ${st.baseUrl}")
                        onUnavailable?.invoke("health-check failed")
                        if (st.ownership == Ownership.JARVIS) restart()
                    }
                } catch (_: InterruptedException) {
                    break
                } catch (e: Exception) {
                    OpenCodeLogger.e(TAG, "monitor error: ${e.message}")
                }
            }
        }, "opencode-health-monitor")
        t.isDaemon = true
        t.start()
        return true
    }

    fun stopHealthMonitor() {
        monitorRunning.set(false)
    }

    private fun ownedCreds(): Pair<String, String>? {
        val u = ownedUsername
            ?: store.getString(OpenCodeSessionStore.KEY_SERVER_USERNAME)?.takeIf { it.isNotBlank() }
        val p = ownedPassword
            ?: store.getString(OpenCodeSessionStore.KEY_SERVER_PASSWORD)?.takeIf { it.isNotBlank() }
        return if (u != null && p != null) u to p else null
    }

    companion object {
        const val TAG = "Process"
        const val KEY_PID_MARKER = "owned_server_pid"

        fun defaultCandidates(extraBaseUrls: List<String> = emptyList()): List<String> {
            return extraBaseUrls + OpenCodeConfig.DEFAULT_PORT_CANDIDATES.map { "http://127.0.0.1:$it" }
        }
    }
}
