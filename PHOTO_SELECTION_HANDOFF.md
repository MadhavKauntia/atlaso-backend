# Photo-Selection Redesign — Handoff

Continuation doc for the Atlaso photobook selection/layout rework. Written mid-project (context ran out).

## Goal
Make the auto-generated book reflect the *actual trip*, kill near-dupes/repetition, drop mundane
utility shots, and give the layout editorial variety. Driven by 5 user requirements.

## Decisions locked with the user (do not re-litigate)
1. **Fixed 50 pages, always** (hard requirement). A small trip fills 50 via bigger layouts / last-resort near-dupes, never a short book.
2. **No category quotas.** Mix reflects the trip. Never drop a strong distinct memory to hit a %.
3. **Event/activity-proportional selection**: allocate slots proportional to how much was shot per activity, with a **floor** (every event keeps its best) and a **cap** (no event dominates).
4. **Dedup:** keep **1** per near-dup cluster; keep a **2nd only if clearly distinct** (low object overlap), not merely high-scoring.
5. **Repetition cap applies to OBJECTS/food/landmarks ONLY — NEVER people.** Same person recurring across activities is desired; the vision model can't identify individuals anyway. People variety = event spread + near-dup dedup.

## DONE and DEPLOYED to prod (Phase 1) — commits `2256734`, `fa032a4`
`src/main/kotlin/com/atlaso/application/layout/PhotoSelector.kt`
- Removed `sceneTypeTargets` quota → `selectPhotos` is now event-proportional (`clusterEvents` + floor + cap). `DiversityConfig` = `targetPhotos`, `floorPerEvent=1`, `maxEventShare=0.22`.
- Stronger dedup: `dedupeBursts` is now **time-window-independent** (a cluster extends while consecutive frames stay within `BurstConfig.timeWindowSeconds=90` AND remain near-identical). `isNearDuplicate` = strict AND (subjectType, shotDistance, facesCount, location, `objectJaccard≥0.5`). Added `rankScore(photo)` (static quality for ranking).
- Kept `scorePhoto`/`calculateVarietyBonus`/`PhotoScore`/`ScoreBreakdown` (used by tests + `PhotoScoreExplainer`), now off the main path.

`src/main/kotlin/com/atlaso/application/layout/PhotoGrouper.kt`
- `FILL_RATIO 1.6 → 1.0` (only pull dupes back when <1 distinct photo per page).
- Within-episode dedup: keep 2nd only if `isClearlyDistinct` (`objectJaccard<0.6`), not the old 0.85 strength ratio.
- **Hard-50 fix**: when short of 50 pages, pull back the **best available surplus regardless of grid-worthiness** (grid-worthy first, weaker last). Guarantees 50 pages when trip has ≥50 photos.

Tests (`PhotoSelectorTest.kt`) updated; the 3 long-standing small-input fallback failures fixed. All layout tests green, bootJar OK.

## DONE locally (prompt phase A+B) — built + unit tests green, NOT deployed, NOT yet verified via re-analysis
This is what every "bad photo" the user flagged reduces to. Two root causes → both fixed:

**A. Mundane/utility shots pass** (sharp + ~0.7–0.8 aesthetic, so nothing filters them):
bike-lock code, shoes-on-floor, storefronts, bakeries, a lone coffee cup.
→ Added **`keepsakeInterest`** (0–1) vision signal. `PhotoSelector` **hard-drops** in `filterLowQuality` when `< QualityThresholds.minKeepsakeInterest` (0.3) AND folds a **ranking penalty multiplier** `0.4 + 0.6·keepsake` into `rankScore` (so a merely-crisp mundane frame never out-ranks the real memory beside it). Neutral default 0.5 → pre-signal photos survive.

**B. Repetition + brittle dedup** (8 coconut photos survived one café session — inconsistent vision signals broke the strict AND-match).
→ Added stable **`primarySubject`** label. New `PhotoSelector.capRepeatedSubjects` keeps the best `DiversityConfig.maxSameSubject` (3) per **non-people** subject across the whole book (runs as Phase 2b, after dedup, before the 50/200 size branch). People exempt via `isPeopleSubject` (`subjectType` person/couple/group, or `sceneType==people`, or `primarySubject=="people"`), per decision #5. Prompt tells the model to emit exactly `"people"` for person shots.

Files touched: `PhotoSignals.kt` (+2 fields, neutral defaults), `PhotoAnalysisResponse.kt` (+2 `@JsonProperty`, range-check on keepsake), `PhotoAnalysisService.toPhotoSignals` (mapping, lowercases/trims primarySubject), `OpenAIVisionClient` SYSTEM_PROMPT + `VISION_MODEL_PROMPT.md` (schema+rules; closing line now permits keepsake as the one subjective judgment), `PhotoSelector.kt` (filter drop, rankScore multiplier, cap pass, `PEOPLE_SUBJECT_TYPES`, config fields), `CLAUDE.md` (field table).

