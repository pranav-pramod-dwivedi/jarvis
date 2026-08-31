# Implementation Plan - Integrate Video Splash Screen

The goal is to integrate the provided Jetpack Compose-based Video Splash Screen into `MainActivity.kt` while preserving all existing functionality and logic.

## User Review Required

> [!IMPORTANT]
> The project currently does not use Jetpack Compose. Implementing this request requires adding Compose dependencies and enabling the Compose compiler. This will slightly increase the build time and APK size.

> [!NOTE]
> The existing `MainActivity` uses XML layouts. We will use a hybrid approach where the Splash screen is shown using Compose, and upon completion, the app will transition to the existing XML-based UI.

## Proposed Changes

### Build Configuration

#### [MODIFY] [libs.versions.toml](file:///Users/tanutripathi/AndroidStudioProjects/Jarvis/gradle/libs.versions.toml)
- Add Compose-related versions and libraries (Compose UI, Material 3, Activity Compose).

#### [MODIFY] [build.gradle.kts](file:///Users/tanutripathi/AndroidStudioProjects/Jarvis/app/build.gradle.kts)
- Enable Compose build feature.
- Add Compose dependencies from `libs.versions.toml`.

### App Components

#### [MODIFY] [MainActivity.kt](file:///Users/tanutripathi/AndroidStudioProjects/Jarvis/app/src/main/java/com/pr4nav/jarvis/MainActivity.kt)
- Integrate the `VideoSplashScreen` and `MainSplashApp` composables.
- Update `onCreate` to show the Splash screen first.
- Move the existing initialization logic into a separate method (`initMainLayout`) that is called once the splash animation finishes.
- Use a `ComposeView` or `setContent` to host the splash screen.

## Verification Plan

### Automated Tests
- Run `./gradlew assembleDebug` to ensure the project builds correctly with Compose.

### Manual Verification
- Deploy to an Android device/emulator.
- Verify that the video splash screen plays full-screen.
- Verify that after the video ends, it zooms, fades, and blurs before revealing the original `MainActivity` dashboard.
- Verify that all buttons (Files, Agent, Terminal, etc.) in the dashboard still work as expected.
