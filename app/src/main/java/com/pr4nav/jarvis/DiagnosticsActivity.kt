package com.pr4nav.jarvis

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import kotlin.concurrent.thread

class DiagnosticsActivity : AppCompatActivity() {

    private lateinit var view: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_diagnostics)
        view = findViewById(R.id.diag_view)
        findViewById<Button>(R.id.diag_refresh).setOnClickListener { runChecks() }
        findViewById<Button>(R.id.diag_refresh).text = "RE-RUN CHECKS"
        runChecks()
    }

    private fun line(sym: String, name: String, detail: String = ""): String {
        val d = if (detail.isBlank()) "" else "  — $detail"
        return "$sym $name$d\n"
    }

    private fun runChecks() {
        view.text = "Checking…\n"
        thread {
            val sb = StringBuilder()

            sb.append(line("✓", "Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.SUPPORTED_ABIS.firstOrNull()}"))

            // Storage
            sb.append(if (Environment.isExternalStorageManager()) line("✓", "All-Files-Access") else line("✗", "All-Files-Access", "file manager limited — grant below"))
            sb.append(line(if (Fs.Saf.available) "✓" else "○", "SAF", if (Fs.Saf.available) "tree persisted" else "no location picked (optional)"))
            sb.append(line("•", "Storage Access", Fs.accessLevel))

            // Termux
            val tver = try { packageManager.getPackageInfo(TermuxBridge.TERMUX_PKG, 0).versionName } catch (e: Exception) { null }
            sb.append(line(if (tver != null) "✓" else "✗", "Termux", tver ?: "not installed"))
            sb.append(line(if (TermuxBridge.hasPermission()) "✓" else "✗", "RUN_COMMAND permission"))

            val echo = Shell.termux("echo JARVIS_TERMUX_OK", 15_000)
            sb.append(line(if (echo.out.contains("JARVIS_TERMUX_OK")) "✓" else "✗", "Termux bridge",
                if (echo.out.contains("OK")) "round-trip ${echo.ms}ms" else echo.err.ifBlank { "no response" }))

            // allow-external-apps is proven by the bridge echo itself
            val uname = Shell.termux("uname -m", 15_000)
            sb.append(line(if (uname.rc == 0) "✓" else "✗", "Termux command execution", uname.out.trim()))

            // proot / ubuntu
            val ubuntu = Shell.termux("proot-distro login ubuntu -- /bin/true 2>&1", 30_000)
            sb.append(line(if (ubuntu.rc == 0) "✓" else "✗", "Ubuntu (proot)", if (ubuntu.rc == 0) "starts" else ubuntu.out.take(80)))

            // opencode
            val oc = Shell.termux("proot-distro login ubuntu -- /bin/bash -lc 'command -v opencode && opencode --version' 2>&1", 60_000)
            sb.append(line(if (oc.out.contains("opencode") || oc.out.contains("/")) "✓" else "○", "OpenCode", oc.out.trim().take(60).ifBlank { "not installed" }))

            val auth = Shell.termux("test -s /data/data/com.termux/files/root/.local/share/opencode/auth.json && echo Y || proot-distro login ubuntu -- test -s /root/.local/share/opencode/auth.json && echo Y", 30_000)
            sb.append(line(if (auth.out.contains("Y")) "✓" else "○", "OpenCode auth", if (auth.out.contains("Y")) "credentials present" else "not authenticated"))

            // server port
            val port = Shell.termux("for p in 4096 4097 4098 4099; do curl -sm1 -o /dev/null http://127.0.0.1:\$p/ && echo \$p && break; done", 15_000)
            sb.append(line(if (port.out.isNotBlank()) "✓" else "○", "OpenCode server", if (port.out.isNotBlank()) "listening on :${port.out.trim()}" else "not running"))

            // root
            sb.append(line(if (Fs.Root.available) "✓" else "○", "Root", if (Fs.Root.available) "granted" else "unavailable (optional)"))

            // shell
            val local = Shell.local("echo hi")
            sb.append(line(if (local.out.contains("hi")) "✓" else "✗", "Local shell", "rc=${local.rc}"))

            runOnUiThread { view.text = sb.toString() }
        }
    }
}
