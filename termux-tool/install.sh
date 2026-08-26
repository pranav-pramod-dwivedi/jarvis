#!/data/data/com.termux/files/usr/bin/bash
# jarvis-opencode installer — works both ways:
#   locally:  bash termux-tool/install.sh
#   via curl: curl -fsSL https://raw.githubusercontent.com/pranav-pramod-dwivedi/jarvis/main/termux-tool/install.sh | bash
set -e
echo "[*] jarvis-opencode installer"
if ! command -v curl >/dev/null 2>&1; then echo "[*] Installing curl..."; pkg install -y curl 2>&1 | tail -5; fi
if ! command -v jq >/dev/null 2>&1; then echo "[*] Installing jq..."; pkg install -y jq 2>&1 | tail -5; fi
if ! command -v opencode >/dev/null 2>&1; then
  echo "[!] opencode not found (optional, but needed to run). Install via:"
  echo "    npm i -g opencode   # needs nodejs (pkg install nodejs)"
  echo "    — or wait, the Android app can start the server for you."
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
