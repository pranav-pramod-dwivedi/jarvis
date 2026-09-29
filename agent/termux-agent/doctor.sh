#!/data/data/com.termux/files/usr/bin/bash
set -u
printf '%s\n' '=== JARVIS PHONE AGENT DOCTOR ==='
printf 'model: '; getprop ro.product.model 2>/dev/null || true
printf 'android: '; getprop ro.build.version.release 2>/dev/null || true
printf 'python: '; python --version 2>&1 || true
printf 'root: '; if command -v su >/dev/null 2>&1 && su -c id >/dev/null 2>&1; then echo available; else echo not-available; fi
printf '%s\n' '--- Termux:API ---'
count=0
for cmd in termux-battery-status termux-location termux-notification termux-toast termux-clipboard-get termux-clipboard-set termux-vibrate termux-tts-speak termux-torch termux-volume termux-brightness termux-wifi-connectioninfo termux-wifi-scaninfo termux-contact-list termux-telephony-call termux-telephony-deviceinfo termux-sms-send termux-sms-list termux-camera-photo termux-media-player termux-microphone-record termux-screenshot termux-media-scan termux-share termux-download termux-wallpaper termux-sensor termux-wake-lock; do
  if command -v "$cmd" >/dev/null 2>&1; then printf 'OK   %s\n' "$cmd"; count=$((count+1)); else printf 'MISS %s\n' "$cmd"; fi
done
printf 'Termux API commands available: %s\n' "$count"
printf '%s\n' '--- Agent ---'
[ -f "$HOME/.jarvis-agent/agent.py" ] && echo 'installed: yes' || echo 'installed: no'
[ -f "$HOME/.jarvis-agent/mcp_server.py" ] && echo 'MCP server: yes' || echo 'MCP server: no'
