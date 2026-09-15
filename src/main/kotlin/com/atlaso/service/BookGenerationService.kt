package com.atlaso.service

import com.atlaso.application.layout.BookPlanExplainer
import com.atlaso.application.layout.PhotoSelector
import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.book.PhotoSlot
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PageRepository
import com.atlaso.repository.PhotoRepository
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

@Service
@Transactional
class BookGenerationService(
    private val bookRepository: BookRepository,
    private val photoRepository: PhotoRepository,
    private val pageRepository: PageRepository,
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
    fun startGeneration(tripId: UUID, userId: UUID, coverCountry: String? = null, subtitle: String? = null): Book {
        val trip = tripService.getTrip(tripId, userId)
        val nextVersion = (bookRepository.findByTripIdOrderByVersionDesc(tripId).firstOrNull()?.version ?: 0) + 1
        // Persist the cover country at creation, so it's committed before the worker runs (which
        // reloads and preserves it) and before the ready-email fires — regardless of whether the
        // client stays on the generating tab to PATCH it later. Without this the cover renders
        // blank on a cold load from the email link.
        val book = bookRepository.save(
            Book(
                trip = trip,
                version = nextVersion,
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
     * Starts a regeneration asynchronously: creates a fresh GENERATING book that carries
     * the previous version's cover config forward, then processes it in the background.
     */
    fun startRegeneration(bookId: UUID, userId: UUID): Book {
        val existing = getBookForUser(bookId, userId)
        val trip = existing.trip
        val tripId = trip.id!!
        val nextVersion = (bookRepository.findByTripIdOrderByVersionDesc(tripId).firstOrNull()?.version ?: 0) + 1
        val book = bookRepository.save(
            Book(
                trip = trip,
                version = nextVersion,
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
        book.coverPhoto = selectionResult.photos.firstOrNull()

        val pages = layoutEngine.generatePages(selectionResult.photos)
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
        bookRepository.findById(bookId).ifPresent {
            it.status = BookStatus.FAILED
            bookRepository.save(it)
        }
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
}
