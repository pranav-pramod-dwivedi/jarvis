---
name: jarvisbrowser
description: Autonomous on-demand dynamic UI & web-app generator for JARVIS. Use whenever a user request would benefit from an interactive visual interface, animation, physics simulation, comparison table, timeline, or dashboard. Generates self-contained, offline-first, professional-grade (anti-AI-slop) HTML/CSS/JS mini-apps and launches them instantly in JarvisBrowser.
---

# JarvisBrowser: Dynamic On-Demand UI & Web-App System

JarvisBrowser is JARVIS's internal, dynamic visual UI surface.
**Only AGY is authorized to design, code, and launch JarvisBrowser mini-apps.**

When a user's request is better answered through an interactive visual interface than plain text or voice alone (e.g. physics simulations, dynamic comparisons, workout dashboards, trip route timelines, circuit simulators, math graphs, or custom calculators), you execute a **fast coding speedrun** to build and launch the interface in seconds.

---

## 1. Operating Rules & Speedrun Discipline

1. **Check Reusability First**:
   Before generating, check if a suitable app already exists in `/storage/emulated/0/JARVIS/browser/apps/<app-id>/index.html`.
   If it exists, launch it immediately with `am start` and speak an updated acknowledgement rather than rewriting from scratch.

2. **Zero-Latency / Offline-First**:
   - **NO external npm packages or build tools** (`npm install`, `vite`, `webpack`).
   - **NO external CDN scripts/styles** (`unpkg`, `cdnjs`, `cdn.tailwindcss.com`). All network requests to external CDNs add latency and fail offline.
   - Everything must be **100% self-contained** in a single `index.html` (with inline `<style>` and `<script>`, or adjacent `style.css`/`app.js`).
   - Standard HTML5 `<canvas>`, vanilla JS, and modern CSS3 animations are the gold standard for high performance and 60fps responsiveness.

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

## 2. Anti-AI-Slop & UI/UX Pro Max Design System (Unslop Standard)

Generic "AI slop" (standard white Bootstrap cards, blurry purple gradients, centered text boxes, non-interactive static diagrams, and broken mobile viewports) is strictly prohibited. Every JarvisBrowser app must look like a high-end, bespoke sci-fi/luxury mobile interface.

### The Visual Palette (Obsidian Cyberpunk)
```css
:root {
    --jv-bg: #0B1116;          /* Deep void background */
    --jv-surface: #101820;     /* Elevated card surface */
    --jv-surface-hi: #16232E;  /* Active / hovered surface */
    --jv-border: rgba(79, 209, 197, 0.18); /* Crisp subtle border */
    --jv-accent: #4FD1C5;      /* Neon cyan / teal glow */
    --jv-accent-glow: rgba(79, 209, 197, 0.25);
    --jv-accent-dim: #2A8F87;
    --jv-text: #E6F2FF;        /* High-contrast bright text */
    --jv-text-dim: #6E8CA0;    /* Secondary label text */
    --jv-danger: #FF5252;
    --font: system-ui, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
    --font-mono: 'JetBrains Mono', 'Fira Code', monospace;
}
```

### Mobile Ergonomics & Touch Controls
- Set viewport: `<meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">`.
- Use `-webkit-tap-highlight-color: transparent;` and `touch-action: manipulation;`.
- On interactive canvas elements, use `touch-action: none;` and attach both Mouse (`mousedown`, `mousemove`, `mouseup`) and Touch (`touchstart`, `touchmove`, `touchend`) event listeners.
- Touch targets (buttons, toggles, sliders) must be at least **48px** tall with active state feedback (`transform: scale(0.97)`).
- Prevent horizontal scrollbars (`overflow-x: hidden; width: 100%;`).

### Motion, Real Physics & Micro-Interactions
- **Do not output static placeholders.** Implement the actual physics or algorithmic calculation (e.g. magnetic flux $\Phi = B \cdot A \cos(\theta)$, electromagnetic induction $V = -N \frac{d\Phi}{dt}$, gravitational orbits, interactive Fourier waves, real-time circuit logic).
- Use `requestAnimationFrame` for 60fps canvas loops.
- Provide interactive controls: scrubbable sliders, play/pause toggles, step buttons, parameter inputs, and live readout gauges.

---

## 3. JarvisBridge JavaScript Integration

Every JarvisBrowser app has access to `window.jarvis` (and `window.JarvisNative`) injected automatically:

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

## 4. Standard Manifest Template (`manifest.json`)

```json
{
  "id": "faradays-law",
  "title": "Faraday's Law Simulation",
  "description": "Interactive electromagnetic induction visualizer with magnetic flux and coil physics",
  "icon": "⚡",
  "version": "1.0.0",
  "created_at": 1725320000000,
  "updated_at": 1725320000000,
  "tags": ["physics", "simulation", "education", "magnetism"],
  "is_temporary": false
}
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
