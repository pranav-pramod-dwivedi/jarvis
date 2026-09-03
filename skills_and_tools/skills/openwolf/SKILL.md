---
name: openwolf
description: Cross-agent project memory, context deduplication, and token optimization engine. Intercepts repetitive reads and oversized command outputs that quietly fill context. Use for managing persistent project conventions, durable indexing, and tracking token expenditure across coding sessions.
---

# OpenWolf: Cross-Agent Memory & Context Compression

OpenWolf maintains a unified project memory across AI agents (Claude Code, Codex, AGY, Antigravity, OpenCode), prevents context bloat, and tracks actual token expenditure.

## Core Capabilities

1. **Context Interception & Compression**:
   - Condenses oversized command outputs (e.g. `grep`, `logcat`, test outputs) before entering context.
   - Retains full logs on disk while presenting concise, decisive snippets to the agent.
   - Prevents re-reading already-seen files.

2. **Durable Project Memory (`.wolf/`)**:
   - Stores project conventions, architecture maps, and past bug fixes in persistent `.wolf/` storage.
   - Re-injects active project state after context compaction so the agent never loses continuity.

3. **Symbol Indexing**:
   - Enables fast symbol location without dumping multi-thousand-line files into the prompt.

4. **CLI Integration**:
   - `openwolf status`: Check project memory health and tracked files.
   - `openwolf index`: Index project symbols and module boundaries.
   - `openwolf log`: View historical corrections and conventions.
