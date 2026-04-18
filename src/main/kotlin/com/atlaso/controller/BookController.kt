package com.atlaso.controller

import com.atlaso.controller.dto.BookResponse
import com.atlaso.service.BookGenerationService
import com.atlaso.service.PdfExportService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
    fun generateBook(
        @PathVariable tripId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = bookGenerationService.generateBook(tripId, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(BookResponse.from(book))
    }

    @GetMapping("/books/{bookId}")
    fun getBook(
        @PathVariable bookId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = bookGenerationService.getBookForUser(bookId, userId)
        return ResponseEntity.ok(BookResponse.from(book))
    }

    @PostMapping("/books/{bookId}/regenerate")
    fun regenerateBook(
        @PathVariable bookId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = bookGenerationService.regenerateBook(bookId, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(BookResponse.from(book))
    }

    @PostMapping("/books/{bookId}/export")
    fun exportBook(
        @PathVariable bookId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = pdfExportService.exportBook(bookId, userId)
        return ResponseEntity.ok(BookResponse.from(book))
    }

    @PatchMapping("/pages/{pageId}/slots/{slotIndex}/offset")
    fun updateSlotOffset(
        @PathVariable pageId: UUID,
        @PathVariable slotIndex: Int,
        @RequestBody body: SlotOffsetRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        val userId = UUID.fromString(jwt.subject)
        bookGenerationService.updateSlotOffset(pageId, slotIndex, body.offsetX, body.offsetY, userId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/books/{bookId}/pdf")
    fun downloadPdf(
        @PathVariable bookId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<ByteArray> {
        val userId = UUID.fromString(jwt.subject)
        val (pdfBytes, filename) = pdfExportService.loadPdf(bookId, userId)
        return ResponseEntity.ok()
            .contentType(MediaType.APPLICATION_PDF)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$filename\"")
            .body(pdfBytes)
    }
}
