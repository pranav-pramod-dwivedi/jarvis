# AI Design Tells - master catalog

The definitive list of patterns that make a website / web app / mobile UI read as
AI-generated or generic-template. This is the product's core asset: the
`no-ai-slop` skill is generated from it.

**Root cause across every category: uniformity + unconsidered defaults.** One card
recipe, one button pair, one font, one radius, one shadow, one centered-section
template, one accent at one lightness, applied to every piece of content. The
highest-leverage fixes: (1) don't reuse a single template for differing content,
(2) don't ship framework defaults (Inter/Geist, indigo-500, shadcn card), (3) don't
add decorative slots that carry no information (pills, eyebrows, blobs, count-ups,
one-side stripes), (4) signal hierarchy with size/weight/space, not `scale-105` +
soft-shadow lifts, (5) write real copy and use real imagery.

Two layers come out of this list:
1. **Universal anti-tells** - avoid always (below).
2. **Per-style positive direction** - the "build like this" for each selected look (separate doc).

Compiled from a 6-agent research pass (current 2025-2026 designer/dev discourse) + live review. Sources at the end.

---

## 1. Typography & fonts
- **Inter as the default everything** (the literal `font-sans` Next/shadcn stack). The "Helvetica of the LLM era" - shipped when no font was chosen. *Fix:* pick a face with a point of view; pair with a contrasting body face.
- **Geist / Geist Mono outside a Vercel project** (often still the `--font-geist-sans` var). Signals scaffolded-never-rebranded. *Fix:* reserve Geist for Vercel-adjacent tooling.
- **The trending trio**: Space Grotesk headings + an Instrument Serif *italic accent word* + Inter/Geist body, appearing together. Reads as a unit, not a choice. *Fix:* keep at most one; pair with something off-trend.
- **A lone serif-italic word** dropped into one hero phrase ("build *beautiful* products"). Canned "add personality" move. *Fix:* commit serif/sans as a system or not at all.
- **Display serif for "premium"** (Playfair, Instrument, DM Serif Display) at large default tracking. Everyone's shorthand for luxury. *Fix:* a transitional/old-style face (Tiempos, GT Sectra, Source Serif) with tuned optical sizing.
- **`font-semibold` (600) as the heaviest weight on the page** - no true 700-900 anywhere, so nothing tops the hierarchy. *Fix:* real weight contrast (body 400, headings 700-800). Inverse tell: everything `font-medium` (flat mush).
- **`text-6xl/7xl` + `tracking-tight`/`tracking-tighter` reflex on every heading**, even faces that don't need negative tracking. *Fix:* set tracking optically per face/size; large display often wants only -0.01 to -0.02em.
- **Negative tracking copied down to body/labels** (14-16px). Hurts legibility, exposes a blanket class. *Fix:* body at 0.
- **Gradient-clipped heading text** (`bg-clip-text text-transparent`, usually purple-blue). Decorative, tanks contrast. *Fix:* solid high-contrast color.
- **`text-wrap: balance` on every heading** - pyramid shapes, stranded last lines. *Fix:* use it only on 2-3 line headings; `text-pretty` for body; control wrap with measure.
- **Forced `<br>` in headings** (break wrong at other breakpoints). *Fix:* constrain with `max-w-[Nch]`, let it wrap; `whitespace-nowrap` only for true keep-together pairs.
- **Overlong headline wrapping 4+ lines / one-word orphan** (models pad with "all-in-one platform to..."). *Fix:* cut to one punchy line of ~5-9 words; set a measure that prevents orphans.
- **Artificially narrow left-aligned subtitle/paragraph** (capped at `max-w-md/lg`, wrapping into a thin floating column). *Fix:* let left-aligned body run to the container's content width; reserve a max-width measure for *centered* text only.
- **Body locked at 16px `leading-7` everywhere** - captions, body, lead all one size/leading. *Fix:* a real scale (lead 18-20, UI 14, caption 13) with role-based line-height.
- **Untouched mathematical modular scale** (a clean 1.250 with nothing custom). Feels mechanical. *Fix:* hand-adjust steps for your content.
- **Uppercase eyebrow over every section** (`text-xs uppercase tracking-widest text-muted` "WHY US", "FEATURES"). *Fix:* none by default; let the heading lead.
- **Decorative rule/hairline + spaced-caps kicker** above the heading - the subtle cousin of the pill; do not swap one for the other. *Fix:* no eyebrow.
- **Monospace for non-code chrome** (eyebrows, nav, labels) to borrow dev cred. *Fix:* mono for code/tabular numerals only.
- **Faux italics / no optical sizing** (slanted roman on a face with no italic; ignoring variable `opsz`). *Fix:* real italics; `font-optical-sizing: auto`.
- **Non-tabular numerals in stats/pricing/tables** (figures wobble and misalign). *Fix:* `font-variant-numeric: tabular-nums`.
- **Heading and body in the same family at the same width** (no textural contrast). *Fix:* contrast by category or width.
- **Buttons/nav all `text-sm font-medium`, identical** - every CTA one undifferentiated voice. *Fix:* give the primary CTA real presence.
- **Justified body (`text-justify`) on web columns** (rivers). *Fix:* ragged-right left-align.
- **All-caps multi-word phrases at default tracking** (cramped). *Fix:* add 0.05-0.1em, short strings only.
- **`text-shadow`/`drop-shadow` glow on flat headings.** *Fix:* contrast + weight; a solid scrim if over a busy bg.

