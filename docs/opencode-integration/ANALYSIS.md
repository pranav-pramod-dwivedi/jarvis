# JARVIS ⇄ OpenCode Native Integration — Pre-Implementation Analysis

Status: ANALYSIS COMPLETE (verified against installed OpenCode 1.18.23 on this machine)
Scope guard: **permissions / file-manager / installer modules are owned by other agents — this
integration touches none of their files.**

---

## 1. Current JARVIS architecture (as found)

- Single-module Android app, 100% Kotlin, Views/XML UI, `minSdk 24`, Java 11 target.
- Package: flat `com.pr4nav.jarvis`, ~11 source files.
- Conventions: stateless `object` singletons as managers (`TermuxBridge`, `Shell`, `Fs`,
  `SessionState`, `ResultBus`), each with explicit `init(context)` called from `MainActivity.onCreate`.
- Concurrency: raw `Thread {}` + `Handler(Looper.getMainLooper())` + `runOnUiThread`. No coroutines.
- IPC: Termux `RUN_COMMAND` intent bridge (`TermuxResultReceiver.kt`), blocking request/response via
  `SynchronousQueue` + one-shot PendingIntent; `ResultBus` listener list for fan-out.
- Process mgmt: `Shell.termux()` (bridge), `Shell.local()` (`ProcessBuilder("sh","-c")`),
  `Shell.root()` (`su -c`). Bootstrap deploys `assets/bootstrap.sh` into Termux which installs
  OpenCode inside proot-Ubuntu and starts `opencode serve --hostname 0.0.0.0 --port 4096..4099`,
  persisting the chosen port to `~/jarvis/state/server.port`.
- Persistence: SharedPreferences only. JSON: `org.json` only. Networking libs: NONE.
- Existing OpenCode touchpoints are all shell-mediated: install, auth detection, one-shot
  `opencode run '<prompt>'` (300 s) in `AgentActivity`, port probes in `DiagnosticsActivity`.
- INTERNET permission is declared (`AndroidManifest.xml`).
- Test infra: JUnit4 wired (`testImplementation(libs.junit)`); only template tests exist.

Feature-ownership map (DO NOT TOUCH):
- permissions → `PermissionsActivity.kt`, `AdminReceiver.kt`, `JarvisAccessibilityService.kt`,
  manifest permission block, `activity_permissions.xml`, accessibility/device-admin xml configs.
- installer → `bootstrap.sh`, MainActivity INSTALLER section, `DiagnosticsActivity.kt`.
- file manager → `Fs.kt`, `BrowserActivity.kt`, `item_file.xml`, browser menus.

## 2. Reference architecture findings (grinev/opencode-telegram-bot v0.24.1, MIT)

Studied, not copied. Key patterns worth mirroring:

1. Single HTTP client against `opencode serve` (Server API). **No ACP** anywhere in the reference.
2. Client returns `{data, error}` result pairs; app never lets transport exceptions escape.
3. Output channel = **fire-and-forget prompt + SSE event stream** (`promptAsync`, not blocking prompt).
   Optimistic busy-marking before dispatch; idle restored from events.
4. Global SSE first (`/global/event`), legacy per-directory SSE as fallback; 30 s idle-timeout
   watchdog per attempt; exponential reconnect backoff 1 s→15 s cap; directory filtering of
   envelope payloads; single active subscription with generation counters to drop stale callbacks.
5. Busy-state reconciliation: poll `session.status` on server heartbeat / on demand with grace
   windows, because events can be missed across reconnects.
6. Permissions: dedupe/group requests, reply `once|always|reject` keyed by requestID;
   "not found" treated as benign duplicate resolution. Questions answered via structured
   `answers[][]` payload.
7. Process ownership implicit-by-locality: spawn/restart/stop only when URL host is local;
   health probe everywhere; auto-restart opt-in; stop via PID discovery (lsof/ss), SIGTERM→SIGKILL
   escalation. Never touches remote servers' lifecycle.
8. Model catalog from `/config/providers`; favorites/recents read from OpenCode's own state file
   (`~/.local/state/opencode/model.json`) and validated against catalog; variants enumerated per model.
