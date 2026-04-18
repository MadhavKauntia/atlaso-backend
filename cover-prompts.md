# Atlaso Cover System — Claude Code Prompts

Run these prompts **in order**. Each builds on the previous. Before starting, make sure Claude Code knows your project's tech stack (React/Next.js/Vue/etc), folder structure, and where the cover editor will live in your routing.

---

## Prompt 0 — Context dump (run this first every session)

```
I'm building Atlaso, a travel photobook company. Users upload trip photos, our AI lays out a photobook, they review it, and we print + ship it.

I need to build a **cover editor system** for the photobooks. Here's the architecture:

- Each cover is an **SVG template** defined as a JSON config + SVG markup
- Users can edit: title (destination name), subtitle (trip description), and select from palette variants
- The cover preview renders live as the user types
- Final covers export to high-res PDF/PNG for print (300 dpi, with 3mm bleed)

Tech constraints:
- [YOUR FRAMEWORK: e.g., Next.js 14 / App Router / TypeScript]
- [YOUR STYLING: e.g., Tailwind CSS + CSS modules]
- [YOUR STATE MANAGEMENT: e.g., Zustand / React Context]
- Font for covers: [YOUR CHOSEN DISPLAY FONT, e.g., GT Sectra / Recoleta / Editorial New] — already loaded via @font-face

Don't write any code yet. Just confirm you understand the system and ask any clarifying questions about the existing codebase.
```

---

## Prompt 1 — Template data model + palette system

```
Create the data model and palette system for Atlaso cover templates.

1. Create `src/lib/covers/palette.ts`:
   - Define the Atlaso master palette as a const object. Here are the 12 colors:
     - terracotta: #C8421F
     - deepTeal: #1F4B4C
     - mustard: #E5B84B
     - inkNavy: #1A3A6B
     - roseBrick: #A83A5C
     - olive: #4A6B3A
     - bone: #E8DDC7
     - sand: #D4C4A8
     - sageMist: #C9D4D0
     - shell: #F2E4D4
     - ink: #1C1C1C
     - paper: #F5F0E6
   - Define a `CoverPairing` type: `{ id: string, background: string, accent: string, textPrimary: string, textSecondary: string, category: 'warm' | 'cool' | 'neutral' }`
   - Export 8-10 curated pairings. Examples:
     - "lisbon-sun": bone bg, terracotta accent, ink text
     - "kyoto-night": inkNavy bg, mustard accent, paper text
     - "nordic-mist": sageMist bg, deepTeal accent, ink text
     - "desert-rose": shell bg, roseBrick accent, ink text
     - "forest-deep": olive bg, sand accent, paper text
     - "ocean-classic": deepTeal bg, mustard accent, paper text
     (create 2-3 more pairings that make sense)

2. Create `src/lib/covers/types.ts`:
   - `CoverTemplate` type with fields:
     - id: string
     - name: string (human-readable, e.g., "Archway")
     - paletteId: string (default pairing)
     - compatiblePalettes: string[] (all pairings that work with this template)
     - category: 'landmark' | 'nature' | 'coastal' | 'urban' | 'abstract' | 'minimal'
     - titleConfig: { x: number, y: number, maxChars: number, baseFontSize: number, fontSizeBreakpoints: { chars: number, size: number }[] }
     - subtitleConfig: { x: number, y: number, maxChars: number, fontSize: number }
     - volumeLabel: { x: number, y: number }
     - coordinateLabel: { x: number, y: number }
     - illustration: string (SVG path data or component reference)
   - `CoverUserInput` type: { title: string, subtitle: string, paletteId?: string }

3. Create `src/lib/covers/templates/` directory with an index.ts that exports a `TEMPLATES` map.

Make the types strict — no `any`. Add JSDoc comments explaining each field's purpose.
```

---

## Prompt 2 — SVG cover renderer component

