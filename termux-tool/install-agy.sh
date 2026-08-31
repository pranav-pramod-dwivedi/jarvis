#!/data/data/com.termux/files/usr/bin/bash
# install-agy.sh — Install and configure jarvis-agy daemon in Termux
set -e

echo "[*] Installing Antigravity (AGY) tools for Jarvis..."

PREFIX_BIN="${PREFIX:-/data/data/com.termux/files/usr}/bin"
mkdir -p "$PREFIX_BIN"

# Check python
if ! command -v python3 >/dev/null 2>&1 && ! command -v python >/dev/null 2>&1; then
  echo "[*] Installing python..."
  pkg install -y python
fi

# Copy scripts
DIR="$(cd "$(dirname "$0")" && pwd)"
for target in "$PREFIX_BIN" "/usr/local/bin" "$HOME/.local/bin"; do
  if [[ -d "$target" ]] || mkdir -p "$target" 2>/dev/null; then
    for script in jarvis-agy agy-daemon agy_server.py systemctl; do
      if [[ -f "$DIR/$script" ]]; then
        cp "$DIR/$script" "$target/$script" 2>/dev/null || true
        chmod +x "$target/$script" 2>/dev/null || true
      fi
    done
    cat > "$target/agy-serve" <<'EOF'
#!/bin/sh
exec agy-daemon start "$@"
EOF
    chmod +x "$target/agy-serve" 2>/dev/null || true
  fi
done

echo "[✓] Successfully installed agy-daemon, jarvis-agy, and agy-serve."
echo "    Commands available:"
echo "      agy-daemon start     # Start Antigravity background services (persistent)"
echo "      agy-daemon status    # Check running status and health"
echo "      agy-daemon stop      # Stop all background services"
echo "      agy-daemon log       # View live logs"
echo "      agy-serve            # Shortcut to start daemon"