**Re-analysis approach chosen: manual SQL clear when testing** (no new re-analyze code). To verify on Bali: clear `signals` for the trip via `/prod-sql`, then user regenerates in UI → new signals populate. Prompt NOT yet shown-for-approval/deployed (deploy-only-on-request).

Still pending (user requirements ④⑤ — deferred, not started this pass):
- **④ `SINGLE_FRAMED` as a first-class layout** (white-margin single, for variety — not just crop-safety fallback).
- **⑤ Editorial layout balance**: cap any single layout's share, avoid same layout on consecutive pages, build "hero single ⟷ distinct-4 grid" spreads. (See `LayoutEngine`.)
- Tilt/horizon signal so a tilted food shot is penalised.

### Implementation notes
- New signals go in `PhotoSignals` (`src/main/kotlin/com/atlaso/domain/photo/PhotoSignals.kt`), a JSONB data class — **new fields with defaults deserialize fine, NO DB migration needed.**
- Vision prompt lives in `VISION_MODEL_PROMPT.md` (repo root) AND embedded in `OpenAIVisionClient` (`maxTokens=400, temp=0.3`). Update both. Show the user the prompt before deploying (agreed).
- ⚠️ **GOTCHA — re-analysis:** `BookGenerationService.runGeneration` calls `analyzeUnanalyzedPhotos` which **skips already-analyzed photos**. Changing the prompt does NOT re-score existing photos. To see the new signals you must force re-analysis (e.g. clear `signals` for the trip, or add a re-analyze path). Handle this or the prompt change won't take effect on the Bali test trip.

## Test data (live prod DB — NOT empty; user has trips)
- **Bali** trip `e854c7ba-46d4-4bd1-befe-412ba698f4a3`, latest book **v5 `0e24cef8-129d-4426-b180-d3daedb61ab7`** (50 pages). ~999 photos across 3 trips, ~962 analyzed.
- Culprit photos (for verifying the fix): bike lock `b222f369` (aes 0.5, `bicycle lock, foot, ground`), shoes `3d3c2a99` (`shoes, floor`), storefront `eb0ea58b` (aes 0.8), 8 coconuts on 2025-10-10 11:05–11:34.

## Current config values
- `QualityThresholds`: minAesthetic 0.4, maxBlur 0.6, minDimension 1200, **minKeepsakeInterest 0.3** (NEW), `excludedObjectTags` (menu/receipt/text/screenshot/document/whiteboard/qr code/signage/poster/brochure/price tag/label/ticket/boarding pass/passport/booking). NOTE: `sign`, `phone`, `book` deliberately NOT excluded.
- `ScoringWeights`: aesthetic 0.65, sharpness 0.12, sceneVariety 0.09, timeVariety 0.05, orientation 0.05, lighting 0.04.
- `BurstConfig.timeWindowSeconds` 90. `CoverageConfig.eventGapMinutes` 60.
- `DiversityConfig`: targetPhotos 30 (called with 200), floorPerEvent 1, maxEventShare 0.22, **maxSameSubject 3** (NEW).
- `PhotoGrouper`: TARGET_PAGE_COUNT 50, MAX_PHOTOS_PER_PAGE 4, HERO_THRESHOLD 0.75, HERO_PROMOTE_THRESHOLD 0.62, GRID_MIN_AESTHETIC 0.55, FILL_RATIO 1.0, EPISODE_THRESHOLD 0.55, DUP_KEEP_SECOND_RATIO (now unused).
- `PhotoSignals` fields: aestheticScore, blurScore, sceneType, timeOfDay, facesCount, dominantColors, detectedObjects, colorTemperature, mood, depthOfField, isBlurry, subjectType, shotDistance, locationTag, subjectProminence, backgroundComplexity, negativeSpace, settingScope, **keepsakeInterest (0.5 default), primarySubject (null default)** (NEW).

## Ops / workflow
- **Deploy backend:** `railway up --detach` from `atlaso-backend` (NOT git push). ⚠️ deploys the WORKING DIRECTORY (uncommitted changes ship too). Then verify build Online + `Started AtlasoBackendApplication` + no Flyway error + health 200/401. Slash command: **`/deploy-backend`**.
- **Prod DB:** pipe SQL into `railway connect Postgres` (hardcoded psql creds rotate & fail). Slash commands: **`/prod-sql "<query>"`**, **`/book-status <bookId|tripId>`**, **`/clear-data`**.
- **Regenerate:** user does it in the UI ("Regenerate layout"); the regenerate endpoint needs the owner JWT (we can't trigger it). Then inspect the new book from the DB.
- The frontend layout renderer + how slots crop: `LayoutEngine` at `src/main/kotlin/com/atlaso/service/LayoutEngine.kt` (NOT in application/layout). `orderSlotsForLayout` matches orientation to slot shape; grids cover-crop to fixed cells.

## Don't touch / be aware
- User has **local uncommitted edits** in `EmailService.kt` + `BookGenerationService.kt` (adding a cover image to the book-ready email — `coverPhotoId`/`coverUrl`). Do NOT revert.
- Deploy-only-on-request rule still applies; don't commit/push/deploy unless asked.