```
Create the core SVG cover renderer component at `src/components/covers/CoverRenderer.tsx`.

Props:
- template: CoverTemplate
- pairing: CoverPairing
- title: string (user input, will update on every keystroke)
- subtitle: string (user input)
- volumeNumber?: number
- coordinates?: string (lat/lon string, optional)
- className?: string
- mode: 'preview' | 'export' (preview = screen display, export = print-ready)

Requirements:

1. Render a fully self-contained SVG with viewBox="0 0 300 400" (3:4 book ratio).

2. The SVG must include:
   - Background rect filled with pairing.background
   - A grain texture overlay (use an SVG <pattern> with tiny scattered dots in the accent color at low opacity — this is critical to the Atlaso look)
   - Volume label top-left: "ATLASO · VOL. {nn}" in accent color, 11px, letter-spacing 3
   - Title text at template.titleConfig position. Use the fontSizeBreakpoints array to auto-shrink: loop through breakpoints, if title.length > breakpoint.chars, use that breakpoint.size. Apply text-transform uppercase.
   - Subtitle below title at subtitleConfig position. Prefix with an em dash: "— {subtitle}". Italic. Secondary text color.
   - Coordinate label bottom-left, tiny, low opacity
   - The illustration SVG content from the template (render as a <g> group positioned according to the template)

3. All text elements must use the display font (pass as a prop or use CSS var --font-cover-display).

4. For mode='export': increase viewBox to 900x1200 (3x for 300dpi at ~3 inch width), scale all positions and font sizes by 3x, add 9px (3mm × 3) bleed on all sides.

5. The component must be a pure render — no state, no effects. It re-renders on every prop change (this is what makes live preview work).

6. Use React.memo to avoid unnecessary re-renders when only unrelated parent state changes.

7. Add a subtle CSS animation: when the component first mounts, fade in over 400ms with a slight translateY.

Do NOT include any editor UI — this is purely the renderer. It takes data and draws an SVG.
```

---

## Prompt 3 — Three starter templates

```
Create three starter cover templates in `src/lib/covers/templates/`.

Each template is a separate file exporting a CoverTemplate object + an SVG illustration component.

**Template 1: "Archway"** (`archway.ts` + `ArchIllustration.tsx`)
- Category: landmark
- Default palette: "lisbon-sun" (bone + terracotta)
- Illustration: A simplified architectural archway/doorway shape. Two-tone: accent color for the arch fill, darker shade for window/door cutouts. Think of a Moorish or Mediterranean arch — one shape, not literal.
- Title at top-left, subtitle below, illustration centered in lower 60%

**Template 2: "Rising Sun"** (`rising-sun.ts` + `SunIllustration.tsx`)
- Category: nature
- Default palette: "kyoto-night" (inkNavy + mustard)
- Illustration: A large circle (sun/moon) with horizontal wave/mountain lines crossing through it. Simple, graphic, like a Japanese woodblock print reduced to essentials.
- Same text layout grid

**Template 3: "Ridgeline"** (`ridgeline.ts` + `RidgeIllustration.tsx`)
- Category: nature
- Default palette: "nordic-mist" (sageMist + deepTeal)
- Illustration: A mountain ridgeline made of 2-3 overlapping SVG paths. The paths should be filled in the accent color at different opacities (100%, 70%, 40%) to create depth.
- Same text layout grid

For each illustration component:
- Accept `accentColor` and `backgroundColor` as props so they re-color with palette swaps
- Use only SVG primitives (path, rect, circle, line) — no images
- Keep path data clean and minimal — these are graphic marks, not detailed drawings
- All coordinates relative to the cover's viewBox (300×400)

Also update the templates index.ts to export all three.
```

---

## Prompt 4 — Cover editor UI

