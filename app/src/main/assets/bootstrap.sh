#!/data/data/com.termux/files/usr/bin/bash
# jarvis-bootstrap v2 — self-healing, resumable Jarvis environment installer
# Runs INSIDE Termux. Stages (each idempotent, verify-first):
#   termux -> ubuntu -> login -> packages -> opencode -> auth -> server -> model
# Protocol: appends NDJSON {"stage","status","msg","detail"} to ~/jarvis/state/events.ndjson
# Usage: bootstrap.sh          full pipeline (resume-safe)
#        bootstrap.sh auth     open INTERACTIVE opencode auth login (needs a real terminal)

set -u

J="$HOME/jarvis"
STATE="$J/state"; LOGS="$J/logs"; MEM="$J/memory"; BK="$J/backup"
mkdir -p "$J/bin" "$STATE" "$LOGS" "$MEM" "$BK"
EVENTS="$STATE/events.ndjson"
LOG="$LOGS/bootstrap-$(date +%Y%m%d-%H%M%S).log"
ROOTFS="$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"

# ---------- single-instance lock (stale-lock safe) ----------
LOCK="$J/run/bootstrap.lock"
acquire_lock(){
  if mkdir "$LOCK" 2>/dev/null; then
    printf '%s\n' "$$" >"$LOCK/pid"
  else
    local op; op=$(cat "$LOCK/pid" 2>/dev/null || true)
    if [ -n "$op" ] && kill -0 "$op" 2>/dev/null; then
      echo "bootstrap already running (pid $op)"; exit 0
    fi
    rm -rf "$LOCK"; mkdir "$LOCK"; printf '%s\n' "$$" >"$LOCK/pid"
  fi
  trap 'rm -rf "$LOCK"' EXIT
}
acquire_lock
# Fresh run: clear old events so poller sees clean state
> "$EVENTS"

# ---------- output protocol ----------
esc(){ printf '%s' "$1" | tr -d '\000' | tr '\n\r' '  ' | sed -e 's/\\/\\\\/g' -e 's/"/\\"/g'; }
emit(){ # <stage> <status> <msg> [detail]
  printf '{"ts":%s,"stage":"%s","status":"%s","msg":"%s","detail":"%s"}\n' \
    "$(date +%s)" "$1" "$2" "$(esc "$3")" "$(esc "${4:-}")" >>"$EVENTS"
  printf '%s|%s|%s\n' "$1" "$2" "$3" >"$STATE/status.txt"
}
die(){ emit "$1" failed "$2" "${3:-see logs}"; exit 1; }
log(){ printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >>"$LOG"; }

# ---------- error memory ----------
mem_lookup(){ awk -F'\t' -v c="$1" '$1==c{print $3; exit}' "$MEM/history.tsv" 2>/dev/null; }
mem_save(){ # <category> <cause-one-line> <fix-one-line> <verified?>
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$(esc "$2" | cut -c1-160)" "$3" "${4:-unverified}" \
    "$(date -u +%FT%TZ)" >>"$MEM/history.tsv"
}

classify(){ case "$1" in
  *"container.*busy"*|*"container 'ubuntu' is busy"*)   echo proot_busy ;;
  *"Uninstall:   proot-distro remove ubuntu"*|*"Uninstall:*proot-distro remove"*) echo proot_uninstall_stuck ;;
  *"command not found"*)            echo missing_command ;;
  *"No space left"*|*"No such file or directory"*) echo fs ;;
  *"Could not get lock"*|*"dpkg was interrupted"*|*"dpkg --configure"*) echo apt_lock ;;
  *"Temporary failure resolving"*|*"Could not resolve host"*|*"Network is unreachable"*|*"Connection timed out"*|*"Failed to connect"*) echo network ;;
  *"Permission denied"*)            echo permission ;;
  *"Unable to locate package"*)     echo pkg_missing ;;
  *)                                echo unknown ;;
esac; }

