#!/data/data/com.termux/files/usr/bin/bash
# Install jarvis-opencode into Termux $PREFIX/bin
set -e
echo "[*] jarvis-opencode installer"
if ! command -v curl >/dev/null 2>&1; then echo "Installing curl jq..."; pkg install -y curl jq 2>&1 | tail -5; fi
if ! command -v jq >/dev/null 2>&1; then pkg install -y jq 2>&1 | tail -5; fi
if ! command -v opencode >/dev/null 2>&1; then
  echo "[!] opencode not found."
  echo "    Try: npm i -g opencode  (requires nodejs)  OR  pkg install opencode"
  echo "    Then re-run this installer."
fi
PREFIX_BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"
SRC="$(cd "$(dirname "$0")" && pwd)/jarvis-opencode"
DST="$PREFIX_BIN/jarvis-opencode"
echo "[*] Copying to $DST"
mkdir -p "$PREFIX_BIN"
cp "$SRC" "$DST"
chmod +x "$DST"
# allow http cleartext is not needed in Termux (native curl), but ensure config dir
mkdir -p ~/.config/jarvis-opencode
echo "[✓] Installed. Try:"
echo "    jarvis-opencode status"
echo "    jarvis-opencode start 4096"
echo "    jarvis-opencode projects | jq ."
echo "    jarvis-opencode sessions | jq ."
echo ""
echo "If server was started by the Android app, it already listens on 127.0.0.1:4096."
echo "Just run:  jarvis-opencode sessions"