```
Create the cover editor component at `src/components/covers/CoverEditor.tsx`.

This is the user-facing editor where they customize their cover. Layout: two-column on desktop (editor controls left, live preview right), stacked on mobile (preview top, controls below).

Left column — controls:
1. **Title input**: large text input, placeholder "Your destination". Max 12 characters. Show character count. As user types, the CoverRenderer updates in real time (no debounce — instant).
2. **Subtitle input**: regular text input, placeholder "e.g., a week in spring". Max 40 characters.
3. **Template selector**: horizontal scrollable row of small thumbnail previews (render each CoverRenderer at ~80px wide). Clicking one selects it. Selected state = ring/border highlight.
4. **Palette selector**: show the compatiblePalettes for the selected template as color-swatch pairs (two circles — bg + accent). Clicking one swaps the palette. Selected state = ring.

Right column — preview:
- CoverRenderer at a comfortable size (~320px wide on desktop, full-width on mobile)
- Below the preview: small text showing the coordinate string (if provided) and "This is how your cover will print"

State management:
- Local component state is fine for this (useState for title, subtitle, selectedTemplateId, selectedPaletteId)
- Expose an `onCoverChange` callback prop that emits the current { templateId, paletteId, title, subtitle } on every change — the parent page needs this to save the cover config

Styling:
- Use the project's existing design system / Tailwind classes
- The editor should feel premium — generous whitespace, smooth transitions on template/palette swap
- Template thumbnails should have a hover scale effect (scale 1.03, 150ms ease)
- When palette changes, the preview should cross-fade (CSS transition on background-color, 200ms)

Do NOT build the export/download feature yet — that's a separate prompt.
```

---

## Prompt 5 — Auto-shrink + text safety

```
Create a utility at `src/lib/covers/text-utils.ts` for cover text handling.

Functions needed:

1. `getAutoFontSize(text: string, breakpoints: { chars: number, size: number }[]): number`
   - Given the text and a breakpoints array, return the right font size.
   - Breakpoints are checked in order: if text.length > chars, use that size.
   - If no breakpoint matches, return the first (largest) size as default.

2. `sanitizeCoverTitle(input: string): string`
   - Uppercase, trim whitespace
   - Strip non-alphanumeric except spaces and hyphens
   - Collapse multiple spaces to one
   - Clamp to 12 characters

3. `sanitizeCoverSubtitle(input: string): string`
   - Trim, clamp to 40 characters
   - No special character stripping (subtitles can have punctuation)

4. `estimateTextWidth(text: string, fontSize: number, letterSpacing: number): number`
   - Rough estimation for SVG text width (use average character width ratio of 0.6 × fontSize for uppercase serif)
   - This is for checking if text will overflow the SVG bounds
   - Return estimated width in SVG units

5. `fitTextToWidth(text: string, maxWidth: number, baseFontSize: number, minFontSize: number, letterSpacing: number): { fontSize: number, fits: boolean }`
   - Iteratively reduce font size until estimateTextWidth fits within maxWidth
   - Never go below minFontSize
   - Return the computed size and whether it actually fits

Write unit tests for all functions. Edge cases to test:
- Empty string
- Single character
- Exactly at maxChars boundary
- String with lots of wide characters (W, M)
- String with narrow characters (I, l, 1)
```

---

## Prompt 6 — Print export pipeline

```
Create the server-side export pipeline for cover printing.

1. Create `src/lib/covers/export.ts`:
   - `exportCoverSVG(config: { templateId, paletteId, title, subtitle, volumeNumber, coordinates }): string`
     - Returns a complete SVG string (not a React component) at print resolution
     - ViewBox: 900×1200 (3x screen resolution)
     - All font sizes and positions scaled 3x from the template config
     - Bleed: add 9px (3mm at 300dpi) padding on all sides, making total viewBox 918×1218
     - Embed the font as a base64 @font-face inside a <style> element in the SVG <defs> — this ensures the font renders correctly in any conversion tool
     - Include a crop-mark layer: four small L-shaped marks at the corners of the trim zone (at the 9px inset)

2. Create an API route at `src/app/api/covers/export/route.ts`:
   - POST endpoint accepting the cover config as JSON body
   - Calls exportCoverSVG to generate the SVG string
   - Uses a server-side SVG-to-PNG conversion (options: sharp with svg input, or resvg-js which is faster and handles fonts better — prefer resvg-js)
   - Returns the PNG as a binary response with content-disposition: attachment
   - Also support a `format` query param: 'svg' returns raw SVG, 'png' returns rasterized PNG, 'pdf' wraps the SVG in a single-page PDF (use @react-pdf/renderer or pdf-lib)

3. Create a "Download preview" button component that hits this endpoint.
   - Show a loading spinner while generating
   - For the preview download, use 'png' format at 2x (600×800)
   - For the final print file (called later in the flow), use 'pdf' with bleed marks

Install resvg-js (or your preferred SVG rasterizer) as a dependency. If resvg-js has issues with your Node version, fall back to sharp.
```

