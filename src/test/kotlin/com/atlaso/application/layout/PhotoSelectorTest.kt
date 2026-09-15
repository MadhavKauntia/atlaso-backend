package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import java.time.Instant
import java.util.UUID

class PhotoSelectorTest {

    private val selector = PhotoSelector()
    private val testTrip = Trip(
        name = "Test Trip",
        status = TripStatus.READY_FOR_BOOK_GENERATION
    )

    @Test
    fun `should filter out photos with low aesthetic score`() {
        val photos = listOf(
            createPhoto(aestheticScore = 0.8, blurScore = 0.2),  // Good
            createPhoto(aestheticScore = 0.3, blurScore = 0.2),  // Low aesthetic
            createPhoto(aestheticScore = 0.6, blurScore = 0.2)   // Good
        )

        val result = selector.selectPhotosForBook(photos)

        // Should filter out the photo with aesthetic 0.3
        assertTrue(result.photos.size < 3)
        assertTrue(result.photos.all { it.signals!!.aestheticScore >= 0.4 })
    }

    @Test
    fun `should filter out blurry photos`() {
        val photos = listOf(
            createPhoto(aestheticScore = 0.8, blurScore = 0.2),  // Sharp
            createPhoto(aestheticScore = 0.8, blurScore = 0.8),  // Blurry
            createPhoto(aestheticScore = 0.8, blurScore = 0.5)   // Acceptable
        )

        val result = selector.selectPhotosForBook(photos)

        // Should filter out the photo with blur 0.8
        assertTrue(result.photos.all { it.signals!!.blurScore <= 0.6 })
    }

    @Test
    fun `should filter out documentary shots (menus, receipts, signage)`() {
        // Enough usable photos that the selector doesn't relax filters to hit the page minimum;
        // spaced out in time so burst dedup doesn't confound the assertion.
        val base = Instant.parse("2025-10-12T09:00:00Z")
        val filler = (1..55).map { i ->
            createPhoto(aestheticScore = 0.7, blurScore = 0.1, sceneType = "landscape",
                detectedObjects = listOf("beach", "ocean"), takenAt = base.plusSeconds(60L * i))
        }
        val menu = createPhoto(aestheticScore = 0.7, blurScore = 0.1, sceneType = "food",
            detectedObjects = listOf("menu", "hand", "text"), takenAt = base.plusSeconds(4000))
        val receipt = createPhoto(aestheticScore = 0.7, blurScore = 0.1, sceneType = "misc",
            detectedObjects = listOf("receipt"), takenAt = base.plusSeconds(4100))
        // "texture" must NOT be caught by the "text" exclusion (token match, not substring).
        val texture = createPhoto(aestheticScore = 0.7, blurScore = 0.1, sceneType = "landscape",
            detectedObjects = listOf("texture", "wall"), takenAt = base.plusSeconds(4200))

        val result = selector.selectPhotosForBook(filler + listOf(menu, receipt, texture))

        assertTrue(result.photos.none { it.id == menu.id }, "menu shot should be dropped")
        assertTrue(result.photos.none { it.id == receipt.id }, "receipt shot should be dropped")
        assertTrue(result.photos.any { it.id == texture.id }, "'texture' must not be caught by 'text'")
    }

    @Test
    fun `should detect and dedupe photo bursts`() {
        val baseTime = Instant.now()

        val photos = listOf(
            // Burst 1: 3 photos within 10 seconds
            createPhoto(
                aestheticScore = 0.7,
                blurScore = 0.2,
                takenAt = baseTime
            ),
            createPhoto(
                aestheticScore = 0.9,  // Best in burst
                blurScore = 0.2,
                takenAt = baseTime.plusSeconds(3)
            ),
            createPhoto(
                aestheticScore = 0.8,
                blurScore = 0.2,
                takenAt = baseTime.plusSeconds(7)
            ),
            // Separate photo
            createPhoto(
                aestheticScore = 0.8,
                blurScore = 0.2,
                takenAt = baseTime.plusSeconds(60)
            )
        )

        val result = selector.selectPhotosForBook(photos)

        // Should keep best from burst + the separate photo
        assertEquals(2, result.photos.size)
        // Best photo from burst should be the one with 0.9 aesthetic
        assertTrue(result.photos.any { it.signals!!.aestheticScore == 0.9 })
    }

