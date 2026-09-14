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
data class SlotPhotoRequest(val photoId: UUID)
data class CoverConfigRequest(
    val templateId: String? = null,
    val paletteId: String? = null,
    val country: String? = null,
    val subtitle: String? = null
)
data class ExportBookRequest(val coverImageBase64: String?, val backImageBase64: String? = null)

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
        // Async: returns a GENERATING book immediately; the client polls GET /books/{id}
        // until status is READY_FOR_PREVIEW (or FAILED).
        val book = bookGenerationService.startGeneration(tripId, userId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BookResponse.from(book))
    }

    @GetMapping("/trips/{tripId}/book")
    fun getLatestBookForTrip(
        @PathVariable tripId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = bookGenerationService.getLatestBookByTripId(tripId, userId)
        return ResponseEntity.ok(BookResponse.from(book))
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
        // Async: returns a fresh GENERATING book immediately; the client polls until ready.
        val book = bookGenerationService.startRegeneration(bookId, userId)
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(BookResponse.from(book))
    }

    @PostMapping("/books/{bookId}/export")
    fun exportBook(
        @PathVariable bookId: UUID,
        @RequestBody(required = false) body: ExportBookRequest?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val coverPng = body?.coverImageBase64?.let { java.util.Base64.getDecoder().decode(it) }
        val backPng = body?.backImageBase64?.let { java.util.Base64.getDecoder().decode(it) }
        val book = pdfExportService.exportBook(bookId, userId, coverPng, backPng)
        return ResponseEntity.ok(BookResponse.from(book))
    }

    @PatchMapping("/books/{bookId}/cover")
    fun saveCoverConfig(
        @PathVariable bookId: UUID,
        @RequestBody body: CoverConfigRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = bookGenerationService.saveCoverConfig(bookId, userId, body.templateId, body.paletteId, body.country, body.subtitle)
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

    @PatchMapping("/pages/{pageId}/slots/{slotIndex}/photo")
    fun updateSlotPhoto(
        @PathVariable pageId: UUID,
        @PathVariable slotIndex: Int,
        @RequestBody body: SlotPhotoRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        val userId = UUID.fromString(jwt.subject)
        bookGenerationService.updateSlotPhoto(pageId, slotIndex, body.photoId, userId)
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
