# Photobook Generation — Prompts & Arrangement Rules (Handoff)

Scope: everything about **what we ask the vision model** and **how photos are
arranged into a book**. This is the "V2" algorithm. It is an incremental upgrade
of the existing pipeline — same flow, no new DB migrations.

## Pipeline (order of operations)

`BookGenerationService.generateBook(tripId)`:
1. `PhotoAnalysisService.analyzeUnanalyzedPhotos` — vision analysis. Re-analyzes a
   photo only when it has no `signals` **or** its `signals.schemaVersion` is below
   `PhotoSignals.CURRENT_SCHEMA_VERSION`; otherwise reuses the stored analysis
   (regenerate is analysis-free).
2. `PhotoSelector.selectPhotosForBook` — quality filter → episodes → per-episode
   near-duplicate clustering → **coverage-first** reservation (every episode + every
   trip day) → **saturation** fill by `adjustedScore`. Returns photos sorted
   chronologically. Curation, not top-N.
3. `LayoutEngine.generatePages` → internally `PhotoGrouper.group` (episodes → hero
   reservation with per-role solo caps → 1/2/4-photo pages that mix visual roles →
   50-page normalize) then per-group layout choice → pacing pass → **spread-
   composition pass** → **`FinalAudit`**.
4. Persist `Book` + `Page`s; trip status → `BOOK_GENERATED`.

Shared deterministic building blocks in `application/layout/`: `EpisodeDetector`,
`PhotoSimilarity` (pairwise + spread similarity, per-run cache), `VisualRole`,
`StandaloneScorer`, `FinalAudit` (+ `LayoutSiblings`). Design priority order:
**coverage > uniqueness > hierarchy > spread composition > layout variety > color.**

Everything from step 2 on is **deterministic** (no randomness): same photos +
same signals → same book. (So "Regenerate layout" only differs if photos change.)

Hard invariants: exactly **50 pages** when ≥50 photos, **only 1/2/4 photos per page**
(never 3/5/6+), each source photo used **≤ 1 time**, **≤ 1** double-page hero (currently
0 — reserved). Captions: the slot has a `caption` field but nothing generates it.

---

## 1. Vision prompt — what we ask OpenAI

- Model `gpt-4o-mini`, `max_tokens: 600`, `temperature: 0.3`, image `detail: "low"`
  (downsamples to 512px server-side: fewer tokens, faster — coarse signals don't
  need full res).
- Prompt lives embedded in `infrastructure/ai/OpenAIVisionClient.kt`
  (`SYSTEM_PROMPT`); mirrored in `VISION_MODEL_PROMPT.md`. **Keep both in sync.**
- The model returns ONLY JSON. Parsed by `infrastructure/ai/PhotoAnalysisResponse.kt`
  (all fields have safe defaults so a malformed/partial response still parses),
  stored on `domain/photo/PhotoSignals.kt` (JSONB, nullable = unanalyzed).

Fields requested (all **observable** properties — we deliberately do NOT ask the
model for hero/story-role/editorial-strength/layout recommendations):

| Field | Values |
|---|---|
| `aesthetic_score` | 0.0–1.0 (0 poor, 1 stunning) |
| `blur_score` | 0.0–1.0 (0 sharp, 1 blurry) |
| `faces_count` | integer |
| `scene_type` | people / landscape / food / city / misc |
| `time_of_day` | day / golden_hour / night |
| `dominant_colors` | 2–4 hex, most dominant first |
| `detected_objects` | 3–6 key labels |
| `color_temperature` | warm / cool / neutral |
| `mood` | joyful / serene / dramatic / adventurous |
| `depth_of_field` | shallow / deep |
| `location_tag` | beach / mountain / restaurant / temple / … / other |
| `subject_type` | person / couple / group / landscape / food / object / architecture / activity / other |
| `shot_distance` | closeup / medium / wide |
| `subject_prominence` | low / medium / high |
| `setting_scope` | detail / subject / environment |
| `background_complexity` | low / medium / high |
| `negative_space` | low / medium / high |

**Analysis orchestration** (`PhotoAnalysisService`):
- Concurrency is configurable: `openai.api.analysis-concurrency` (default 20;
  prod env `OPENAI_API_ANALYSIS_CONCURRENCY=12`). Calls are synchronous, so pool
  size = effective parallelism. The real ceiling is the OpenAI tier's TPM (this
  account: RPM 10,000 / **TPM 200,000** → TPM is the bottleneck).
- 3 retries + exponential backoff; on total failure writes neutral fallback
  signals so generation never breaks (`VisionModelService`).
- **Burst pre-filter before vision:** clusters tight 2-second time-bursts and only
  sends the **2 sharpest frames per burst** to the model (clusters of ≤2 lose
  nothing). Sharpness is a Laplacian-variance score computed on the **client**
  during upload and stored in `PhotoMetadata.sharpness`.

---

## 2. Scores (`application/layout/StandaloneScorer.kt`)

Two scores, both quality-derived, no vision calls:

**`baseScore`** (selection quality) — no uniqueness, used before photos are grouped:
```
base = aesthetic*0.55 + (1-blur)*0.15 + prominence*0.15 + bg_cleanliness*0.15
       prominence: high=1.0 medium=0.7 low=0.4 ; bg_cleanliness: low=1.0 medium=0.7 high=0.4
```

**`score`** (standalone_score) — how well a photo carries a page alone → hero reservation
and slot sizing; blends episode-relative uniqueness:
```
standalone = aesthetic*0.50 + (1-blur)*0.15 + prominence*0.10 + bg_cleanliness*0.10
           + uniqueness*0.15
```
`uniqueness = 1 - avg(similarity to other photos in the episode)` (alone = 1.0).
Similarity lives in `PhotoSimilarity`: `subject_type(0.35) + shot_distance(0.25) +
setting_scope(0.15) + objectJaccard*0.25`. `STRONG_THRESHOLD = 0.75`.

---

## 3. Selection rules (`application/layout/PhotoSelector.kt`) — coverage-first + saturation

1. **Quality filter:** drop if `signals == null`, `aesthetic < 0.4`, `blur > 0.6`, or
   either dimension `< 1200px`.
2. **Episodes:** `EpisodeDetector.detect` over chronologically-sorted survivors.
3. **Near-duplicate clustering (per episode):** cluster photos by composition similarity
   (`≥ 0.80`) **or** burst timestamp (within 10s + similarity `≥ 0.60` + same faces +
   same/absent location). Different `sceneType` ⇒ never a near-dup. Keep the best of each
   cluster; keep a **2nd only** if it differs meaningfully (different visual role, shot
   distance, setting scope, or — when both list objects — low object overlap). So 5
   same-view landscapes → 1; wide surfing environment + close surfing action → both.
4. **Coverage:** reserve the highest-`baseScore` representative of **every episode**, then
   ensure **every trip day** with usable photos has one selected. A distinct activity is
   never dropped because another activity has more raw photos.
5. **Saturation fill:** greedily add the remaining candidate with the highest
   `adjustedScore` until the budget is hit or (once full enough) the best remaining photo
   scores below the floor. Global `usedPhotoIds` — never reuse a source.
   ```
   adjustedScore = 1.0*quality + 0.5*uniqueness + 0.4*underrepBonus
                 − 0.6*similarityPenalty − 0.08*perEpisodeCount − roleSatPenalty
   roleSatPenalty = 0.05*perRoleCount, ×1.5 for GROUP / LANDSCAPE_ENVIRONMENT / DETAIL_OBJECT
   ```
   `quality = baseScore`; `uniqueness` vs already-selected in the same episode;
   `similarityPenalty` = closeness to the most-similar already-selected photo anywhere;
   `underrepBonus = 1/(1+perEpisodeCount)`. Budget ≤ 200 (50 pages × 4), ≥ ~50 when
   material allows (floor `0.45`, `minEnough = min(candidates, 50)`).
6. Output sorted chronologically. Small sets degrade gracefully.

---

## 4. Grouping into pages (`application/layout/PhotoGrouper.kt`)

1. Sort chronologically + `EpisodeDetector.detect` (boundary threshold **0.55** = temporal
   gap `min(gapMin/120,1)*0.40` + location 0.25 + scene 0.20 + object dissimilarity 0.10 +
   colour-temp 0.05).
2. **Per episode:** heroes = photos with `standalone ≥ 0.75` (else, ≥3-photo episodes may
   promote the single best if `≥ 0.68`), **capped at 2 solo pages per visual role** (does
   not cap how many distinct photos of the event appear in multi-photo pages). Heroes become
   **solo pages inline**; the rest are chunked into **1/2/4-photo pages** (never 3) whose
   photos are **interleaved by visual role** so each page mixes roles.
3. **Normalize to exactly 50 pages:** too many → merge the weakest collage into a neighbour,
   snapping the result to a valid {1,2,4} size (drop weakest extras / a 3rd); too few →
   split the densest page (4→2+2, 2→1+1), a single strong split becoming a hero. Each step
   bails out if it can't move further.
4. `<50` photos → one photo per page; strong ones flagged hero.

Output: `List<PhotoGroup>` carrying `photos` (standalone-desc, best first),
`standaloneScores`, `isHero`, and `episodeIndex` (used by the spread pass).

---

## 5. Layout choice + geometry (`service/LayoutEngine.kt`, enum `domain/book/Layout.kt`)

Layout is chosen from **count + orientation + standalone + shot_distance +
subject_prominence + setting_scope + crop-safety + previous layout** — never count
alone. `PAGE_ASPECT = 6.9/9.8 ≈ 0.704`. `coverVisibleFraction =
min(photoAspect,pageAspect)/max(...)`; `< 0.62` = crop-unsafe.

- **1 photo →** crop-unsafe → `SINGLE_FRAMED`; else `environment`/`wide` →
  full-bleed (`HERO_LANDSCAPE` if landscape else `SINGLE_FULL`); `closeup`/high
  `negative_space` → `SINGLE_FRAMED`; else full-bleed.