# runx <stage> <cmd...> : capture rc+output, classify failures, bounded smart retries.
# Sets R_OUT on success; returns last rc otherwise. Never blindly loops: each retry
# escalates (sleep backoff -> built-in repair -> known-fix from memory -> give up).
runx(){ local st="$1"; shift; local out rc cat tries=0 fix
  while :; do
    out=$("$@" 2>&1); rc=$?
    printf '$ %s\nrc=%s\n%s\n---\n' "$*" "$rc" "$out" >>"$LOG"
    [ $rc -eq 0 ] && { R_OUT="$out"; return 0; }
    cat=$(classify "$out"); fix="$(mem_lookup "$cat")"
    tries=$((tries+1)); [ $tries -ge 4 ] && { mem_save "$cat" "$out" "UNRESOLVED after retries" fail; R_OUT="$out"; return $rc; }
    case "$cat" in
      network)  emit "$st" fixing "Network hiccup — retrying (${tries}/3)…" "$(printf '%s' "$out" | tail -1)"
                [ -n "$fix" ] && log "memory[$cat]: $fix"
                sleep $((tries*8)) ;;
      apt_lock) emit "$st" fixing "Package manager was interrupted — repairing…" ""
                ub_repair_apt || true ;;
      proot_busy) emit "$st" fixing "Ubuntu container busy — clearing stale proot processes…" ""
                pkill -9 -f proot-distro 2>/dev/null
                pkill -9 -f 'proot login' 2>/dev/null
                sleep 2 ;;
      proot_uninstall_stuck) emit "$st" fixing "Partial Ubuntu detected — removing cleanly…" ""
                proot-distro remove ubuntu -f >/dev/null 2>&1
                rm -rf "$ROOTFS" "$ROOTFS".tmp "$ROOTFS".old 2>/dev/null
                rm -rf "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"* 2>/dev/null
                sleep 2 ;;
      fs)       emit "$st" fixing "Storage issue detected — checking space…" ""
                df -h "$HOME" >>"$LOG" 2>&1; sleep 2 ;;
      *)        emit "$st" fixing "Issue ($cat) — diagnosing…" "$(printf '%s' "$out" | tail -1)"
                [ -n "$fix" ] && log "memory[$cat]: $fix"
                sleep 3 ;;
    esac
  done
}

# ---------- ubuntu helpers ----------
ub_run(){ proot-distro login ubuntu -- /bin/bash -c "$1" 2>&1; }   # one-liners
ub_pipe(){ proot-distro login ubuntu -- /bin/bash -s 2>&1; }       # reads stdin
forward(){ printf '%s\n' "$@" | grep '^@JARVIS {' | sed 's/^@JARVIS //' >>"$EVENTS"; }
ub_repair_apt(){
  ub_pipe <<'EOF' >/dev/null 2>&1
export DEBIAN_FRONTEND=noninteractive
pgrep -x dpkg >/dev/null 2>&1 && sleep 8
rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock /var/lib/apt/lists/lock
dpkg --configure -a
apt-get -f install -y
EOF
  return 0
}

# ---------- Stage: termux base ----------
TPKGS="bash curl ca-certificates git nodejs npm proot-distro"
tmissing(){ local m="" p; for p in $TPKGS; do dpkg -s "$p" >/dev/null 2>&1 || m="$m $p"; done; printf '%s' "${m# }"; }
termux_base(){
  local m; m=$(tmissing)
  if [ -n "$m" ]; then
    emit termux running "Installing Termux packages: $m"
    runx termux pkg update -y || true
    local n=0
    until runx termux pkg install -y $m; do
      n=$((n+1)); [ $n -ge 3 ] && die termux "Couldn't install Termux packages:$m" "$(tail -2 "$LOG")"
      emit termux fixing "Package manager glitch — repairing (pass $n)…"
      sleep 5; runx termux pkg update -y || true
    done
  fi
  m=$(tmissing); [ -n "$m" ] && die termux "Still missing after install:$m"
  local c
  for c in bash curl git node npm proot-distro; do
    command -v "$c" >/dev/null 2>&1 || die termux "$c not found on PATH" ""
  done
  emit termux done "Termux base ready ($(node --version 2>/dev/null))"
}

# ---------- Stage: ubuntu ----------
ubuntu_install(){
  if [ -d "$ROOTFS" ]; then
    emit ubuntu fixing "Partial Ubuntu found — removing before fresh install…"
    proot-distro remove ubuntu -f >/dev/null 2>&1
    rm -rf "$ROOTFS" "$ROOTFS".tmp "$ROOTFS".old 2>/dev/null
    sleep 1
  fi
  emit ubuntu running "Downloading Ubuntu (one-time, few hundred MB)…"
  runx ubuntu proot-distro install ubuntu || {
    emit ubuntu fixing "Install interrupted — cleaning and resuming…"
    rm -rf "$ROOTFS" "$ROOTFS".tmp "$ROOTFS".old 2>/dev/null
    rm -rf "$PREFIX/var/lib/proot-distro/installed-rootfs/ubuntu"* 2>/dev/null
    sleep 2
    runx ubuntu proot-distro install ubuntu || die ubuntu "Ubuntu install failed after cleanup" "$(tail -2 "$LOG")"
  }
  [ -d "$ROOTFS" ] && emit ubuntu done "Ubuntu installed" || die ubuntu "Ubuntu rootfs missing after install"
}
ubuntu_login(){
  if ub_run 'true' >/dev/null 2>&1; then emit login done "Ubuntu starts cleanly"; return 0; fi
  emit login fixing "Ubuntu not starting — diagnosing…" ""
  df -h "$HOME" >>"$LOG" 2>&1
  if [ -d "$ROOTFS" ]; then
    emit login fixing "Backing up Ubuntu home, then repairing container…"
    tar -czf "$BK/ubuntu-home-$(date +%s).tgz" -C "$ROOTFS/root" . 2>>"$LOG"
    runx login proot-distro remove ubuntu -f || true
    runx login proot-distro install ubuntu || die login "Ubuntu reinstall failed" "$(tail -2 "$LOG")"
  else
    ubuntu_install
  fi
  ub_run 'true' >/dev/null 2>&1 && emit login done "Ubuntu repaired" || die login "Ubuntu cannot start even after repair" ""
}

