#!/usr/bin/env python3
"""
agy_server.py — Antigravity (AGY) Server Daemon
Serves the Antigravity CLI (agy) over HTTP/SSE for remote console UI in Jarvis Android app and Web.

Endpoints:
  GET  /health, /api/status   -> Server health, agy version, status
  POST /api/prompt, /api/chat -> Runs `agy -p` with stream-json and streams SSE events
  POST /api/abort             -> Aborts active agy prompt process
  POST /api/exec              -> Runs quick shell / agy command
  GET  /api/models            -> Lists supported model presets
  GET  /, /web                -> Sleek dark-mode AGY Web Console
"""

import http.server
import json
import os
import re
import shutil
import subprocess
import sys
import threading
import urllib.parse

PORT = int(os.environ.get("AGY_PORT", "5050"))
for arg in sys.argv[1:]:
    if arg.isdigit():
        PORT = int(arg)
    elif arg.startswith("--port="):
        PORT = int(arg.split("=")[1])

active_proc = None
active_proc_lock = threading.Lock()
cached_agy_path = None
cached_version = None


def _is_busy():
    """Thread-safe check for whether a prompt process is active."""
    with active_proc_lock:
        return active_proc is not None

def find_agy():
    global cached_agy_path
    if cached_agy_path and os.path.exists(cached_agy_path):
        return cached_agy_path
    candidates = [
        shutil.which("agy"),
        os.path.expanduser("~/.local/bin/agy"),
        "/usr/local/bin/agy",
        "/data/data/com.termux/files/usr/bin/agy",
        "/data/data/com.termux/files/home/.local/bin/agy",
    ]
    for c in candidates:
        if c and os.path.isfile(c) and os.access(c, os.X_OK):
            cached_agy_path = c
            return c
    return "agy"

def get_agy_version():
    global cached_version
    if cached_version:
        return cached_version
    agy = find_agy()
    try:
        r = subprocess.run([agy, "changelog"], capture_output=True, text=True, timeout=5)
        out = r.stdout
        m = re.search(r"(\d+\.\d+\.\d+)", out)
        if m:
            cached_version = m.group(1)
            return cached_version
    except Exception:
        pass
    try:
        r = subprocess.run([agy, "--help"], capture_output=True, text=True, timeout=5)
        if r.returncode == 0:
            cached_version = "1.0"
            return cached_version
    except Exception:
        pass
    return "unknown"

