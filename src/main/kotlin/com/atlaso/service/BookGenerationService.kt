package com.atlaso.service

import com.atlaso.application.layout.BookPlanExplainer
import com.atlaso.application.layout.PhotoSelector
import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.book.Layout
import com.atlaso.domain.book.PhotoSlot
import com.atlaso.domain.book.slotCount
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.trip.TripStatus
import com.atlaso.domain.trip.Trip
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PageRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Lazy
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionSynchronization
import org.springframework.transaction.support.TransactionSynchronizationManager
import java.util.UUID

class BookNotFoundException(id: UUID) : RuntimeException("Book not found: $id")
class NoPhotosAvailableException(tripId: UUID) : RuntimeException("No photos available for trip: $tripId")
class FreePreviewQuotaExceededException(userId: UUID) : RuntimeException("Free preview quota exhausted for user: $userId")
class InsufficientPhotosException(val needed: Int, val available: Int) :
    RuntimeException("Not enough unused photos to fill this layout: need $needed more, $available available")

@Service
@Transactional
class BookGenerationService(
    private val bookRepository: BookRepository,
    private val photoRepository: PhotoRepository,
    private val pageRepository: PageRepository,
    private val userRepository: UserRepository,
    private val tripRepository: TripRepository,
    private val photoAnalysisService: PhotoAnalysisService,
    private val photoSelector: PhotoSelector,
    private val layoutEngine: LayoutEngine,
    private val tripService: TripService,
    private val bookPlanExplainer: BookPlanExplainer,
    private val emailService: EmailService,
    private val storageService: StorageService,
    // @Lazy breaks the BookGenerationService <-> BookGenerationProcessor construction cycle.
    @Lazy private val processor: BookGenerationProcessor,
    // Dev switch: log the full spread-by-spread plan after every generation. Enable
    // locally with ATLASO_DEBUG_LOG_BOOK_PLAN=true (kept off in prod to avoid log spam).
    @Value("\${atlaso.debug.log-book-plan:false}") private val logBookPlan: Boolean = false
) {
    private val logger = LoggerFactory.getLogger(BookGenerationService::class.java)

    /**
     * Starts book generation asynchronously. Creates a placeholder book in GENERATING
     * status, commits it, and hands the heavy work (analysis + selection + layout) to a
     * background thread. Returns immediately so the HTTP request never spans the minutes
     * of vision analysis. The client polls GET /books/{id} until status flips to
     * READY_FOR_PREVIEW (or FAILED).
     */
    @Transactional
    fun startGeneration(tripId: UUID, userId: UUID, coverCountry: String? = null, subtitle: String? = null): Book {
        tripService.getTrip(tripId, userId) // ownership check
        // Serialize all of a trip's generation/upload activity on the trip row (uploads take the
        // same lock). This makes the version calc, the quota reservation, and the photo-set lock
        // race-free: concurrent submits queue here rather than double-charging or duplicating v1.
        val trip = tripRepository.findByIdForUpdate(tripId).orElseThrow { IllegalStateException("Trip not found: $tripId") }
        // A rapid double-submit of the first generation returns the in-flight book instead of
        // starting a second worker (the unique (trip_id, version) constraint is the final guard).
        val latest = bookRepository.findByTripIdOrderByVersionDesc(tripId).firstOrNull()
        if (latest != null && latest.status == BookStatus.GENERATING) return latest

        reservePreviewIfNeeded(trip, userId)

        // Persist the cover country at creation, so it's committed before the worker runs (which
        // reloads and preserves it) and before the ready-email fires — regardless of whether the
        // client stays on the generating tab to PATCH it later. Without this the cover renders
        // blank on a cold load from the email link.
        val book = bookRepository.save(
            Book(
                trip = trip,
                version = (latest?.version ?: 0) + 1,
                title = trip.name,
                subtitle = subtitle?.takeIf { it.isNotBlank() } ?: trip.destination,
                status = BookStatus.GENERATING,
                coverCountry = coverCountry?.takeIf { it.isNotBlank() }
            )
        )
        val bookId = book.id!!
        // Only kick off the worker once this book row is actually committed, so the
        // background thread's fresh transaction can find it.
        afterCommit { processor.process(bookId, tripId) }
        logger.info("Started async generation: book {} (v{}) for trip {}", bookId, book.version, tripId)
        return book
    }

    /**
     * Consumes one free preview for [trip]'s photo analysis, exactly once per trip. The reservation
     * is durable ([Trip.previewChargedAt]) and held across retries/regenerations, so a
     * failed-then-retried generation can't run paid analysis without a charge. Throws (→ 402) when
     * the user is out of previews. Must be called while holding the trip's pessimistic lock.
     */
    private fun reservePreviewIfNeeded(trip: Trip, userId: UUID) {
        if (trip.previewChargedAt != null) return // already reserved for this trip
        if (userRepository.tryConsumeFreePreview(userId) == 0) throw FreePreviewQuotaExceededException(userId)
        trip.previewChargedAt = java.time.Instant.now()
        tripRepository.save(trip)
    }

    /**
     * Starts a regeneration asynchronously: creates a fresh GENERATING book that carries
     * the previous version's cover config forward, then processes it in the background.
     */
    @Transactional
    fun startRegeneration(bookId: UUID, userId: UUID): Book {
        val existing = getBookForUser(bookId, userId)
        val tripId = existing.trip.id!!
        // Same per-trip lock as first generation, so a regeneration can't race another
        // generation on the next version number.
        val trip = tripRepository.findByIdForUpdate(tripId).orElseThrow { IllegalStateException("Trip not found: $tripId") }
        // If a generation is already in flight for this trip, return it instead of starting a
        // second worker: the lock is released once each placeholder commits, but the async worker
        // keeps running without it, so spamming regenerate would otherwise re-analyse the same
        // photos in parallel (duplicate paid vision calls the version constraint can't stop).
        val latest = bookRepository.findByTripIdOrderByVersionDesc(tripId).firstOrNull()
        if (latest != null && latest.status == BookStatus.GENERATING) return latest
        // Normally a no-op (the trip was reserved at its first generation). Kept so a trip that
        // somehow never reserved still can't regenerate free of charge.
        reservePreviewIfNeeded(trip, userId)
        val book = bookRepository.save(
            Book(
                trip = trip,
                version = (latest?.version ?: 0) + 1,
                title = existing.title,
                subtitle = existing.subtitle,
                status = BookStatus.GENERATING,
                coverCountry = existing.coverCountry,
                coverTemplateId = existing.coverTemplateId,
                coverPaletteId = existing.coverPaletteId
            )
        )
        val newBookId = book.id!!
        afterCommit { processor.process(newBookId, tripId) }
        logger.info("Started async regeneration: book {} (v{}) for trip {}", newBookId, book.version, tripId)
        return book
    }

    /**
     * The heavy generation work, run on a background thread. Reloads the placeholder book,
     * analyzes photos, selects and lays them out, fills the book's pages, and flips it to
     * READY_FOR_PREVIEW. Throws on failure so the caller can mark the book FAILED.
     */
    @Transactional
    fun runGeneration(bookId: UUID, tripId: UUID) {
        photoAnalysisService.analyzeUnanalyzedPhotos(tripId)

        val analyzedPhotos = photoRepository.findByTripIdAndSignalsIsNotNull(tripId)
        if (analyzedPhotos.isEmpty()) {
            throw NoPhotosAvailableException(tripId)
        }

        val selectionResult = photoSelector.selectPhotosForBook(analyzedPhotos)
        logger.info("Selected {} photos for book {}", selectionResult.photos.size, bookId)

        val book = bookRepository.findById(bookId).orElseThrow { BookNotFoundException(bookId) }

        val pages = layoutEngine.generatePages(selectionResult.photos)
        // The cover follows the curated opener (page 1's featured photo) so it's the book's
        // strongest arrival shot, not merely the earliest photo. Falls back to first chronological.
        val openerPhotoId = pages.firstOrNull()?.slots?.firstOrNull()?.photoId
        book.coverPhoto = openerPhotoId?.let { id -> selectionResult.photos.firstOrNull { it.id == id } }
            ?: selectionResult.photos.firstOrNull()
        pages.forEach { page -> book.addPage(page) }

        book.status = BookStatus.READY_FOR_PREVIEW
        bookRepository.save(book)
        logger.info("Generated book: {} (v{}) with {} pages", book.id, book.version, book.pages.size)

        if (logBookPlan) {
            val byId = selectionResult.photos.mapNotNull { p -> p.id?.let { it to p } }.toMap()
            logger.info("Book plan for {}:\n{}", book.id, bookPlanExplainer.explain(pages, byId))
        }

        tripService.updateStatus(tripId, TripStatus.BOOK_GENERATED)
    }

    /** Marks a book FAILED (its own transaction) so a failed background run is visible to the client. */
    @Transactional
    fun markFailed(bookId: UUID) {
        // One-time GENERATING -> FAILED transition (idempotent across repeated failure callbacks).
        // No quota is refunded: the trip's preview reservation is durable and covers retries, so a
        // failed-then-retried generation can't perform paid analysis without a charge.
        bookRepository.markFailedIfGenerating(bookId, BookStatus.GENERATING, BookStatus.FAILED)
    }

    /**
     * Emails the trip owner that their book is ready — once, only for the INITIAL generation
     * (version 1). Called after [runGeneration] commits so a user who closed the tab still gets
     * the preview link. Idempotent via [Book.readyEmailSentAt]; email failures never surface.
     */
    @Transactional
    fun sendBookReadyEmailIfNeeded(bookId: UUID) {
        val book = bookRepository.findById(bookId).orElse(null) ?: return
        if (book.status != BookStatus.READY_FOR_PREVIEW) return
        if (book.version != 1) return              // regenerations don't re-notify
        if (book.readyEmailSentAt != null) return  // already sent

        val owner = book.trip.user
        val email = owner?.email?.takeIf { it.isNotBlank() }
        if (email == null) {
            logger.info("Book {} has no owner email — skipping book-ready email", bookId)
            return
        }

        // Claim the send before dispatching so a retry can't double-send; the email call itself
        // is best-effort and swallows its own errors.
        book.readyEmailSentAt = java.time.Instant.now()
        bookRepository.save(book)
        emailService.sendBookReadyEmail(email, owner.name, book.trip.id!!, bookId, book.title, book.coverPhoto?.id)
    }

    /**
     * Loads the cover image bytes for [bookId] with NO ownership check — the book UUID itself is
     * the capability (same posture as the emailed preview link). Backs the public cover endpoint so
     * the book-ready email can render the cover without the recipient being authenticated. Prefers
     * the small thumbnail derivative. Returns null when the book or its cover photo is missing.
     */
    @Transactional(readOnly = true)
    fun loadCoverImage(bookId: UUID): Pair<ByteArray, String>? {
        val book = bookRepository.findById(bookId).orElse(null) ?: return null
        val photo = book.coverPhoto ?: return null
        val thumb = photo.thumbnailKey
        val key = thumb ?: photo.storageKey
        val contentType = if (thumb != null) "image/jpeg" else photo.contentType
        return storageService.load(key) to contentType
    }

    /** Runs [action] after the current transaction commits (or immediately if none is active). */
    private fun afterCommit(action: () -> Unit) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(object : TransactionSynchronization {
                override fun afterCommit() = action()
            })
        } else {
            action()
        }
    }

    @Transactional(readOnly = true)
    fun getBookForUser(bookId: UUID, userId: UUID): Book {
        return bookRepository.findByIdAndTripUserId(bookId, userId)
            .orElseThrow { BookNotFoundException(bookId) }
    }

    /**
     * Renders the book's layout as a spread-by-spread text plan (with a rule-compliance
     * header) for eyeballing the algorithm without the frontend. Dev/debug aid.
     */
    @Transactional(readOnly = true)
    fun getBookPlan(bookId: UUID, userId: UUID): String {
        val book = getBookForUser(bookId, userId)
        val photosById = photoRepository.findByTripId(book.trip.id!!).mapNotNull { p -> p.id?.let { it to p } }.toMap()
        return bookPlanExplainer.explain(book.pages, photosById)
    }

    @Transactional(readOnly = true)
    fun getLatestBookByTripId(tripId: UUID, userId: UUID): Book {
        val books = bookRepository.findByTripIdOrderByVersionDesc(tripId)
        return books.firstOrNull { it.trip.user?.id == userId }
            ?: throw BookNotFoundException(tripId)
    }

    // Internal use only — called by PdfExportService after ownership is already verified at controller level
    @Transactional(readOnly = true)
    internal fun getBook(bookId: UUID): Book {
        return bookRepository.findById(bookId)
            .orElseThrow { BookNotFoundException(bookId) }
    }

    @Transactional
    fun saveCoverConfig(
        bookId: UUID,
        userId: UUID,
        templateId: String?,
        paletteId: String?,
        country: String? = null,
        subtitle: String? = null
    ): Book {
        val book = bookRepository.findByIdAndTripUserId(bookId, userId)
            .orElseThrow { BookNotFoundException(bookId) }
        templateId?.let { book.coverTemplateId = it }
        paletteId?.let { book.coverPaletteId = it }
        country?.let { book.coverCountry = it }
        subtitle?.let { book.subtitle = it.ifBlank { null } }
        return bookRepository.save(book)
    }

    fun updateSlotOffset(pageId: UUID, slotIndex: Int, offsetX: Double, offsetY: Double, userId: UUID) {
        val page = pageRepository.findById(pageId)
            .orElseThrow { RuntimeException("Page not found: $pageId") }
        if (page.book?.trip?.user?.id != userId) {
            throw RuntimeException("Page not found: $pageId")
        }
        require(slotIndex in page.slots.indices) { "Slot index $slotIndex out of range" }
        val updatedSlots = page.slots.toMutableList()
        updatedSlots[slotIndex] = updatedSlots[slotIndex].copy(
            offsetX = offsetX.coerceIn(0.0, 1.0),
            offsetY = offsetY.coerceIn(0.0, 1.0)
        )
        val updatedPage = page.copy(slots = updatedSlots)
        pageRepository.save(updatedPage)
        logger.info("Updated slot {}/{} offset to ({}, {})", pageId, slotIndex, offsetX, offsetY)
    }

    fun updateSlotPhoto(pageId: UUID, slotIndex: Int, photoId: UUID, userId: UUID) {
        val page = pageRepository.findById(pageId)
            .orElseThrow { RuntimeException("Page not found: $pageId") }
        val trip = page.book?.trip
        if (trip?.user?.id != userId) {
            throw RuntimeException("Page not found: $pageId")
        }
        require(slotIndex in page.slots.indices) { "Slot index $slotIndex out of range" }
        // The replacement photo must belong to the same trip.
        photoRepository.findByIdAndTripId(photoId, trip.id!!)
            .orElseThrow { PhotoNotFoundException(photoId) }
        val updatedSlots = page.slots.toMutableList()
        // New image gets fresh framing: recenter the crop and drop any rotation.
        updatedSlots[slotIndex] = updatedSlots[slotIndex].copy(
            photoId = photoId,
            offsetX = null,
            offsetY = null,
            rotation = 0
        )
        val updatedPage = page.copy(slots = updatedSlots)
        pageRepository.save(updatedPage)
        logger.info("Replaced slot {}/{} photo with {}", pageId, slotIndex, photoId)
    }

    /**
     * Switches a page to [newLayout] and returns the updated book. The photo count is fixed per
     * layout, so:
     *  - shrinking keeps the first N photos in order (the rest stay reachable via Replace);
     *  - growing pulls the extra photos from the trip's unused pool (chronological by upload),
     *    excluding every photo already placed anywhere in the book so a shot never appears twice —
     *    and throws [InsufficientPhotosException] when the pool can't cover the new slots;
     *  - same-count switches keep every photo.
     * Framing is recentred on every slot because the geometry (and each crop's aspect ratio) changes.
     */
    @Transactional
    fun changePageLayout(pageId: UUID, newLayout: Layout, userId: UUID): Book {
        // A double-page spread occupies two facing pages; it can't be applied to a single page.
        require(newLayout != Layout.DOUBLE_PAGE_FULL_BLEED) { "Layout $newLayout can't be applied to a single page" }

        val page = pageRepository.findById(pageId)
            .orElseThrow { RuntimeException("Page not found: $pageId") }
        val book = page.book
        val trip = book?.trip
        if (trip?.user?.id != userId) {
            throw RuntimeException("Page not found: $pageId")
        }

        val targetCount = newLayout.slotCount
        // Keep the first min(current, target) photos, in their existing order.
        val retained = page.slots.take(targetCount)

        val extraNeeded = targetCount - retained.size
        val fillers: List<Photo> = if (extraNeeded > 0) {
            val usedPhotoIds = book.pages.flatMap { it.slots }.mapTo(mutableSetOf()) { it.photoId }
            val pool = photoRepository.findByTripId(trip.id!!)
                .filter { it.id !in usedPhotoIds }
                .sortedBy { it.uploadedAt }
            if (pool.size < extraNeeded) throw InsufficientPhotosException(extraNeeded, pool.size)
            pool.take(extraNeeded)
        } else emptyList()

        val newSlots = buildList {
            // Retained photos keep their id + intrinsic rotation; only their frame is recentred.
            retained.forEachIndexed { index, slot ->
                val (position, size) = layoutEngine.getSlotGeometry(newLayout, index, targetCount)
                add(slot.copy(position = position, size = size, offsetX = null, offsetY = null))
            }
            fillers.forEachIndexed { i, photo ->
                val index = retained.size + i
                val (position, size) = layoutEngine.getSlotGeometry(newLayout, index, targetCount)
                add(PhotoSlot(photoId = photo.id!!, position = position, size = size, rotation = photo.rotation))
            }
        }

        pageRepository.save(page.copy(layout = newLayout, slots = newSlots))
        logger.info(
            "Changed page {} layout {} -> {} ({} slots, {} filled from pool)",
            pageId, page.layout, newLayout, targetCount, fillers.size
        )
        return getBookForUser(book.id!!, userId)
    }
}
