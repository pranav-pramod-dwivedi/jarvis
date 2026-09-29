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

_DEFAULT_HOME = Path("/data/data/com.termux/files/home") if Path("/data/data/com.termux/files/home").exists() else Path.home()
ROOT = Path(os.environ.get("JARVIS_AGENT_HOME", str(_DEFAULT_HOME / ".jarvis-agent")))
CATALOG = Path(os.environ.get("JARVIS_AGENT_CATALOG", str(ROOT / "catalog.json")))
TOKEN_FILE = Path(os.environ.get("JARVIS_AGENT_TOKEN_FILE", str(ROOT / "token")))
COMMAND_TIMEOUT = int(os.environ.get("JARVIS_COMMAND_TIMEOUT", "30"))
TERMUX_BIN = os.environ.get("JARVIS_TERMUX_BIN", "/data/data/com.termux/files/usr/bin")


def token() -> str:
    value = TOKEN_FILE.read_text().strip()
    if len(value) < 32:
        raise RuntimeError("agent token is missing or too short")
    return value


def run(argv: list[str], timeout: int = COMMAND_TIMEOUT) -> dict[str, Any]:
    proc = subprocess.run(argv, capture_output=True, text=True, timeout=timeout)
    return {"success": proc.returncode == 0, "exitCode": proc.returncode,
            "stdout": proc.stdout, "stderr": proc.stderr}


DANGEROUS_PATTERNS = (
    r"\brm\s+-[rfR]*\s+/(?:\s|$|\*)",
    r"\bmkfs(?:\.|\s)",
    r"\bdd\s+if=",
    r"(?:>|of=)\s*/dev/(?:block/)?",
    r"\bfastboot\s+(?:flash|erase)\b",
    r"\bparted\b.*\bmklabel\b",
)

def shell(command: str) -> dict[str, Any]:
    # Root makes the shell extremely powerful. Keep routine commands open, but
    # refuse irreversible storage/firmware primitives through the generic tool.
    import re
    for pattern in DANGEROUS_PATTERNS:
        if re.search(pattern, command, re.IGNORECASE):
            return {"success": False, "error": {"code": "DANGEROUS_COMMAND_BLOCKED", "message": pattern}}
    return run(["sh", "-lc", command])


def termux_api(binary: str, args: list[str] | None = None) -> dict[str, Any]:
    return run([str(Path(TERMUX_BIN) / binary), *(args or [])])


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



