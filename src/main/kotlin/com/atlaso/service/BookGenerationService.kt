package com.atlaso.service

import com.atlaso.application.layout.PhotoSelector
import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.book.PhotoSlot
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PageRepository
import com.atlaso.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
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
    private val tripService: TripService
) {
    private val logger = LoggerFactory.getLogger(BookGenerationService::class.java)

    fun generateBook(tripId: UUID, userId: UUID): Book {
        val trip = tripService.getTrip(tripId, userId)

        photoAnalysisService.analyzeUnanalyzedPhotos(tripId)

        val analyzedPhotos = photoRepository.findByTripIdAndSignalsIsNotNull(tripId)
        if (analyzedPhotos.isEmpty()) {
            throw NoPhotosAvailableException(tripId)
        }

        val selectionResult = photoSelector.selectPhotosForBook(analyzedPhotos)
        logger.info("Selected {} photos for book", selectionResult.photos.size)

        val existingBooks = bookRepository.findByTripIdOrderByVersionDesc(tripId)
        val nextVersion = (existingBooks.firstOrNull()?.version ?: 0) + 1

        val book = Book(
            trip = trip,
            version = nextVersion,
            title = trip.name,
            subtitle = trip.destination,
            coverPhoto = selectionResult.photos.firstOrNull(),
            status = BookStatus.GENERATING
        )

        val pages = layoutEngine.generatePages(selectionResult.photos)
        pages.forEach { page -> book.addPage(page) }

        book.status = BookStatus.READY_FOR_PREVIEW
        val saved = bookRepository.save(book)
        logger.info("Generated book: {} (v{}) with {} pages", saved.id, saved.version, saved.pages.size)

        tripService.updateStatus(tripId, TripStatus.BOOK_GENERATED)

        return saved
    }

    fun regenerateBook(bookId: UUID, userId: UUID): Book {
        val existingBook = getBookForUser(bookId, userId)
        return generateBook(existingBook.trip.id!!, userId)
    }

    @Transactional(readOnly = true)
    fun getBookForUser(bookId: UUID, userId: UUID): Book {
        return bookRepository.findByIdAndTripUserId(bookId, userId)
            .orElseThrow { BookNotFoundException(bookId) }
    }

    // Internal use only — called by PdfExportService after ownership is already verified at controller level
    @Transactional(readOnly = true)
    internal fun getBook(bookId: UUID): Book {
        return bookRepository.findById(bookId)
            .orElseThrow { BookNotFoundException(bookId) }
    }

    @Transactional
    fun saveCoverConfig(bookId: UUID, userId: UUID, templateId: String, paletteId: String): Book {
        val book = bookRepository.findByIdAndTripUserId(bookId, userId)
            .orElseThrow { BookNotFoundException(bookId) }
        book.coverTemplateId = templateId
        book.coverPaletteId = paletteId
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
}
