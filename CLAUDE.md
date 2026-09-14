# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What This Is

Atlaso is a backend service that generates travel photobooks. It accepts bulk photo uploads, analyzes them with GPT-4o-mini vision, selects the best ~30 photos using a multi-phase algorithm, arranges them into page layouts, and exports a printable PDF. The PDF is internal-only — users see a preview in the frontend but never download the PDF directly. Once confirmed, the PDF is stored and sent to the printing company.

## Commands

```bash
./gradlew bootRun          # Start the server (port 8080)
./gradlew build            # Compile and package
./gradlew test             # Run all tests
./gradlew test --tests "com.atlaso.application.layout.PhotoSelectorTest"        # Single test class
./gradlew test --tests "com.atlaso.application.layout.PhotoSelectorTest.methodName"  # Single test method
```

**Prerequisites:** PostgreSQL running at `localhost:5432/atlaso` (user: `postgres`, pass: `postgres`). The app uses `ddl-auto: validate` — schema is managed entirely by Flyway migrations, not Hibernate.

## Architecture

The layered package structure under `com.atlaso`:

| Package | Role |
|---|---|
| `domain/` | Pure JPA entities — no Spring annotations, no business logic |
| `application/layout/` | Core photo selection algorithm (`PhotoSelector`) |
| `infrastructure/ai/` | OpenAI Vision API integration via Retrofit |
| `service/` | Spring `@Service` orchestrators — coordinate domain, infrastructure, and repositories |
| `controller/` | REST layer with DTOs in `controller/dto/` |
| `repository/` | Spring Data JPA interfaces |
| `config/` | Spring `@Configuration` beans (Retrofit client, storage, CORS) |

## Key Data Flow

`POST /api/trips/{id}/book/generate` triggers `BookGenerationService`, which:
1. Calls `PhotoAnalysisService` → `VisionModelService` → OpenAI (skips already-analyzed photos)
2. Calls `PhotoSelector.selectPhotosForBook()` — 4-phase algorithm, returns `PhotoSelectionResult`
3. Calls `LayoutEngine.generatePages()` — internally runs `PhotoGrouper` then assigns layouts
4. Persists `Book` + `Page` entities, updates `Trip` status to `BOOK_GENERATED`

`POST /api/books/{id}/export` → `PdfExportService` → `PdfRenderer` (PDFBox) → saves to `./uploads/pdfs/`. PDFs are internal-only — users never download them; they are stored for fulfilment by the printing company.

The deterministic pipeline aims for a **curated memory of the whole trip**, not the
highest-scoring photos. Priority order: coverage > uniqueness > hierarchy > spread
composition > layout variety > color. Shared building blocks live in `application/layout/`:
`EpisodeDetector` (natural events), `PhotoSimilarity` (one home for pairwise similarity +
spread similarity, with a per-run cache), `VisualRole` (LANDSCAPE_ENVIRONMENT / SOLO_COUPLE
/ GROUP / ACTION_CANDID / DETAIL_OBJECT, derived from signals), `StandaloneScorer`
(`baseScore` for selection quality, `score` for hero reservation), and `FinalAudit`.

## Photo Selection Algorithm (`PhotoSelector`)

Curates *which* photos enter the book (coverage-first, not top-N):
1. **Quality filter** — drops photos with aesthetic < 0.4, blur > 0.6, or dimensions < 1200px, or missing signals.
2. **Episodes** — chronological split into natural events (`EpisodeDetector`, boundary threshold 0.55).
3. **Near-duplicate clustering (per episode)** — cluster by composition similarity + burst timestamp; keep 1 per cluster, a 2nd only if it communicates meaningfully different info. Different `sceneType` is never a near-dup.
4. **Coverage** — reserve the strongest representative of *every* episode, then ensure *every trip day* with usable photos is represented. A distinct activity never disappears because another has more photos.
5. **Saturation fill** — add remaining photos by `adjustedScore = quality + uniqueness + underrepBonus − similarityPenalty − episodeSaturationPenalty − visualRoleSaturationPenalty` (group/landscape/detail penalized harder). Stops once the book is full enough and the next photo no longer earns its place. Global `usedPhotoIds` — a source photo is never reused.

Budget: up to 200 photos (50 pages × 4), at least ~50 when material allows. Output sorted chronologically.

## Photo Grouping (`PhotoGrouper`)

Groups selected photos into page-sized clusters (each group = one page) using the same
`EpisodeDetector`. Per episode: reserve strong standalone photos as **solo pages** (capped
at 2 per visual role, §hero-reservation), then chunk the rest into **1/2/4-photo pages**
(never 3/5/6+) that mix visual roles. Pages are normalized to exactly **50** by merging
weak pages (too many) or splitting dense ones (too few), always snapping to a valid
{1,2,4} size. `PhotoGroup` carries `episodeIndex` for spread reasoning.

## LayoutEngine

