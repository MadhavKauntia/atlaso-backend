package com.atlaso.service

import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.repository.BookRepository
import com.atlaso.repository.PhotoRepository
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

@Service
@Transactional
class PdfExportService(
    private val bookRepository: BookRepository,
    private val bookGenerationService: BookGenerationService,
    private val photoRepository: PhotoRepository,
    private val storageService: StorageService,
    private val pdfRenderer: PdfRenderer,
) {
    private val logger = LoggerFactory.getLogger(PdfExportService::class.java)

    private companion object {
        const val IMAGE_LOAD_ATTEMPTS = 3
        const val IMAGE_LOAD_BACKOFF_MS = 200L
    }

    fun exportBook(bookId: UUID, userId: UUID, coverPng: ByteArray? = null, backPng: ByteArray? = null): Book {
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
                    // Retry transient S3 read failures before giving up — a single blip must
                    // not silently blank a slot in a book bound for print.
                    val imageBytes = if (photo == null) null else retryOrNull(
                        attempts = IMAGE_LOAD_ATTEMPTS,
                        backoffMs = IMAGE_LOAD_BACKOFF_MS,
                        onError = { attempt, e -> logger.warn("Load attempt {}/{} failed for photo {}", attempt, IMAGE_LOAD_ATTEMPTS, slot.photoId, e) }
                    ) { storageService.load(photo.storageKey) }
                    if (photo != null && imageBytes == null) {
                        logger.error("Could not load photo {} after {} attempts; slot will render as a placeholder", slot.photoId, IMAGE_LOAD_ATTEMPTS)
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
                        offsetY = slot.offsetY ?: 0.5,
                        zoomScale = slot.zoomScale ?: 1.0
                    )
                }
                PageRenderData(
                    pageNumber = page.pageNumber,
                    slots = slotDataList
                )
            }

            val contentPdfBytes = pdfRenderer.render(
                title = book.title,
                subtitle = book.subtitle,
                pages = pageRenderDataList
            )

            val coverPdfBytes = buildCoverPdf(book, coverPng, backPng)

            storageService.store("pdfs/${bookId}/cover.pdf", ByteArrayInputStream(coverPdfBytes), "application/pdf")
            storageService.store("pdfs/${bookId}/photobook.pdf", ByteArrayInputStream(contentPdfBytes), "application/pdf")
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

    /**
     * Cover PDF: page 1 = front cover, page 2 = hardcover back (same bg colour as the
     * cover with the atlaso wordmark + URL). Both pages are client-rendered PNGs. When
     * no client cover is supplied we fall back to the simple single-page text cover.
     */
    private fun buildCoverPdf(book: Book, coverPng: ByteArray?, backPng: ByteArray?): ByteArray {
        if (coverPng == null) {
            return pdfRenderer.renderCover(book.title, book.subtitle)
        }
        val document = PDDocument()
        try {
            addPngPage(document, coverPng, "cover")
            if (backPng != null) addPngPage(document, backPng, "back")
            val out = ByteArrayOutputStream()
            document.save(out)
            return out.toByteArray()
        } finally {
            document.close()
        }
    }

    /** Adds a full-bleed PNG page (trim size = the interior page size) to the document. */
    private fun addPngPage(document: PDDocument, pngBytes: ByteArray, name: String) {
        val image = PDImageXObject.createFromByteArray(document, pngBytes, name)
        val pageW = PdfRenderer.PAGE_WIDTH
        val pageH = PdfRenderer.PAGE_HEIGHT
        val page = PDPage(PDRectangle(pageW, pageH))
        document.addPage(page)
        PDPageContentStream(document, page).use { cs ->
            cs.drawImage(image, 0f, 0f, pageW, pageH)
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
