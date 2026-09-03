package com.pr4nav.jarvis.browser

import android.content.Context
import android.util.Log
import com.pr4nav.jarvis.Shell
import com.pr4nav.jarvis.llm.GroqClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Orchestrates manual and voice UI creation via /ui command and HUD 'make an ui' trigger.
 * Executes fast generation with auto-fallback to AGY (Antigravity PRoot Autonomous Agent).
 */
object JarvisUiGenerator {

    private const val TAG = "JarvisUiGenerator"

    suspend fun generateAndLaunch(
        context: Context,
        rawPrompt: String,
        onStatus: (String) -> Unit
    ): String = withContext(Dispatchers.IO) {
        val prompt = rawPrompt.trim().ifBlank { "Futuristic Jarvis Interactive Command Center" }
        Log.i(TAG, "Starting UI generation for prompt: '$prompt'")
        onStatus("🎨 Synthesizing UI specifications for: \"$prompt\"…")

        // 1. First Attempt: Groq Compound Agent with browser_render_app tool
        var groqSuccess = false
        var groqReply = ""

        try {
            onStatus("⚡ Fast synthesis via Groq & JarvisBrowser…")
            val groqInstruction = "Generate an interactive, award-winning UI mini web-app in JarvisBrowser for: '$prompt'.\n" +
                    "You MUST invoke the tool `browser_render_app` with:\n" +
                    "- app_id: a clean slug, e.g. 'ui-${System.currentTimeMillis() % 100000}'\n" +
                    "- title: high-impact title\n" +
                    "- html: complete self-contained HTML5 code (with internal CSS, canvas/animations, and script)\n" +
                    "- explanation_speech: short concise verbal narration explaining how to use this UI\n" +
                    "Strictly follow Awwwards-Winner and Anti-AI-slop design rules: unexpected typography scaling, 60fps animations, realistic cubic-bezier easing, zero purple gradients or AI slop."

            val latch = java.util.concurrent.CountDownLatch(1)
            GroqClient.query(
                context = context,
                prompt = groqInstruction,
                onSuccess = { res ->
                    groqReply = res.response
                    groqSuccess = res.toolCallsExecuted.any { it.toolName == "browser_render_app" }
                    latch.countDown()
                },
                onError = { err ->
                    Log.w(TAG, "Groq UI generation failed: $err")
                    latch.countDown()
                }
            )
            latch.await(35, java.util.concurrent.TimeUnit.SECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "Exception during Groq UI generation: ${e.message}")
        }

        if (groqSuccess) {
            return@withContext "✨ Launched interactive UI for \"$prompt\" in JarvisBrowser."
        }

        // 2. Auto Fallback to AGY (PRoot Linux Autonomous Agent)
        onStatus("⚡ Auto fallback to AGY Autonomous Agent (PRoot Linux)…")
        Log.i(TAG, "Groq did not render app; falling back to AGY PRoot autonomous agent")

        try {
            val agyTask = "Use jarvisbrowser skill to generate an award-winning interactive UI for: '$prompt'. " +
                    "Follow Awwwards-Winner design rules. Save the application into /storage/emulated/0/JARVIS/browser/apps/ and launch JarvisBrowser."

            val agyRes = Shell.agy(agyTask, timeoutMs = 50_000L)
            if (agyRes.rc == 0 && agyRes.out.isNotBlank()) {
                val latest = JarvisBrowserAppManager.listApps(context).firstOrNull()
                if (latest != null) {
                    JarvisBrowserActivity.launch(context, latest.id, "Here is your generated UI for $prompt.")
                    return@withContext "✨ AGY generated and launched '${latest.title}' in JarvisBrowser."
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AGY fallback failed: ${e.message}")
        }

        // 3. Resilient Fallback: Synthesize an immediate award-winning interactive widget
        onStatus("✨ Rendering immediate interactive UI surface…")
        val appId = "ui-" + prompt.lowercase().replace(Regex("[^a-z0-9]"), "-").take(20).trim('-')
        val title = prompt.split(" ").take(4).joinToString(" ").replaceFirstChar { it.uppercase() }

        val fallbackHtml = """
            <!DOCTYPE html>
            <html lang="en">
            <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>$title</title>
            <style>
              * { box-sizing: border-box; margin: 0; padding: 0; }
              body {
                background: #090B10;
                color: #F3F4F6;
                font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif;
                min-height: 100vh;
                padding: 24px;
                display: flex;
                flex-direction: column;
                justify-content: space-between;
              }
              header {
                border-bottom: 1px solid rgba(255,255,255,0.1);
                padding-bottom: 16px;
                margin-bottom: 24px;
              }
              .badge {
                display: inline-block;
                padding: 4px 10px;
                background: rgba(16,185,129,0.15);
                color: #10B981;
                border: 1px solid rgba(16,185,129,0.3);
                border-radius: 20px;
                font-size: 11px;
                font-weight: bold;
                letter-spacing: 1px;
                text-transform: uppercase;
                margin-bottom: 10px;
              }
              h1 {
                font-size: 26px;
                font-weight: 800;
                letter-spacing: -0.5px;
                color: #FFFFFF;
              }
              p.desc {
                font-size: 14px;
                color: rgba(255,255,255,0.6);
                margin-top: 6px;
              }
              .interactive-card {
                background: #121620;
                border: 1px solid rgba(255,255,255,0.08);
                border-radius: 16px;
                padding: 24px;
                margin: 20px 0;
                flex: 1;
                display: flex;
                flex-direction: column;
                align-items: center;
                justify-content: center;
                text-align: center;
              }
              canvas {
                width: 100%;
                max-width: 340px;
                height: 220px;
                border-radius: 12px;
                background: #0D1117;
                border: 1px solid rgba(255,255,255,0.05);
              }
              .controls {
                display: flex;
                gap: 12px;
                margin-top: 20px;
              }
              button {
                background: #10B981;
                color: #000;
                font-weight: 700;
                font-size: 13px;
                border: none;
                border-radius: 10px;
                padding: 12px 20px;
                cursor: pointer;
                transition: transform 0.1s ease;
              }
              button:active { transform: scale(0.96); }
              button.secondary {
                background: rgba(255,255,255,0.08);
                color: #FFF;
                border: 1px solid rgba(255,255,255,0.15);
              }
              footer {
                text-align: center;
                font-size: 11px;
                color: rgba(255,255,255,0.3);
                letter-spacing: 0.5px;
              }
            </style>
            </head>
            <body>
              <div>
                <header>
                  <div class="badge">⚡ JarvisBrowser Dynamic Surface</div>
                  <h1>$title</h1>
                  <p class="desc">$prompt</p>
                </header>
                <div class="interactive-card">
                  <canvas id="canvas" width="680" height="440"></canvas>
                  <div class="controls">
                    <button id="btnAction" onclick="pulseAction()">Interact</button>
                    <button class="secondary" onclick="speakStatus()">Speak</button>
                  </div>
                </div>
              </div>
              <footer>Generated on-demand by JARVIS AI Engine</footer>
              <script>
                const canvas = document.getElementById('canvas');
                const ctx = canvas.getContext('2d');
                let t = 0;
                let speed = 0.03;

                function draw() {
                  t += speed;
                  ctx.fillStyle = '#0D1117';
                  ctx.fillRect(0, 0, canvas.width, canvas.height);

                  const cx = canvas.width / 2;
                  const cy = canvas.height / 2;

                  for (let i = 0; i < 5; i++) {
                    const radius = 40 + i * 32 + Math.sin(t + i) * 12;
                    ctx.beginPath();
                    ctx.arc(cx, cy, radius, 0, Math.PI * 2);
                    ctx.strokeStyle = i === 2 ? '#10B981' : 'rgba(255,255,255,' + (0.15 + i * 0.08) + ')';
                    ctx.lineWidth = i === 2 ? 3 : 1.5;
                    ctx.stroke();

                    // Orbital particles
                    const px = cx + Math.cos(t * (1 + i * 0.3)) * radius;
                    const py = cy + Math.sin(t * (1 + i * 0.3)) * radius;
                    ctx.beginPath();
                    ctx.arc(px, py, 5, 0, Math.PI * 2);
                    ctx.fillStyle = '#10B981';
                    ctx.fill();
                  }

                  requestAnimationFrame(draw);
                }
                draw();

                function pulseAction() {
                  speed = speed === 0.03 ? 0.09 : 0.03;
                  if (window.jarvis && window.jarvis.toast) {
                    window.jarvis.toast("Interaction updated");
                  }
                }

                function speakStatus() {
                  if (window.jarvis && window.jarvis.speak) {
                    window.jarvis.speak("Displaying interactive visualization for $title.");
                  }
                }
              </script>
            </body>
            </html>
        """.trimIndent()

        val app = JarvisBrowserAppManager.createApp(
            ctx = context,
            appId = appId,
            title = title,
            description = prompt,
            html = fallbackHtml,
            isTemporary = false,
            icon = "🎨"
        )
        JarvisBrowserActivity.launch(context, app.id, "Displaying dynamic UI for $title in JarvisBrowser.")

        "✨ Generated and launched dynamic UI for \"$prompt\" in JarvisBrowser."
    }
}