---

## Prompt 7 — Template matching by destination

```
Create a smart template suggestion system at `src/lib/covers/suggest.ts`.

When a user types a destination name, we want to auto-suggest the best template + palette pairing.

1. Create a `DESTINATION_HINTS` const map — a lightweight lookup of ~50 popular destinations with metadata:
   - Key: lowercase destination name (e.g., "paris", "bali", "reykjavik")
   - Value: { type: 'city' | 'beach' | 'mountain' | 'island' | 'desert' | 'forest', region: 'europe' | 'asia' | 'americas' | 'africa' | 'oceania' | 'middle-east', climate: 'tropical' | 'temperate' | 'cold' | 'arid', coordinates: string }

2. Create matching rules:
   - `suggestTemplate(destination: string): { templateId: string, paletteId: string, coordinates?: string }`
   - First, check DESTINATION_HINTS for an exact match (lowercase). If found, use the metadata.
   - Map destination type to template category: city/urban → 'landmark' or 'urban' templates, beach/island → 'coastal', mountain/forest → 'nature'
   - Map climate to palette category: tropical/arid → 'warm' pairings, temperate → 'neutral', cold → 'cool'
   - Return the best match. If multiple templates match, prefer the one whose default palette matches the climate category.
   - If no hint found, return a sensible default (the 'abstract' or 'minimal' template with a neutral palette).

3. Wire this into the CoverEditor: when the title input changes and matches a known destination, auto-select the suggested template and palette (but let the user override). Show a subtle toast: "We picked a style for Lisbon — feel free to change it."

This should be a pure client-side function — no API calls. The hints map is small enough to ship in the bundle. Later we can expand this with an LLM call for unknown destinations, but start simple.
```

---

## Prompt 8 — Integration with existing flow

```
I need to integrate the cover editor into my existing photobook creation flow.

In my app, the user flow is:
1. Upload photos → 2. AI generates layout → 3. User reviews pages → **4. Cover editor (NEW)** → 5. Checkout

The cover editor should live at [YOUR ROUTE, e.g., /create/[bookId]/cover].

Please:
1. Create the page/route that renders the CoverEditor component
2. On page load, fetch the book data (I have an existing API at [YOUR ENDPOINT]) to get:
   - The book's destination (pre-fill the title)
   - The trip dates (pre-fill the subtitle with formatted dates)
   - The volume number (based on how many books this user has created)
3. When the user is happy with the cover, the "Continue" button should:
   - Save the cover config to the book record via [YOUR SAVE ENDPOINT]
   - Navigate to the checkout page
4. Add a "Back" button that returns to the page review step
5. Add the page to the existing step-indicator/progress-bar component (if one exists) as step 4

If any of the endpoints I referenced don't exist yet, create stub API routes with TODO comments and mock data so the page works in development.
```

---

## Notes for all prompts

- Replace all `[BRACKETED PLACEHOLDERS]` with your actual values before pasting
- If Claude Code asks about your framework version, component library, or folder conventions — answer specifically, it helps a lot
- Each prompt should result in a working, testable piece. Run the dev server after each one and verify before moving to the next.
- If a prompt produces something that doesn't look right, paste a screenshot and say "this doesn't match what I expected, here's what's wrong: ..."