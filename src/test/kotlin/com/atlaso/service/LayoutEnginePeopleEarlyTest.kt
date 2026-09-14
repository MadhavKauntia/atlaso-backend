package com.atlaso.service

import com.atlaso.application.layout.PhotoGroup
import com.atlaso.application.layout.PhotoGrouper
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class LayoutEnginePeopleEarlyTest {

    private val layoutEngine = LayoutEngine(PhotoGrouper())
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)

    @Test
    fun `a people photo appears within the first three pages`() {
        // Opening runs on scenery; the only people photo sits deep in the book.
        val groups = mutableListOf<PhotoGroup>()
        repeat(8) { groups.add(soloGroup(peoplePhoto = false, index = it)) }
        val peopleId = UUID.randomUUID()
        groups.add(6, soloGroup(peoplePhoto = true, index = 99, forcedId = peopleId))

        val pages = layoutEngine.generatePagesFromGroups(groups)

        val firstThree = pages.take(3).flatMap { it.slots }.map { it.photoId }
        assertTrue(peopleId in firstThree) { "No people photo within the first three pages" }
    }

    private fun soloGroup(peoplePhoto: Boolean, index: Int, forcedId: UUID = UUID.randomUUID()): PhotoGroup {
        val p = Photo(
            id = forcedId,
            trip = trip,
            storageKey = "test/$forcedId.jpg",
            originalFilename = "IMG_$index.jpg",
            contentType = "image/jpeg",
            fileSize = 2_000_000,
            metadata = PhotoMetadata(width = 4000, height = 3000, takenAt = Instant.parse("2025-06-01T08:00:00Z").plusSeconds(index * 600L), orientation = 1),
            signals = PhotoSignals(
                sceneType = if (peoplePhoto) "people" else "landscape",
                aestheticScore = 0.7,
                blurScore = 0.2,
                facesCount = if (peoplePhoto) 2 else 0,
                subjectType = if (peoplePhoto) "group" else "landscape",
                shotDistance = if (peoplePhoto) "medium" else "wide",
                settingScope = if (peoplePhoto) "subject" else "environment"
            )
        )
        return PhotoGroup(
            photos = listOf(p),
            representativeTime = p.metadata.takenAt,
            standaloneScores = mapOf(forcedId to 0.7),
            isHero = false
        )
    }
}
