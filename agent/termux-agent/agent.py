#!/usr/bin/env python3
"""Phone-native JARVIS-compatible agent runtime for Termux.

The transport layer is deliberately separate from skills. The remote side
sends signed requests; this process validates them and executes only registered
skills. No ADB or desktop computer is required.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import os
import shlex
import subprocess
import time
import uuid
from pathlib import Path
from typing import Any, Callable

ROOT = Path(os.environ.get("JARVIS_AGENT_HOME", str(Path.home() / ".jarvis-agent")))
CATALOG = Path(os.environ.get("JARVIS_AGENT_CATALOG", str(ROOT / "catalog.json")))
TOKEN_FILE = Path(os.environ.get("JARVIS_AGENT_TOKEN_FILE", str(ROOT / "token")))
COMMAND_TIMEOUT = int(os.environ.get("JARVIS_COMMAND_TIMEOUT", "30"))


def token() -> str:
    value = TOKEN_FILE.read_text().strip()
    if len(value) < 32:
        raise RuntimeError("agent token is missing or too short")
    return value


def run(argv: list[str], timeout: int = COMMAND_TIMEOUT) -> dict[str, Any]:
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    return {"success": proc.returncode == 0, "exitCode": proc.returncode,
            "stdout": proc.stdout, "stderr": proc.stderr}


def shell(command: str) -> dict[str, Any]:
    # Explicit shell skill. Callers must opt into it; ordinary skills never
    # interpolate user input into a shell command.
    return run(["sh", "-lc", command])


def termux_api(binary: str, args: list[str] | None = None) -> dict[str, Any]:
    return run([binary, *(args or [])])


def android_intent(action: str, extras: dict[str, str] | None = None) -> dict[str, Any]:
    argv = ["am", "start", "-a", action]
    for key, value in (extras or {}).items():
        argv += ["--es", key, value]
    return run(argv)


Skill = Callable[[dict[str, Any]], dict[str, Any]]
SKILLS: dict[str, tuple[str, Skill]] = {}


def skill(name: str, description: str):
    def register(fn: Skill) -> Skill:
        SKILLS[name] = (description, fn)
        return fn
    return register


@skill("device.battery", "Read battery percentage and charging state.")
def battery(_: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-battery-status")


@skill("device.location", "Read current device location when Termux:API location permission is available.")
def location(_: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-location", ["-p", "gps", "-r", "once"])


@skill("device.notification", "Create an Android notification.")
def notification(args: dict[str, Any]) -> dict[str, Any]:
    title = str(args.get("title", "JARVIS"))
    content = str(args.get("content", ""))
    return termux_api("termux-notification", ["--title", title, "--content", content])


@skill("device.toast", "Show a short Android toast.")
def toast(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-toast", [str(args.get("text", ""))])


@skill("device.clipboard_get", "Read the Android clipboard.")
def clipboard_get(_: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-clipboard-get")


@skill("device.clipboard_set", "Write text to the Android clipboard.")
def clipboard_set(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-clipboard-set", [str(args.get("text", ""))])


@skill("device.vibrate", "Vibrate the phone.")
def vibrate(args: dict[str, Any]) -> dict[str, Any]:
    ms = max(1, min(int(args.get("durationMs", 250)), 10_000))
    return termux_api("termux-vibrate", ["-d", str(ms)])


@skill("device.speak", "Speak text through Android text-to-speech.")
def speak(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-tts-speak", [str(args.get("text", ""))])


@skill("system.open_url", "Open a URL using Android's normal intent resolver.")
def open_url(args: dict[str, Any]) -> dict[str, Any]:
    return run(["termux-open-url", str(args["url"])])


@skill("system.share", "Share text using Android's share sheet.")
def share(args: dict[str, Any]) -> dict[str, Any]:
    return run(["termux-share", "--send", str(args.get("text", ""))])


@skill("files.read", "Read a UTF-8 text file inside the agent workspace.")
def files_read(args: dict[str, Any]) -> dict[str, Any]:
    path = (ROOT / str(args["path"])).resolve()
    if ROOT not in path.parents and path != ROOT:
        return {"success": False, "error": "path outside agent workspace"}
    return {"success": True, "path": str(path), "content": path.read_text()}


@skill("shell.exec", "Execute an explicit Termux shell command. Use only when a structured skill is insufficient.")
def shell_exec(args: dict[str, Any]) -> dict[str, Any]:
    return shell(str(args["command"]))


@skill("agent.info", "Return agent/runtime information and registered skills.")
def agent_info(_: dict[str, Any]) -> dict[str, Any]:
    return {"success": True, "platform": "termux", "deviceTarget": "Redmi Note 8 Pro",
            "pid": os.getpid(), "skills": sorted(SKILLS), "catalog": str(CATALOG)}


def discover() -> dict[str, Any]:
    return {"skills": [{"name": n, "description": d, "inputSchema": {"type": "object"}}
            for n, (d, _) in sorted(SKILLS.items())]}


def execute(name: str, arguments: dict[str, Any]) -> dict[str, Any]:
    entry = SKILLS.get(name)
    if not entry:
        return {"success": False, "error": {"code": "UNKNOWN_SKILL", "message": name}}
    try:
        result = entry[1](arguments)
        result.setdefault("requestId", str(uuid.uuid4()))
        return result
    except Exception as exc:
        return {"success": False, "error": {"code": "SKILL_ERROR", "message": str(exc)}}


def verify_signature(body: bytes, signature: str) -> bool:
    expected = hmac.new(token().encode(), body, hashlib.sha256).hexdigest()
    return hmac.compare_digest(expected, signature)


if __name__ == "__main__":
    print(json.dumps({"ok": True, **agent_info()}, indent=2))
