# jarvis-opencode — Termux tool (one OpenCode brain)

Same API the **Jarvis Android app** uses (`opencode serve` or `opencode web` — both expose `/global/health`, `/session`, `/project`, `/permission`).

No Chrome, no proot required. The Android native GUI, the Jarvis agent (`opencode.*` tools), and this Termux CLI all hit **one server**.

### Install in Termux — 3 ways

**1) npx (no install)**
```bash
pkg install nodejs curl jq -y
npx jarvis-opencode status
npx jarvis-opencode sessions | jq .
```

**2) npm global**
```bash
npm i -g jarvis-opencode
jarvis-opencode status
```

**3) bash installer (no npm)**
```bash
curl -fsSL https://raw.githubusercontent.com/pranav-pramod-dwivedi/jarvis/main/termux-tool/jarvis-opencode -o /tmp/jarvis-opencode
bash /tmp/jarvis-opencode help
# or clone repo:
git clone https://github.com/pranav-pramod-dwivedi/jarvis.git
bash jarvis/termux-tool/install.sh
```

### Prereqs
```bash
pkg install curl jq nodejs -y
# opencode binary (pick one):
npm i -g opencode
# or: pkg install opencode
```

### Use
```bash
jarvis-opencode status
jarvis-opencode start 4096
jarvis-opencode projects | jq .
jarvis-opencode sessions | jq .
jarvis-opencode create /data/data/com.termux/files/home/myproj "fix bug"
jarvis-opencode send ses_xxx "explain this file" build
jarvis-opencode send ses_xxx "fix it" "" "anthropic/claude-sonnet-4"
jarvis-opencode abort ses_xxx
jarvis-opencode rename ses_xxx "new title"
jarvis-opencode delete ses_xxx
jarvis-opencode perm req_xxx once
jarvis-opencode events | jq .
jarvis-opencode log
```

`serve` vs `web`: both same API. `web` is easier to verify — it serves HTML at `http://127.0.0.1:4096/`.

### Config
Env or `~/.config/jarvis-opencode/config.json`:
```json
{"baseUrl":"http://127.0.0.1:4096","username":"jarvis","password":"..."}
```
Set via `OPENCODE_SERVER_USERNAME` / `OPENCODE_SERVER_PASSWORD` or `jarvis-opencode connect http://127.0.0.1:4096`.

### Also available as Android tools
The same bridge is registered in Jarvis as `opencode.*`:
`opencode.start`, `opencode.status`, `opencode.projects`, `opencode.sessions`, `opencode.session.create/send/abort/fork/rename/delete`, `opencode.permission.respond`, `opencode.question.respond`, `opencode.events`.

### License
MIT — Pranav Pramod Dwivedi
