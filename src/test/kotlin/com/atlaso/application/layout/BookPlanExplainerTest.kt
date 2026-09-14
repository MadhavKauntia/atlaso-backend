package com.atlaso.application.layout

import com.atlaso.domain.book.Layout
import com.atlaso.domain.book.Page
import com.atlaso.domain.book.PhotoSlot
import com.atlaso.domain.book.Position
import com.atlaso.domain.book.Size
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class BookPlanExplainerTest {

    private val explainer = BookPlanExplainer()
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)
    private val base: Instant = Instant.parse("2025-06-01T08:00:00Z")

    @Test
    fun `renders a compliance header and a line per page`() {
        val photos = mutableListOf<Photo>()
        val pages = mutableListOf<Page>()
        // Page 1 solo landscape, then a two-up, then a dense four-grid.
        pages.add(page(1, Layout.HERO_LANDSCAPE, listOf(photo(photos, 0, people = false))))
        pages.add(page(2, Layout.TWO_HORIZONTAL, listOf(photo(photos, 1, people = true), photo(photos, 2, people = false))))
        pages.add(page(3, Layout.FOUR_GRID, (3..6).map { photo(photos, it, people = false) }))

        val report = explainer.explain(pages, photos.associateBy { it.id!! })

        assertTrue(report.contains("BOOK PLAN — 3 pages"))
        assertTrue(report.contains("RULE CHECK"))
        assertTrue(report.contains("dense-dense spreads"))
        assertTrue(report.contains("[DENSE]")) { "four-grid page should be flagged dense" }
        assertTrue(report.contains("[PEOPLE]")) { "the people page should be flagged" }
        // Every photo's filename appears.
        photos.forEach { p -> assertTrue(report.contains(p.originalFilename)) { "missing ${p.originalFilename}" } }
    }

    @Test
    fun `flags a dense-dense spread`() {
        val photos = mutableListOf<Photo>()
        val pages = listOf(
            page(1, Layout.SINGLE_FULL, listOf(photo(photos, 0, people = true))),
            page(2, Layout.FOUR_GRID, (1..4).map { photo(photos, it, people = false) }),   // left of spread 1
            page(3, Layout.THREE_GRID, (5..7).map { photo(photos, it, people = false) })    // right of spread 1
        )

        val report = explainer.explain(pages, photos.associateBy { it.id!! })

        assertTrue(report.contains("DENSE-DENSE spread")) { "the facing collages should be flagged" }
        // One dense-dense spread is within the allowed max, so the header counts it as OK.
        assertTrue(report.contains("dense-dense spreads : 1"))
    }

    private fun page(number: Int, layout: Layout, photos: List<Photo>): Page =
        Page(
            pageNumber = number,
            layout = layout,
            slots = photos.map { PhotoSlot(photoId = it.id!!, position = Position(0.0, 0.0), size = Size(1.0, 1.0)) }
        )

    private fun photo(sink: MutableList<Photo>, index: Int, people: Boolean): Photo {
        val id = UUID.randomUUID()
        val p = Photo(
            id = id,
            trip = trip,
            storageKey = "test/$id.jpg",
            originalFilename = "IMG_$index.jpg",
            contentType = "image/jpeg",
            fileSize = 2_000_000,
            metadata = PhotoMetadata(width = 4000, height = 3000, takenAt = base.plusSeconds(index * 600L), orientation = 1),
            signals = PhotoSignals(
                sceneType = if (people) "people" else "landscape",
                aestheticScore = 0.6,
                blurScore = 0.2,
                facesCount = if (people) 2 else 0,
                subjectType = if (people) "group" else "landscape",
                dominantColors = listOf("#3366cc")
            )
        )
        sink.add(p)
        return p
    }
}
