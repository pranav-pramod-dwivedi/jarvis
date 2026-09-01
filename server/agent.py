#!/usr/bin/env python3
"""
Lightweight Autonomous Shell Agent Server for llama serve + Qwen3.5-2B
Zero external dependencies - uses Python 3 standard library only.
"""

import http.server
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.request

LLAMA_URL = os.environ.get("LLAMA_SERVER_URL", "http://127.0.0.1:8080")
AGENT_PORT = int(os.environ.get("PORT", "8081"))
MAX_TOOL_ITERATIONS = 6
MAX_COMMAND_OUTPUT_CHARS = 2000
MAX_HISTORY_TURNS = 4

SYSTEM_PROMPT = """You are JARVIS, an autonomous, highly capable personal AI assistant equipped with a local shell tool.
You have permission to run shell commands to inspect files, query the system, and execute tasks.

When you need to run a shell command, respond ONLY with a JSON object in this exact format:
{"tool": "shell", "command": "<your_shell_command>"}

When you have the information you need or when answering conversational queries, respond directly in natural language without JSON.
Keep your answers direct, concise, and clear."""

# Blocked high-risk / destructive commands
BLOCKED_PATTERNS = [
    r"\brm\s+-[rfR]*\s+/(?:\s|$|\*)",       # rm -rf /
    r"\bmkfs\b",                            # formatting filesystems
    r"\bdd\s+if=",                          # direct disk overwrites
    r">\s*/dev/sd[a-z]",                    # writing directly to raw drives
    r":\(\)\s*\{\s*:\s*\|\s*:\s*&\s*\}\s*;", # fork bombs
    r"\bshutdown\b",                        # machine shutdown
    r"\breboot\b",                          # machine reboot
    r"\binit\s+0\b",
]

def is_command_safe(cmd: str) -> tuple[bool, str]:
    trimmed = cmd.strip()
    if not trimmed:
        return False, "Empty command"
    for pattern in BLOCKED_PATTERNS:
        if re.search(pattern, trimmed, re.IGNORECASE):
            return False, f"Command rejected by security filter (matches pattern: {pattern})"
    return True, "OK"

def execute_shell_command(cmd: str) -> tuple[int, str]:
    safe, reason = is_command_safe(cmd)
    if not safe:
        return 1, f"Security Violation: {reason}"

    try:
        proc = subprocess.run(
            cmd,
            shell=True,
            capture_output=True,
            text=True,
            timeout=25
        )
        stdout = proc.stdout.strip()
        stderr = proc.stderr.strip()

        if stdout and stderr:
            output = f"Stdout:\n{stdout}\n\nStderr:\n{stderr}"
        elif stdout:
            output = stdout
        elif stderr:
            output = f"Stderr:\n{stderr}"
        else:
            output = "(Command completed with empty output)"

        if len(output) > MAX_COMMAND_OUTPUT_CHARS:
            output = output[:MAX_COMMAND_OUTPUT_CHARS] + "\n... [Output truncated]"

        return proc.returncode, output
    except subprocess.TimeoutExpired:
        return 124, "Error: Command timed out after 25 seconds."
    except Exception as e:
        return 1, f"Execution exception: {str(e)}"

def query_llama_completions(messages: list[dict], llama_url: str) -> str:
    url = f"{llama_url.rstrip('/')}/v1/chat/completions"
    payload = {
        "messages": messages,
        "temperature": 0.4,
        "max_tokens": 512,
        "stream": False
    }
    data = json.dumps(payload).encode("utf-8")
    req = urllib.request.Request(
        url,
        data=data,
        headers={"Content-Type": "application/json"}
    )
    try:
        with urllib.request.urlopen(req, timeout=45) as resp:
            body = json.loads(resp.read().decode("utf-8"))
            choices = body.get("choices", [])
            if choices:
                return choices[0].get("message", {}).get("content", "").strip()
            return ""
    except urllib.error.URLError as e:
        raise RuntimeError(f"Failed to connect to llama serve at {llama_url}: {e}")

def parse_tool_call(text: str) -> dict | None:
    text = text.strip()
    
    # Check for direct JSON
    if text.startswith("{") and text.endswith("}"):
        try:
            data = json.loads(text)
            if data.get("tool") == "shell" and "command" in data:
                return data
        except Exception:
            pass

    # Check for markdown code blocks ```json { ... } ``` or ``` { ... } ```
    json_match = re.search(r"```(?:json)?\s*(\{[\s\S]*?\})\s*```", text)
    if json_match:
        try:
            data = json.loads(json_match.group(1))
            if data.get("tool") == "shell" and "command" in data:
                return data
        except Exception:
            pass

    # Embedded JSON pattern
    raw_match = re.search(r'\{\s*"tool"\s*:\s*"shell"\s*,\s*"command"\s*:\s*"(.*?)"\s*\}', text)
    if raw_match:
        try:
            data = json.loads(raw_match.group(0))
            return data
        except Exception:
            pass

    return None