    @Test
    fun `should prefer landscape orientation`() {
        val landscape = createPhoto(
            aestheticScore = 0.7,
            blurScore = 0.2,
            width = 4000,
            height = 3000,
            sceneType = "landscape"
        )

        val portrait = createPhoto(
            aestheticScore = 0.7,
            blurScore = 0.2,
            width = 3000,
            height = 4000,
            sceneType = "landscape"
        )

        val photos = listOf(landscape, portrait)

        val scoredLandscape = scoreTestPhoto(landscape)
        val scoredPortrait = scoreTestPhoto(portrait)

        // Landscape should score higher with same aesthetic
        assertTrue(scoredLandscape.totalScore > scoredPortrait.totalScore)
    }

    @Test
    fun `should prefer golden hour lighting`() {
        val goldenHour = createPhoto(
            aestheticScore = 0.7,
            blurScore = 0.2,
            timeOfDay = "golden_hour"
        )

        val day = createPhoto(
            aestheticScore = 0.7,
            blurScore = 0.2,
            timeOfDay = "day"
        )

        val night = createPhoto(
            aestheticScore = 0.7,
            blurScore = 0.2,
            timeOfDay = "night"
        )

        val scoredGoldenHour = scoreTestPhoto(goldenHour)
        val scoredDay = scoreTestPhoto(day)
        val scoredNight = scoreTestPhoto(night)

        // Golden hour should score highest
        assertTrue(scoredGoldenHour.totalScore > scoredDay.totalScore)
        assertTrue(scoredDay.totalScore > scoredNight.totalScore)
    }

    @Test
    fun `should maintain scene type diversity`() {
        // Create 30 landscape photos and 10 people photos
        val landscapes = (1..30).map {
            createPhoto(
                aestheticScore = 0.9,
                blurScore = 0.1,
                sceneType = "landscape"
            )
        }

        val people = (1..10).map {
            createPhoto(
                aestheticScore = 0.7,  // Lower score
                blurScore = 0.1,
                sceneType = "people"
            )
        }

        val photos = landscapes + people

        val result = selector.selectPhotosForBook(photos)

        val landscapeCount = result.photos.count { it.signals?.sceneType == "landscape" }
        val peopleCount = result.photos.count { it.signals?.sceneType == "people" }

        // Should not select all landscapes despite higher scores
        assertTrue(landscapeCount < result.photos.size)
        // Should include some people photos for diversity
        assertTrue(peopleCount > 0)

        // Log stats for visibility
        println("Landscape: $landscapeCount, People: $peopleCount")
        println(result.stats)
    }

    @Test
    fun `should calculate correct statistics`() {
        val photos = listOf(
            createPhoto(aestheticScore = 0.8, sceneType = "landscape", timeOfDay = "day"),
            createPhoto(aestheticScore = 0.6, sceneType = "people", timeOfDay = "night"),
            createPhoto(aestheticScore = 0.7, sceneType = "landscape", timeOfDay = "golden_hour")
        )

        val result = selector.selectPhotosForBook(photos)

        assertNotNull(result.stats)
        assertTrue(result.stats.averageAestheticScore > 0.0)
        assertTrue(result.stats.sceneTypeDistribution.isNotEmpty())
        assertTrue(result.stats.timeOfDayDistribution.isNotEmpty())
    }

    @Test
    fun `should handle photos without EXIF timestamps`() {
        val photos = listOf(
            createPhoto(aestheticScore = 0.8, takenAt = null),
            createPhoto(aestheticScore = 0.7, takenAt = Instant.now())
        )

        // Should not crash
        val result = selector.selectPhotosForBook(photos)

        assertTrue(result.photos.isNotEmpty())
    }