9. Session cache synced incrementally with epoch watermark; selection is a purely local concept.
10. Prompt queue (in-memory, bounded) while busy; cleared on abort/switch.

## 3. Exact interface available in THIS installation

- Binary: `~/.opencode/bin/opencode`, version **1.18.23** (macOS arm64 here; same CLI family inside
  Termux/proot on-device per bootstrap.sh).
- Interfaces exposed by this version:
  - **Server API** — `opencode serve --port N --hostname H` headless HTTP server,
    OpenAPI 3.1 spec at `GET /doc` (162 paths captured to docs/opencode-integration/openapi-paths.txt).
  - **SSE events** — `GET /global/event` and `GET /event?directory=…`.
  - ACP (`opencode acp`) exists but is IDE-oriented; NOT used (matches reference findings).
  - CLI attach/run — already partially used by AgentActivity; remains available as fallback.
- Auth model verified live: Basic auth **only if** `OPENCODE_SERVER_PASSWORD` env is set at server
  start (username default `opencode`, override `OPENCODE_SERVER_USERNAME`). No password ⇒ no auth
  (dev-source confirms; warning printed). NOTE: this machine's shell exports
  `OPENCODE_SERVER_PASSWORD` (set by OpenChamber) — any local test must set its own creds explicitly.
- Directory scoping: `?directory=<path>` query parameter on session/permission/question endpoints;
  `x-opencode-directory` header also accepted server-side.
- Verified endpoint subset JARVIS will use (full list in openapi-paths.txt):

| Purpose | Call |
|---|---|
| Health/version | `GET /global/health` |
| Global SSE | `GET /global/event` |
| Per-dir SSE | `GET /event?directory=` |
| Projects | `GET /project`, `GET /project/current` |
| Sessions | `GET/POST /session?directory=`, `GET/PATCH/DELETE /session/{id}` |
| Session map | `GET /session/status` |
| Prompt | `POST /session/{id}/prompt_async` body `{parts:[{type:text,text}], agent?, model?{providerID,modelID}, variant?}` |
| Abort | `POST /session/{id}/abort` |
| Fork | `POST /session/{id}/fork` |
| Messages | `GET /session/{id}/message`, `GET /session/{id}/message/{mid}` |
| Diff/todo | `GET /session/{id}/diff`, `GET /session/{id}/todo`, `GET /session/{id}/children` |
| Permissions | `GET /permission?directory=`, `POST /permission/{requestID}/reply {reply:"once"|"always"|"reject"}` |
| Questions | `GET /question?directory=`, `POST /question/{requestID}/reply {answers:[[..]]}`, `POST /question/{requestID}/reject` |
| Models | `GET /config/providers` (providers→models→variants) |
| Agents | `GET /agent` |
| Commands/skills | `GET /command`, `GET /skill` |
| Paths | `GET /path` |
| VCS | `GET /vcs/status`, `GET /vcs/diff` |

Event types consumed (from spec + reference behavior): `server.connected`, `server.heartbeat`,
`message.updated`, `message.part.updated`, `message.part.delta`, `session.created/updated/error/idle/
compacted/diff/status`, `permission.asked/replied`, `question.asked/replied/rejected`.

## 4. Gap analysis (JARVIS today vs required)

| Requirement | Status |
|---|---|
| HTTP/SSE client | MISSING (no networking code at all) |
| Server process lifecycle (start/stop/health/restart, ownership) | PARTIAL — bootstrap.sh can start serve; app cannot control/verify beyond curl probe in Diagnostics |
| Typed session API (create/resume/fork/abort/rename/list) | MISSING (only one-shot CLI run) |
| Event stream → internal bus | MISSING (NDJSON file polling only, unrelated to server events) |
| Structured tool-call representation | MISSING |
| Permission/question round-trip | MISSING |
| Model/agent discovery + switching | MISSING (hardcoded nothing; CLI `-m` only) |
| Multi-session registry + persistence | MISSING (`SessionState.dir` is a single path var) |
| Per-session locks/queueing | MISSING |
| Background session notifications | MISSING (no NotificationManager usage) |
| Structured errors/logging | PARTIAL (android.util.log sprinkled; Result has err fields) |

