package com.atlaso.service

import com.atlaso.domain.book.Book
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.UUID

/**
 * Where a book's print PDF lives, and how to read it back.
 *
 * Books exported now have ONE file, `pdfs/{id}/book.pdf`: front cover, interior pages, back cover.
 * Books exported before that have two files (`cover.pdf` = front + back, `photobook.pdf` =
 * interior); [load] merges those on the fly into the same single-file order, without writing
 * anything back, so every download is one PDF whichever way the book was exported.
 */
@Component
class PrintPdfStore(private val storageService: StorageService) {

    companion object {
        fun key(bookId: UUID) = "pdfs/$bookId/book.pdf"
        fun legacyCoverKey(bookId: UUID) = "pdfs/$bookId/cover.pdf"
        fun legacyInteriorKey(bookId: UUID) = "pdfs/$bookId/photobook.pdf"

        /**
         * Builds the single-file order from a legacy pair: cover page 1 (front), every interior
         * page, then cover page 2 (back) when there is one. A legacy text-only cover had no back.
         */
        fun mergeLegacy(coverPdf: ByteArray, interiorPdf: ByteArray): ByteArray =
            Loader.loadPDF(coverPdf).use { cover ->
                Loader.loadPDF(interiorPdf).use { interior ->
                    require(cover.numberOfPages >= 1) { "Legacy cover PDF has no pages" }
                    PDDocument().use { out ->
                        // importPage shares the source page's resources; both sources stay open
                        // until save() below, which writes everything into the new file.
                        out.importPage(cover.getPage(0))
                        interior.pages.forEach { out.importPage(it) }
                        if (cover.numberOfPages >= 2) out.importPage(cover.getPage(1))
                        ByteArrayOutputStream().also { out.save(it) }.toByteArray()
                    }
                }
            }
    }

    /** Stores a freshly exported print PDF and returns its key (to save as the book's pdfUrl). */
    fun save(bookId: UUID, pdf: ByteArray): String {
        val key = key(bookId)
        storageService.store(key, ByteArrayInputStream(pdf), "application/pdf")
        return key
    }

    /** The book's print PDF as one file (merging a legacy cover + interior pair if needed). */
    fun load(book: Book): ByteArray {
        val id = book.id ?: throw IllegalStateException("Book has no id")
        return when (book.pdfUrl) {
            key(id) -> storageService.load(key(id))
            legacyInteriorKey(id) -> mergeLegacy(storageService.load(legacyCoverKey(id)), storageService.load(legacyInteriorKey(id)))
            null -> throw IllegalStateException("Book PDF is not available")
            else -> throw IllegalStateException("Unrecognised PDF location for book $id: ${book.pdfUrl}")
        }
    }
}