@skill("device.torch", "Turn the phone flashlight on or off.")
def torch(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-torch", ["on" if bool(args.get("state", True)) else "off"])


@skill("device.volume", "Read or set Android audio stream volumes.")
def volume(args: dict[str, Any]) -> dict[str, Any]:
    if "level" in args:
        stream = str(args.get("stream", "music"))
        return termux_api("termux-volume", [stream, str(int(args["level"]))])
    return termux_api("termux-volume")


@skill("device.brightness", "Read or set screen brightness.")
def brightness(args: dict[str, Any]) -> dict[str, Any]:
    if "level" in args:
        return termux_api("termux-brightness", [str(max(0, min(255, int(args["level"]))))])
    return termux_api("termux-brightness", ["auto"])


@skill("device.wifi", "Read Wi-Fi connection information or trigger a Wi-Fi scan.")
def wifi(args: dict[str, Any]) -> dict[str, Any]:
    if args.get("scan"):
        return termux_api("termux-wifi-scaninfo")
    return termux_api("termux-wifi-connectioninfo")


@skill("device.contacts", "Search/list contacts through Termux:API.")
def contacts(args: dict[str, Any]) -> dict[str, Any]:
    result = termux_api("termux-contact-list")
    query = str(args.get("query", "")).strip().lower()
    if query and result.get("success"):
        try:
            rows = json.loads(result["stdout"])
            result["stdout"] = json.dumps([r for r in rows if query in json.dumps(r).lower()])
        except Exception:
            pass
    return result


@skill("device.call", "Place a phone call through Android/Termux:API.")
def call_phone(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-telephony-call", [str(args["number"])])


@skill("device.sms", "Send an SMS through Android/Termux:API.")
def sms(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-sms-send", ["-n", str(args["number"]), str(args["text"])])


@skill("device.sms_inbox", "Read recent SMS messages.")
def sms_inbox(args: dict[str, Any]) -> dict[str, Any]:
    limit = max(1, min(int(args.get("limit", 20)), 100))
    return termux_api("termux-sms-list", ["-l", str(limit)])


@skill("device.camera", "Take a photo with the Redmi camera through Termux:API.")
def camera(args: dict[str, Any]) -> dict[str, Any]:
    output = Path(str(args.get("path", ROOT / "captures" / f"photo-{int(time.time())}.jpg"))).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    camera_id = str(args.get("camera", "0"))
    return termux_api("termux-camera-photo", ["-c", camera_id, str(output)])


@skill("device.media", "Control Android media playback.")
def media(args: dict[str, Any]) -> dict[str, Any]:
    action = str(args.get("action", "info"))
    if action == "play": return termux_api("termux-media-player", ["play"])
    if action == "pause": return termux_api("termux-media-player", ["pause"])
    if action == "stop": return termux_api("termux-media-player", ["stop"])
    if action == "info": return termux_api("termux-media-player", ["info"])
    if action == "play_file": return termux_api("termux-media-player", ["play", str(args["path"])])
    return {"success": False, "error": f"unsupported media action: {action}"}


@skill("device.microphone", "Record audio through the Redmi microphone.")
def microphone(args: dict[str, Any]) -> dict[str, Any]:
    output = Path(str(args.get("path", ROOT / "captures" / f"recording-{int(time.time())}.m4a"))).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    duration = max(1, min(int(args.get("durationSec", 10)), 300))
    return termux_api("termux-microphone-record", ["-f", str(output), "-l", str(duration)])


@skill("device.wallpaper", "Set the Android wallpaper from a local image.")
def wallpaper(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-wallpaper", ["-f", str(Path(args["path"]).expanduser())])


@skill("device.telephony_info", "Read cellular device information.")
def telephony_info(_: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-telephony-deviceinfo")


@skill("device.wake_lock", "Keep the Android device awake while a task is running.")
def wake_lock(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-wake-lock" if bool(args.get("lock", True)) else "termux-wake-unlock")


@skill("device.notification_remove", "Remove an Android notification created through Termux.")
def notification_remove(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-notification-remove", [str(args["id"])])


@skill("device.tts_engines", "List available Android text-to-speech engines.")
def tts_engines(_: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-tts-engines")


@skill("device.sensor", "Read Android sensor data.")
def sensor(args: dict[str, Any]) -> dict[str, Any]:
    argv = []
    if args.get("sensor"):
        argv += ["-s", str(args["sensor"])]
    if args.get("delay"):
        argv += ["-d", str(args["delay"])]
    return termux_api("termux-sensor", argv)

@skill("device.screenshot", "Capture the current Android screen to a local file without sending the image through the MCP transport.")
def screenshot(args: dict[str, Any]) -> dict[str, Any]:
    output = Path(str(args.get("path", ROOT / "captures" / f"screen-{int(time.time())}.png"))).expanduser()
    output.parent.mkdir(parents=True, exist_ok=True)
    return termux_api("termux-screenshot", ["-f", str(output)])


@skill("android.open_app", "Launch an Android package by package name.")
def open_app(args: dict[str, Any]) -> dict[str, Any]:
    package = str(args["package"])
    return run(["monkey", "-p", package, "1"])


@skill("android.open_settings", "Open an Android settings page by intent action.")
def open_settings(args: dict[str, Any]) -> dict[str, Any]:
    action = str(args.get("action", "android.settings.SETTINGS"))
    return android_intent(action)


@skill("android.open_url", "Open a URL using Android's default browser.")
def android_open_url(args: dict[str, Any]) -> dict[str, Any]:
    return run(["am", "start", "-a", "android.intent.action.VIEW", "-d", str(args["url"])])


@skill("android.media_scan", "Ask Android media providers to scan a file/directory.")
def media_scan(args: dict[str, Any]) -> dict[str, Any]:
    return termux_api("termux-media-scan", ["-r", str(args["path"])])


@skill("android.share", "Share a local file or text using Android's share sheet.")
def android_share(args: dict[str, Any]) -> dict[str, Any]:
    argv = ["termux-share"]
    if args.get("title"): argv += ["--title", str(args["title"])]
    if args.get("contentType"): argv += ["--content-type", str(args["contentType"])]
    argv.append(str(args.get("path", args.get("text", ""))))
    return run(argv)


@skill("android.download", "Download a URL into the Termux/Android download area.")
def download(args: dict[str, Any]) -> dict[str, Any]:
    argv = [str(args["url"])]
    if args.get("output"):
        argv += ["-o", str(args["output"])]
    return termux_api("termux-download", argv)

# Extended rooted-device primitives. These are structured tools so the model can
# operate the phone without falling back to an unrestricted shell for routine work.
@skill("device.root_status", "Verify root access and return the effective Android identity.")
def root_status(_: dict[str, Any]) -> dict[str, Any]:
    return run(["su", "-c", "id"])

@skill("device.system_info", "Return concise Android model, build, kernel, storage and memory information.")
def system_info(_: dict[str, Any]) -> dict[str, Any]:
    return shell("printf 'model='; getprop ro.product.model; printf 'android='; getprop ro.build.version.release; printf 'build='; getprop ro.build.display.id; printf 'kernel='; uname -r; printf 'memory='; cat /proc/meminfo | head -3; printf 'storage='; df -h /data /sdcard")

@skill("device.processes", "List Android processes with PID, user and command information.")
def processes(args: dict[str, Any]) -> dict[str, Any]:
    limit = max(1, min(int(args.get("limit", 100)), 500))
    return shell(f"ps -A -o USER,PID,PPID,NAME,ARGS | head -n {limit + 1}")

@skill("device.service", "Inspect or control an Android init service using rooted shell.")
def service(args: dict[str, Any]) -> dict[str, Any]:
    name = str(args["name"])
    action = str(args.get("action", "status"))
    if action not in {"status", "start", "stop", "restart"}:
        return {"success": False, "error": "action must be status/start/stop/restart"}
    if action == "status": return run(["su", "-c", f"getprop init.svc.{shlex.quote(name)}"])
    return run(["su", "-c", f"setprop ctl.{action} {shlex.quote(name)}"])

@skill("apps.list", "List installed Android packages, optionally filtered by text.")
def apps_list(args: dict[str, Any]) -> dict[str, Any]:
    result = run(["pm", "list", "packages", "-f"])
    query = str(args.get("query", "")).lower()
    if query and result.get("success"):
        result["stdout"] = "\n".join(x for x in result["stdout"].splitlines() if query in x.lower()) + "\n"
    return result

@skill("apps.force_stop", "Force-stop an Android application by package name.")
def apps_force_stop(args: dict[str, Any]) -> dict[str, Any]:
    return run(["am", "force-stop", str(args["package"])])

@skill("apps.clear_cache", "Clear an Android application's cache using root/package manager facilities.")
def apps_clear_cache(args: dict[str, Any]) -> dict[str, Any]:
    return run(["su", "-c", f"pm clear --cache-only {shlex.quote(str(args['package']))}"])

@skill("files.write", "Write UTF-8 text to a specified path using root when necessary.")
def files_write(args: dict[str, Any]) -> dict[str, Any]:
    path = str(Path(str(args["path"])).expanduser())
    content = str(args.get("content", ""))
    if not path.startswith(str(ROOT)) and not bool(args.get("allowSystemPath", False)):
        return {"success": False, "error": "path outside agent workspace; set allowSystemPath=true for an explicitly chosen path"}
    Path(path).parent.mkdir(parents=True, exist_ok=True)
    Path(path).write_text(content)
    return {"success": True, "path": path, "bytes": len(content.encode())}

@skill("files.stat", "Return file metadata without transferring file contents.")
def files_stat(args: dict[str, Any]) -> dict[str, Any]:
    p = Path(str(args["path"])).expanduser()
    st = p.stat()
    return {"success": True, "path": str(p), "size": st.st_size, "mtime": st.st_mtime, "isFile": p.is_file(), "isDir": p.is_dir()}

@skill("files.search", "Search filenames under a directory and return a bounded text result.")
def files_search(args: dict[str, Any]) -> dict[str, Any]:
    base = str(Path(str(args.get("path", "/sdcard"))).expanduser())
    pattern = str(args.get("pattern", "*"))
    limit = max(1, min(int(args.get("limit", 200)), 1000))
    return shell(f"find {shlex.quote(base)} -name {shlex.quote(pattern)} -print 2>/dev/null | head -n {limit}")

@skill("files.delete", "Delete an explicitly selected file or empty directory; refuses broad/root paths.")
def files_delete(args: dict[str, Any]) -> dict[str, Any]:
    p = Path(str(args["path"])).expanduser().resolve()
    protected = {Path('/'), Path('/data'), Path('/system'), Path('/vendor'), Path('/product'), Path('/sdcard')}
    if p in protected or len(p.parts) < 4:
        return {"success": False, "error": "protected or insufficiently specific path"}
    if p.is_dir():
        if not bool(args.get("recursive", False)):
            p.rmdir()
        else:
            import shutil
            shutil.rmtree(p)
    else: p.unlink()
    return {"success": True, "path": str(p)}

@skill("network.ping", "Test network reachability with a single bounded ICMP request.")
def network_ping(args: dict[str, Any]) -> dict[str, Any]:
    host = str(args.get("host", "1.1.1.1"))
    return run(["ping", "-c", "1", "-W", "2", host], timeout=5)

@skill("time.now", "Return the phone's current local time as structured text.")
def time_now(_: dict[str, Any]) -> dict[str, Any]:
    return shell("date '+%Y-%m-%dT%H:%M:%S%z %A'")

@skill("calc.evaluate", "Evaluate a basic arithmetic expression without invoking a shell.")
def calc_evaluate(args: dict[str, Any]) -> dict[str, Any]:
    import ast, operator
    ops={ast.Add:operator.add,ast.Sub:operator.sub,ast.Mult:operator.mul,ast.Div:operator.truediv,ast.Mod:operator.mod,ast.Pow:operator.pow,ast.USub:operator.neg,ast.UAdd:operator.pos}
    def ev(n):
        if isinstance(n,ast.Constant) and isinstance(n.value,(int,float)): return n.value
        if isinstance(n,ast.UnaryOp) and type(n.op) in ops: return ops[type(n.op)](ev(n.operand))
        if isinstance(n,ast.BinOp) and type(n.op) in ops: return ops[type(n.op)](ev(n.left),ev(n.right))
        raise ValueError('unsupported expression')
    value=ev(ast.parse(str(args['expression']),mode='eval').body)
    return {"success":True,"value":value}

# Local persistent state: lightweight memory, pins, notes and reminders stay on
# the phone. Only requested records need to cross MCP, keeping traffic cheap.
def _db():
    import sqlite3
    ROOT.mkdir(parents=True, exist_ok=True)
    db=sqlite3.connect(ROOT / "state.sqlite3")
    db.execute("CREATE TABLE IF NOT EXISTS memory (id INTEGER PRIMARY KEY, key TEXT UNIQUE, value TEXT NOT NULL, updated REAL NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS pins (id INTEGER PRIMARY KEY, title TEXT NOT NULL, value TEXT, created REAL NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS notes (id INTEGER PRIMARY KEY, title TEXT NOT NULL, body TEXT NOT NULL, updated REAL NOT NULL)")
    db.execute("CREATE TABLE IF NOT EXISTS reminders (id INTEGER PRIMARY KEY, title TEXT NOT NULL, due REAL NOT NULL, done INTEGER NOT NULL DEFAULT 0)")
    return db

@skill("memory.remember", "Persist a small key/value memory locally on the phone.")
def memory_remember(args: dict[str, Any]) -> dict[str, Any]:
    key=str(args["key"]); value=str(args["value"])
    db=_db(); db.execute("INSERT INTO memory(key,value,updated) VALUES(?,?,?) ON CONFLICT(key) DO UPDATE SET value=excluded.value,updated=excluded.updated",(key,value,time.time())); db.commit(); db.close()
    return {"success":True,"key":key}

@skill("memory.recall", "Recall one or all locally stored memories.")
def memory_recall(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); key=args.get("key")
    if key: rows=db.execute("SELECT key,value,updated FROM memory WHERE key=?",(str(key),)).fetchall()
    else: rows=db.execute("SELECT key,value,updated FROM memory ORDER BY updated DESC LIMIT 100").fetchall()
    db.close(); return {"success":True,"memories":[{"key":a,"value":b,"updated":c} for a,b,c in rows]}

@skill("memory.forget", "Delete one locally stored memory by key.")
def memory_forget(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); db.execute("DELETE FROM memory WHERE key=?",(str(args["key"]),)); db.commit(); db.close(); return {"success":True}

@skill("pin.create", "Create a persistent phone-local pin/bookmark.")
def pin_create(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); cur=db.execute("INSERT INTO pins(title,value,created) VALUES(?,?,?)",(str(args["title"]),str(args.get("value","")),time.time())); db.commit(); i=cur.lastrowid; db.close(); return {"success":True,"id":i}

@skill("pin.list", "List phone-local pins.")
def pin_list(_: dict[str, Any]) -> dict[str, Any]:
    db=_db(); rows=db.execute("SELECT id,title,value,created FROM pins ORDER BY created DESC").fetchall(); db.close(); return {"success":True,"pins":[{"id":a,"title":b,"value":c,"created":d} for a,b,c,d in rows]}

@skill("pin.dismiss", "Dismiss a phone-local pin by id.")
def pin_dismiss(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); db.execute("DELETE FROM pins WHERE id=?",(int(args["id"]),)); db.commit(); db.close(); return {"success":True}

@skill("notes.create", "Create or update a small local note.")
def notes_create(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); title=str(args["title"]); body=str(args.get("body", "")); row=db.execute("SELECT id FROM notes WHERE title=?",(title,)).fetchone()
    if row: db.execute("UPDATE notes SET body=?,updated=? WHERE id=?",(body,time.time(),row[0])); i=row[0]
    else: cur=db.execute("INSERT INTO notes(title,body,updated) VALUES(?,?,?)",(title,body,time.time())); i=cur.lastrowid
    db.commit(); db.close(); return {"success":True,"id":i}

@skill("notes.list", "List local notes, optionally filtered by title.")
def notes_list(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); q=str(args.get("query","")); rows=db.execute("SELECT id,title,body,updated FROM notes WHERE title LIKE ? ORDER BY updated DESC",('%'+q+'%',)).fetchall(); db.close(); return {"success":True,"notes":[{"id":a,"title":b,"body":c,"updated":d} for a,b,c,d in rows]}

@skill("reminders.create", "Create a local reminder with a Unix timestamp deadline.")
def reminders_create(args: dict[str, Any]) -> dict[str, Any]:
    db=_db(); cur=db.execute("INSERT INTO reminders(title,due) VALUES(?,?)",(str(args["title"]),float(args["due"]))); db.commit(); i=cur.lastrowid; db.close(); return {"success":True,"id":i}

@skill("reminders.list", "List pending local reminders.")
def reminders_list(_: dict[str, Any]) -> dict[str, Any]:
    db=_db(); rows=db.execute("SELECT id,title,due,done FROM reminders WHERE done=0 ORDER BY due").fetchall(); db.close(); return {"success":True,"reminders":[{"id":a,"title":b,"due":c,"done":bool(d)} for a,b,c,d in rows]}

@skill("message.whatsapp", "Open a WhatsApp chat for a number with optional prefilled text; does not send automatically.")
def whatsapp(args: dict[str, Any]) -> dict[str, Any]:
    number = ''.join(ch for ch in str(args["number"]) if ch.isdigit() or ch == '+')
    if not number: return {"success": False, "error": "valid phone number required"}
    text = str(args.get("text", ""))
    uri = "https://wa.me/" + number.lstrip('+')
    if text:
        from urllib.parse import quote
        uri += "?text=" + quote(text, safe="")
    return run(["am", "start", "-a", "android.intent.action.VIEW", "-d", uri])

@skill("device.ui_tree", "Return the current Android accessibility/UI hierarchy as text, bounded for transport efficiency.")
def ui_tree(args: dict[str, Any]) -> dict[str, Any]:
    out = "/data/local/tmp/jarvis-window.xml"
    r = run(["su", "-c", f"uiautomator dump {out} >/dev/null 2>&1 && cat {out}"])
    if r.get("success"):
        limit = max(1000, min(int(args.get("maxChars", 30000)), 100000))
        r["stdout"] = r["stdout"][:limit]
    return r

@skill("device.tap", "Tap an Android screen coordinate through rooted input.")
def device_tap(args: dict[str, Any]) -> dict[str, Any]:
    return run(["su", "-c", f"input tap {int(args['x'])} {int(args['y'])}"])

@skill("device.type_text", "Type text into the currently focused Android input field.")
def device_type_text(args: dict[str, Any]) -> dict[str, Any]:
    text = str(args.get("text", ""))
    escaped = text.replace('%','%25').replace(' ','%s')
    return run(["su", "-c", f"input text {shlex.quote(escaped)}"])

@skill("device.keyevent", "Send a bounded Android global key event by numeric code or common name.")
def device_keyevent(args: dict[str, Any]) -> dict[str, Any]:
    names={'back':4,'home':3,'recents':187,'notifications':83,'quick_settings':84,'power':26,'enter':66,'delete':67}
    key=str(args.get('key','back')).lower(); code=names.get(key,key if key.isdigit() else None)
    if code is None: return {"success":False,"error":"unknown key"}
    return run(["su","-c",f"input keyevent {code}"])

# JarvisBrowser-compatible local mini-app library. The transport returns metadata;
# HTML/CSS/JS remains on the phone unless explicitly requested.
BROWSER_ROOT = ROOT / "browser"

@skill("browser.render_app", "Create a self-contained local HTML mini-app and optionally open it in the Android browser.")
def browser_render_app(args: dict[str, Any]) -> dict[str, Any]:
    import re
    app_id=re.sub(r"[^a-zA-Z0-9_-]", "-", str(args["app_id"]).strip())[:80]
    if not app_id: return {"success":False,"error":"invalid app_id"}
    title=str(args.get("title",app_id)); html=str(args.get("html",""))
    if not html: return {"success":False,"error":"html is required"}
    css=str(args.get("css","")); js=str(args.get("js",""))
    body=html if re.search(r"<html[ >]",html,re.I) else f"<!doctype html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'><title>{title}</title><style>{css}</style></head><body>{html}<script>{js}</script></body></html>"
    temporary=bool(args.get("is_temporary",True))
    bucket="temporary" if temporary else "saved"
    target=BROWSER_ROOT/bucket/app_id
    target.mkdir(parents=True,exist_ok=True); (target/"index.html").write_text(body)
    if bool(args.get("launch",True)):
        r=run(["am","start","-a","android.intent.action.VIEW","-d",f"file://{target/'index.html'}"])
    else: r={"success":True,"exitCode":0,"stdout":"","stderr":""}
    return {"success":r.get("success",False),"app_id":app_id,"title":title,"path":str(target/"index.html"),"is_temporary":temporary,"launch":r}

@skill("browser.list_apps", "List local JarvisBrowser-compatible saved mini-apps without reading their contents.")
def browser_list_apps(_: dict[str, Any]) -> dict[str, Any]:
    base=BROWSER_ROOT/"saved"; base.mkdir(parents=True,exist_ok=True)
    apps=[]
    for p in sorted(base.iterdir()):
        if p.is_dir() and (p/"index.html").exists(): apps.append({"app_id":p.name,"path":str(p/"index.html"),"size":(p/"index.html").stat().st_size})
    return {"success":True,"count":len(apps),"apps":apps}

@skill("browser.launch_app", "Launch a saved local mini-app by ID.")
def browser_launch_app(args: dict[str, Any]) -> dict[str, Any]:
    app_id=str(args["app_id"]); p=(BROWSER_ROOT/"saved"/app_id/"index.html").resolve()
    base=(BROWSER_ROOT/"saved").resolve()
    if base not in p.parents or not p.exists(): return {"success":False,"error":"app not found"}
    r=run(["am","start","-a","android.intent.action.VIEW","-d",f"file://{p}"])
    return {**r,"app_id":app_id,"path":str(p)}

@skill("browser.delete_app", "Delete a saved local mini-app by exact ID.")
def browser_delete_app(args: dict[str, Any]) -> dict[str, Any]:
    import shutil
    app_id=str(args["app_id"]); p=(BROWSER_ROOT/"saved"/app_id).resolve(); base=(BROWSER_ROOT/"saved").resolve()
    if base not in p.parents or not p.is_dir(): return {"success":False,"error":"app not found"}
    shutil.rmtree(p); return {"success":True,"app_id":app_id}

@skill("browser.save_app", "Promote a temporary local mini-app into the persistent saved library.")
def browser_save_app(args: dict[str, Any]) -> dict[str, Any]:
    import shutil
    app_id=str(args["app_id"]); src=(BROWSER_ROOT/"temporary"/app_id).resolve(); base=(BROWSER_ROOT/"temporary").resolve()
    if base not in src.parents or not src.is_dir(): return {"success":False,"error":"temporary app not found"}
    dst=(BROWSER_ROOT/"saved"/app_id).resolve(); dst.parent.mkdir(parents=True,exist_ok=True)
    if dst.exists(): shutil.rmtree(dst)
    shutil.move(str(src),str(dst)); return {"success":True,"app_id":app_id,"path":str(dst/"index.html")}

@skill("clock.world_time", "Return current time for an IANA timezone or common city name.")
def clock_world_time(args: dict[str, Any]) -> dict[str, Any]:
    import datetime as _dt
    import subprocess as _sp
    aliases={'tokyo':'Asia/Tokyo','london':'Europe/London','new york':'America/New_York','los angeles':'America/Los_Angeles','san francisco':'America/Los_Angeles','delhi':'Asia/Kolkata','mumbai':'Asia/Kolkata','bengaluru':'Asia/Kolkata','singapore':'Asia/Singapore','dubai':'Asia/Dubai'}
    location=str(args['location']).strip(); zone=aliases.get(location.lower(),location)
    try:
        proc=_sp.run(['sh','-lc',f'TZ={shlex.quote(zone)} date +%Y-%m-%dT%H:%M:%S%z %Z'],capture_output=True,text=True,timeout=3)
        if proc.returncode != 0: raise ValueError(proc.stderr.strip() or 'timezone unavailable')
        display=proc.stdout.strip()
    except Exception as exc: return {"success":False,"error":f"unknown timezone/location: {location}","detail":str(exc)}
    return {"success":True,"location":location,"timezone":zone,"display":display}
