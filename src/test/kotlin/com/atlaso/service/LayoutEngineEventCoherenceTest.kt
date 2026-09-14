package com.atlaso.service

import com.atlaso.application.layout.PhotoGroup
import com.atlaso.application.layout.PhotoGrouper
import com.atlaso.domain.book.Page
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

/**
 * Req B: pages from one event stay together (contiguous, never scattered) and a small
 * event isn't split across a page turn. Spread model: page 1 lone; spreads (2,3),(4,5)…
 * turns fall after pages 1,3,5… (even planned indices).
 */
class LayoutEngineEventCoherenceTest {

    private val layoutEngine = LayoutEngine(PhotoGrouper())
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)
    private val base: Instant = Instant.parse("2025-06-01T08:00:00Z")

    private val photoEvent = mutableMapOf<UUID, Int>()

    @Test
    fun `an event's pages stay contiguous in the book`() {
        // events: 0(x2), 1(x1), 2(x3), 3(x1), 4(x2), 5(x1)
        val groups = eventsOf(2, 1, 3, 1, 2, 1)

        val pages = layoutEngine.generatePagesFromGroups(groups)

        val order = pages.sortedBy { it.pageNumber }.map { eventOfPage(it) }
        // Each event id appears as a single contiguous run.
        val seen = mutableSetOf<Int>()
        var prev = -999
        for (e in order) {
            if (e != prev) {
                assertFalse(e in seen) { "Event $e is split (non-contiguous) in order $order" }
                seen.add(e)
                prev = e
            }
        }
    }

    @Test
    fun `a two-page event is not split across a page turn`() {
        // Force a 2-page event to start on a turn-straddling index; the pass should fix it.
        // events: 0(x1), 1(x1), 2(x2), 3(x1) → event 2 initially lands on pages 3-4 (a turn).
        val groups = eventsOf(1, 1, 2, 1)

        val pages = layoutEngine.generatePagesFromGroups(groups)

        val byEvent = pages.groupBy { eventOfPage(it) }
        byEvent.forEach { (event, evPages) ->
            if (evPages.size == 2) {
                val spreads = evPages.map { spreadOfPageNumber(it.pageNumber) }.toSet()
                assertEquals(1, spreads.size) { "Event $event (2 pages) split across a page turn: pages ${evPages.map { it.pageNumber }}" }
            }
        }
    }

    // ─── helpers ─────────────────────────────────────────────────────────────────

    /** page 1 lone; spreads (2,3),(4,5)… → spread index of a 1-based page number. */
    private fun spreadOfPageNumber(pageNumber: Int): Int =
        if (pageNumber <= 1) 0 else 1 + (pageNumber - 2) / 2

    private fun eventOfPage(page: Page): Int =
        page.slots.firstNotNullOf { photoEvent[it.photoId] }

    /** Builds one solo PhotoGroup per page, tagged with the given event indices. */
    private fun eventsOf(vararg sizes: Int): List<PhotoGroup> {
        val groups = mutableListOf<PhotoGroup>()
        var t = base
        sizes.forEachIndexed { eventIndex, pageCount ->
            repeat(pageCount) {
                t = t.plusSeconds(600)
                val p = photo(t, eventIndex)
                groups.add(
                    PhotoGroup(
                        photos = listOf(p),
                        representativeTime = t,
                        standaloneScores = mapOf(p.id!! to 0.5),
                        isHero = false,
                        eventIndex = eventIndex
                    )
                )
            }
        }
        return groups
    }

    private fun photo(takenAt: Instant, eventIndex: Int): Photo {
        val id = UUID.randomUUID()
        photoEvent[id] = eventIndex
        return Photo(
            id = id,
            trip = trip,
            storageKey = "test/$id.jpg",
            originalFilename = "IMG_$id.jpg",
            contentType = "image/jpeg",
            fileSize = 2_000_000,
            metadata = PhotoMetadata(width = 4000, height = 3000, takenAt = takenAt, orientation = 1),
            // Neutral signals: not people / establishing / quiet, so only event passes act.
            signals = PhotoSignals(
                sceneType = "food",
                aestheticScore = 0.6,
                blurScore = 0.2,
                facesCount = 0,
                subjectType = "food",
                shotDistance = "medium",
                settingScope = "subject",
                mood = "joyful"
            )
        )
    }
}