WEB_HTML = """<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
<title>AGY Remote Console · Jarvis</title>
<style>
  :root {
    --bg-main: #0B0F17;
    --bg-card: #151D2A;
    --bg-card-hover: #1E293B;
    --border: #243042;
    --primary: #38BDF8;
    --primary-glow: rgba(56, 189, 248, 0.2);
    --accent: #818CF8;
    --text-main: #F1F5F9;
    --text-muted: #94A3B8;
    --green: #34D399;
    --red: #F87171;
    --amber: #FBBF24;
    --purple: #C084FC;
  }
  * { box-sizing: border-box; margin: 0; padding: 0; -webkit-tap-highlight-color: transparent; }
  body {
    background: var(--bg-main);
    color: var(--text-main);
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, monospace;
    display: flex;
    flex-direction: column;
    height: 100vh;
    height: 100dvh;
    overflow: hidden;
  }
  header {
    background: var(--bg-card);
    border-bottom: 1px solid var(--border);
    padding: 10px 16px;
    display: flex;
    align-items: center;
    justify-content: space-between;
    gap: 10px;
    flex-shrink: 0;
  }
  .title-group { display: flex; align-items: center; gap: 8px; }
  .dot { width: 10px; height: 10px; border-radius: 50%; background: var(--green); box-shadow: 0 0 8px var(--green); }
  .dot.busy { background: var(--amber); box-shadow: 0 0 8px var(--amber); }
  .dot.error { background: var(--red); box-shadow: 0 0 8px var(--red); }
  h1 { font-size: 15px; font-weight: 700; letter-spacing: 0.5px; color: var(--primary); }
  .badge { font-size: 11px; background: rgba(56, 189, 248, 0.15); color: var(--primary); padding: 2px 8px; border-radius: 12px; border: 1px solid var(--border); }
  .header-actions { display: flex; gap: 8px; }
  .btn-sm {
    background: var(--bg-card-hover);
    color: var(--text-muted);
    border: 1px solid var(--border);
    padding: 5px 10px;
    border-radius: 6px;
    font-size: 12px;
    cursor: pointer;
    transition: all 0.15s;
  }
  .btn-sm:hover { color: var(--text-main); border-color: var(--primary); }

  /* Controls row */
  .controls {
    display: flex;
    gap: 8px;
    padding: 8px 16px;
    background: rgba(15, 23, 42, 0.7);
    border-bottom: 1px solid var(--border);
    overflow-x: auto;
    flex-shrink: 0;
    align-items: center;
  }
  select {
    background: var(--bg-card);
    color: var(--text-main);
    border: 1px solid var(--border);
    padding: 6px 10px;
    border-radius: 6px;
    font-size: 12px;
    outline: none;
  }
  .chip {
    background: var(--bg-card);
    color: var(--text-muted);
    border: 1px solid var(--border);
    padding: 4px 10px;
    border-radius: 12px;
    font-size: 11px;
    cursor: pointer;
    white-space: nowrap;
  }
  .chip:hover { color: var(--primary); border-color: var(--primary); }

  /* Output / Chat */
  #output {
    flex: 1;
    overflow-y: auto;
    padding: 16px;
    display: flex;
    flex-direction: column;
    gap: 12px;
    font-family: "JetBrains Mono", Consolas, Menlo, monospace;
    font-size: 13px;
    line-height: 1.5;
  }
  .msg-user {
    align-self: flex-end;
    background: #1E293B;
    border: 1px solid rgba(56, 189, 248, 0.3);
    border-left: 3px solid var(--primary);
    padding: 10px 14px;
    border-radius: 8px;
    max-width: 85%;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .msg-assistant {
    align-self: flex-start;
    background: var(--bg-card);
    border: 1px solid var(--border);
    padding: 12px 16px;
    border-radius: 8px;
    max-width: 95%;
    width: 100%;
    white-space: pre-wrap;
    word-break: break-word;
  }
  .msg-step {
    font-size: 11px;
    color: var(--purple);
    background: rgba(192, 132, 252, 0.08);
    border: 1px dashed rgba(192, 132, 252, 0.3);
    padding: 6px 10px;
    border-radius: 6px;
    margin-bottom: 6px;
  }
  .msg-tool {
    font-size: 11px;
    color: var(--amber);
    background: rgba(251, 191, 36, 0.08);
    border-left: 3px solid var(--amber);
    padding: 6px 10px;
    border-radius: 4px;
    margin: 4px 0;
  }

  /* Input bar */
  .input-area {
    background: var(--bg-card);
    border-top: 1px solid var(--border);
    padding: 10px 16px;
    display: flex;
    gap: 8px;
    align-items: flex-end;
    flex-shrink: 0;
  }
  textarea {
    flex: 1;
    background: var(--bg-main);
    color: var(--text-main);
    border: 1px solid var(--border);
    border-radius: 8px;
    padding: 10px 12px;
    font-family: inherit;
    font-size: 14px;
    resize: none;
    min-height: 42px;
    max-height: 120px;
    outline: none;
  }
  textarea:focus { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-glow); }
  .btn-send {
    background: var(--primary);
    color: #030712;
    border: none;
    padding: 10px 18px;
    border-radius: 8px;
    font-weight: 600;
    font-size: 14px;
    cursor: pointer;
    height: 42px;
  }
  .btn-send:disabled { background: var(--border); color: var(--text-muted); cursor: not-allowed; }
  .btn-abort { background: var(--red); color: white; display: none; }
</style>
</head>
<body>
<header>
  <div class="title-group">
    <div class="dot" id="statusDot"></div>
    <h1>AGY REMOTE CONSOLE</h1>
    <span class="badge" id="versionBadge">agy</span>
  </div>
  <div class="header-actions">
    <button class="btn-sm" onclick="clearOutput()">Clear</button>
    <button class="btn-sm" onclick="checkHealth()">Ping</button>
  </div>
</header>

<div class="controls">
  <select id="modelSelect">
    <option value="default">Model: Default</option>
    <option value="gemini-2.5-pro">gemini-2.5-pro</option>
    <option value="gemini-2.5-flash">gemini-2.5-flash</option>
    <option value="gemini-2.0-flash">gemini-2.0-flash</option>
  </select>
  <select id="modeSelect">
    <option value="default">Mode: Default</option>
    <option value="accept-edits">accept-edits</option>
    <option value="plan">plan</option>
  </select>
  <span class="chip" onclick="quickSend('agy --help')">agy --help</span>
  <span class="chip" onclick="quickSend('/help')">/help</span>
  <span class="chip" onclick="quickSend('git status')">git status</span>
  <span class="chip" onclick="quickSend('pwd')">pwd</span>
</div>

<div id="output">
  <div class="msg-assistant" style="color: var(--text-muted);">
    ⚡ Antigravity (AGY) Remote Console ready.<br>
    Connected to local AGY daemon on Termux. Type your prompt below.
  </div>
</div>

<div class="input-area">
  <textarea id="promptInput" rows="1" placeholder="Type prompt or command..." onkeydown="handleKey(event)"></textarea>
  <button id="sendBtn" class="btn-send" onclick="submitPrompt()">Send</button>
  <button id="abortBtn" class="btn-send btn-abort" onclick="abortPrompt()">Stop</button>
</div>

<script>
let isBusy = false;
let currentAbortCtrl = null;

async function checkHealth() {
  try {
    const res = await fetch('/health');
    const data = await res.json();
    document.getElementById('statusDot').className = data.running ? 'dot' : 'dot error';
    document.getElementById('versionBadge').textContent = 'v' + (data.version || '1.0');
  } catch (e) {
    document.getElementById('statusDot').className = 'dot error';
  }
}

function clearOutput() {
  document.getElementById('output').innerHTML = '';
}

function quickSend(cmd) {
  document.getElementById('promptInput').value = cmd;
  submitPrompt();
}

function handleKey(e) {
  if (e.key === 'Enter' && !e.shiftKey) {
    e.preventDefault();
    submitPrompt();
  }
}

async function submitPrompt() {
  const input = document.getElementById('promptInput');
  const text = input.value.trim();
  if (!text || isBusy) return;

  input.value = '';
  input.style.height = '42px';
  setBusy(true);

  // Append user message
  const out = document.getElementById('output');
  const userDiv = document.createElement('div');
  userDiv.className = 'msg-user';
  userDiv.textContent = text;
  out.appendChild(userDiv);

  // Prepare assistant response container
  const asstDiv = document.createElement('div');
  asstDiv.className = 'msg-assistant';
  out.appendChild(asstDiv);
  out.scrollTop = out.scrollHeight;

  const model = document.getElementById('modelSelect').value;
  const mode = document.getElementById('modeSelect').value;

  try {
    currentAbortCtrl = new AbortController();
    const resp = await fetch('/api/prompt', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ prompt: text, model, mode, continue: true }),
      signal: currentAbortCtrl.signal
    });

    const reader = resp.body.getReader();
    const decoder = new TextDecoder();
    let buffer = '';
    let responseText = '';

    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split('\\n');
      buffer = lines.pop() || '';

      for (const line of lines) {
        if (!line.startsWith('data: ')) continue;
        const raw = line.slice(6).trim();
        if (!raw || raw === '[DONE]') continue;
        try {
          const ev = JSON.parse(raw);
          if (ev.event === 'step_update' && ev.step_update) {
            const su = ev.step_update;
            if (su.text_delta) {
              responseText += su.text_delta;
              asstDiv.textContent = responseText;
              out.scrollTop = out.scrollHeight;
            }
          } else if (ev.event === 'result' && ev.result) {
            if (ev.result.response && !responseText) {
              responseText = ev.result.response;
              asstDiv.textContent = responseText;
            }
          }
        } catch (err) {}
      }
    }
  } catch (err) {
    if (err.name !== 'AbortError') {
      asstDiv.textContent += '\\n[Error: ' + err.message + ']';
    }
  } finally {
    setBusy(false);
    currentAbortCtrl = null;
    out.scrollTop = out.scrollHeight;
  }
}

async function abortPrompt() {
  if (currentAbortCtrl) {
    currentAbortCtrl.abort();
  }
  try {
    await fetch('/api/abort', { method: 'POST' });
  } catch (e) {}
  setBusy(false);
}

function setBusy(b) {
  isBusy = b;
  document.getElementById('statusDot').className = b ? 'dot busy' : 'dot';
  document.getElementById('sendBtn').style.display = b ? 'none' : 'block';
  document.getElementById('abortBtn').style.display = b ? 'block' : 'none';
}

checkHealth();
setInterval(checkHealth, 10000);
</script>
</body>
</html>
"""

