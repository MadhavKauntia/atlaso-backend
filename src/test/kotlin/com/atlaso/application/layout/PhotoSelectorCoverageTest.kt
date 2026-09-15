package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class PhotoSelectorCoverageTest {

    private val selector = PhotoSelector()
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)
    private val base: Instant = Instant.parse("2025-06-01T08:00:00Z")

    @Test
    fun `keeps a low-scoring event on large trips that competition would otherwise drop`() {
        // 5 rich events of 50 strong photos each (250 total) → forces the >120 selection
        // path. Plus one isolated event with a single usable-but-weaker photo.
        val photos = mutableListOf<Photo>()
        var t = base
        repeat(5) {
            repeat(50) {
                t = t.plusSeconds(60) // 60s apart → no burst collapse, same event
                photos.add(photo(aesthetic = 0.9, takenAt = t))
            }
            t = t.plusSeconds(3 * 3600) // 3h gap → next event
        }
        t = t.plusSeconds(3 * 3600)
        val lonely = photo(aesthetic = 0.5, takenAt = t) // usable, but far weaker than the crowd
        photos.add(lonely)

        val result = selector.selectPhotosForBook(photos)

        assertEquals(120, result.photos.size) { "Expected the large-trip cap of 120" }
        assertTrue(lonely.id in result.photos.mapNotNull { it.id }) {
            "Coverage guard failed: the only photo of an event was dropped by competition"
        }
    }

    private fun photo(aesthetic: Double, takenAt: Instant): Photo = Photo(
        id = UUID.randomUUID(),
        trip = trip,
        storageKey = "test/${UUID.randomUUID()}.jpg",
        originalFilename = "IMG_${UUID.randomUUID()}.jpg",
        contentType = "image/jpeg",
        fileSize = 2_000_000,
        metadata = PhotoMetadata(width = 4000, height = 3000, takenAt = takenAt, orientation = 1),
        signals = PhotoSignals(
            sceneType = "landscape",
            aestheticScore = aesthetic,
            blurScore = 0.2,
            timeOfDay = "day"
        )
    )
}
