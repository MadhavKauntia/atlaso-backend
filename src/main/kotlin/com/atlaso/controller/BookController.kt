package com.atlaso.controller

import com.atlaso.controller.dto.BookResponse
import com.atlaso.domain.book.Layout
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
data class PageLayoutRequest(val layout: Layout)
data class SlotSwapRequest(val pageAId: UUID, val slotA: Int, val pageBId: UUID, val slotB: Int)
data class CoverConfigRequest(
    val templateId: String? = null,
    val paletteId: String? = null,
    val country: String? = null,
    val subtitle: String? = null
)
data class ExportBookRequest(val coverImageBase64: String?, val backImageBase64: String? = null)
data class GenerateBookRequest(val country: String? = null, val subtitle: String? = null)

@RestController
@RequestMapping("/api")
class BookController(
    private val bookGenerationService: BookGenerationService,
    private val pdfExportService: PdfExportService
) {

    @PostMapping("/trips/{tripId}/book/generate")
    fun generateBook(
        @PathVariable tripId: UUID,
        @RequestBody(required = false) body: GenerateBookRequest?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        // Async: returns a GENERATING book immediately; the client polls GET /books/{id}
        // until status is READY_FOR_PREVIEW (or FAILED). The cover country is persisted now
        // (at creation) so it survives the user leaving the generating tab.
        val book = bookGenerationService.startGeneration(tripId, userId, body?.country, body?.subtitle)
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

    /** Dev/debug: spread-by-spread text plan of the book's layout with a rule-compliance header. */
    @GetMapping("/books/{bookId}/plan", produces = [MediaType.TEXT_PLAIN_VALUE])
    fun getBookPlan(
        @PathVariable bookId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<String> {
        val userId = UUID.fromString(jwt.subject)
        return ResponseEntity.ok(bookGenerationService.getBookPlan(bookId, userId))
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

    @PatchMapping("/pages/{pageId}/layout")
    fun changePageLayout(
        @PathVariable pageId: UUID,
        @RequestBody body: PageLayoutRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BookResponse> {
        val userId = UUID.fromString(jwt.subject)
        val book = bookGenerationService.changePageLayout(pageId, body.layout, userId)
        return ResponseEntity.ok(BookResponse.from(book))
    }

    @PatchMapping("/slots/swap")
    fun swapSlots(
        @RequestBody body: SlotSwapRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        val userId = UUID.fromString(jwt.subject)
        bookGenerationService.swapSlots(body.pageAId, body.slotA, body.pageBId, body.slotB, userId)
        return ResponseEntity.noContent().build()
    }

    /**
     * Public cover image for the book-ready email. No auth by design: the unguessable book UUID is
     * the capability (same as the emailed preview link). Streams the bytes — rather than redirecting
     * to a presigned URL — so email image proxies that don't follow redirects still render it, and
     * so nothing expires. Cached a day so repeat opens don't re-hit storage.
     */
    @GetMapping("/books/{bookId}/cover")
    fun getBookCover(@PathVariable bookId: UUID): ResponseEntity<ByteArray> {
        val (bytes, contentType) = bookGenerationService.loadCoverImage(bookId)
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(contentType))
            .header(HttpHeaders.CACHE_CONTROL, "public, max-age=86400")
            .body(bytes)
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
