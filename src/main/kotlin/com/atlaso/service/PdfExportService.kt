package com.atlaso.service

import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PhotoRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.util.UUID

@Service
@Transactional
class PdfExportService(
    private val bookRepository: BookRepository,
    private val bookGenerationService: BookGenerationService,
    private val photoRepository: PhotoRepository,
    private val storageService: StorageService,
    private val pdfRenderer: PdfRenderer
) {
    private val logger = LoggerFactory.getLogger(PdfExportService::class.java)

    fun exportBook(bookId: UUID, userId: UUID): Book {
        val book = bookGenerationService.getBookForUser(bookId, userId)

        if (book.status != BookStatus.READY_FOR_PREVIEW && book.status != BookStatus.FAILED && book.status != BookStatus.PDF_READY) {
            throw IllegalStateException(
                "Book $bookId cannot be exported in status ${book.status}. " +
                    "Expected READY_FOR_PREVIEW, PDF_READY, or FAILED."
            )
        }

        book.status = BookStatus.EXPORTING_PDF
        bookRepository.save(book)

        try {
            val pageRenderDataList = book.pages.map { page ->
                val slotDataList = page.slots.map { slot ->
                    val photo = try {
                        photoRepository.findById(slot.photoId).orElse(null)
                    } catch (e: Exception) {
                        logger.warn("Failed to find photo {} for slot", slot.photoId, e)
                        null
                    }
                    val imageBytes = try {
                        if (photo != null) storageService.load(photo.storageKey) else null
                    } catch (e: Exception) {
                        logger.warn("Failed to load photo {} for slot", slot.photoId, e)
                        null
                    }
                    SlotRenderData(
                        x = slot.position.x,
                        y = slot.position.y,
                        width = slot.size.width,
                        height = slot.size.height,
                        imageBytes = imageBytes,
                        caption = slot.caption,
                        rotation = slot.rotation,
                        offsetX = slot.offsetX ?: 0.5,
                        offsetY = slot.offsetY ?: 0.5
                    )
                }
                PageRenderData(
                    pageNumber = page.pageNumber,
                    slots = slotDataList
                )
            }

            val pdfBytes = pdfRenderer.render(
                title = book.title,
                subtitle = book.subtitle,
                pages = pageRenderDataList
            )

            val coverBytes = pdfRenderer.renderCover(book.title, book.subtitle)
            storageService.store("pdfs/${bookId}/cover.pdf", ByteArrayInputStream(coverBytes), "application/pdf")
            storageService.store("pdfs/${bookId}/photobook.pdf", ByteArrayInputStream(pdfBytes), "application/pdf")
            logger.info("Saved cover and photobook PDFs for book {}", bookId)

            book.pdfUrl = "pdfs/${bookId}/photobook.pdf"
            book.status = BookStatus.PDF_READY
            return bookRepository.save(book)
        } catch (e: Exception) {
            logger.error("PDF export failed for book {}", bookId, e)
            book.status = BookStatus.FAILED
            bookRepository.save(book)
            throw e
        }
    }

    @Transactional(readOnly = true)
    fun loadPdf(bookId: UUID, userId: UUID): Pair<ByteArray, String> {
        val book = bookGenerationService.getBookForUser(bookId, userId)

        if (book.pdfUrl == null || book.status != BookStatus.PDF_READY) {
            throw IllegalStateException("PDF is not available for book $bookId")
        }

        val bytes = storageService.load("pdfs/${bookId}/photobook.pdf")
        val filename = "${book.title.replace(Regex("[^a-zA-Z0-9._-]"), "_")}.pdf"
        return Pair(bytes, filename)
    }
}
