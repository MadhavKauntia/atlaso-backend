# Plan: move vision calls out of the DB transaction

**Status:** Not started — parked for later.

## Goal
No Postgres connection is held across OpenAI I/O. The request-thread transaction
that currently spans the entire generation (minutes) is replaced by (a) a
transaction-free analysis phase and (b) one short transaction for selection +
layout + persistence. Removes the ~10-concurrent-generation Hikari cliff and
stops generations from starving unrelated endpoints.

## Current behavior (what we're changing)
- `BookGenerationService.generateBook` is `@Transactional` → opens connection
  **C1** on the request thread at method entry.
- It calls `photoAnalysisService.analyzeUnanalyzedPhotos(tripId)` on the same
  thread, then blocks on `futures.map { it.get() }`. C1 stays checked out (idle)
  the whole time.
- Latent hazard being fixed along the way: `Photo` entities are loaded on the
  request thread but mutated (`photo.signals = ...`) and `save()`d on **executor
  worker threads** (`PhotoAnalysisService.kt:50-53, 100-103`). The Spring/JPA
  persistence context is thread-bound and not inherited by the pool threads, so
  this is cross-thread entity sharing that happens to work only because the
  repository `save()` opens its own tx per worker. We'll make this correct by
  construction.

## Target shape
Two phases, neither holding a connection across network I/O:

1. **Analysis phase — no ambient transaction.** Orchestrate vision calls;
   persist each photo's signals in its own short `REQUIRES_NEW` transaction on
   the worker thread, keyed by photo id (reload → set → save → commit).
   Connections are held only for the millisecond-scale write, never across the
   OpenAI call.
2. **Assembly phase — one short transaction.** Reload analyzed photos, run
   `PhotoSelector` + `LayoutEngine` (pure, no I/O), persist `Book`/`Page`s, bump
   version, update trip status. This transaction does zero external I/O.

## Changes

### 1. New bean: `PhotoSignalsWriter`
```kotlin
@Service
class PhotoSignalsWriter(private val photoRepository: PhotoRepository) {
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun persist(photoId: UUID, signals: PhotoSignals) {
        val photo = photoRepository.findById(photoId).orElse(null) ?: return
        photo.signals = signals
        photo.analyzedAt = Instant.now()
        // save is redundant inside tx (dirty checking), keep for clarity
    }
}
```
Why a separate bean: `@Transactional` is proxy-based, and the current
`analyzePhoto` is called via self-invocation from a lambda, so a `REQUIRES_NEW`
annotation on it would be silently ignored. A distinct injected bean gets a real
proxy → real short transaction. Reloading by id inside the worker's own tx also
eliminates the cross-thread detached-entity hazard.

### 2. `PhotoAnalysisService`
- Remove the class-level `@Transactional` (line 18).
- `analyzeUnanalyzedPhotos` stays as the orchestrator but holds no transaction.
  The initial `findByTripIdAndSignalsIsNull` read is fine without an explicit tx
  (Spring Data opens a short one per call); the returned entities are used
  **read-only** as data carriers (need `id`, `storageKey`, `contentType`,
  `metadata.takenAt`, `metadata.sharpness`) — no cross-thread mutation.
- `analyzePhoto` (worker task) becomes: build access URL →
  `visionModelService.analyzePhoto(url)` (no tx) →
  `signalsWriter.persist(photo.id, toPhotoSignals(response))`. Drop the in-place
  `photo.signals = …; save()`.
- Keep the executor, burst pre-filter, and `toPhotoSignals` mapping unchanged.

### 3. `BookGenerationService.generateBook`
- Remove `@Transactional` from `generateBook` (currently inherited from the
  class annotation at line 20). Simplest: drop the class-level annotation and
  annotate only the methods that should be transactional.
- Sequence becomes:
  1. `tripService.getTrip(tripId, userId)` — its own short tx (TripService is
     already `@Transactional`).
  2. `photoAnalysisService.analyzeUnanalyzedPhotos(tripId)` — **no ambient tx**.
  3. Call a new `@Transactional fun assembleAndPersist(tripId, userId): Book`
     that does: `findByTripIdAndSignalsIsNotNull` → empty check → `PhotoSelector`
     → version calc → build `Book` → `LayoutEngine.generatePages` → persist →
     `tripService.updateStatus(...)`. Single short transaction, pure compute +
     writes.
- `regenerateBook` already delegates to `generateBook`, so it inherits the fix.
  Verify its trailing cover-config carry-forward `save` sits in a transaction
  (wrap that tail in the assembly tx or its own `@Transactional`).

### 4. Leave untouched
`saveCoverConfig`, `updateSlotOffset`, `updateSlotPhoto`, `getBookForUser`, etc.
keep their own `@Transactional` — they're already short and I/O-free.

## Concurrency note (still per-fix scope)
This fixes connection *holding*, not the global pool sizes. After this, a
generation only touches the DB in brief bursts, so default Hikari 10 is no longer
a hard cap on concurrent generations. The shared 20-thread vision pool and
account-global TPM (the other two contention points) remain — out of scope here,
but worth an explicit `spring.datasource.hikari.maximum-pool-size` once analysis
no longer pins connections.

## Testing / verification
- **Unit**: existing `PhotoSelectorTest` unaffected (pure). Add a test that
  `PhotoSignalsWriter.persist` commits independently (a failing later photo
  doesn't roll back an earlier one) — a real improvement over today's
  all-or-nothing tx.
- **Behavioral**: assert `generateBook` runs with no transaction active during
  vision calls (e.g. `TransactionSynchronizationManager.isActualTransactionActive()`
  is false inside a mocked `visionModelService.analyzePhoto`).
- **Manual/load**: fire N=15 concurrent `POST /book/generate` against 15 trips;
  before the change the 11th+ block on Hikari and unrelated endpoints time out;
  after, they proceed and other endpoints stay responsive.
- Confirm partial-failure semantics: a photo whose vision call exhausts retries
  writes fallback signals in its own tx (unchanged from `VisionModelService`),
  and no longer risks rolling back the whole batch.

## Risks / call-outs
- **Partial analysis now commits incrementally.** If assembly fails after
  analysis, signals persist — which is actually the documented intent
  ("regenerate is analysis-free"); today a late failure rolls *everything* back.
  Behavior improvement, but worth noting.
- **`toPhotoSignals` runs on the worker thread** — pure mapping, fine.
- Ensure no lazy-loaded association on `Photo` is touched on a worker thread
  outside a tx (the read-only carrier fields above are all eager/JSONB, so safe —
  verify `metadata`/`storageKey` aren't lazy).