class AgyHandler(http.server.BaseHTTPRequestHandler):
    def send_cors(self):
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS, PUT, DELETE")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Requested-With")

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_cors()
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")
        if path == "" or path == "/web":
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_cors()
            self.end_headers()
            self.wfile.write(WEB_HTML.encode("utf-8"))
            return

        if path in ("/health", "/api/status", "/api/health"):
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors()
            self.end_headers()
            resp = {
                "ok": True,
                "running": True,
                "version": get_agy_version(),
                "port": PORT,
                "agy_path": find_agy(),
                "busy": _is_busy()
            }
            self.wfile.write(json.dumps(resp).encode("utf-8"))
            return

        if path == "/api/models":
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors()
            self.end_headers()
            models = {
                "models": [
                    {"id": "default", "name": "Default (Auto)"},
                    {"id": "gemini-2.5-pro", "name": "Gemini 2.5 Pro"},
                    {"id": "gemini-2.5-flash", "name": "Gemini 2.5 Flash"},
                    {"id": "gemini-2.0-flash", "name": "Gemini 2.0 Flash"}
                ]
            }
            self.wfile.write(json.dumps(models).encode("utf-8"))
            return

        self.send_response(404)
        self.send_cors()
        self.end_headers()
        self.wfile.write(b'{"error":"not found"}')

    def do_POST(self):
        global active_proc
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path.rstrip("/")

        length = int(self.headers.get("Content-Length", 0))
        body_bytes = self.rfile.read(length) if length > 0 else b"{}"
        try:
            body = json.loads(body_bytes.decode("utf-8")) if body_bytes else {}
        except Exception:
            body = {}

        if path in ("/api/prompt", "/api/chat"):
            prompt = body.get("prompt", "")
            if not prompt:
                self.send_response(400)
                self.send_cors()
                self.end_headers()
                self.wfile.write(b'{"error":"empty prompt"}')
                return

            model = body.get("model")
            mode = body.get("mode")
            effort = body.get("effort")
            cont = body.get("continue", False)
            conv_id = body.get("conversation_id")
            skip_perm = body.get("skip_permissions", True)

            agy = find_agy()
            cmd = [agy, "--output-format", "stream-json"]
            if skip_perm:
                cmd.append("--dangerously-skip-permissions")
            if model and model != "default":
                cmd.extend(["--model", model])
            if mode and mode != "default":
                cmd.extend(["--mode", mode])
            if effort and effort in ("low", "medium", "high"):
                cmd.extend(["--effort", effort])
            if conv_id:
                cmd.extend(["--conversation", conv_id])
            elif cont:
                cmd.append("-c")
            cmd.extend(["-p", prompt])

            self.send_response(200)
            self.send_header("Content-Type", "text/event-stream")
            self.send_header("Cache-Control", "no-cache")
            self.send_header("Connection", "keep-alive")
            self.send_cors()
            self.end_headers()

            with active_proc_lock:
                if active_proc is not None:
                    try:
                        active_proc.terminate()
                    except Exception:
                        pass
                try:
                    proc = subprocess.Popen(
                        cmd,
                        stdout=subprocess.PIPE,
                        stderr=subprocess.STDOUT,
                        text=True,
                        bufsize=1
                    )
                    active_proc = proc
                except Exception as e:
                    err_json = json.dumps({"event": "error", "error": str(e)})
                    self.wfile.write(f"data: {err_json}\n\n".encode("utf-8"))
                    return

            try:
                for line in proc.stdout:
                    line = line.strip()
                    if line:
                        chunk = f"data: {line}\n\n"
                        self.wfile.write(chunk.encode("utf-8"))
                        self.wfile.flush()
                proc.wait()
                done_json = json.dumps({"event": "done", "rc": proc.returncode})
                self.wfile.write(f"data: {done_json}\n\n".encode("utf-8"))
                self.wfile.flush()
            except Exception:
                pass
            finally:
                with active_proc_lock:
                    if active_proc == proc:
                        active_proc = None
                # Terminate subprocess if still running (e.g. client disconnected mid-stream).
                # In the normal case proc.wait() has returned and poll() is non-None, so
                # this only fires when the streaming loop was interrupted by an exception.
                if proc.poll() is None:
                    try:
                        proc.terminate()
                    except Exception:
                        pass
            return

        if path == "/api/abort":
            with active_proc_lock:
                if active_proc is not None:
                    try:
                        active_proc.kill()
                    except Exception:
                        pass
                    active_proc = None
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors()
            self.end_headers()
            self.wfile.write(b'{"ok":true,"aborted":true}')
            return

        if path == "/api/exec":
            cmd_str = body.get("cmd", "")
            if not cmd_str:
                self.send_response(400)
                self.send_cors()
                self.end_headers()
                self.wfile.write(b'{"error":"empty cmd"}')
                return
            try:
                res = subprocess.run(cmd_str, shell=True, capture_output=True, text=True, timeout=60)
                out = {"ok": True, "rc": res.returncode, "stdout": res.stdout, "stderr": res.stderr}
            except Exception as e:
                out = {"ok": False, "error": str(e)}
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.send_cors()
            self.end_headers()
            self.wfile.write(json.dumps(out).encode("utf-8"))
            return

        self.send_response(404)
        self.send_cors()
        self.end_headers()
        self.wfile.write(b'{"error":"not found"}')

def run_server():
    server_address = ("127.0.0.1", PORT)
    httpd = http.server.ThreadingHTTPServer(server_address, AgyHandler)
    print(f"[*] AGY Server running on http://127.0.0.1:{PORT}")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\n[*] Shutting down AGY Server.")
        httpd.server_close()

if __name__ == "__main__":
    run_server()