## 5. Proposed file structure (new package, zero collisions)

All NEW files under `app/src/main/java/com/pr4nav/jarvis/opencode/` plus one JVM test tree under
`app/src/test/java/com/pr4nav/jarvis/opencode/`. No existing file is modified in this increment.

```
opencode/
├── OpenCode.kt                 // facade singleton init(context)/shutdown() following project convention
├── OpenCodeConfig.kt           // baseUrl, credentials, timeouts (from prefs; never logs secrets)
├── OpenCodeException.kt        // typed errors: UNAVAILABLE/AUTH/SESSION_MISSING/PERMISSION/TIMEOUT/…
├── OpenCodeLogger.kt          // structured tag-based logging wrapper (redacts auth)
├── transport/
│   ├── OpenCodeHttp.kt         // HttpURLConnection GET/POST/PATCH/DELETE + Basic auth, Result<T>
│   └── OpenCodeSse.kt          // text/event-stream line parser over streaming connection
├── json/                       // small org.json parsers DTO ← server JSON (tolerant of extra fields)
│   ├── Dto.kt                  // OcSession/OcMessage/OcPart/OcModel/OcAgent/OcPermission/… data classes
│   └── Events.kt               // sealed OcEvent + decoder from SSE data payloads
├── OpenCodeProcessManager.kt   // health, detect-existing, spawn via Shell.termux(), stop, restart, crash detect
├── OpenCodeClient.kt           // typed methods ONLY (createSession/sendPrompt/abort/respondPermission/…) —
│                               // no arbitrary-request surface
├── OpenCodeSessionManager.kt   // registry: create/load/resume/switch/fork/abort/rename/archive/list
│                               // per-session Mutex + busy state + bounded prompt queue
├── OpenCodeProjectManager.kt   // list projects, current, git/worktree detection helpers
├── OpenCodeEventManager.kt     // single global SSE loop, reconnect/backoff/watchdog, dir filter,
│                               // fan-out to listeners; throttled UI deltas
├── OpenCodeModelManager.kt     // providers/models/variants catalog (10 min cache), favorites/recents read
├── OpenCodeAgentManager.kt     // agents list, plan/build switching
├── OpenCodePermissionManager.kt// pending permission/question queue, explicit respond(), never auto-approve
├── OpenCodeSessionStore.kt     // SharedPreferences-backed registry JSON (metadata only, no secrets)
└── OpenCodeToolTracker.kt      // ToolCall structs assembled from part events (status/input/output/timing)
```

Threading: manager objects expose blocking suspend-free methods executed on caller threads
(project convention: activities already use `Thread{}`); SSE loop runs on a dedicated daemon thread.
UI callbacks always marshalled by the listener (existing pattern uses `runOnUiThread`).

## 6. Data models (core)

```
OcSessionRef    { id, projectId?, directory, title, model{provider,model,variant}?, agent?,
                  status(IDLE|BUSY|RETRY|ERROR|UNKNOWN), background, createdAt, updatedAt,
                  lastActivityAt, unread }
OcProject       { id, worktree, name, lastUpdated }
OcToolCall      { sessionId, callId, tool, status(PENDING|RUNNING|COMPLETED|ERROR),
                  input(json), title?, output/meta(json), startedAt, completedAt, error? }
OcPermissionReq { requestId, sessionId, type, patterns[], title?, metadata }
OcQuestionReq   { requestId, sessionId, questions[{question,header,options[{label,description}],multiple}] }
OcModelId       { providerID, modelID, variant? }
OcEvent         (sealed): ServerConnected, Heartbeat, MessageUpdated(info), PartUpdated(part),
                  PartDelta(sessionId,messageId,partId,type,delta), SessionCreated/Updated(info),
                  SessionIdle(sessionId), SessionError(sessionId,error), SessionDiff(files[]),
                  SessionStatus(sessionId,status,message?), PermissionAsked(req), PermissionReplied,
                  QuestionAsked(req), QuestionReplied, Unknown(type)
```

Registry record persisted in prefs (JSON array):
`{sessionId, directory, title, model, agent, background, createdAt, updatedAt, archived, label?}`.

