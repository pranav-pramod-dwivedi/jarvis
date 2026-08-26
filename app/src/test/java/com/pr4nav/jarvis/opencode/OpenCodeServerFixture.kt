package com.pr4nav.jarvis.opencode

import org.junit.Assume
import java.io.File
import java.net.HttpURLConnection
import java.net.ServerSocket
import java.net.URL
import java.nio.file.Files
import java.util.Base64

class OpenCodeServerFixture(
    val username: String = "jarvistest",
    val password: String = "jtpw-" + java.security.SecureRandom().nextInt(100000, 999999)
) {
    var binary: String? = null
        private set
    var port: Int = 0
        private set
    var homeDir: File? = null
        private set
    var projectDir: File? = null
        private set
    private var process: Process? = null

    val baseUrl: String get() = "http://127.0.0.1:$port"

    fun config(): OpenCodeConfig =
        OpenCodeConfig(baseUrl = baseUrl, username = username, password = password)

    fun start(): Boolean {
        binary = locateBinary()
        Assume.assumeTrue("opencode binary not found; integration tier skipped", binary != null)
        port = freePort()
        val home = Files.createTempDirectory("jarvis-oc-home").toFile()
        val proj = Files.createTempDirectory("jarvis-oc-project").toFile()
        homeDir = home
        projectDir = proj
        spawn(port)
        return waitForHealth(45_000)
    }

    fun restartOnSamePort(): Boolean {
        stopServerProcess()
        spawn(port)
        return waitForHealth(45_000)
    }

    private fun spawn(port: Int) {
        val pb = ProcessBuilder(
            binary!!, "serve",
            "--port", port.toString(),
            "--hostname", "127.0.0.1"
        )
        pb.environment()["HOME"] = homeDir!!.absolutePath
        pb.environment()["OPENCODE_SERVER_USERNAME"] = username
        pb.environment()["OPENCODE_SERVER_PASSWORD"] = password
        pb.environment()["OPENCODE_DISABLE_AUTOUPDATE"] = "1"
        pb.environment()["OPENCODE_DISABLE_ERROR_REPORTING"] = "1"
        pb.redirectErrorStream(true)
        val logFile = File(homeDir, "serve.log")
        pb.redirectOutput(logFile)
        process = pb.start()
    }

    fun stopServerProcess() {
        process?.let { p ->
            p.destroy()
            val exited = try {
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS)
            } catch (_: InterruptedException) {
                false
            }
            if (!exited) p.destroyForcibly()
            Thread.sleep(300)
        }
        process = null
    }

    fun stop() {
        stopServerProcess()
        listOfNotNull(homeDir, projectDir).forEach { dir ->
            dir.deleteRecursively()
        }
    }

    fun waitForHealth(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (healthProbe()) return true
            Thread.sleep(500)
        }
        return false
    }

    fun healthProbe(): Boolean = rawHealth(baseUrl, username, password).first

    fun writeOpencodeConfig(json: String) {
        File(projectDir!!, "opencode.json").writeText(json)
    }

    companion object {
        fun locateBinary(): String? {
            System.getenv("JARVIS_OC_BIN")?.let { if (File(it).canExecute()) return it }
            val default = File(System.getProperty("user.home"), ".opencode/bin/opencode")
            if (default.canExecute()) return default.absolutePath
            val pathBin = File("/usr/local/bin/opencode")
            if (pathBin.canExecute()) return pathBin.absolutePath
            val optHomebrew = File("/opt/homebrew/bin/opencode")
            if (optHomebrew.canExecute()) return optHomebrew.absolutePath
            return null
        }

        fun freePort(): Int = ServerSocket(0).use { it.localPort }

        fun basicHeader(user: String, pass: String): String =
            "Basic " + Base64.getEncoder().encodeToString("$user:$pass".toByteArray())

        fun rawHealth(baseUrl: String, user: String?, pass: String?): Pair<Boolean, String?> {
            return try {
                val conn = URL("$baseUrl/global/health").openConnection() as HttpURLConnection
                conn.connectTimeout = 1500
                conn.readTimeout = 2500
                if (user != null && pass != null) {
                    conn.setRequestProperty("Authorization", basicHeader(user, pass))
                }
                val code = conn.responseCode
                val ok = code in 200..299
                conn.disconnect()
                ok to null
            } catch (_: Exception) {
                false to null
            }
        }
    }
}
