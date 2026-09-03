---
name: jarvisbrowser
description: Autonomous on-demand dynamic UI & web-app generator for JARVIS. Use whenever a user request would benefit from an interactive visual interface, animation, physics simulation, comparison table, timeline, or dashboard. Generates self-contained, offline-first, professional-grade (anti-AI-slop, Awwwards-winner craft) HTML/CSS/JS mini-apps and launches them instantly in JarvisBrowser.
---

# JarvisBrowser: Dynamic On-Demand UI & Web-App System

JarvisBrowser is JARVIS's internal, dynamic visual UI surface.
**Only AGY is authorized to design, code, and launch JarvisBrowser mini-apps.**

When a user's request is better answered through an interactive visual interface than plain text or voice alone (e.g. physics simulations, dynamic comparisons, workout dashboards, trip route timelines, circuit simulators, math graphs, or custom calculators), you execute a **fast coding speedrun** to build and launch the interface in seconds.

---

## 1. Zero AI Slop & Awwwards-Winner Engineering Mandate

Every app generated for JarvisBrowser **strictly outlaws generic AI templates, default shadcn/Tailwind cards, and repetitive purple/indigo gradients**. You must synthesize the core tenets of the installed skills:

1. **Hallmark Structural Variety (`hallmark`)**:
   - Never generate cookie-cutter hero → 3-card-grid → footer layouts.
   - Varied alignment, asymmetrical layouts, editorial negative space.
   - Strict token locking: All colors and fonts reference defined variables; no mid-render hex improvisation.

2. **Unslop Universal Anti-Tells (`unslop-ui` / `no-ai-slop`)**:
   - **No default fonts** (Inter, Geist alone). Pair bold characterful display headings with ultra-clean monospace or high-readability sans body.
   - **No generic purple/indigo gradients** (`#8B5CF6`, violet-600) or cream backgrounds.
   - **No pill badge floating above headings.** Heading leads immediately.
   - **High-contrast text**: `#EAF4FC` on `#070B0E` void darks. Zero low-contrast gray-400/500 body text.
   - **No em-dashes** or marketing fluff ("unlock", "supercharge", "seamless"). Real, decisive metrics and labels only.

3. **Awwwards-Winner Design Engine (`awwwards-winner`)**:
   - **Unexpected Typography Scaling**: Large expressive headers paired with ultra-clean technical monospace copy.
   - **Dynamic Motion Cohesion**: Realistic cubic-bezier easing (`cubic-bezier(0.16, 1, 0.3, 1)` for ultra-smooth out-quint).
   - **Organic Canvas / WebGL Micro-Interactions**: Interactive wave simulations, particle magnetic deformation, real physical state logic.
   - **Buttery 60fps Rendering**: Hardware-accelerated CSS transforms and opacity only; never animate width/height/top.

4. **Impeccable Craft Floor (`impeccable`)**:
   - Inspect desktop and mobile simultaneously. Flawless 320/375/414px mobile rendering.
   - Zero horizontal overflow (`overflow-x: hidden; width: 100%;`).
   - Touch targets $\ge$ 48px with active state feedback (`transform: scale(0.97)`).

---

## 2. Operating Rules & Speedrun Discipline

1. **Check Reusability First**:
   Before generating, check if a suitable app already exists in `/storage/emulated/0/JARVIS/browser/apps/<app-id>/index.html`.
   If it exists, launch it immediately with `am start` and speak an updated acknowledgement rather than rewriting from scratch.

2. **Zero-Latency / Offline-First**:
   - **NO external npm packages or build tools** (`npm install`, `vite`, `webpack`).
   - **NO external CDN scripts/styles** (`unpkg`, `cdnjs`, `cdn.tailwindcss.com`). All external CDNs add network latency and fail offline.
   - Everything must be **100% self-contained** in a single `index.html` (with inline `<style>` and `<script>`).
   - Standard HTML5 `<canvas>`, vanilla JS, and modern CSS3 transforms are the gold standard for high performance and 60fps responsiveness.

3. **Storage & Launch Contract**:
   - App Directory: `/storage/emulated/0/JARVIS/browser/apps/<app-id>/` (or `/storage/emulated/0/JARVIS/browser/temp/<app-id>/` if temporary).
   - Write:
     1. `index.html`: The complete self-contained application.
     2. `manifest.json`: Metadata defining `id`, `title`, `description`, `icon`, `created_at`, `tags`, `is_temporary`.
   - Launch immediately via Android CLI:
     ```bash
     am start -n com.pr4nav.jarvis/.browser.JarvisBrowserActivity \
       --es extra_app_id "<app-id>" \
       --es extra_explanation_speech "<Concise voice explanation JARVIS will speak while the UI is open>"
     ```

---

## 3. The Visual Palette (Obsidian Cyberpunk & Void Luxury)
```css
:root {
    --jv-bg: #070B0E;          /* Deep void background */
    --jv-surface: #0E161C;     /* Elevated card surface */
    --jv-surface-hi: #15222B;  /* Active / hovered surface */
    --jv-border: rgba(79, 209, 197, 0.2); /* Crisp subtle border */
    --jv-accent: #4FD1C5;      /* Neon cyan / teal glow */
    --jv-accent-glow: rgba(79, 209, 197, 0.35);
    --jv-accent-dim: #2A8F87;
    --jv-text: #EAF4FC;        /* High-contrast bright text */
    --jv-text-dim: #708A9E;    /* Secondary label text */
    --jv-danger: #FF4757;
    --font: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    --font-mono: 'JetBrains Mono', monospace;
    --ease-out-quint: cubic-bezier(0.16, 1, 0.3, 1);
}
```

---

## 4. JarvisBridge JavaScript Integration

Every JarvisBrowser app has access to `window.jarvis` injected automatically:

```javascript
// 1. Speak an explanation through JARVIS's native voice engine
window.jarvis.speak("Notice how increasing the magnet's speed creates a stronger current spike.");

// 2. Show a native Android toast
window.jarvis.toast("Simulation reset");

// 3. Save current temporary app to the user's permanent library
window.jarvis.saveApp("Faraday's Law Simulation", "Interactive electromagnetic induction visualizer");

// 4. Close the visualizer
window.jarvis.close();

// 5. Call audited JARVIS tools (device_info, timer, battery, etc.)
window.jarvis.callTool('battery_status', {}).then(res => {
    console.log('Battery level:', res.level);
});
```

---

## 5. End-to-End Speedrun Execution Template

When instructed to create a UI (e.g. "Faraday's law with animations"):

1. Create target folder:
   ```bash
   mkdir -p /storage/emulated/0/JARVIS/browser/apps/faradays-law
   ```

2. Write `manifest.json`:
   ```bash
   cat << 'EOF' > /storage/emulated/0/JARVIS/browser/apps/faradays-law/manifest.json
   {
     "id": "faradays-law",
     "title": "Faraday's Law Simulation",
     "description": "Interactive electromagnetic induction with live coil flux",
     "icon": "⚡",
     "tags": ["physics", "induction"],
     "is_temporary": false
   }
   EOF
   ```

3. Write complete `index.html` with canvas, sliders, induction math, and voice narration trigger.

4. Launch immediately:
   ```bash
   am start -n com.pr4nav.jarvis/.browser.JarvisBrowserActivity \
     --es extra_app_id "faradays-law" \
     --es extra_explanation_speech "Here is an interactive simulation of Faraday's law. Drag the bar magnet through the coil to see electromagnetic induction produce a live current."
   ```

5. Confirm launch to the user with a concise, intelligent response.
