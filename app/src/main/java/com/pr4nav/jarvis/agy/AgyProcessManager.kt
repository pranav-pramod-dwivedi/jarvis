package com.pr4nav.jarvis.agy

import android.os.Handler
import android.os.Looper
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.TermuxBridge
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class AgyProcessManager(
    private val client: AgyClient = AgyClient(),
    private val config: AgyConfig = AgyConfig()
) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val isStarting = AtomicBoolean(false)

    var onStatusChanged: ((running: Boolean, detail: String) -> Unit)? = null

    fun checkStatus() {
        client.checkHealth(
            onSuccess = { h ->
                val detail = "Online (Antigravity v${h.version ?: "2.11.0"} on :${config.port})"
                onStatusChanged?.invoke(true, detail)
            },
            onError = { err ->
                onStatusChanged?.invoke(false, "Offline on :${config.port}")
            }
        )
    }

    fun startServer(onResult: (success: Boolean, message: String) -> Unit) {
        if (isStarting.getAndSet(true)) {
            onResult(false, "Start already in progress")
            return
        }

        executor.execute {
            // First probe if already running
            var alreadyRunning = false
            val latch = java.util.concurrent.CountDownLatch(1)
            client.checkHealth(
                onSuccess = {
                    alreadyRunning = true
                    latch.countDown()
                },
                onError = {
                    latch.countDown()
                }
            )
            latch.await(2000, java.util.concurrent.TimeUnit.MILLISECONDS)

            if (alreadyRunning) {
                isStarting.set(false)
                mainHandler.post {
                    onStatusChanged?.invoke(true, "Online on port ${config.port}")
                    onResult(true, "Already running on port ${config.port}")
                }
                return@execute
            }

            mainHandler.post {
                onStatusChanged?.invoke(false, "Dispatching start to Termux...")
            }

            val cmd = buildStartCommand()
            val res = TermuxBridge.execute("agy-start", cmd, timeoutMs = 15_000)

            // Poll with backoff for health endpoint to become ready
            var ready = false
            for (i in 1..12) {
                Thread.sleep(500)
                val probeLatch = java.util.concurrent.CountDownLatch(1)
                client.checkHealth(
                    onSuccess = {
                        ready = true
                        probeLatch.countDown()
                    },
                    onError = {
                        probeLatch.countDown()
                    }
                )
                probeLatch.await(1000, java.util.concurrent.TimeUnit.MILLISECONDS)
                if (ready) break
            }

            isStarting.set(false)
            mainHandler.post {
                if (ready) {
                    onStatusChanged?.invoke(true, "Online on port ${config.port}")
                    onResult(true, "AGY serve daemon started successfully")
                } else {
                    val detail = if (res?.internalError?.contains("dispatch") == true) {
                        "Termux bridge blocked — add 'allow-external-apps = true' to ~/.termux/termux.properties, or start python3 agy_server.py in Termux"
                    } else {
                        res?.stdout?.take(200) ?: res?.internalError ?: "Server not answering on port ${config.port}"
                    }
                    onStatusChanged?.invoke(false, "Offline")
                    onResult(false, detail)
                }
            }
        }
    }

    fun stopServer(onResult: (success: Boolean) -> Unit) {
        executor.execute {
            val cmd = "export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"; " +
                      "if command -v proot-distro >/dev/null 2>&1; then " +
                      "  proot-distro login ubuntu -- /bin/bash -c 'pkill -f \"agy.*--remote-control\" || pkill -f agy || true'; " +
                      "fi; " +
                      "pkill -f \"agy.*--remote-control\" || pkill -f agy || true; echo STOPPED"
            TermuxBridge.execute("agy-stop", cmd, timeoutMs = 8_000)
            Thread.sleep(600)
            mainHandler.post {
                onStatusChanged?.invoke(false, "Stopped")
                onResult(true)
            }
        }
    }

    private fun buildStartCommand(): String {
        return "export PATH=\"/data/data/com.termux/files/usr/bin:\$PATH\"; " +
               "if command -v proot-distro >/dev/null 2>&1; then " +
               "  proot-distro login ubuntu -- /bin/bash -c 'export PATH=\"/root/.local/bin:/usr/local/bin:\$PATH\"; nohup agy --remote-control --hub-port ${config.port} --host 0.0.0.0 >/root/.agy-serve.log 2>&1 || nohup agy --remote-control --hub-port ${config.port} >/root/.agy-serve.log 2>&1 &'; " +
               "else " +
               "  nohup agy --remote-control --hub-port ${config.port} --host 0.0.0.0 >/tmp/agy-serve.log 2>&1 || nohup agy --remote-control --hub-port ${config.port} >/tmp/agy-serve.log 2>&1 &; " +
               "fi"
    }
}
