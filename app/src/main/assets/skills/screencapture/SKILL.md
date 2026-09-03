---
name: screencapture
description: High-speed text-only screen capture that reads live UI hierarchy, labels, and exact tap coordinates in txt format (zero image latency) with virtual tap execution. Raw image/bitmap screenshots are restricted to AGY only.
---

# SCREENCAPTURE & VIRTUAL TAP SKILL

## 1. Zero-Latency Text-Only Screen Capture
Traditional image-based screenshots introduce 2-5 seconds of bitmap capture, file encoding, and multi-modal vision latency.
This skill uses **high-speed text-only screen capture** with exact bounding box and center coordinate resolution:
- Reads the entire active Android window hierarchy instantly (<30ms).
- Emits every interactive element with its exact tap coordinates:
  ```text
  [BUTTON] "Settings" center=(540, 320) bounds=[60,280][1020,360] id=settings_btn
  [INPUT] "Search..." center=(540, 160) bounds=[48,120][1032,200] id=search_edit_text
  [TEXT] "Connected to Wi-Fi" center=(540, 380) bounds=[60,360][1020,400]
  ```
- Automatically saves to disk at:
  `/storage/emulated/0/JARVIS/workspace/screen_capture.txt`
- The agent reads this file or the tool output immediately to understand the exact state of the screen.

## 2. Virtual Tap & Gesture Execution
Agents can interact directly with any coordinate or text element found in the capture:
- **`virtual_touch(x, y)`**: Taps the exact center coordinate `(cx, cy)`.
- **`virtual_touch(text="Settings")`**: Dispatches click directly on the matching text element.
- **Infallible Fallback**: If the Accessibility service is not connected, the system automatically falls back to root `input tap <x> <y>` via `/product/bin/su`.

## 3. Strict Image Screenshot Access Policy: *Only AGY*
- Graphical bitmap screenshots (PNG/JPEG via `screencap -p` or MediaProjection) are **strictly restricted to AGY** (Antigravity PRoot Autonomous Agent).
- Standard assistant turns and fast conversational agents MUST use the text-only screencapture skill (`read_screen_text` / `screencapture`).
- If an agent other than AGY requests a screenshot, the system automatically serves the high-speed text + coordinate capture instead.
