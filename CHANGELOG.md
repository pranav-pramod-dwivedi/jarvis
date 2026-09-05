# Changelog — JARVIS v1.1.1 Hotfix

## v1.1.1 — HUD Latency Hotfix (2026-09-05)

### Performance Fixes

- **JarvisVoiceEngine Singleton**: Reuses TTS/SpeechRecognizer across all services instead of creating new instances per HUD open (~500ms saved)
- **Pre-inflated Overlay View**: HUD layout is now inflated once at service start and shown/hidden via visibility toggle instead of add/remove on each activation
- **Removed Unused TextureView/MediaPlayer**: Eliminated dead code path that loaded `apple_siri_orb.mp4` video on every HUD init (~200-500ms saved)
- **Optimized RecyclerView Scrolling**: Removed redundant `smoothScrollToPosition` calls on partial STT results
- **Reduced Error Backoff**: SpeechRecognizer error recovery reduced from 3s to 1s
- **Parallelized HUD Init**: UI appears immediately on wake word, voice flow starts 100ms later

### User Experience

- **Haptic Feedback on Wake Word**: Device vibrates briefly when wake word is detected, providing immediate tactile confirmation

### Bug Fixes

- Fixed HUD appearing 3-4 seconds late after wake word detection
- Fixed redundant scroll animations during live speech recognition

---

## Previous Releases

### v1.1.0 — Android 16 Production Candidate (2026-09-03)
- Full Android 16 compatibility
- Production-ready stability improvements

### v1.0.0 — Initial Release (2026-08-31)
- Neural on-device TTS (Kokoro-82M)
- Wake word detection (openWakeWord)
- Floating HUD overlay
- 3-tier intelligence pipeline