## 7. Session lifecycle

CREATE → POST /session {directory} → register locally (status IDLE).
PROMPT → per-session mutex acquire; if BUSY: enqueue (max 8) or reject per policy → mark BUSY
optimistically → POST prompt_async → return immediately.
STREAM → events update message/tool/state until `session.idle|error` → status IDLE → drain queue.
RESUME/SWITCH → pure local pointer change + `GET /session/{id}/message` backfill; NEVER sends
anything implicitly (prevents cross-session prompt accidents; switch refuses while source busy
unless user chose detach semantics).
FORK → POST /session/{id}/fork {messageID?} → new session registered.
ABORT → POST abort → verify via /session/status poll ≤5 s → clear queue.
ARCHIVE/DELETE → DELETE /session/{id} where supported; else archive flag locally.
Background: any registered session keeps receiving events regardless of "current"; completion or
permission.asked on non-current session raises unread + notification hook.

## 8. Event lifecycle

One daemon thread owns the global SSE connection: connect → filter by selected directories
(envelope `{directory,payload}` or bare `{type,properties}`) → decode → dispatch to
OpenCodeEventManager.listeners (copy-on-write list, marshalled on caller of subscribe or via
Handler if requested) → 30 s read-idle watchdog → close → backoff reconnect (1s×2^n cap 15s,
reset on success). On reconnect: refresh /session/status to reconcile stuck BUSY states.
Part deltas throttled per (messageId) at ~100 ms before fan-out to UI-tagged listeners.

## 9. Process lifecycle (on-device)

DETECT: try saved baseUrls (127.0.0.1:4096..4099 + persisted port file content relayed through
shell `cat ~/jarvis/state/server.port`) with /global/health (2 s timeout).
If healthy → adopt (connect-only; remember we do NOT own it).
If none → SPAWN (ownership=JARVIS): generate random password, persist in private prefs, launch via
`Shell.termux()` inside the proot Ubuntu exactly like bootstrap.sh does, with our env creds;
poll health up to 20 s; store pid marker file `~/jarvis/state/server.pid.<port>` we wrote ourselves.
CRASH: health failures on owned server trigger restart w/ backoff (≤3 attempts then UNAVAILABLE +
user notification hook). STOP: only processes we own — kill by our pid marker (never pkill by name);
SIGTERM → wait 3 s → SIGKILL. Restart = stop+spawn. All process ops serialized on a monitor lock.

## 10. Migration plan (increments)

1. **This increment**: opencode package (transport→client→managers→store) + JVM integration tests
   running REAL `opencode serve` (spun up by tests on an ephemeral port with throwaway HOME/config
   dir so user sessions/auth are untouched). Zero changes to existing files.
2. Wire-up increment (after agents land their work): `MainActivity.onCreate` gains one
   `OpenCode.init(this)` line; new `OpenCodeActivity` console screen; manifest entry.
3. Agent-console routing: AgentActivity `opencode` keyword re-routed through OpenCodeManager
   (replaces 300 s CLI exec with server session flow) — coordinated edit, single when-branch.
4. Notifications + background tracking polish; scheduled-task support later if wanted.

## Testing strategy (this increment)

JUnit4 JVM tests, tagged/integrated so they only run where the `opencode` binary exists
(AssumeTrue on `opencode --version`): real server spawned on port 45xx with isolated
`OPENCODE_CONFIG_DIR`/HOME temp dir + test password; covers: connect, unavailable-detect, spawn,
health, create session, prompt_async + streamed text via SSE, tool events, completion/idle,
resume/backfill, two concurrent sessions, background session events while "focused" elsewhere,
abort, fork, permission ask→reply (forced via a prompt that writes outside sandbox? NO — use
permission-triggering bash command with ask policy in temp project config), question flow skip
(model-dependent → assumed out), model catalog fetch, project list, rename, delete, reconnect
(kill server mid-stream, expect recovery after respawn), crash detection, auth-required rejection
(wrong password ⇒ AUTH error), directory scoping isolation.

No mock server. No stub backend. Claims gated on these tests passing against 1.18.23.
