<p align="center">
  <img src="assets/Gemini_Generated_Image_pqvzvspqvzvspqvz.png" alt="JARVIS Banner" width="100%">
</p>

<h1 align="center">J4rvis</h1>

<p align="center">
  <strong>Your phone, but now with a body.</strong>
</p>

<p align="center">
  Jarvis is an Android AI assistant built to let your conversations become actions on your phone.
  Ask, instruct, and let Jarvis work with the device instead of stopping at a chat response.
</p>

<p align="center">
  <a href="https://github.com/pranav-pramod-dwivedi/jarvis/releases"><img src="https://img.shields.io/badge/Android-10%2B-blue.svg" alt="Android 10+"></a>
  <a href="https://github.com/pranav-pramod-dwivedi/jarvis/releases"><img src="https://img.shields.io/badge/License-Apache%202.0-orange.svg" alt="Apache 2.0"></a>
</p>

---

# 🚀 Jarvis is entering its next chapter

Jarvis started as an ambitious Android assistant. It could listen, speak, understand context, control parts of the phone, and act through an increasingly capable set of tools.

But the original experience had a real limitation:

**it worked on fewer devices, and only a smaller portion of the commands we wanted could reliably reach the Android system.**

That is now the problem we are actively fixing.

The next phase of Jarvis is focused on making the assistant's capabilities much more broadly usable through a **phone-native MCP agent**.

The Android app is **not being abandoned**. It will continue to be maintained and kept up to date. But going forward, a major part of Jarvis development will be focused on the MCP side: making Jarvis easier to connect to, more capable, more reliable, and able to turn an AI conversation into real device actions.

## 🧠 What this means for you

The goal is simple:

> **Your AI conversation should be able to reach your phone.**

Instead of treating Jarvis as only another Android app with a fixed collection of commands, the MCP direction turns the phone itself into an agent that can expose its capabilities to an AI client.

That means the experience is moving toward:

- **💬 Chat-first control** — give instructions naturally instead of learning a command list.
- **📱 Your phone as the agent** — the device can perform actions instead of merely telling you how to perform them.
- **🧩 A growing capability layer** — Android, Termux, device controls, files, apps, browser workflows, time, calendar, and Jarvis-compatible capabilities can live behind one structured interface.
- **🔗 MCP-native integration** — the focus is on connecting Jarvis to the AI experience you already use.
- **🔐 Authenticated communication** — remote control is designed around authenticated requests rather than exposing an unprotected device endpoint.
- **🛠️ Real device work** — the project is being rebuilt around reliable execution and verification, not just a larger list of commands.
- **🔄 Continuous improvement** — Jarvis is going to keep getting updates as the MCP agent and Android app evolve together.

## ✨ The big change

The original Jarvis asked:

**“What commands can this particular Android build handle?”**

The new direction asks:

**“What can we make your phone capable of doing for you?”**

That distinction is the heart of the project.

The MCP agent is being built to preserve Jarvis's existing capability surface while making it much easier to extend, test, repair, and connect to modern AI clients. Existing Jarvis functionality remains valuable; the new agent layer is the foundation for taking it further.

## 🌐 Built around your AI conversation

Jarvis MCP is designed around a straightforward experience:

**You talk to your AI → the AI calls Jarvis → Jarvis acts on your phone → the result comes back to the conversation.**

No separate Jarvis cloud account is required for the MCP agent.

No separate Jarvis API product.

No hidden Jarvis usage meter.

No need to build a second AI interface just to control your phone.

The intent is to keep the connection transparent: your AI client and your own device are the important pieces, while Jarvis provides the phone-side capabilities.

> **Use the AI experience you already have. Give it a body.**

## 📲 The Android app is staying

The original Android application remains part of Jarvis.

Its voice assistant, floating companion experience, screen awareness, neural speech stack, wake-word system, and Android-native UI are still important to the project.

The difference is where the next wave of engineering attention goes:

**the app stays maintained; the MCP agent becomes a major focus.**

