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

## Photo Selection Algorithm (`PhotoSelector`)

Four sequential phases — each phase feeds into the next:
1. **Quality filter** — drops photos with aesthetic score < 0.4, blur > 0.6, or dimensions < 1200px
2. **Burst dedup** — photos within 10 seconds of each other are clustered; only the highest-scoring survives
3. **Weighted scoring** — aesthetic (50%) + sharpness (20%) + scene variety bonus (10%) + orientation (10%) + lighting (5%) + time variety (5%)
4. **Diversity selection** — iteratively picks photos to hit scene-type targets: landscape 35%, people 30%, city 15%, food 10%, misc 10%

Output is sorted chronologically for narrative flow.

## Photo Grouping (`PhotoGrouper`)

After `PhotoSelector` picks the best ~30 photos, `PhotoGrouper` (in `application/layout/`) groups them into page-sized clusters before `LayoutEngine` assigns layouts. Each group becomes one page.

Similarity score between two photos (weighted sum):
- **Color harmony** (25%) — hex dominant colors converted to hue, circular distance mapped to compatibility score; `colorTemperature` field adds ±0.1 bonus/penalty
- **Time of day** (20%) — exact match = 1.0, adjacent categories = 0.5, opposite = 0.0
- **Object overlap** (20%) — Jaccard similarity on `detectedObjects`
- **Scene compatibility** (20%) — matrix-based: people+food = 0.7, landscape+food = 0.1, etc.
- **Temporal proximity** (15%) — exponential decay: `exp(-gapMinutes / 15)`

Grouping threshold: 0.55. Groups sorted chronologically by median `takenAt`. Single-photo orphans merge with nearest compatible neighbor (unless temporal gap > 3 hours).

## LayoutEngine

`LayoutEngine` takes `List<Photo>` (delegates internally to `PhotoGrouper`) or `List<PhotoGroup>` via `generatePagesFromGroups()`. Layout is chosen by group size (1→HERO/SINGLE, 2→TWO_HORIZONTAL, 3→THREE_GRID, 4→FOUR_GRID). In `THREE_GRID`, the featured top slot is assigned by `featuredScore`: aesthetic×0.5 + faces bonus + shallow DoF + golden hour + landscape orientation.

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

All fields have safe defaults — photos analyzed before new fields were added deserialize without errors.

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

`PhotoSelectorTest.should maintain scene type diversity` is a pre-existing failure — the test's `createPhoto()` helper uses `Instant.now()` for all photos, so they all land within the 10-second burst window, get collapsed to one photo by burst dedup, and the diversity assertion fails. Fix: space out `takenAt` timestamps by scene type in that test.

## What's Not Implemented

- Authentication/authorization
- Cloud storage (S3/GCS) — local filesystem only
- Async photo analysis (currently synchronous, blocks book generation request)
- Book customization (title/subtitle editing, page reordering, captions)
- CORS (stub `WebConfig` exists but is empty)
