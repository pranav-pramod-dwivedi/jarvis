#!/data/data/com.termux/files/usr/bin/bash
# jarvis-opencode installer — works both ways:
#   locally:  bash termux-tool/install.sh
#   via curl: curl -fsSL https://raw.githubusercontent.com/pranav-pramod-dwivedi/jarvis/main/termux-tool/install.sh | bash
set -e
echo "[*] jarvis-opencode installer"
if ! command -v curl >/dev/null 2>&1; then echo "[*] Installing curl..."; pkg install -y curl 2>&1 | tail -5; fi
if ! command -v jq >/dev/null 2>&1; then echo "[*] Installing jq..."; pkg install -y jq 2>&1 | tail -5; fi
if ! command -v opencode >/dev/null 2>&1; then
  echo "[*] opencode not found — installing..."
  if ! command -v node >/dev/null 2>&1; then pkg install -y nodejs 2>&1 | tail -5; fi
  # Try npm first (works on Termux), fallback to official install script
  if npm i -g opencode 2>&1 | tail -10; then echo "[✓] opencode installed via npm"; else
    echo "[*] npm install failed, trying opencode.ai/install ..."
    curl -fsSL https://opencode.ai/install | bash 2>&1 | tail -10 || echo "[!] manual: npm i -g opencode"
  fi
  # proot is NOT needed — native Termux opencode is used
  hash -r 2>/dev/null || true
fi
if ! command -v opencode >/dev/null 2>&1; then
  echo "[!] opencode still not found. After install, restart Termux and re-run this."
fi
PREFIX_BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"
DST="$PREFIX_BIN/jarvis-opencode"
mkdir -p "$PREFIX_BIN" ~/.config/jarvis-opencode 2>/dev/null || true

# If we were piped via curl, $0 has no dir — fetch from GitHub raw
if [[ -f "$(dirname "$0")/jarvis-opencode" ]]; then
  SRC="$(cd "$(dirname "$0")" && pwd)/jarvis-opencode"
  echo "[*] Installing from local $SRC → $DST"
  cp "$SRC" "$DST"
else
  URL="https://raw.githubusercontent.com/pranav-pramod-dwivedi/jarvis/main/termux-tool/jarvis-opencode"
  echo "[*] Fetching $URL → $DST"
  curl -fsSL "$URL" -o "$DST"
fi
chmod +x "$DST"
echo "[✓] Installed to $DST"
echo "    Try:"
echo "      jarvis-opencode status"
echo "      jarvis-opencode start 4096"
echo "      jarvis-opencode projects | jq ."
echo "      curl -fsSL https://raw.githubusercontent.com/pranav-pramod-dwivedi/jarvis/main/termux-tool/jarvis-opencode | head"