# ---------- Stage: ubuntu packages ----------
ubuntu_packages(){
  emit packages running "Preparing Ubuntu tools…"
  local out; out=$(ub_pipe <<'EOF'
export DEBIAN_FRONTEND=noninteractive
repair_apt(){
  pgrep -x dpkg >/dev/null 2>&1 && sleep 8
  rm -f /var/lib/dpkg/lock-frontend /var/lib/dpkg/lock /var/cache/apt/archives/lock /var/lib/apt/lists/lock
  dpkg --configure -a
  apt-get -f install -y
}
MISS=""
for p in curl ca-certificates git bash; do
  command -v "$p" >/dev/null 2>&1 || dpkg -s "$p" >/dev/null 2>&1 || MISS="$MISS $p"
done
if [ -n "${MISS# }" ]; then
  apt-get update -qq >/dev/null 2>&1 || { repair_apt; sleep 3; apt-get update -qq; }
  apt-get install -y ${MISS# } || { repair_apt; apt-get install -y ${MISS# }; }
fi
BAD=""
for p in curl ca-certificates git bash; do command -v "$p" >/dev/null 2>&1 || BAD="$BAD $p"; done
if [ -n "${BAD# }" ]; then
  printf '@JARVIS {"stage":"packages","status":"failed","msg":"missing:%s"}\n' "${BAD# }"
else
  printf '@JARVIS {"stage":"packages","status":"done","msg":"Ubuntu tools ready"}\n'
fi
EOF
) ; log "packages: $out"; forward "$out"
  case "$out" in *@JARVIS*failed*) die packages "Ubuntu package setup failed" "$(printf '%s' "$out" | tail -1)";; esac
}

# ---------- Stage: opencode ----------
opencode_stage(){
  emit opencode running "Checking OpenCode…"
  local out; out=$(ub_pipe <<'EOF'
export DEBIAN_FRONTEND=noninteractive
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
OC="$(command -v opencode 2>/dev/null)"
[ -z "$OC" ] && [ -x "$HOME/.local/bin/opencode" ] && OC="$HOME/.local/bin/opencode"
if [ -z "$OC" ]; then
  printf '@JARVIS {"stage":"opencode","status":"running","msg":"Installing OpenCode (official installer)…"}\n'
  i=1
  while [ $i -le 3 ]; do
    curl -fsSL https://opencode.ai/install | bash >/tmp/jarvis-oc-install.log 2>&1 && break
    i=$((i+1)); sleep $((i*5))
  done
  [ -x "$HOME/.local/bin/opencode" ] && OC="$HOME/.local/bin/opencode"
  [ -x /usr/local/bin/opencode ] && OC=/usr/local/bin/opencode
  [ -z "$OC" ] && { printf '@JARVIS {"stage":"opencode","status":"failed","msg":"OpenCode install failed"}\n'; tail -3 /tmp/jarvis-oc-install.log; exit 1; }
fi
[ -x "$HOME/.local/bin/opencode" ] && ln -sf "$HOME/.local/bin/opencode" /usr/local/bin/opencode 2>/dev/null
for f in /root/.bashrc /root/.profile; do
  [ -f "$f" ] && ! grep -q '.local/bin' "$f" 2>/dev/null && printf 'export PATH="%s/.local/bin:%s"\n' "$HOME" "$PATH" >> "$f"
done
V="$(opencode --version 2>/dev/null || "$OC" --version 2>/dev/null)"
[ -n "$V" ] && printf '@JARVIS {"stage":"opencode","status":"done","msg":"OpenCode %s ready"}\n' "$V" \
  || printf '@JARVIS {"stage":"opencode","status":"failed","msg":"OpenCode present but broken"}\n'
EOF
) ; log "opencode: $out"; forward "$out"
  case "$out" in *@JARVIS*failed*) die opencode "OpenCode setup failed" "$(printf '%s' "$out" | tail -1)";; esac
}

