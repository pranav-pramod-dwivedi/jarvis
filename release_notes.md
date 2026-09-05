## JARVIS v1.1.1 — HUD Latency Hotfix

**JARVIS** is an autonomous, on-device AI personal assistant built for Android that rivals Siri and Google Assistant.

### What's New in v1.1.1

#### Performance Improvements
| Fix | Impact |
|-----|--------|
| JarvisVoiceEngine Singleton | ~500ms faster HUD launch |
| Pre-inflated Overlay | Instant HUD visibility toggle |
| Removed Video Player | ~200-500ms saved per HUD open |
| Optimized Scrolling | Eliminated janky mid-stream animations |
| Faster Error Recovery | 3s → 1s backoff on recognition errors |
| Haptic Feedback | Tactile confirmation on wake word |

#### Bug Fixes
- **HUD Latency Fixed**: HUD now appears immediately after wake word detection instead of 3-4 seconds late
- Fixed redundant scroll animations during live speech recognition

### Installation

1. Download `app-release.apk` below
2. Enable "Install from unknown sources" in Settings
3. Open the APK to install

### Requirements
- Android 10+ (API 29+)
- Microphone permission
- Overlay permission (SYSTEM_ALERT_WINDOW)

### Full Changelog
See [CHANGELOG.md](CHANGELOG.md) for complete release history.