- **2 →** both portrait → `TWO_VERTICAL`; both landscape → `TWO_HORIZONTAL`; else
  `TWO_HORIZONTAL`.
- **4 →** `FOUR_MIXED` if one photo clearly stands out (top standalone − rest avg
  ≥ 0.12 and top ≥ 0.6) or to break a run of `FOUR_GRID`; else `FOUR_GRID`.

`THREE_GRID` and `DOUBLE_PAGE_FULL_BLEED` exist in the enum + geometry but are **reserved
and never emitted** — grouping produces only 1/2/4-photo pages, and the double-page needs
cross-page frontend/PDF work (deferred).

Slots are normalized 0–1 `position`/`size`. **Both the web preview and `PdfRenderer`
render purely from slot geometry — neither switches on the Layout enum — so new
per-page layouts need zero frontend/PDF changes.** `SINGLE_FRAMED` = one inset slot
(page background shows as the margin).

**Pacing pass** (after layouts are chosen):
- Break runs of 3 identical layouts by flipping the middle to a same-slot-count sibling
  (`LayoutSiblings`: `FOUR_GRID↔FOUR_MIXED`, `TWO_HORIZONTAL↔TWO_VERTICAL`,
  `SINGLE_FULL/HERO_LANDSCAPE↔SINGLE_FRAMED`).
- Opening: move an establishing single (environment/wide/landscape/architecture) to the front.
- Ending: move a quiet single (serene/closeup/landscape/environment) to the end.

**Spread-composition pass** (§12/§13, after pacing): facing pairs (pages 0-1, 2-3, …) with
`PhotoSimilarity.spreadSimilarity ≥ 0.6` are made more complementary by swapping the right
page with its **same-episode** neighbour when that lowers similarity (a safe reorder that
preserves episode order) and by adding layout contrast when the pair shares a layout. Colour
is a light secondary signal folded into `spreadSimilarity` (§14) — never overrides
chronology/coverage/uniqueness/quality.

**Final audit** (§18, `FinalAudit`, after the spread pass): enforces the hard invariants —
only 1/2/4 photos per page, no source photo reused, the same layout ≤ 2 consecutive pages
(fixed in place via `LayoutSiblings`), ≤ 1 double-page — and logs any remaining
near-duplicate adjacencies. Coverage/uniqueness are guaranteed upstream by `PhotoSelector`.

---

## File map

| Concern | File |
|---|---|
| Vision prompt (source of truth) | `infrastructure/ai/OpenAIVisionClient.kt` (`SYSTEM_PROMPT`) |
| Vision prompt (doc mirror) | `VISION_MODEL_PROMPT.md` |
| Response parsing + defaults | `infrastructure/ai/PhotoAnalysisResponse.kt` |
| Retry / fallback | `infrastructure/ai/VisionModelService.kt` |
| Signals model (JSONB) + `schemaVersion` | `domain/photo/PhotoSignals.kt` |
| Analysis + burst pre-filter + schema-version reuse | `service/PhotoAnalysisService.kt` |
| Episodes (shared) | `application/layout/EpisodeDetector.kt` |
| Similarity + spread similarity (cached) | `application/layout/PhotoSimilarity.kt` |
| Visual roles | `application/layout/VisualRole.kt` |
| Scores (`baseScore` + standalone) | `application/layout/StandaloneScorer.kt` |
| Selection (coverage + saturation) | `application/layout/PhotoSelector.kt` |
| Grouping / episodes / heroes / normalize | `application/layout/PhotoGrouper.kt` |
| Final audit + layout siblings | `application/layout/FinalAudit.kt` |
| Layout choice + pacing + spreads + geometry | `service/LayoutEngine.kt` |
| Layout enum | `domain/book/Layout.kt` |
| Slots / pages | `domain/book/PhotoSlot.kt`, `Page.kt` |
| Orchestration | `service/BookGenerationService.kt` |
| PDF render (geometry-driven) | `service/PdfRenderer.kt`, `PdfExportService.kt` |

## Known caveats / gotchas
- Deterministic pipeline → "Regenerate layout" produces the same result unless the
  photo set changes, and it discards manual slot edits (builds a fresh version).
- `PhotoSelectorTest` passes in full. `AtlasoBackendApplicationTests.contextLoads`
  needs a running PostgreSQL (Flyway) — it fails with a connection error when no DB
  is up; environmental, not a code failure.
- Bumping `PhotoSignals.CURRENT_SCHEMA_VERSION` forces re-analysis of all photos on
  the next generation (existing analyses become "stale"); leave it unless the schema
  meaningfully changes.
- Adding vision fields needs no migration (JSONB). Adding `Layout` values needs no
  migration (STRING column). If you drop a field the model still returns it — just
  ignored by Jackson.
- Quality of the newer observable fields depends on the prompt's per-field rules;
  keep the "Rules:" section in the prompt if you rely on `subject_type` etc.