# ---------- Stage: auth (detect only — never handles the key) ----------
auth_stage(){
  local out; out=$(ub_pipe <<'EOF'
A=""
for f in /root/.local/share/opencode/auth.json /root/.config/opencode/auth.json; do
  [ -s "$f" ] && A="$f" && break
done
if [ -n "$A" ]; then
  printf '@JARVIS {"stage":"auth","status":"done","msg":"Already authenticated"}\n'
else
  printf '@JARVIS {"stage":"auth","status":"auth_required","msg":"One-time API key needed"}\n'
fi
EOF
) ; forward "$out"
  case "$out" in *auth_required*)
    emit auth auth_required "Authentication required — tap OPEN AUTH once, sign in, then tap BOOTSTRAP again."
    exit 0 ;;
  esac
}

# ---------- Stage: server ----------
serve_stage(){
  emit server running "Starting OpenCode server…"
  local out; out=$(ub_pipe <<'EOF'
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
probe(){ curl -sm2 -o /dev/null "http://127.0.0.1:$1/" 2>/dev/null; }
EXIST=""
for p in 4096 4097 4098 4099; do probe $p && EXIST=$p && break; done
if [ -n "$EXIST" ]; then
  printf '@JARVIS {"stage":"server","status":"done","msg":"Healthy server already on :%s","port":"%s"}\n' "$EXIST" "$EXIST"
  exit 0
fi
PORT=""
for p in 4096 4097 4098 4099; do probe $p || { PORT=$p; break; }; done
[ -z "$PORT" ] && PORT=4096
nohup opencode serve --hostname 0.0.0.0 --port "$PORT" >/root/.jarvis-serve.log 2>&1 &
i=1; OK=""
while [ $i -le 30 ]; do probe $PORT && OK=1 && break; sleep 1; i=$((i+1)); done
if [ -z "$OK" ]; then
  kill %1 2>/dev/null
  nohup opencode serve >/root/.jarvis-serve.log 2>&1 &
  i=1
  while [ $i -le 20 ]; do probe 4096 && { PORT=4096; OK=1; break; }; sleep 1; i=$((i+1)); done
fi
if [ -n "$OK" ]; then
  printf '@JARVIS {"stage":"server","status":"done","msg":"Server live on :%s","port":"%s"}\n' "$PORT" "$PORT"
else
  printf '@JARVIS {"stage":"server","status":"failed","msg":"Server failed to start"}\n'
  tail -4 /root/.jarvis-serve.log 2>/dev/null
fi
EOF
) ; log "server: $out"; forward "$out"; local port; port=$(printf '%s' "$out" | grep -o '"port":"[0-9]*"' | grep -o '[0-9]*' | tail -1)
  [ -n "$port" ] && printf '%s' "$port" >"$STATE/server.port"
  case "$out" in *@JARVIS*failed*) die server "Server did not start" "$(printf '%s' "$out" | tail -1)";; esac
}

# ---------- Stage: model round-trip (best effort) ----------
model_stage(){
  emit model running "Verifying model access…"
  local out; out=$(ub_pipe <<'EOF'
export PATH="$HOME/.local/bin:/usr/local/bin:$PATH"
O="$(timeout 120 opencode run 'Reply with exactly: OK' 2>&1)"
case "$O" in
  *OK*) printf '@JARVIS {"stage":"model","status":"done","msg":"Model round-trip OK"}\n' ;;
  *) Oe="$(printf '%s' "$O" | tr -d '"' | tr '\n' ' ' | cut -c1-160)"
     printf '@JARVIS {"stage":"model","status":"warn","msg":"Model check inconclusive","detail":"%s"}\n' "$Oe" ;;
esac
EOF
) ; log "model: $out"; forward "$out"
}

# ---------- entry ----------
case "${1:-}" in
  auth)
    emit auth running "Opening interactive OpenCode login (finish here, then tap BOOTSTRAP)…"
    exec proot-distro login ubuntu -- /bin/bash -lc 'opencode auth login'
    ;;
  health)
    termux_base; ubuntu_install; ubuntu_login; ubuntu_packages; opencode_stage; auth_stage; serve_stage; model_stage
    emit jarvis done "Health sweep complete"
    exit 0 ;;
esac

emit boot running "Preparing Jarvis…"
termux_base
ubuntu_install
ubuntu_login
ubuntu_packages
opencode_stage
auth_stage
serve_stage
model_stage
emit jarvis done "Jarvis environment ready"