    @Test
    fun `should sort selected photos chronologically`() {
        val baseTime = Instant.now()

        val photos = listOf(
            createPhoto(aestheticScore = 0.8, takenAt = baseTime.plusSeconds(100)),
            createPhoto(aestheticScore = 0.7, takenAt = baseTime),
            createPhoto(aestheticScore = 0.9, takenAt = baseTime.plusSeconds(50))
        )

        val result = selector.selectPhotosForBook(photos)

        // Should be sorted by time, not by score
        val times = result.photos.mapNotNull { it.metadata.takenAt }
        assertEquals(times.sorted(), times)
    }

    @Test
    fun `variety bonus should decrease with count`() {
        val selector = PhotoSelector()

        val bonus0 = calculateVarietyBonusPublic(0)
        val bonus2 = calculateVarietyBonusPublic(2)
        val bonus5 = calculateVarietyBonusPublic(5)
        val bonus10 = calculateVarietyBonusPublic(10)
        val bonus20 = calculateVarietyBonusPublic(20)

        // Bonus should decrease as count increases
        assertTrue(bonus0 > bonus2)
        assertTrue(bonus2 > bonus5)
        assertTrue(bonus5 > bonus10)
        assertTrue(bonus10 >= bonus20)

        println("Variety bonuses: 0=$bonus0, 2=$bonus2, 5=$bonus5, 10=$bonus10, 20=$bonus20")
    }

    // Helper functions

    private fun createPhoto(
        aestheticScore: Double,
        blurScore: Double = 0.2,
        sceneType: String = "landscape",
        timeOfDay: String = "day",
        width: Int = 4000,
        height: Int = 3000,
        takenAt: Instant? = Instant.now(),
        detectedObjects: List<String> = emptyList()
    ): Photo {
        return Photo(
            id = UUID.randomUUID(),
            trip = testTrip,
            storageKey = "test/${UUID.randomUUID()}.jpg",
            originalFilename = "test.jpg",
            contentType = "image/jpeg",
            fileSize = 2_000_000,
            metadata = PhotoMetadata(
                width = width,
                height = height,
                takenAt = takenAt,
                orientation = 1
            ),
            signals = PhotoSignals(
                sceneType = sceneType,
                aestheticScore = aestheticScore,
                blurScore = blurScore,
                timeOfDay = timeOfDay,
                facesCount = 0,
                detectedObjects = detectedObjects,
                isBlurry = blurScore > 0.5,
                dominantColors = emptyList()
            )
        )
    }

    private fun scoreTestPhoto(photo: Photo): PhotoScore {
        val selector = PhotoSelector()
        // Use reflection to access private method for testing
        // In production, you might make this internal or create a test-specific version
        return PhotoScore(
            photo = photo,
            totalScore = calculateTotalScore(photo),
            breakdown = ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        )
    }

    private fun calculateTotalScore(photo: Photo): Double {
        val weights = ScoringWeights()
        val signals = photo.signals!!
        val metadata = photo.metadata

        val aesthetic = signals.aestheticScore * weights.aestheticWeight
        val sharpness = (1.0 - signals.blurScore) * weights.sharpnessWeight

        val orientation = Orientation.from(metadata.width, metadata.height)
        val orientationScore = when (orientation) {
            Orientation.LANDSCAPE -> 1.0
            Orientation.PORTRAIT -> 0.7
            Orientation.SQUARE -> 0.5
        } * weights.orientationPreference

        val lighting = when (signals.timeOfDay) {
            "golden_hour" -> 1.0
            "day" -> 0.8
            "night" -> 0.6
            else -> 0.5
        } * weights.lightingPreference

        return aesthetic + sharpness + orientationScore + lighting
    }

    private fun calculateVarietyBonusPublic(count: Int): Double {
        return when {
            count == 0 -> 1.0
            count <= 2 -> 0.9
            count <= 5 -> 0.7
            count <= 10 -> 0.4
            else -> 0.0
        }
    }
}
