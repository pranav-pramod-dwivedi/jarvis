#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
SCRIPT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)"
ROOT="$HOME/.jarvis-agent"
mkdir -p "$ROOT"
for f in agent.py mcp_server.py legacy_dispatch.py capability_matrix.json catalog.json self_test.py; do
  cp "$SCRIPT_DIR/$f" "$ROOT/$f"
done
chmod 700 "$ROOT"
chmod 700 "$ROOT"/*.py
if [ ! -f "$ROOT/token" ]; then
  python3 - <<'PY'
import secrets
from pathlib import Path
p=Path.home()/'.jarvis-agent'/'token'
p.write_text(secrets.token_hex(32)+'\n')
p.chmod(0o600)
PY
fi
printf '%s\n' 'Agent installed at' "$ROOT"
printf '%s\n' 'Install Termux:API separately if device APIs are needed.'