This lets Jarvis evolve in two directions without throwing away the work that came before.

## 🔥 From a limited command surface to a compatibility layer

A major part of the current work is migrating the original Jarvis capability surface into a structured agent layer.

The compatibility layer currently covers the original **134-tool Jarvis surface**, with:

- **119 capabilities** mapped to structured native agent tools.
- **9 capabilities** using audited root-shell adapters.
- **4 capabilities** remaining network-dependent.
- **2 capabilities** remaining app-dependent.

This is not the finish line. It is the foundation.

The project is actively working through the gaps that previously made Jarvis feel limited to certain devices or command paths.

## 🤖 What Jarvis is becoming

Jarvis is being shaped into a phone-side agent that can:

- understand structured requests from an AI client;
- inspect device state;
- interact with Android and Termux;
- control supported phone hardware and system functions;
- work with files and applications;
- launch Android intents;
- perform UI actions when structured APIs are not enough;
- expose legacy Jarvis capabilities through a compatibility layer;
- report results back in structured form;
- diagnose parts of its own runtime;
- and grow without requiring the Android app itself to contain every new capability.

The important part is not a giant command list.

**It is the bridge between intelligence and execution.**

## 🛡️ Built to be useful, not mysterious

Jarvis is moving toward structured tools, explicit capabilities, authenticated communication, and auditable device operations.

The project is also deliberately avoiding the idea that “root access” should mean “anything goes.” Sensitive operations need guardrails, and the agent should be able to explain what it is doing and whether an operation succeeded.

That is especially important as Jarvis becomes more capable.

## 🛣️ What's next

Jarvis is going to keep moving.

The immediate development focus is the **Jarvis MCP agent**: expanding device coverage, strengthening reliability, improving diagnostics and recovery, tightening the remote transport, and making the phone increasingly independent from desktop-side tooling.

The Android application will continue to receive maintenance and updates alongside that work.

So if you are watching this repository, this is the announcement:

> **Jarvis is not finished. Jarvis is getting a new body.**

And this time, the goal is not simply to make a smarter assistant.

**The goal is to make your AI actually able to work with your phone.**

---

## 🧱 Project structure

The repository contains both the original Android application and the newer phone-native agent layer.

```
app/                         # Original Android Jarvis application
agent/termux-agent/          # Phone-native MCP/agent runtime
  agent.py                   # Native device skills
  mcp_server.py              # MCP stdio server
  legacy_dispatch.py         # Original Jarvis compatibility layer
  capability_matrix.json     # Capability coverage
  catalog.json               # Versioned agent catalog
  install.sh                 # Termux installer
  doctor.sh                  # Runtime diagnostics
  self_test.py               # Agent integrity tests
```

## 🔧 Android app

The original Jarvis app includes:

- Neural Kokoro TTS
- openWakeWord-based “Hey Jarvis” detection
- Floating companion HUD
- On-screen context awareness
- Android Quick Settings integration
- Voice assistant services
- Local and cloud intelligence routing
- Android-native device capabilities

The app remains actively maintained while MCP becomes a major development focus.

## 📦 Building the Android app

Clone the repository:

```bash
git clone https://github.com/pranav-pramod-dwivedi/jarvis.git
cd jarvis
```

Build a debug APK:

```./gradlew assembleDebug
```

Install it on a connected Android device:

```adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 📱 Jarvis MCP on Android

The MCP agent targets Android devices running Termux and is designed to move toward a phone-initiated remote connection rather than making ADB or a Mac a permanent requirement.

The development architecture is intentionally separated into:

```
AI client
   ↓
authenticated MCP / relay transport
   ↓
Jarvis agent on the phone
   ↓
Android + Termux + device capabilities
```

The long-term objective is simple:

**the phone should be able to connect outward and make itself available as the agent.**

## 📄 License

Copyright (c) 2026 Pranav Pramod Dwivedi. All rights reserved.

Distributed under the Apache 2.0 License.
