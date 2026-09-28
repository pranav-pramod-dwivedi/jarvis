#!/data/data/com.termux/files/usr/bin/bash
set -euo pipefail
ROOT="$HOME/.jarvis-agent"
mkdir -p "$ROOT"
cp "$(dirname "$0")/agent.py" "$ROOT/agent.py"
cp "$(dirname "$0")/mcp_server.py" "$ROOT/mcp_server.py"
cp "$(dirname "$0")/catalog.json" "$ROOT/catalog.json"
chmod 700 "$ROOT" "$ROOT/agent.py"
if [ ! -f "$ROOT/token" ]; then
  python - <<'PY'
import secrets
from pathlib import Path
p=Path.home()/'.jarvis-agent'/'token'
p.write_text(secrets.token_hex(32)+'\n')
p.chmod(0o600)
PY
fi
printf '%s\n' 'Agent installed at' "$ROOT"
printf '%s\n' 'Install Termux:API separately if device APIs are needed.'
