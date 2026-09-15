# Sending Fewer Images to the Vision API — What & Why

> Status: **not implemented** — a keepable explanation of the idea for when/if we decide to do it.
> Scope: reduce vision-analysis cost by cutting the *number* of images we send to the model,
> using only local (free) computation. Independent of, and complementary to, switching model/provider
> or moving to the Batch API.

## The goal

The dominant cost of photo analysis is **per image**, not per field. On `gpt-4o-mini` a single
image is billed at ~2,800 tokens *even at `detail: "low"`* (4o-mini multiplies image tokens ~33×),
so the image swamps the prompt (~600 tokens) and the output (~200 tokens) on every call. Cost is
therefore roughly **linear in the number of images we send**.

The cheapest image is the one we never send. So the biggest *free* lever is to **stop paying the
model to look at photos we were always going to throw away** — junk frames and near-duplicates —
by filtering and de-duplicating them **locally, before the API call**.

Important nuance: moving individual *fields* to local computation does **not** save money on its
own, because we still send the image once for the semantic fields. The only way to cut the image
cost is to send fewer images.

This also helps two other problems at once:
- **Repetition** (e.g. the "8 coconuts") — content-based dedup kills repeats at the source, before
  they can reach selection/layout.
- **Throughput** — fewer calls means the whole trip finishes faster (relevant while sync
  concurrency is capped).

## What we already have (starting point)

We already compute a fair amount locally, for free:
- `PhotoMetadata` carries `width`, `height`, **client-computed `sharpness`** (Laplacian variance),
  `takenAt`, GPS `location`, `orientation`, `cameraModel`.
- A **downsampled thumbnail** is stored per photo (`thumbnailStorageKey`).
- `PhotoAnalysisService.selectRepresentatives` already collapses tight time-bursts before analysis
  (`BURST_WINDOW_SECONDS = 2`, keeps the `REPS_PER_BURST = 2` sharpest frames).
- `analyzeUnanalyzedPhotos` skips already-analyzed photos.

So the hooks and some of the raw signals exist; this idea extends them.

## The three levers

### 1. Local junk pre-filter (zero API)
Before calling the model, drop photos that clearly fail *objective* quality gates that need no VLM:
- **Sharpness** below a floor (very blurry) — we already have Laplacian variance in metadata.
- **Dimensions** below the print minimum — this gate already exists in `PhotoSelector.filterLowQuality`
  but runs *after* analysis; run it *before* so we never pay to analyze an under-res photo.
- Optional extras: near-black / blown-out frames (mean luminance from the thumbnail), extreme aspect
  ratios / screenshots (dimensions + missing EXIF camera).

Net: never spend a call on a photo we'd filter out anyway.

### 2. Perceptual-hash near-duplicate dedup (zero API)
Compute a **perceptual hash** (pHash / dHash — cheap, run on the thumbnail) per photo. Cluster by
Hamming distance and keep only the sharpest 1–2 per cluster. This collapses "let me take another"
repeats and burst sprawl **before** the API *and* before selection.

Why it's better than what we do today: the current near-dup logic leans on time windows and noisy
model labels (`subjectType`, `shotDistance`, object overlap) that the model reports inconsistently
(that's exactly how 8 coconuts slipped through). A perceptual hash is **content-based** and
independent of the model, so it's both cheaper (pre-API) and more reliable.

### 3. More aggressive representative selection
Widen/soften the current burst logic: cluster on short time gaps **combined with** pHash similarity
(and optionally GPS proximity), and keep fewer frames per cluster. Tune to trip size so big trips
prune harder.

## Where it fits in the code
The natural home is `PhotoAnalysisService.analyzeUnanalyzedPhotos` → `selectRepresentatives`. Today
it clusters purely by time; extend it to, in order, **(a)** apply the local quality gates, **(b)**
pHash-dedup, **(c)** pick representatives — all *before* the executor submits any vision call. Keep
the existing post-analysis gates in `PhotoSelector` as a backstop, but short-circuit earlier so the
spend never happens.

## Local vs VLM-only signals
- **Computable locally (free):** blur/sharpness, dimensions, orientation, dominant colors (histogram
  / k-means on the thumbnail), GPS/location, capture time, perceptual hash / near-duplicate grouping.
- **Still needs the VLM:** `sceneType`, `subjectType`, `aestheticScore`, `keepsakeInterest`,
  `primarySubject`, `mood`, `facesCount`, `detectedObjects`, `shotDistance`, etc.

So the model is only paid for *semantic judgment*; everything mechanical is free.

## Expected impact
Cost scales with images sent, so the saving is proportional to how many we prune. On burst-heavy
trips (e.g. Bali: 999 uploaded → ~123 kept after all current filtering) a large share of the 999 are
near-dupes or junk that each currently cost a call. Local junk-gate + pHash dedup could plausibly cut
API calls by a meaningful fraction (illustratively ~30–60% on burst-heavy trips; trip-dependent) for
free. Stacks multiplicatively with a cheaper model and/or the Batch API.

## Trade-offs & risks
- **Over-merging:** pHash can merge genuinely distinct shots (two different dishes at the same table).
  Keep the Hamming threshold conservative and keep ~2 per cluster to hedge.
- **`sharpness` is nullable / client-computed** and may be missing or inconsistent. Only gate when
  present; never drop a photo solely because sharpness is null.
- **Pre-API drops are irreversible for that run** — a photo pruned locally never gets signals. Keep
  local gates strict-safe (drop only the obviously-bad) so we don't lose a keeper before the model
  ever sees it.
- Dedup must be **deterministic** so regenerations are stable.

## Non-goals
- Not switching model/provider (separate lever — Gemini Flash-Lite / Claude Haiku).
- Not the Batch API (separate lever).
- Not changing the semantic selection/layout algorithms.
- Not re-analyzing already-analyzed photos.

## Open questions / decisions for later
- **Where to compute pHash:** client (frontend at upload, consistent with how `sharpness` is already
  produced — cheapest server-side) vs backend (on the thumbnail — self-contained). Leaning client.
- **Persist pHash** on `PhotoMetadata` (new nullable field; it's a JSONB column, so no DB migration)
  so it's reusable and dedup is cheap on regeneration.
- **Tuning:** Hamming threshold + representatives-per-cluster; make both configurable.
- Whether to fold **GPS proximity** into clustering/dedup alongside time + pHash.