def run_agent_loop(user_message: str, history: list[dict] = None, llama_url: str = LLAMA_URL) -> dict:
    t0 = time.time()
    tool_calls_executed = []

    # Format recent history (keep context tiny)
    recent_history = []
    if history:
        recent_history = history[-(MAX_HISTORY_TURNS * 2):]

    messages = [{"role": "system", "content": SYSTEM_PROMPT}]
    for turn in recent_history:
        role = turn.get("role", "user")
        content = turn.get("content", "")
        if role in ("user", "assistant") and content:
            messages.append({"role": role, "content": content})

    messages.append({"role": "user", "content": user_message})

    final_response = ""
    for iteration in range(MAX_TOOL_ITERATIONS):
        try:
            assistant_reply = query_llama_completions(messages, llama_url)
        except Exception as e:
            return {
                "success": False,
                "error": str(e),
                "response": f"Error connecting to local LLM: {str(e)}",
                "tool_calls_executed": tool_calls_executed,
                "latency_ms": int((time.time() - t0) * 1000)
            }

        tool_call = parse_tool_call(assistant_reply)
        if tool_call:
            cmd = tool_call.get("command", "").strip()
            rc, output = execute_shell_command(cmd)
            tool_calls_executed.append({
                "iteration": iteration + 1,
                "command": cmd,
                "exit_code": rc,
                "output": output
            })

            # Feed result back to Qwen
            messages.append({"role": "assistant", "content": json.dumps(tool_call)})
            messages.append({
                "role": "user",
                "content": f"Command `{cmd}` executed with exit code {rc}.\nOutput:\n{output}\n\nBased on this output, proceed with answering the user's request."
            })
        else:
            final_response = assistant_reply
            break

    if not final_response and tool_calls_executed:
        final_response = f"Executed {len(tool_calls_executed)} commands. Latest output: {tool_calls_executed[-1]['output']}"

    latency_ms = int((time.time() - t0) * 1000)
    return {
        "success": True,
        "response": final_response,
        "tool_calls_executed": tool_calls_executed,
        "latency_ms": latency_ms
    }

class AgentHttpHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, format, *args):
        print(f"[{time.strftime('%Y-%m-%d %H:%M:%S')}] {args[0]} - {args[1]} - {args[2]}")

    def send_json(self, status: int, data: dict):
        body = json.dumps(data, indent=2).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(200)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.end_headers()

    def do_GET(self):
        if self.path in ("/", "/health"):
            # Check llama server health
            llama_ok = False
            try:
                req = urllib.request.Request(f"{LLAMA_URL.rstrip('/')}/v1/models")
                with urllib.request.urlopen(req, timeout=2) as resp:
                    if resp.status == 200:
                        llama_ok = True
            except Exception:
                llama_ok = False

            self.send_json(200, {
                "status": "healthy",
                "service": "JARVIS Qwen Shell Agent",
                "llama_server": LLAMA_URL,
                "llama_connected": llama_ok
            })
        else:
            self.send_json(404, {"error": "Not Found"})

    def do_POST(self):
        if self.path in ("/chat", "/v1/chat"):
            try:
                length = int(self.headers.get("Content-Length", 0))
                raw_body = self.rfile.read(length).decode("utf-8")
                payload = json.loads(raw_body) if raw_body else {}
            except Exception as e:
                self.send_json(400, {"error": f"Invalid JSON payload: {e}"})
                return

            user_msg = payload.get("message") or payload.get("prompt", "")
            history = payload.get("history", [])
            custom_llama_url = payload.get("llama_url", LLAMA_URL)

            if not user_msg:
                self.send_json(400, {"error": "'message' field is required"})
                return

            result = run_agent_loop(user_msg, history, custom_llama_url)
            self.send_json(200, result)
        else:
            self.send_json(404, {"error": "Endpoint not found. Use POST /chat"})

def main():
    global LLAMA_URL
    import argparse
    parser = argparse.ArgumentParser(description="JARVIS Qwen3.5-2B Shell Agent Server")
    parser.add_argument("--port", type=int, default=AGENT_PORT, help="Port to listen on (default: 8081)")
    parser.add_argument("--llama-url", type=str, default=LLAMA_URL, help="llama serve URL (default: http://127.0.0.1:8080)")
    args = parser.parse_args()

    LLAMA_URL = args.llama_url

    server = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), AgentHttpHandler)
    print("=" * 60)
    print(f"🤖 JARVIS Qwen Shell Agent Server starting on http://0.0.0.0:{args.port}")
    print(f"🔗 Connected to llama serve backend at: {LLAMA_URL}")
    print(f"📡 Endpoint ready: POST http://127.0.0.1:{args.port}/chat")
    print("=" * 60)

    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nShutting down agent server...")
        server.shutdown()

if __name__ == "__main__":
    main()
