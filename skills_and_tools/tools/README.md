# JARVIS Canonical Tools System

This directory contains the canonical tool definitions, schemas, and catalog implementations for the JARVIS AI Assistant.

## Overview

JARVIS features **130 canonical tools** across 16 capability domains. Every tool follows a strict validation, permission-gating, and safety pipeline:

```
User Query / Voice / LLM Decision
               │
               ▼
      Canonical Tool Name
               │
               ▼
      [ToolValidator] ── Schema & Argument Validation
               │
               ▼
        [CmdGuard] ── Destructive Command Prevention (Shell/Termux)
               │
               ▼
     [Permission Check] ── Android Runtime & System Grants
               │
               ▼
      [Native Execution] ── Android OS / Termux Bridge / Audio / UI
               │
               ▼
         [ToolResult] ── Structured JSON output returned to Agent
```

---

## Tool Categories

| Category | File | Description | Tools Count |
| :--- | :--- | :--- | :--- |
| **App & System Shortcuts** | `catalog/AppSystemShortcutTools.kt` | App launches, settings toggles, deep intents, shortcuts | 14 |
| **Clock & Timers** | `catalog/ClockTimerTools.kt` | Alarms, timers, stopwatch, countdowns, timezone lookup | 12 |
| **Device Hardware** | `catalog/DeviceHardwareTools.kt` | Flashlight, battery status, screen brightness, volume, orientation | 11 |
| **Contact & Phone** | `catalog/ContactPhoneCommTools.kt` | Hands-free calls, contact resolution, SMS dispatch | 9 |
| **Media & Audio** | `catalog/MediaAudioTools.kt` | Music playback, track skipping, audio ducking, volume stream controls | 8 |
| **Connectivity & Network** | `catalog/ConnectivityNetworkTools.kt` | Wi-Fi, Bluetooth, mobile data status, IP & ping diagnostic | 6 |
| **JarvisBrowser & Dynamic UI** | `catalog/JarvisBrowserTools.kt` | Generation and live rendering of offline HTML5 mini-apps | 7 |
| **Terminal & Shell** | `catalog/AppSystemShortcutTools.kt` | Termux command execution, proot Linux tasks, OpenCode server | 8 |
| **Calculation & Unit Converter** | `catalog/CalculationConverterTools.kt` | Math evaluations, currency conversion, imperial/metric transforms | 8 |
| **Calendar & Schedules** | `catalog/CalendarScheduleTools.kt` | Calendar events, appointment scheduling, agenda queries | 8 |
| **Reminders & Tasks** | `catalog/ReminderTaskTools.kt` | Reminders creation, to-do lists, completion checks | 7 |
| **Notes & Lists** | `catalog/NotesListsTools.kt` | Quick note storage, checklist management, scrapbooks | 6 |
| **Navigation & Travel** | `catalog/NavigationTravelTools.kt` | Google Maps directions, traffic queries, geo-coordinates | 6 |
| **Web Knowledge** | `catalog/WebKnowledgeTools.kt` | DuckDuckGo search, URL content scraping, live weather query | 6 |
| **Weather & Environment** | `catalog/WeatherEnvironmentTools.kt` | Current weather, hourly/weekly forecasts, air quality alerts | 5 |
| **Camera & Photos** | `catalog/CameraPhotosTools.kt` | Photo taking, gallery access, image viewing | 4 |

---

## Machine-Readable Specification

The full machine-readable JSON schema for all tools is located in:
[`CANONICAL_TOOLS_REGISTRY.json`](./CANONICAL_TOOLS_REGISTRY.json)

## Security Rules

1. **Model output is never executed directly as raw shell.** Every command must pass through `CmdGuard.check()`.
2. **Destructive commands are rejected by default:** `rm`, `rmdir`, `unlink`, `shred`, `dd`, `mkfs`, `kill -9`, `reboot`, `shutdown`, `proot-distro remove`, `apt purge`.
3. **Web sandbox boundary:** Mini-apps running in `JarvisBrowser` can only access a strictly defined whitelist of non-destructive read-only tools through `JarvisBrowserBridge`. Shell and filesystem write tools are inaccessible from the web view.
