# JARVIS Agent Skills Matrix

This directory contains the operational skills, prompts, workflows, and behavioral rules powering the JARVIS agentic intelligence.

## Installed Skills

| Skill | Role | Key Directives | Trigger Phrases |
| :--- | :--- | :--- | :--- |
| **`use-all-skills`** | Master Orchestrator | Enforces sequential execution of all installed skills. Prevents generic fallback. | `/use-all-skills`, `use all skills` |
| **`hallmark`** | Structural Anti-Slop Design | Architectural layouts, 21 named color themes, anti-slop rules, pre-emit critique. | `/hallmark`, `redesign`, `design system` |
| **`no-ai-slop`** | Anti-AI-Tells Design | Eliminates standard AI tells: Inter/Geist fonts, purple gradients, centered card grids. | `anti-slop`, `fix slop`, `no ai slop` |
| **`unslop-ui`** | Clean UI Polishing | Removes generic SaaS design clutter, enforces asymmetric whitespace and contrast. | `unslop`, `clean ui` |
| **`ui-ux-pro-max`** | Full-Stack UI/UX Engineering | Design tokens, WCAG AA compliance, responsive breakpoints (320/375/414px). | `ui/ux`, `responsive design` |
| **`awwwards-winner`** | Award-Winning Motion & WebGL | Editorial typography scaling, WebGL/canvas effects, `cubic-bezier(0.16, 1, 0.3, 1)` easing. | `awwwards`, `editorial design`, `motion` |
| **`impeccable`** | Frontend Review & Hardening | Craft floor enforcement, component audit, micro-interaction tuning. | `impeccable`, `audit ui`, `polish` |
| **`caveman`** | Ultra-Compressed Token Mode | Extreme output token savings, ultra-terse code answers without losing precision. | `/caveman`, `caveman mode`, `be brief` |
| **`token-efficient`** | Concise Output Discipline | Eliminates conversational filler, greetings, and repetitive architectural summaries. | `token efficient`, `less tokens` |
| **`openwolf`** | Persistent Agent Memory | Cross-session memory, context deduplication, token expenditure optimization. | `openwolf`, `project memory` |
| **`jarvisbrowser`** | Dynamic Web-App Generator | Generates zero-latency, self-contained offline HTML5/CSS/JS applications for JarvisBrowser. | `generate app`, `jarvisbrowser` |
| **`screencapture`** | Zero-Latency Text OCR Capture | High-speed virtual screen reading with element labels and coordinates. | `read screen`, `screencapture` |
| **`unlazy`** | Autonomous Completion Discipline | Depth Tree task decomposition, acceptance gates, exhaustive verification passes. | `/unlazy`, `gates`, `do not stop` |
| **`find-skills`** | Skill Discovery Engine | Automated search and integration of new agent capabilities. | `find skills`, `install skill` |

---

## Directory Structure per Skill

Each skill is a self-contained module containing:
- `SKILL.md`: Core system instructions, operational constraints, and prompt directives.
- `scripts/`: Local helper utilities and validators (where applicable).
- `references/`: Detailed typography scales, color palettes, and component cheat sheets.
