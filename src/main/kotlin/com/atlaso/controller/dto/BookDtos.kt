package com.atlaso.controller.dto

import com.atlaso.domain.book.Book
import com.atlaso.domain.book.BookStatus
import com.atlaso.domain.book.Layout
import com.atlaso.domain.book.Page
import com.atlaso.domain.book.PhotoSlot
import java.time.Instant
import java.util.UUID

data class BookResponse(
    val id: UUID,
    val tripId: UUID,
    val version: Int,
    val title: String,
    val subtitle: String?,
    val coverPhotoId: UUID?,
    val coverTemplateId: String?,
    val coverPaletteId: String?,
    val status: BookStatus,
    val generatedAt: Instant?,
    val pdfUrl: String?,
    val pages: List<PageResponse>
) {
    companion object {
        fun from(book: Book): BookResponse = BookResponse(
            id = book.id!!,
            tripId = book.trip.id!!,
            version = book.version,
            title = book.title,
            subtitle = book.subtitle,
            coverPhotoId = book.coverPhoto?.id,
            coverTemplateId = book.coverTemplateId,
            coverPaletteId = book.coverPaletteId,
            status = book.status,
            generatedAt = book.generatedAt,
            pdfUrl = book.pdfUrl,
            pages = book.pages.map { PageResponse.from(it) }
        )
    }
}

data class PageResponse(
    val id: UUID,
    val pageNumber: Int,
    val layout: Layout,
    val slots: List<PhotoSlot>
) {
    companion object {
        fun from(page: Page): PageResponse = PageResponse(
            id = page.id!!,
            pageNumber = page.pageNumber,
            layout = page.layout,
            slots = page.slots
        )
    }
}

data class BookSummaryResponse(
    val id: UUID,
    val tripId: UUID,
    val version: Int,
    val title: String,
    val status: BookStatus,
    val pageCount: Int,
    val generatedAt: Instant?
) {
    companion object {
        fun from(book: Book): BookSummaryResponse = BookSummaryResponse(
            id = book.id!!,
            tripId = book.trip.id!!,
            version = book.version,
            title = book.title,
            status = book.status,
            pageCount = book.pages.size,
            generatedAt = book.generatedAt
        )
    }
}
