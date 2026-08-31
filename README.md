# JARVIS — Neural On-Device Personal Voice Assistant

JARVIS is an autonomous, on-device AI personal assistant built for Android. It combines offline neural wake-word verification, low-latency canonical tool routing, local on-device SLM natural language understanding (Qwen 2.5), zero-API-key AGY autonomous CLI execution via Ubuntu PRoot / Termux, and hands-free background operation.

**Author**: Pranav Pramod Dwivedi (`pranav-pramod`)

---

## 3-Tier Intelligence Architecture

```
User Voice / Text
       │
       ▼
┌─────────────────────────────────────────────────────────┐
│              JARVIS Voice Assistant Pipeline            │
│                                                         │
│  [Low-Power Acoustic Monitor]                           │
│           │                                             │
│           ▼                                             │
│  [OnnxWakeWordEngine] ── 16kHz PCM (openWakeWord)       │
│    ├── melspectrogram.onnx (log-mel extraction)         │
│    ├── embedding_model.onnx (Google speech embedding)   │
│    └── hey_jarvis_v0.1.onnx (neural probability)        │
│           │                                             │
│           ▼ "Jarvis" confirmed (Prob ≥ 0.35)           │
│  [Single Intentional SpeechRecognizer Session]          │
└─────────────────────────┬───────────────────────────────┘
                          │ Recognized Utterance
                          ▼
┌─────────────────────────────────────────────────────────┐
│              Unified Assistant Dispatcher               │
│                                                         │
│  Tier 1: Deterministic Needle 2 & Intent Router (<15ms) │
│    ├── Direct regex & LanguageNormalizer (En / Hi)      │
│    └── Canonical tools (Torch, WiFi, Apps, Calls, etc.) │
│                                                         │
│  Tier 2: On-Device Local SLM (Qwen 2.5 0.5B/1.5B GGUF)  │
│    ├── Completely offline reasoning & tool translation  │
│    └── Maps ambiguous natural queries to Needle tools   │
│                                                         │
│  Tier 3: Autonomous Ubuntu AGY CLI & Cloud Intelligence │
│    ├── Zero-API-key AGY CLI inside Ubuntu PRoot / Termux│
│    └── Gemini Cloud generative reasoning fallback       │
└─────────────────────────────────────────────────────────┘
```

---

## Features

### 1. Offline Neural Wake-Word Detection (`openWakeWord`)
- **Completely offline**: Runs with ONNX Runtime (`ai.onnxruntime:onnxruntime-android`).
- **Standardized acoustic pipeline**:
  - Audio ingested at 16 kHz, 16-bit mono PCM.
  - Streaming window of 1760 samples (1280 new + 480 historical context).
  - Mel transform formula: `mel = raw_mel / 10.0 + 2.0`.
  - 76-frame mel spectrogram buffer fed into Google Speech Embedding model (96 dimensions).
  - 16-frame embedding sliding buffer fed into `hey_jarvis_v0.1.onnx`.
- **Zero false triggers**: SpeechRecognizer is never run in an infinite restart loop; it is triggered only upon confirmed neural wake detection.

### 2. Hands-Free Background Operation
- Runs as an Android Foreground Service (`JarvisVoiceService`) with `FOREGROUND_SERVICE_TYPE_MICROPHONE`.
- Continues running when the app is minimized or the screen is locked.
- Supports **Barge-In Interruption**: User can say "Stop", "Chup", or start speaking to instantly halt TTS playback.
- Configurable **Follow-Up Conversation Window**: Automatically listens for subsequent commands without requiring the wake word again.

### 3. Companion Mode & Fluid UX
- Dribbble & Awwwards-inspired glowing orb UI with animated breathing, listening, and processing states.
- Clean assistant execution cards displaying step-by-step thinking, command status, and tool execution logs.
- Quick navigation hub between Agent Chat Stream, Voice Settings, Connected Services, and Diagnostics.

### 4. Canonical Tool Execution & Termux Bridge
- Resolves contact names and executes direct phone calls (`CallTool`).
- Navigation intent resolution for queries like *"take me home"* or *"ghar ka rasta bata"*.
- System hardware controls: Flashlight, Volume, Camera, Alarms, Battery status.
- Termux headless `RUN_COMMAND` integration for local bash and AGY Python agent execution.

---

## Project Structure

```
app/src/main/
├── assets/
│   ├── melspectrogram.onnx      # Audio PCM to 32-bin log-mel spectrogram
│   ├── embedding_model.onnx     # Mel frames to 96-dim speech embedding
│   ├── hey_jarvis_v0.1.onnx     # Neural classifier for "Hey Jarvis"
│   └── agy-daemon / needle      # Autonomous agent daemon scripts
├── java/com/pr4nav/jarvis/
│   ├── MainActivity.kt          # Main glowing orb UI and companion mode
│   ├── AgentActivity.kt         # Live execution stream with thinking/tool cards
│   ├── voice/
│   │   ├── OnnxWakeWordEngine.kt    # Production streaming ONNX wake engine
│   │   ├── AcousticWakeDetector.kt  # AudioRecord PCM stream reader
│   │   ├── JarvisVoiceService.kt    # Persistent Hands-Free foreground service
│   │   ├── JarvisVoiceEngine.kt     # Android TextToSpeech wrapper
│   │   └── VoiceSettingsActivity.kt # Hands-free, barge-in, & threshold settings
│   ├── router/
│   │   ├── JarvisIntentRouter.kt    # Multi-tier intent router
│   │   └── NeedleEngine.kt          # Fast deterministic pattern matching
│   └── tools/
│       ├── CanonicalToolRegistry.kt # Registry of device and system tools
│       └── CanonicalTool.kt         # Tool interface & standard definitions
```

---

## Getting Started

### Prerequisites
- Android Studio Ladybug or later
- Android device running Android 10+ (API 29+) with developer options & ADB enabled
- (Optional) [Termux](https://github.com/termux/termux-app) installed on the device for Linux tools

### Build & Installation

1. **Clone the repository**:
   ```bash
   git clone https://github.com/pranav-pramod-dwivedi/jarvis.git
   cd jarvis
   ```

2. **Build and install debug APK**:
   ```bash
   ./gradlew assembleDebug
   adb install -r app/build/outputs/apk/debug/app-debug.apk
   ```

3. **Grant Required Permissions**:
   ```bash
   adb shell pm grant com.pr4nav.jarvis android.permission.RECORD_AUDIO
   adb shell pm grant com.pr4nav.jarvis android.permission.CALL_PHONE
   adb shell pm grant com.pr4nav.jarvis android.permission.READ_CONTACTS
   ```

4. **Launch JARVIS**:
   ```bash
   adb shell am start -n com.pr4nav.jarvis/.MainActivity
   ```

---

## License

Copyright (c) 2026 Pranav Pramod Dwivedi. All rights reserved.
Distributed under the Apache 2.0 License.
