# JARVIS — Termux Bridge Prototype

Minimal Android (Kotlin) prototype that proves a standalone **Jarvis APK → Termux → bash command** pipeline using Termux's **official RUN_COMMAND intent interface**, with results (stdout / stderr / exit code) returned to the app.

Authored by **Pranav Pramod Dwivedi**.

---

## Architecture

```
Jarvis.apk  (UI/client — no shell, no terminal emulation)
   ↓
Android Intent → com.termux/.app.RunCommandService   (official Termux API)
   ↓
Termux app  (Linux execution environment)
   ↓
sh -c "<command>"  or  ~/jarvis_test.sh
   ↓
stdout / stderr / exit code via PendingIntent callback
   ↓
Jarvis.apk  (status pill + output log)
```

The APK is fully **offline by design**: it requests **no INTERNET permission**. All IPC is local Android intents.

## Interface used (official Termux mechanism)

Verified against [termux-app `TermuxConstants`](https://github.com/termux/termux-app/blob/master/termux-shared/src/main/java/com/termux/shared/termux/TermuxConstants.java) and the [RUN_COMMAND wiki](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

| Field | Value |
|---|---|
| Component | `com.termux/com.termux.app.RunCommandService` |
| Action | `com.termux.RUN_COMMAND` |
| Path extra | `com.termux.RUN_COMMAND_PATH` = `/data/data/com.termux/files/usr/bin/sh` |
| Args extra | `com.termux.RUN_COMMAND_ARGUMENTS` = `["-c", "<command>"]` |
| Workdir extra | `com.termux.RUN_COMMAND_WORKDIR` = `/data/data/com.termux/files/home` |
| Background extra | `com.termux.RUN_COMMAND_BACKGROUND` = `true` (headless; works with Termux not visible) |
| Result return | `com.termux.RUN_COMMAND_PENDING_INTENT` = one-shot mutable `PendingIntent.getBroadcast(...)` |
| Result bundle | extra `"result"` → keys `stdout`, `stderr`, `exitCode`, `err` (`-1` = no internal error), `errmsg` |

Result return requires **Termux ≥ 0.109**. Tested against v0.118.x.

## Features

- Buttons: `echo`, `pwd`, `whoami`, `uname -a`, `exit 3` (exit-code proof), `stderr` (stderr + nonzero-rc proof)
- `TEST TERMUX` — runs the four basic commands sequentially, one result at a time
- `TEST JARVIS SCRIPT` — creates `~/jarvis_test.sh` inside Termux home, then executes it as a script
- Status pill: **Connected** (green) / **Waiting…** (amber) / **Failed** (red, with exact reason)
- Every result logged to logcat tag `JARVIS` as JSON: `{label, cmd, stdout, stderr, rc, err, errmsg}`
- Timeout (20 s) + explicit error surfacing — never fakes success
- ADB-driven automation: `--es auto basic|script|all`

## Setup (one-time)

1. Install [Termux](https://github.com/termux/termux-app/releases) (**≥ 0.109**, arm64-v8a build recommended) and open it once.
2. Inside Termux:
   ```bash
   mkdir -p ~/.termux && printf '\nallow-external-apps=true\n' >> ~/.termux/termux.properties && termux-reload-settings
   ```
3. Build & install Jarvis:
   ```bash
   ./gradlew :app:assembleDebug
   adb install app/build/outputs/apk/debug/app-debug.apk
   ```
4. Grant the permission (runtime dialog on first launch also works):
   ```bash
   adb shell pm grant com.pr4nav.jarvis com.termux.permission.RUN_COMMAND
   ```

## Run tests

Tap buttons in the app, or drive everything from ADB:

```bash
adb shell am start -n com.pr4nav.jarvis/.MainActivity --es auto all
adb logcat -s JARVIS
```

Background-Termux test: press Home so Termux is not visible and run again — background commands need no visible session and no "Draw over apps" grant.

## Project layout

```
app/src/main/java/com/pr4nav/jarvis/
├── MainActivity.kt          # UI, permission flow, sequential command runner, timeouts
├── TermuxResultReceiver.kt  # BroadcastReceiver for Termux result PendingIntents (+ JSON logging)
app/src/main/AndroidManifest.xml  # RUN_COMMAND permission, <queries> visibility, receiver
```
