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

/** Covers the narrative-bookend rules: strong establishing opener, clean opening spread, calm closer. */
class LayoutEngineBookendTest {

    private val layoutEngine = LayoutEngine(PhotoGrouper())
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)
    private val base: Instant = Instant.parse("2025-06-01T08:00:00Z")

    @Test
    fun `opener is the strongest establishing single, not the earliest`() {
        val groups = mutableListOf<PhotoGroup>()
        // Page 1 starts on a people shot (not establishing) so the opener pass engages.
        groups.add(solo(index = 0, subjectType = "group", scene = "people", faces = 2, shot = "medium", scope = "subject"))
        // A weak establishing single early…
        groups.add(solo(index = 1, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.55, keepsake = 0.5))
        // …and a much stronger establishing single a bit later — this one should lead.
        val strongId = UUID.randomUUID()
        groups.add(solo(index = 2, forcedId = strongId, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.95, keepsake = 0.95))
        repeat(5) { groups.add(solo(index = 10 + it, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.6, keepsake = 0.6)) }

        val pages = layoutEngine.generatePagesFromGroups(groups)

        assertEquals(strongId, pages.first().slots.first().photoId) { "Page 1 should be the strongest establishing shot" }
    }

    @Test
    fun `an object misc single is kept out of the opening three pages`() {
        val groups = mutableListOf<PhotoGroup>()
        groups.add(solo(index = 0, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.9, keepsake = 0.9)) // strong opener, stays
        val muggyId = UUID.randomUUID()
        groups.add(solo(index = 1, forcedId = muggyId, subjectType = "object", scene = "misc", shot = "medium", scope = "subject", aesthetic = 0.6, keepsake = 0.7)) // souvenir mug
        groups.add(solo(index = 2, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.7, keepsake = 0.7))
        repeat(5) { groups.add(solo(index = 10 + it, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.85, keepsake = 0.85)) }

        val pages = layoutEngine.generatePagesFromGroups(groups)

        val firstThree = pages.take(3).flatMap { it.slots }.map { it.photoId }
        assertFalse(muggyId in firstThree) { "The object/misc souvenir shot should not sit in the opening three pages" }
    }

    @Test
    fun `book ends on a calm single even when the last group is a busy grid`() {
        val groups = mutableListOf<PhotoGroup>()
        // A run of establishing singles…
        repeat(6) { groups.add(solo(index = it, subjectType = "landscape", scene = "landscape", shot = "wide", scope = "environment", aesthetic = 0.7, keepsake = 0.7)) }
        // …a quiet scenic single near the end…
        val calmId = UUID.randomUUID()
        groups.add(solo(index = 6, forcedId = calmId, subjectType = "landscape", scene = "landscape", shot = "closeup", scope = "environment", mood = "serene", aesthetic = 0.9, keepsake = 0.9))
        // …and a busy 4-photo grid as the final group.
        groups.add(grid(indexBase = 20))

        val pages = layoutEngine.generatePagesFromGroups(groups)

        val lastPage = pages.last()
        assertEquals(1, lastPage.slots.size) { "Book should close on a single, not a grid" }
        assertEquals(calmId, lastPage.slots.first().photoId) { "Book should close on the strongest quiet/scenic shot" }
    }

    private fun solo(
        index: Int,
        forcedId: UUID = UUID.randomUUID(),
        subjectType: String = "landscape",
        scene: String = "landscape",
        faces: Int = 0,
        shot: String = "wide",
        scope: String = "environment",
        mood: String = "serene",
        aesthetic: Double = 0.7,
        keepsake: Double = 0.7
    ): PhotoGroup = groupOf(listOf(photo(forcedId, index, subjectType, scene, faces, shot, scope, mood, aesthetic, keepsake)))

    private fun grid(indexBase: Int): PhotoGroup =
        groupOf((0 until 4).map { photo(UUID.randomUUID(), indexBase + it, "food", "food", 0, "medium", "subject", "joyful", 0.6, 0.6) })

    private fun groupOf(photos: List<Photo>): PhotoGroup = PhotoGroup(
        photos = photos,
        representativeTime = photos.first().metadata.takenAt,
        standaloneScores = photos.associate { it.id!! to 0.7 },
        isHero = false
    )

    private fun photo(
        id: UUID, index: Int, subjectType: String, scene: String, faces: Int,
        shot: String, scope: String, mood: String, aesthetic: Double, keepsake: Double
    ): Photo = Photo(
        id = id,
        trip = trip,
        storageKey = "test/$id.jpg",
        originalFilename = "IMG_$index.jpg",
        contentType = "image/jpeg",
        fileSize = 2_000_000,
        metadata = PhotoMetadata(width = 4000, height = 3000, takenAt = base.plusSeconds(index * 600L), orientation = 1),
        signals = PhotoSignals(
            sceneType = scene,
            aestheticScore = aesthetic,
            blurScore = 0.2,
            facesCount = faces,
            mood = mood,
            subjectType = subjectType,
            shotDistance = shot,
            settingScope = scope,
            keepsakeInterest = keepsake
        )
    )
}
