package com.atlaso.controller

import com.atlaso.controller.dto.BookResponse
import com.atlaso.service.BookGenerationService
import com.atlaso.service.PdfExportService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class SlotOffsetRequest(val offsetX: Double, val offsetY: Double)

@RestController
@RequestMapping("/api")
class BookController(
    private val bookGenerationService: BookGenerationService,
    private val pdfExportService: PdfExportService
) {

    @PostMapping("/trips/{tripId}/book/generate")
    fun generateBook(@PathVariable tripId: UUID): ResponseEntity<BookResponse> {
        val book = bookGenerationService.generateBook(tripId)
        return ResponseEntity.status(HttpStatus.CREATED).body(BookResponse.from(book))
    }

    @GetMapping("/books/{bookId}")
    fun getBook(@PathVariable bookId: UUID): ResponseEntity<BookResponse> {
        val book = bookGenerationService.getBook(bookId)
        return ResponseEntity.ok(BookResponse.from(book))
    }

    @PostMapping("/books/{bookId}/regenerate")
    fun regenerateBook(@PathVariable bookId: UUID): ResponseEntity<BookResponse> {
        val book = bookGenerationService.regenerateBook(bookId)
        return ResponseEntity.status(HttpStatus.CREATED).body(BookResponse.from(book))
    }

    @PostMapping("/books/{bookId}/export")
    fun exportBook(@PathVariable bookId: UUID): ResponseEntity<BookResponse> {
        val book = pdfExportService.exportBook(bookId)
        return ResponseEntity.ok(BookResponse.from(book))
    }

    @PatchMapping("/pages/{pageId}/slots/{slotIndex}/offset")
    fun updateSlotOffset(
        @PathVariable pageId: UUID,
        @PathVariable slotIndex: Int,
        @RequestBody body: SlotOffsetRequest
    ): ResponseEntity<Void> {
        bookGenerationService.updateSlotOffset(pageId, slotIndex, body.offsetX, body.offsetY)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/books/{bookId}/pdf")
    fun downloadPdf(@PathVariable bookId: UUID): ResponseEntity<ByteArray> {
        val (pdfBytes, filename) = pdfExportService.loadPdf(bookId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(pdfBytes)
    }
}
