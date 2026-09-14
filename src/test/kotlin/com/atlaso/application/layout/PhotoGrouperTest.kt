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

class PhotoGrouperTest {

    private val grouper = PhotoGrouper()
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)
    private val base: Instant = Instant.parse("2025-06-01T08:00:00Z")

    @Test
    fun `every page holds only 1, 2, or 4 photos`() {
        val photos = manyDistinctPhotos(count = 130)

        val groups = grouper.group(photos)

        assertTrue(groups.isNotEmpty())
        groups.forEach { g ->
            assertTrue(g.photos.size in setOf(1, 2, 4)) { "Illegal page size ${g.photos.size}" }
        }
    }

    @Test
    fun `produces exactly the target page count when photos are plentiful`() {
        val photos = manyDistinctPhotos(count = 130)

        val groups = grouper.group(photos, targetPages = 50)

        assertEquals(50, groups.size)
    }

    @Test
    fun `collapses near-duplicate shots from the same event to at most two`() {
        // A crowd of ~130 varied photos so the book is comfortably full (no dedup relaxing),
        // plus one event of 6 near-identical group shots taken seconds apart.
        val filler = manyDistinctPhotos(count = 130)
        val dupTime = base.plusSeconds(500_000) // far from filler episodes
        val dupIds = mutableListOf<UUID>()
        val dupes = (0 until 6).map { i ->
            photo(
                aesthetic = 0.6,
                takenAt = dupTime.plusSeconds(i * 4L),
                subjectType = "group",
                shotDistance = "medium",
                facesCount = 4,
                locationTag = "beach"
            ).also { dupIds.add(it.id!!) }
        }

        val groups = grouper.group(filler + dupes)

        val placedDupes = groups.flatMap { it.photos }.mapNotNull { it.id }.count { it in dupIds }
        assertTrue(placedDupes <= 2) { "Expected at most 2 near-duplicates, kept $placedDupes" }
        assertTrue(placedDupes >= 1) { "Near-duplicate event lost entirely" }
    }

    @Test
    fun `keeps at least one photo from every event even when weak`() {
        val filler = manyDistinctPhotos(count = 130)
        // A distinct, isolated event with a single mediocre photo.
        val lonely = photo(
            aesthetic = 0.35,
            takenAt = base.plusSeconds(900_000),
            subjectType = "food",
            shotDistance = "closeup",
            facesCount = 0,
            locationTag = "restaurant"
        )

        val groups = grouper.group(filler + lonely)

        val placed = groups.flatMap { it.photos }.mapNotNull { it.id }
        assertTrue(lonely.id in placed) { "Coverage violated: the only photo of an event was dropped" }
    }

    /**
     * [count] photos spread across many episodes (a fresh location + a >2h gap every 8
     * shots forces an episode break), with rotating composition so nothing dedups away.
     */
    private fun manyDistinctPhotos(count: Int): List<Photo> {
        val subjects = listOf("person", "landscape", "architecture", "food", "activity")
        val shots = listOf("wide", "medium", "closeup")
        val places = listOf("beach", "mountain", "city_street", "market", "temple", "park")
        var t = base
        return (0 until count).map { i ->
            if (i > 0 && i % 8 == 0) t = t.plusSeconds(3 * 3600L) else t = t.plusSeconds(90L)
            photo(
                aesthetic = 0.5 + (i % 5) * 0.08,
                takenAt = t,
                subjectType = subjects[i % subjects.size],
                shotDistance = shots[i % shots.size],
                facesCount = if (subjects[i % subjects.size] == "person") 1 else 0,
                locationTag = places[(i / 8) % places.size]
            )
        }
    }

    private fun photo(
        aesthetic: Double,
        takenAt: Instant?,
        subjectType: String,
        shotDistance: String,
        facesCount: Int,
        locationTag: String,
        blur: Double = 0.2,
        width: Int = 4000,
        height: Int = 3000
    ): Photo = Photo(
        id = UUID.randomUUID(),
        trip = trip,
        storageKey = "test/${UUID.randomUUID()}.jpg",
        originalFilename = "IMG_${UUID.randomUUID()}.jpg",
        contentType = "image/jpeg",
        fileSize = 2_000_000,
        metadata = PhotoMetadata(width = width, height = height, takenAt = takenAt, orientation = 1),
        signals = PhotoSignals(
            sceneType = subjectType,
            aestheticScore = aesthetic,
            blurScore = blur,
            facesCount = facesCount,
            locationTag = locationTag,
            subjectType = subjectType,
            shotDistance = shotDistance
        )
    )
}
