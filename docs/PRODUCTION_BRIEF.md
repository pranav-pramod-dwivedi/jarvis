# Production Brief for GPT Astra — backend production-grade + dub-model fix

Date: 2026-09-16. Base: `origin/main` HEAD (pull first). Owner directive relayed via Opencode session.

## Mission A — Fix dub models
"Dub" = duplicate AND dud (dead) model entries surfacing in pickers and breaking turns.
1. Audit every model-list path: `KiraClient.fetchAvailableModels`, `GroqClient.fetchAvailableModels`, `GeminiCloudLLM.fetchAvailableModels`, `OllamaClient.fetchModels`, `GeminiLiveClient.LIVE_MODELS`.
2. Normalize + dedupe: case-insensitive id match, strip `models/` and provider prefixes before comparing, one entry per model. Prove with unit tests (crafted lists with `Foo`, `foo`, `models/Foo`, `provider/Foo`).
3. Validate-on-select: when a user picks a model, confirm the id exists in the fresh fetched list; if missing, show the `nearestPlatformId`-style suggestion and refuse to save a dead id. Unit-test the matcher incl. renamed-id cases.
4. Dead-id runtime guard: a 404 for a selected model must surface the server body verbatim in the error bubble and suggest the nearest live id — never a bare "HTTP 404".
5. Keep the existing `TtsWalletProbeTest`-style rule: no instrumentation runs against live user state (they wipe app data incl. API keys). JVM tests only unless the user explicitly backs up.

## Mission B — Whole backend production-grade
For EVERY engine (Kira, Groq, Gemini, Ollama, Live, Local) and every fallback hop in `UnifiedAssistantDispatcher`:
1. **No unbounded waits**: every socket has connect+read timeouts; every multi-turn loop has a wall-clock deadline; every background wait (`latch.await`, `future.get`) has a timeout. Grep for `await(`, `.get(`, `readText()` on streams, `Thread.sleep` in request paths and justify or bound each.
2. **No swallowed errors**: every `catch (_: Exception)` in request paths must log with TAG + model/endpoint context and propagate a meaningful message. Audit and fix; add tests where the error string is asserted (quota/timeout/401/402/404 shapes already exist as precedent in `EngineFallbackTest`).
3. **No retry storms**: at most one retry per failure class; cascade breakers on quota + stall (pattern exists in KiraClient — extend the same discipline to Groq/Gemini/Ollama/Live paths).
4. **Structured request logging**: every outbound call logs engine + model + ms + outcome code at info/warn (no keys, no bodies over 200 chars). The Kira `Attempting Kira AI model:` line is the pattern to replicate.
5. **Health visibility**: extend the harness TEST CONNECTION probe beyond Kira — one tap must report per-engine reachability (key present? endpoint alive? ms?). No new screens; extend `RouteHarnessActivity` + `KiraClient.probeEndpoints` pattern.
6. **Persistence safety**: API keys, selected models, and routes must survive process death and reinstall-safe paths — verify `apply()` vs `commit()` usage where loss was observed; add a JVM test that set→get round-trips via an in-memory stand-in where Android prefs are unavailable (do NOT instrument user state).
7. **Concurrency**: singleton clients (`socket`, `listener`, audio player) must survive overlapping turns — write-lock or generation-guard every shared mutable; add a JVM concurrency test where feasible (see `concurrentFramesStaySequential` precedent).

## Acceptance gates (all mandatory)
- `./gradlew :app:testDebugUnitTest` fully green (currently 376/376 — keep it 100%, add tests for every fix).
- `./gradlew assembleDebug` green; `adb install -r` + launch smoke with zero FATALs in logcat.
- New user-visible behavior verified from evidence (logcat lines, probe screenshots), never assumed.
- Commit ONLY touched files with `feat:`/`fix:`/`test:` messages; push to `origin main`.
- Reply to the user with: what was broken (with log evidence), what changed (files), test score, and anything still needing them (keys, wallet top-up, screenshots).

## Standing context (do not rediscover)
- Kira wallet was 0 VND (402s); API keys were once wiped by instrumentation runs — user re-enters keys in Provider keys screen.
- Kira chat endpoint hangs past socket timeouts on this network; tiered deadlines + breakers exist — do not flatten to unbounded.
- Device screen stays OFF over adb: no screenshots/taps; verify via install + logcat + unit tests.
- Skills-as-context architecture and 2-line system prompts are intentional — do not re-bloat prompts.
- No emojis in UI (SVG drawables); match existing code style per area (programmatic views vs Compose).
