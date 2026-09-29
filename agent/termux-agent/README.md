# Redmi Agent Runtime

This is the **agent runtime**, not a replacement ChatGPT app.

Target: Redmi Note 8 Pro + Termux.

Architecture:

```text
ChatGPT
   ↓ MCP
remote authenticated relay
   ↓ outbound connection
Termux agent on Redmi Note 8 Pro
   ├─ native Termux commands
   ├─ Termux:API
   ├─ Android intents (`am`)
   ├─ local files/processes
   └─ Jarvis-compatible skill catalog
```

The old Android Jarvis project remains in this repository as the capability reference. The agent does not depend on the Jarvis Android UI or APK.

## Design rules

1. No ADB dependency.
2. No Mac dependency.
3. Phone initiates the remote connection.
4. Bind local control endpoints to loopback only.
5. Authenticate every remote request.
6. Prefer structured Android/Termux APIs over screenshot-driven clicking.
7. Use screen capture/UI inspection only when a task genuinely requires visual state.
8. Keep destructive/device-sensitive operations explicitly auditable.
9. Discover skills from a versioned catalog rather than hiding commands in prompt text.

## Install on Termux

The installer is `install.sh`. It installs the MCP runtime, legacy dispatcher, capability matrix, and self-test under `~/.jarvis-agent`. Termux:API is installed separately and root-only operations use `su`.
