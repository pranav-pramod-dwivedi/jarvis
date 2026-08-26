# JARVIS — Progress Log

Autonomous development log. Each entry records what was ACTUALLY verified and the next concrete task.

---

## 2026-08-26 — File manager core + unified FS layer (iteration 4)

**Direction change:** JARVIS is now a file-manager-first AI environment (Material Files used as UX reference), with Termux/OpenCode as engines underneath.

### Landed (code complete, compiles green)

- **Unified filesystem layer (`Fs.kt`)** — one interface (`list/read/write/mkdir/create/delete/rename/copy/move/exists/stat/search`) over 4 backends with automatic routing by path:
  - `Java` (java.io.File; full power under All-Files-Access)
  - `SAF` (persisted document tree; picked via ACTION_OPEN_DOCUMENT_TREE)
  - `Termux` (private Termux home through the RUN_COMMAND bridge)
  - `Root` (su; only when device grants it — detected, never assumed)
  - `Fs.accessLevel` reports honest access: App-Private / SAF / Full Storage / Root.
- **Shell bridge (`TermuxResultReceiver.kt`)** — `TermuxBridge.execute()` (blocking, PendingIntent result, unique req IDs, timeout) + `Shell.termux/local/root` runners returning stdout/stderr/rc/elapsed/timeout.
- **File manager UI (`BrowserActivity`)** — breadcrumbs (tappable), list with type icons + size/date, multi-select ActionMode (copy/cut/delete/share/rename/properties), paste, new folder/file, sort (name/size/date/type + direction, persisted), hidden-files toggle, subtree search, SAF picker, open-with via FileProvider, properties dialog, storage-access shortcut.
- **Agent (`AgentActivity`)** — real operations against the SAME Fs layer: `ls/open/read/write/mkdir/delete/find/search(stat)/run/opencode/projects`. Real errors surfaced; no fake results. `SessionState.dir` is shared with the browser (agent sees what user browses).
- **Diagnostics (`DiagnosticsActivity`)** — live checks: Android ver/arch, All-Files, SAF, storage access level, Termux installed/perm/bridge round-trip/command exec, Ubuntu login, OpenCode presence+auth, server port, root availability, local shell.
- **Dashboard (`MainActivity`)** — "JARVIS — File Manager · AI Agent · Termux Bridge", OPEN FILE MANAGER button, storage card (StatFs), bootstrap controls, stage tracker.
- **Bootstrap fixes (`assets/bootstrap.sh`)** — `proot_busy` repair now kills stale proot processes from the Termux side (previous version tried to pkill via the container it couldn't enter); `proot_uninstall_stuck` now runs `proot-distro remove -f` before rm; fresh-run event reset; single-instance PID lock.
- **SelfTest (`SelfTest.kt`)** — 16 real operations (app-dir CRUD+search, /sdcard CRUD+rename/copy/move/list/cleanup, termux echo/pipe/stderr+rc) with pass/fail per line and cleanup.

### Verified

- `./gradlew :app:assembleDebug` — **BUILD SUCCESSFUL** with both this iteration and the parallel permissions workstream merged in the same tree.

### NOT yet verified on device (deliberately held)

- Install + selftest run on the Redmi Note 8 Pro is **on hold** to avoid interfering with the parallel permissions agent's device testing. No adb grants/installs were performed this iteration.
- Bootstrap end-to-end (Ubuntu install → OpenCode → server) still blocked on the earlier "partial rootfs" state; the fixed repair path has not been exercised on device yet.

### Coordination notes

- `AndroidManifest.xml` currently contains BOTH workstreams' entries (my activities/provider/permissions + their service/receiver). It is intentionally **not committed** yet — it must be committed together with the permissions agent's components (PermissionsActivity, JarvisAccessibilityService, AdminReceiver, strings.xml additions) in one atomic commit, otherwise the repo would reference classes it doesn't contain.

### Next concrete task

1. After permissions work lands: coordinated commit (manifest + their components + strings).
2. Install on device → run `--es auto selftest` → require 16/16 PASS in logcat `JARVIS` tag.
3. Run `--es auto bootstrap` → watch stages; expect Ubuntu download to proceed past the previous "container busy / Uninstall:" failures thanks to the Termux-side pkill + `proot-distro remove -f` repair.
4. If auth_required appears → OPEN AUTH flow (interactive, user enters OpenCode Zen key in the Termux window; key never touches Jarvis).
5. Then: agent↔OpenCode round trip (`opencode run`) and server health check in Diagnostics.