`LayoutEngine` takes `List<Photo>` (delegates to `PhotoGrouper`) or `List<PhotoGroup>` via
`generatePagesFromGroups()`. Layout is chosen from count + orientation + standalone +
shot/scope + crop-safety + previous layout (1→full-bleed/framed, 2→two horizontal/vertical,
4→FOUR_GRID/FOUR_MIXED). **`THREE_GRID` and `DOUBLE_PAGE_FULL_BLEED` are reserved and never
emitted.** After layout: a **pacing pass** (break identical runs, establishing/quiet ends),
a **spread-composition pass** (facing pages 0-1/2-3/… reduced for redundancy via safe
same-episode reorders + layout contrast; color is a light secondary signal), then
**`FinalAudit`** enforces structural invariants (1/2/4 per page, ≤2 consecutive identical
layouts, ≤1 double-page, no reused photo, flags near-duplicate adjacencies).

## JSONB Columns

`Photo.metadata` and `Photo.signals` are PostgreSQL JSONB columns backed by Kotlin data classes (`PhotoMetadata`, `PhotoSignals`). Hibernate serialization uses Hypersistence Utils (`@Type(JsonType::class)`). `signals` is nullable — null means the photo hasn't been analyzed yet.

`Page.slots` is also JSONB — a list of `PhotoSlot` objects with normalized (0.0–1.0) position and size coordinates.

## Vision Model Fields

`PhotoSignals` (stored as JSONB on `photos.signals`) contains all GPT-returned fields:

| Field | Type | Notes |
|---|---|---|
| `aestheticScore` | Double 0–1 | Quality/composition score |
| `blurScore` | Double 0–1 | 0 = sharp, 1 = blurry |
| `sceneType` | String | people / landscape / city / food / misc |
| `timeOfDay` | String | day / golden_hour / night |
| `facesCount` | Int | Visible human faces |
| `dominantColors` | List\<String\> | 2–4 hex codes, most dominant first |
| `detectedObjects` | List\<String\> | 3–6 key object labels |
| `colorTemperature` | String | warm / cool / neutral |
| `mood` | String | joyful / serene / dramatic / adventurous |
| `depthOfField` | String | shallow / deep |
| `isBlurry` | Boolean | Derived: blurScore > 0.6 |
| `locationTag` | String? | beach / mountain / restaurant / temple / … / other |
| `subjectType` | String | person / couple / group / landscape / food / object / architecture / activity / other |
| `shotDistance` | String | closeup / medium / wide |
| `subjectProminence` | String | low / medium / high |
| `settingScope` | String | detail / subject / environment |
| `backgroundComplexity` | String | low / medium / high |
| `negativeSpace` | String | low / medium / high |
| `schemaVersion` | Int | Analysis schema version (§reuse); `CURRENT_SCHEMA_VERSION` |

All fields have safe defaults — photos analyzed before new fields were added deserialize without errors. `PhotoAnalysisService` re-analyzes a photo only when `signals == null` or its `schemaVersion` is below `PhotoSignals.CURRENT_SCHEMA_VERSION` (bump that constant to force re-analysis after a meaningful schema change); valid, current analyses are reused with no vision call. `subjectType`…`negativeSpace` are objective composition fields; downstream code (visual roles, layout, saturation) is deterministic and never asks the model for hero/story/layout judgments.

The vision prompt is in `VISION_MODEL_PROMPT.md` and embedded in `OpenAIVisionClient`. `maxTokens = 400`, `temperature = 0.3`.

## Vision Model Retry Logic

`VisionModelService` wraps every OpenAI call with 3 retries and exponential backoff (1s, 2s, 5s). On total failure it returns a hardcoded fallback (`aestheticScore=0.5, blurScore=0.5, sceneType=MISC`, all new fields at neutral defaults) so book generation always completes.

## Storage

`StorageService` is an interface. The only implementation is `LocalStorageService`, which stores files at:
- Photos: `./uploads/photos/{tripId}/{photoId}.{ext}`
- PDFs: `./uploads/pdfs/{bookId}.pdf`

HEIC/HEIF uploads are auto-converted to JPEG via macOS `sips` before storage.

## OpenAI Config

API key and model (`gpt-4o-mini`) are in `src/main/resources/application.yml`. The Retrofit client is wired in `RetrofitConfig`. The vision prompt/schema is in `VISION_MODEL_PROMPT.md` at the repo root and is embedded in `OpenAIVisionClient`.

## Known Test Issues

`PhotoSelectorTest` passes in full. `AtlasoBackendApplicationTests.contextLoads` requires a running PostgreSQL (Flyway migrates on startup); it fails with a connection error when no DB is up — that's environmental, not a code failure.

## What's Not Implemented

- Authentication/authorization
- Cloud storage (S3/GCS) — local filesystem only
- Async photo analysis (currently synchronous, blocks book generation request)
- Book customization (title/subtitle editing, page reordering, captions)
- CORS (stub `WebConfig` exists but is empty)
