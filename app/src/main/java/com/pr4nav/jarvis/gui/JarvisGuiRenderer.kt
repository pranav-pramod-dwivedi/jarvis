package com.pr4nav.jarvis.gui

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import com.pr4nav.jarvis.AgyWebActivity
import com.pr4nav.jarvis.capabilities.DeviceCapability
import java.io.File

/**
 * JARVIS GUI Task Renderer
 * Renders data-driven HTML visualizations and interactive dashboards
 * for tasks that require visual inspection (CPU, RAM, Storage, Files).
 */
object JarvisGuiRenderer {

    fun showSystemDashboard(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val totalRamMb = memInfo.totalMem / (1024 * 1024)
        val availRamMb = memInfo.availMem / (1024 * 1024)
        val usedRamMb = totalRamMb - availRamMb
        val ramPct = if (totalRamMb > 0) ((usedRamMb.toDouble() / totalRamMb) * 100).toInt() else 0

        val stat = StatFs(Environment.getDataDirectory().path)
        val totalStorageMb = (stat.blockCountLong * stat.blockSizeLong) / (1024 * 1024)
        val freeStorageMb = (stat.availableBlocksLong * stat.blockSizeLong) / (1024 * 1024)
        val usedStorageMb = totalStorageMb - freeStorageMb
        val storagePct = if (totalStorageMb > 0) ((usedStorageMb.toDouble() / totalStorageMb) * 100).toInt() else 0

        val (batteryPct, charging) = DeviceCapability.battery()
        val cores = Runtime.getRuntime().availableProcessors()

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <style>
                    body { background: #0B0F17; color: #F8FAFC; font-family: -apple-system, system-ui, sans-serif; margin: 0; padding: 16px; }
                    .card { background: #151D2A; border-radius: 12px; padding: 16px; margin-bottom: 12px; border: 1px solid #1E293B; }
                    h2 { color: #38BDF8; margin-top: 0; font-size: 18px; }
                    .bar-bg { background: #1E293B; border-radius: 8px; height: 16px; overflow: hidden; margin-top: 8px; }
                    .bar-fill { height: 100%; border-radius: 8px; transition: width 0.5s; }
                    .fill-ram { width: ${ramPct}%; background: #38BDF8; }
                    .fill-storage { width: ${storagePct}%; background: #A855F7; }
                    .fill-battery { width: ${batteryPct}%; background: #10B981; }
                    .metric { display: flex; justify-content: space-between; font-size: 13px; color: #94A3B8; margin-top: 4px; }
                    .badge { background: #1E293B; color: #38BDF8; padding: 4px 8px; border-radius: 6px; font-size: 11px; font-weight: bold; }
                </style>
            </head>
            <body>
                <div style="display:flex; justify-content:space-between; align-items:center; margin-bottom:16px;">
                    <h1 style="color:#38BDF8; margin:0; font-size:22px;">JARVIS METRICS</h1>
                    <span class="badge">${Build.MODEL}</span>
                </div>

                <div class="card">
                    <h2>🧠 Memory (RAM)</h2>
                    <div class="bar-bg"><div class="bar-fill fill-ram"></div></div>
                    <div class="metric"><span>Used: ${usedRamMb} MB (${ramPct}%)</span><span>Total: ${totalRamMb} MB</span></div>
                </div>

                <div class="card">
                    <h2>💾 Internal Storage</h2>
                    <div class="bar-bg"><div class="bar-fill fill-storage"></div></div>
                    <div class="metric"><span>Used: ${usedStorageMb / 1024} GB (${storagePct}%)</span><span>Total: ${totalStorageMb / 1024} GB</span></div>
                </div>

                <div class="card">
                    <h2>🔋 Battery</h2>
                    <div class="bar-bg"><div class="bar-fill fill-battery"></div></div>
                    <div class="metric"><span>Level: ${batteryPct}%</span><span>Status: ${if (charging) "Charging ⚡" else "Discharging"}</span></div>
                </div>

                <div class="card">
                    <h2>⚡ CPU & Architecture</h2>
                    <div class="metric"><span>Processor Cores:</span><span style="color:#FFF;">$cores cores active</span></div>
                    <div class="metric" style="margin-top:8px;"><span>Android Release:</span><span style="color:#FFF;">Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})</span></div>
                </div>
            </body>
            </html>
        """.trimIndent()

        // Write HTML to local cache and open in Web Activity
        try {
            val file = File(context.cacheDir, "jarvis_dashboard.html")
            file.writeText(html)
            val intent = Intent(context, AgyWebActivity::class.java).apply {
                putExtra("target_url", "file://${file.absolutePath}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            return "Rendered live hardware dashboard (CPU: $cores cores, RAM: $ramPct%, Storage: $storagePct%)."
        } catch (e: Exception) {
            return "RAM: $usedRamMb / $totalRamMb MB ($ramPct%) · Storage: $storagePct% · Battery: $batteryPct%"
        }
    }
}