## 2. Color & contrast
- **`bg-indigo-600`/`bg-violet-600` (#4f46e5 / #7c3aed) as the primary**, often the only saturated color - the literal Tailwind/shadcn default. *Fix:* a specific brand hex from outside the 500-600 indigo/violet band, defined as a token.
- **"VibeCode purple" #8B5CF6 / #A78BFA** in icons, glows, links, charts at once. The single most over-represented AI hue. *Fix:* ban the 250-280 hue range from accents for one project.
- **Forced dark-only mode** (the #1 single tell, ~34% of AI pages) - "dark = premium" and it hides layout sins. *Fix:* default light unless the product lives at night; make dark a real second theme.
- **Pure black `#000` page background** (vibrates against white text, crushes elevation). *Fix:* off-black with a slight temperature (#0E0E10 / zinc-950 / warm #14110F) + a real elevation ramp.
- **The shadcn default dark stack** (zinc-950 page, zinc-900 cards, zinc-800 borders, violet accent) shipped unmodified. *Fix:* shift the whole neutral ramp to a deliberate temperature; replace the accent.
- **Grey/low-contrast body text** - `text-gray-500/400` (#6b7280/#9ca3af) on white, or `slate-400` on dark. AI tell AND a WCAG fail. *Fix:* near-black body on light (gray-700+ / #1a1a1a), slate-200+ on dark; verify >=4.5:1. Greys for genuinely secondary metadata only.
- **"Timid" evenly-distributed palette** (4-5 colors at similar saturation/value, no dominant, no sharp accent). *Fix:* one dominant neutral + a single loud accent (~90/10).
- **Everything in a narrow mid-value band** (no true black, no true white). *Fix:* anchor with at least one near-black and one near-white.
- **Single accent at one fixed lightness** for links/buttons/icons/rings/charts (no 50-900 ramp). *Fix:* build the accent as a full ramp.
- **Raw status colors** (`green-500/red-500/yellow-500` untouched, appearing together). *Fix:* tune semantics to your palette's temperature.
- **Pastel-100 icon tiles + 600 icon** (`bg-indigo-100` holding an `indigo-600` glyph, 3-up). *Fix:* drop the colored chips.
- **"Safe B2B" sky/blue trio** (blue-600 / sky-500 / slate-50). The fallback-of-the-default. *Fix:* a specific off-blue + a real secondary.
- **Default `ring-blue-500`/`ring-indigo-500` focus** regardless of brand. *Fix:* focus/selection = your accent token.
- **Neon-on-dark colored glow** (`shadow-[0_0_40px_rgba(139,92,246,0.5)]`) on buttons/cards. *Fix:* restrained neutral elevation; at most one deliberate glow.
- **Cyan/teal-on-near-black "terminal" duo** (#22D3EE on #0A0A0A) for "dev tool". *Fix:* a non-cyan signal color or warm the scheme.
- **The "calm" cream + sage + charcoal combo** (#faf8f4 + ~#A3B18A + charcoal) - now the *anti*-purple default, its own template. *Fix:* keep a warm base but commit to a less-defaulted secondary (terracotta, ochre, oxblood, ink-blue) and vary value contrast.

## 3. Backgrounds & surfaces
- **Cream / warm off-white `#faf8f4`-`#f5f1ea`** as the "intentional minimalist" background. Now a tell of its own. *Fix:* clean white or a deliberate, characterful surface.
- **Purple-to-blue hero gradient** `from-purple-600 via-violet-500 to-blue-500` (+ indigo/fuchsia cousins), usually `to-r`/`to-br`. The canonical AI-startup hero. *Fix:* tonal (two shades of one hue), or a real photo; avoid the cross-hue sweep.
- **Amber-to-pink "warm gradient"** (`from-amber-400 to-pink-500`) - the new fallback once people tired of purple. Same lazy two-stop sweep. *Fix:* earn warmth with real material/photography.
- **Conic/radial "aurora" mesh blobs** (purple/pink/blue at low opacity) behind the hero. Stand-in for "couldn't art-direct a background." *Fix:* flat brand color, a photo, texture, or structured negative space.
- **Blurred gradient "orbs"** (`blur-3xl opacity-30` radial shapes). *Fix:* intentional art direction or confident whitespace.
- **Spotlight radial** (soft white/purple glow from top-center of a dark hero). *Fix:* real content/imagery.
- **Faint `white/5` dotted or line grid** (`bg-[url(/grid.svg)]`) fading out on a dark hero. The purple gradient's companion. *Fix:* brand-meaningful texture or clean space.
- **Glassmorphism** (`bg-white/10 backdrop-blur-md border-white/20` over a gradient). Now a uniform; text-on-blur usually fails contrast. *Fix:* opaque surfaces + real elevation; restrict glass to one chrome element over a calm backdrop.
- **Full-bleed `from-black/60 to-transparent` scrim** over every hero/photo. *Fix:* art-direct contrast per image; localized scrim only where text sits.
- **Uniform soft shadows** - every card `shadow-md/lg` at ~0.1 opacity. *Fix:* a 3-4 step elevation ramp with intentional y-offset; some surfaces use borders/contrast, no shadow.
- **Dark dividers all `border-white/10`** (structure barely visible). *Fix:* surface-elevation contrast for separation; vary border opacity by hierarchy.
- **Gradient borders** (cyan-to-purple `border-image` trick) around cards/CTAs. *Fix:* a solid 1px brand border.
- **SVG "wave"/repeated divider between every band.** *Fix:* let color/content/spacing define section changes.

## 4. Layout & page structure
- **The default hero**: centered single column (`max-w-3xl mx-auto text-center`) - eyebrow, H1, one-line subhead, two stacked buttons, screenshot below. *Fix:* asymmetric hero on a 12-col grid; let one element bleed off-grid.
- **The codified section order**: hero -> logo bar -> 3 feature cards -> "how it works" 3 steps -> stat row -> testimonials -> pricing -> FAQ accordion -> CTA band -> fat footer. The sequence itself is the fingerprint. *Fix:* reorder around your actual argument; drop/merge sections.
- **Three equal columns for everything** (`grid-cols-3`, forced equal height) regardless of content count/importance. *Fix:* vary column count and weight (7/5, 8/4); let item count drive layout.
- **"Feature soup"** - N identical icon cards, same size, repeated. *Fix:* one large showcase row with a real screenshot; demote the rest.
- **One global container width** (`max-w-7xl` on every section, dead side-margins on wide screens). *Fix:* a width system - full-bleed media, wide grids (~1200), narrow prose (~640-720).
- **Sticky frosted nav** (logo-left / centered links / right CTA / `backdrop-blur bg-white/70`). The starter header verbatim. *Fix:* solid or transparent-over-hero; asymmetric link placement; no reflexive blur.
- **Fat 4-column footer** (Product/Company/Resources/Legal + logo+tagline + newsletter + social row + "(c) 2026 ... All rights reserved"), built whether the site has 5 pages or 500. *Fix:* size the footer to the site; drop empty columns and reflexive newsletter/social.
- **Final CTA band mirrors the hero** (same cadence, same two buttons, "No credit card required"). *Fix:* a distinct closing moment, one decisive action.
- **Uniform vertical rhythm** - same `py-20` everywhere, so inter-section spacing == intra-section spacing. *Fix:* a spacing scale with intent; large breaks between movements.
- **Stat band** of 3-4 equal columns (big number + tiny label), evenly distributed. *Fix:* few specific stats integrated into a relevant section; break the even distribution.
- **Testimonials as a tidy 3-up of equal cards.** *Fix:* one strong featured quote at scale, or a varied wall.
- **Cards nested in cards / sections in bordered panels** (over-structuring). *Fix:* flatten - whitespace, dividers, type scale to group.
- **Symmetric 50/50 zigzag** strictly alternating image side down the page (NN/g: slows scanning). *Fix:* vary the ratio, don't auto-flip every row.
- **Siloed bands, zero overlap** - clean top/bottom edge on every section. *Fix:* let elements cross boundaries for depth.
- **Centered hero mockup** in a `rounded-xl shadow-2xl` frame with a glow behind it. *Fix:* crop into a real UI detail, angle/offset, bleed off an edge.
- **Perfectly tessellated grid** (every tile identical, no featured span, no intentional gap). *Fix:* `col-span`/`row-span` variation; a deliberate empty cell.
- **Overabundance of whitespace** (vast empty padding to signal "minimal"). *Fix:* purposeful spacing rhythm; real density where content earns it.
- **Mobile = every grid collapses to one `grid-cols-1`** stack (endless identical card list). *Fix:* keep some 2-up groupings; vary sizes; horizontal scroll for sets.
- **Hero is always two equal buttons** (`flex justify-center gap-4`, solid + ghost). *Fix:* one dominant CTA; demote the secondary to a text link.

## 5. Components & cards
- **The shadcn `<Card>` default** (`rounded-xl border bg-white p-6 shadow-sm`) for every content type. *Fix:* differentiate cards by role; not everything is a box.
- **Icon-feature card** - Lucide/Heroicon glyph in a `h-12 w-12 rounded-lg bg-primary/10` square, title, two gray lines, 3-up. The most-generated component on earth. *Fix:* drop the icon chip; lead with a screenshot/number/oversized index numeral.
- **Card with a thick colored top/left border** (`border-t-4 border-primary`) to "categorize." *Fix:* a real visual anchor or remove the stripe.
- **3-tier pricing, middle "Most Popular" `scale-105` + ring**, identical checkmark lists. *Fix:* match the layout to the offer; signal recommendation via copy/contrast, not a pop-out.
- **Pricing CTAs "Get Started" / "Contact Sales"** identical height/shape. *Fix:* name the real action ("Start the 14-day trial").
- **Testimonial card** - round (often placeholder) avatar, name, "Title at Company" in gray, 5 gold stars, two sentences, 3-up. B2B testimonials don't have stars. *Fix:* real names/logos/photos + specific quotes; vary length.
- **Hero pill badge** (`rounded-full border px-3 py-1` "Now with AI" + sparkle + dot). *Fix:* a real changelog link or nothing.
- **Gradient primary button** (purple-blue, glow). *Fix:* one solid brand color with a real hover state.
- **Primary + ghost hero duo** ("Get Started" / "Learn More", same size). "Learn More" is a non-action. *Fix:* subordinate the secondary; real labels.
- **shadcn `<Input>` default** (`rounded-md border-input` + 2px offset focus ring). *Fix:* a deliberate field language; restyle focus.
- **Newsletter input-with-attached-button + "No spam. Unsubscribe anytime."** *Fix:* a concrete reason to subscribe; specific reassurance.
- **Badge `rounded-full bg-{c}-100 text-{c}-800 text-xs`** (the copy-paste status pill). *Fix:* build badges into your type system.
- **shadcn segmented tabs** (gray track, white chip, soft shadow). *Fix:* underline tabs or just sections.
- **Modal centered `max-w-md` + `bg-black/80` overlay + top-right X + Cancel(ghost)/Confirm(filled).** *Fix:* name the consequence in the button ("Delete 3 files").
- **Comparison rows: green check / red X in circles**, identical every row. *Fix:* restrained checkmarks, real values, quiet dash for absent.
- **Empty state**: centered outline icon + "No data yet" + CTA in a dashed box. *Fix:* teach the next action; show a seeded example.
- **Sonner toast bottom-right** with library-default chrome. *Fix:* position/style to the app; human copy.
- **Avatar stack + "+12k" + "Join 10,000+ developers".** *Fix:* real users only, or a concrete checkable proof point.
- **Hover lift on every card** (`hover:-translate-y-1 hover:shadow-lg`). *Fix:* reserve motion for genuinely clickable elements.
- **Bento grid of asymmetric rounded tiles** applied reflexively without content that earns the tiling. *Fix:* bento only when tiles carry genuinely different weights of real content.
- **"Soft SaaS"** - everything `rounded-2xl/3xl` + `shadow-lg/xl`, nested. *Fix:* a real radius scale used sparingly; some elements square; borders over ambient shadows.

## 6. Imagery & photography
- **No images at all** - text + gradients + icon-cards only. Real sites have images. *Fix:* real, relevant imagery - product screenshots first; Unsplash/Pexels for genuine photography.
- **AI-generated people**: melted/six-finger hands, plastic waxy skin, dead/asymmetric eyes, warped jewelry/glasses/teeth/patterns, impossible-physics or duplicated backgrounds, inconsistent lighting/shadows. *Fix:* real photography (even phone shots); if generative, crop hands out and audit at 200%.
- **Garbled text inside images** (squiggle "dashboards", nonsense signage). *Fix:* never let a model render UI/text; screenshot your real app or composite real type.
- **Overused stock clichés** - "diverse team laughing at a laptop", handshake, recognizable Unsplash hits (woman-laughing-with-salad, hooded hacker). *Fix:* reverse-image-search picks; prefer original or low-download images; show the product doing the job.
- **Fake/AI avatars in testimonials** (DiceBear/Avataaars blobs, AI faces, letter-in-a-circle initials). *Fix:* real headshots + names + linked source, or drop avatars.
- **Corporate-Memphis / "Alegria" flat-vector people** (noodle limbs, lavender-coral). *Fix:* a distinctive illustration style or photography.
- **Generic 3D-blob / chrome-orb / iridescent-bubble "hero art"** with no meaning. *Fix:* a real screenshot or a diagram of actual function.
- **Duotone wash over every photo** to force consistency. *Fix:* genuinely consistent photos; subtle grading, not a full duotone map.
- **Tilted/isometric floating browser mockup** on a gradient (Shotsnapp/Screely look); repeated identical device frames. *Fix:* flat legible screenshot, or different real screens/states.
- **Fake dashboard screenshots** (up-and-to-the-right charts, lorem rows, all-green) and **hallucinated infographics** (bars not matching axes). *Fix:* real screenshots/real data; build charts with a real lib.
- **Warped near-miss brand logos** in "customer" walls (also a legal problem). *Fix:* official assets with permission, or omit.
- **Too-clean, no-grain, batch-uniform images** (same light/DOF/temperature across all). *Fix:* mix real sources; keep honest grain.
- **Idea-metaphor stock** (rocket launching, lightbulb, gears, climbing blocks). *Fix:* show the literal outcome your product produces.
- **AI gradient + nonsense-text OG/share image.** *Fix:* generate OG programmatically with real type, or a clean screenshot; preview the rendered card.

## 7. Icons & illustration
- **One outline icon per feature in a rounded square** (the icon-card grid). *Fix:* icons must earn their place; lead with content/screenshots.
- **Hand-generated/AI icon SVGs** (malformed geometry). *Fix:* **use an established icon library** - Heroicons, Lucide, Phosphor, Hugeicons, Tabler - never let the model draw icon SVGs.
- **Lucide/Heroicons monoline sameness** (the 1.5px set baked into every starter). *Fix:* a less-ubiquitous library (Phosphor with weights, Hugeicons) or a consistent custom treatment (filled, two-tone, brand color); don't ship the default monoline grid untouched.
- **Emoji used as icons** (literal rocket/lightning/lock in bullets or nav; renders inconsistently across OSes). *Fix:* a real icon set or custom marks.
- **The sparkle/star = "AI"** on every AI feature/button/badge. Now visual noise. *Fix:* design a mark specific to what the feature does, or none.
- **Generic flat-pastel spot illustrations / section dividers** matching nothing about the brand. *Fix:* one opinionated illustration or photographic language; real imagery if you can't invest in custom.

## 8. Copy, microcopy & voice
- **Promotional puffery**: "stands as a testament to", "plays a pivotal role", "underscores the importance of", "leaves a lasting impact". *Fix:* state the concrete fact ("99.98% uptime over 12 months").
- **Hedging throat-clearing**: "it's important to note", "it's worth mentioning", "rest assured". *Fix:* delete it; the next sentence is the real one.
- **Context-padding openers**: "in today's fast-paced/digital/ever-evolving world". *Fix:* open on a specific reader pain.
- **Audience-spanning hedge**: "whether you're a startup or an enterprise". *Fix:* name one ICP precisely.
- **Before/after pivots**: "Say goodbye to X / Say hello to Y", "No more X". *Fix:* state the after as a fact.
- **"AI-powered" / "leverage the power of AI" as the headline.** *Fix:* lead with the outcome; mention tech only if it's a real differentiator.
- **Adjective reservoir**: empower, harness, unlock, elevate, supercharge, streamline, robust, seamless, effortless, cutting-edge, best-in-class, next-level, world-class. *Fix:* a plain verb + a number/noun.
- **"Delve"-class diction**: delve, dive into, navigate the complexities of, realm, tapestry, myriad, plethora, boasts. *Fix:* the word you'd say out loud.
- **Adverb stacking**: effortlessly/seamlessly/instantly on every verb. *Fix:* prove the ease (a 3-step flow), drop the adverb.
- **The rule-of-three as a tic**: "Fast, secure, and reliable", "Simple. Powerful. Yours." *Fix:* break the meter; one strong claim or an asymmetric set.
- **"Not just X - it's Y" / "More than just X".** *Fix:* make the positive claim directly.
- **Em-dash as the default connector** (the single most-cited written AI tell). *Fix:* periods/commas; this product BANS em-dashes - use a spaced hyphen or restructure.
- **Hero copy clichés**: "Transform your X", "Simple, transparent pricing", "Everything you need to X". *Fix:* specific, concrete value.
- **Smart quotes / proper ellipsis baked into UI strings & code.** *Fix:* match the codebase convention (usually straight quotes in UI).
- **Title Case On Every Heading.** *Fix:* sentence case for headings and buttons.
- **Emoji heading bullets** ("rocket Fast Setup") and **bold-lead-in lists** ("**Speed:** ...") - the literal shape of a pasted ChatGPT answer. *Fix:* rewrite as prose or varied scannable lines.
- **Checkmark-prefixed feature lists** (every line a green tick). *Fix:* a real comparison table where ticks carry meaning.
- **Placeholder identities left in**: Acme Inc, Your Company, Jane/John Doe, jane@example.com, (555) 123-4567, 123 Main St, Lorem ipsum. *Fix:* real or realistic specifics.
- **Fabricated social proof**: round-number praise ("changed our business! - Sarah M., Marketing Manager"), round vanity stats ("10,000+ Happy Customers", "99% Satisfaction"), "Trusted by industry leaders" with no names. *Fix:* true odd numbers, named/linked sources, or cut it.
- **Model-voice FAQ**: "Is [Product] right for me?", answers that echo the question or open with "Great question!". *Fix:* verbatim questions from real tickets; answer in the first sentence.
- **Fake urgency**: "Only 3 spots left!", static/looping countdown timers. *Fix:* only real, honored deadlines.
- **CTA clones**: "Get Started", "Learn More", "Sign Up Now", "Try it Free". *Fix:* name the action/outcome ("Import my first invoice").
- **Mirrored feature/benefit pairs**: "X so you can Y" on every feature. *Fix:* vary structure.
- **Metronomic rhythm** (every sentence medium, every paragraph 3-4 sentences). *Fix:* vary length hard; drop in a two-word sentence.
- **Symmetrical, opinion-free coverage** (lists everything evenly, emphasizes nothing). *Fix:* lead with your one best thing; say what you're not for.
- **Empty personalization**: "designed with you in mind", "built for the way you work". *Fix:* the specific workflow you fit.
- **Grandiose closers**: "The future of X is here", "Join the revolution", "Experience the difference". *Fix:* a concrete next step + a real reason to act now.
- **Over-explained microcopy/tooltips** ("Click here to add a new item to your list of items"). *Fix:* trim to the minimum that works in context.

## 9. Motion & interaction
- **Fade-in-up on scroll for every element** (the AOS default). *Fix:* one hero reveal or a single staggered group; most content present on load.
- **Scroll-triggered word-by-word text reveals** that gate copy behind scrolling (NN/g: delays reading). *Fix:* render text immediately; animate decoration, never the words.
- **Scroll-jacking / pinned full-screen sequences.** *Fix:* respect native scroll; if you pin, keep it short with an obvious exit.
- **Uniform `hover:scale-105` + shadow lift on everything.** *Fix:* differentiate hover by role; tune easing/duration per surface.
- **Count-up/odometer on every stat** (pairs with fake round numbers). *Fix:* sparingly, for one real metric, or just show the number.
- **Infinite auto-scrolling grayscale logo marquee.** *Fix:* a static legible grid of real logos; if motion, slow and pausable.
- **Parallax overuse** (multi-layer, vestibular issues). *Fix:* skip it or one subtle depth cue; honor reduced-motion.
- **Blur-in / clip text reveal on headings.** *Fix:* crisp headings on load.
- **Buttons snap with no transition while decoration over-animates.** *Fix:* short intentional feedback (~120-200ms) on hover/press/toggle.
- **One global `duration-300 ease-in-out` for all transitions.** *Fix:* a timing scale (fast taps, medium cards, slower page-level); custom easings.
- **Idle floating/bobbing hero illustrations and "breathing" mockups.** *Fix:* remove idle loops or make them barely perceptible.
- **Animated color-shifting gradient backgrounds** (slow purple-blue mesh). *Fix:* static distinctive bg or a real product visual.
- **Custom lagging cursor follower.** *Fix:* native cursor unless the interaction needs one.
- **Tilt-on-hover 3D cards** on feature grids. *Fix:* reserve playful 3D for one spotlight element.
- **Stacked engagement chrome** (scroll-progress bar + back-to-top rocket + reveals) on a short page. *Fix:* add chrome only when it earns its place.
- **No `prefers-reduced-motion` handling** anywhere. *Fix:* gate non-essential motion behind the media query with a static fallback.

---

## Sources
925studios (AI Slop Web Design guide), Impeccable (Slop), Developers Digest (16 vibe-coded patterns), Adrian Krebs (Show HN design-slop scoring), DEV/Alan West (the indigo-500 piece + "AI-generated look"), prg.sh / zeroskillai (purple-gradient), HN (color palette gives away AI slop), NN/g (zigzag layouts, scroll animations), Wikipedia (Signs of AI writing), Plagiarism Today (em-dashes), Lovable Detector / VibeEval, Originality.ai, shadcn docs + "shadcn trap" (freedesignmd), MindStudio / BrainGrid / Puck (design-systems-for-AI), MakeUseOf / ZSky / P20V (AI image artifacts), Linda Caroll (overused Unsplash), Envato (stock cliches), learnui.design (mesh gradients), Deceptive Design (fake urgency), Creative Boom (2026 trends), Hugeicons / shadcndesign (icon libraries).

*Next: split into the skill's actionable Layer 1, add per-style positive directions (Layer 2), and rebuild the example using this full list (real imagery, icon library, full-width subtitle).*
