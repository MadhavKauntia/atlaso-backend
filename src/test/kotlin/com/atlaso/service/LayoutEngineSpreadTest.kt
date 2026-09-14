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
 * Covers the spread-aware pacing rules:
 *  1. at most one spread with two dense (3-4 photo) pages,
 *  2. no layout repeated on more than 2 consecutive pages,
 *  3. similar photos placed diagonally in a 2×2 grid,
 *  8. no more than 2 consecutive spreads without a people photo.
 *
 * Spread model: page 1 is a lone recto; facing spreads are then (2,3), (4,5), …
 */
class LayoutEngineSpreadTest {

    private val layoutEngine = LayoutEngine(PhotoGrouper())
    private val trip = Trip(name = "Test Trip", status = TripStatus.READY_FOR_BOOK_GENERATION)
    private val base: Instant = Instant.parse("2025-06-01T08:00:00Z")

    @Test
    fun `at most one spread has two dense pages`() {
        // Six 4-photo collages up front, then six solos to pair them with.
        val groups = mutableListOf<PhotoGroup>()
        repeat(6) { i -> groups.add(collageGroup(size = 4, index = i)) }
        repeat(6) { i -> groups.add(soloGroup(index = 6 + i)) }

        val pages = layoutEngine.generatePagesFromGroups(groups)

        assertTrue(denseDenseSpreads(pages) <= 1) {
            "Expected ≤1 dense-dense spread, got ${denseDenseSpreads(pages)}"
        }
    }

    @Test
    fun `no layout repeats on more than two consecutive pages`() {
        // A varied deck (sizes 1..4 rotating) so runs can always be broken.
        val groups = (0 until 24).map { i -> collageGroup(size = (i % 4) + 1, index = i) }

        val pages = layoutEngine.generatePagesFromGroups(groups)

        assertTrue(maxConsecutiveSameLayout(pages) <= 2) {
            "A layout repeated more than twice in a row: ${pages.sortedBy { it.pageNumber }.map { it.layout }}"
        }
    }

    @Test
    fun `similar photos sit on a diagonal in a four grid`() {
        // Two red frames taken together, plus a green and a blue taken far apart.
        val red1 = photo(index = 0, takenAt = base, colors = listOf("#cc2222"))
        val red2 = photo(index = 1, takenAt = base.plusSeconds(3), colors = listOf("#cc2222"))
        val green = photo(index = 2, takenAt = base.plusSeconds(4000), colors = listOf("#22cc22"))
        val blue = photo(index = 3, takenAt = base.plusSeconds(8000), colors = listOf("#2222cc"))
        val group = groupOf(listOf(red1, red2, green, blue))

        val pages = layoutEngine.generatePagesFromGroups(listOf(group))

        val slots = pages.single().slots.associateBy { it.photoId }
        val p1 = slots[red1.id]!!
        val p2 = slots[red2.id]!!
        // Diagonal ⇒ different column (x) and different row (y).
        assertTrue(p1.position.x != p2.position.x && p1.position.y != p2.position.y) {
            "Similar photos not diagonal: red1=${p1.position}, red2=${p2.position}"
        }
    }

    @Test
    fun `never more than two consecutive spreads without people`() {
        // People at the head and tail, a run of non-people in the middle. The middle
        // would span 3 people-less spreads unless a later people page is pulled in.
        val groups = mutableListOf<PhotoGroup>()
        repeat(3) { i -> groups.add(soloGroup(index = i, people = true)) }
        repeat(6) { i -> groups.add(soloGroup(index = 3 + i, people = false)) }
        repeat(2) { i -> groups.add(soloGroup(index = 9 + i, people = true)) }

        val pages = layoutEngine.generatePagesFromGroups(groups)

        assertTrue(maxPeoplelessSpreadRun(pages) <= 2) {
            "More than 2 consecutive people-less spreads"
        }
    }

    // ─── spread / page helpers ───────────────────────────────────────────────────

    /** Number of facing spreads (2,3),(4,5),… where both pages carry ≥3 photos. */
    private fun denseDenseSpreads(pages: List<Page>): Int {
        val sorted = pages.sortedBy { it.pageNumber }
        var count = 0
        var i = 1 // sorted[0] is page 1 (lone recto); spreads pair up from index 1
        while (i + 1 < sorted.size) {
            if (sorted[i].slots.size >= 3 && sorted[i + 1].slots.size >= 3) count++
            i += 2
        }
        return count
    }

    private fun maxConsecutiveSameLayout(pages: List<Page>): Int {
        val layouts = pages.sortedBy { it.pageNumber }.map { it.layout }
        var max = 0
        var run = 0
        var prev: Any? = null
        for (l in layouts) {
            run = if (l == prev) run + 1 else 1
            prev = l
            if (run > max) max = run
        }
        return max
    }

    private fun maxPeoplelessSpreadRun(pages: List<Page>): Int {
        val sorted = pages.sortedBy { it.pageNumber }
        // Build spreads: page 1 alone, then facing pairs.
        val spreads = mutableListOf<List<Page>>()
        if (sorted.isNotEmpty()) spreads.add(listOf(sorted[0]))
        var i = 1
        while (i < sorted.size) {
            spreads.add(sorted.subList(i, minOf(i + 2, sorted.size)))
            i += 2
        }
        var max = 0
        var run = 0
        for (spread in spreads) {
            val hasPeople = spread.any { pg -> pg.slots.any { slot -> peopleIds.contains(slot.photoId) } }
            run = if (hasPeople) 0 else run + 1
            if (run > max) max = run
        }
        return max
    }

    // ─── builders ────────────────────────────────────────────────────────────────

    private val peopleIds = mutableSetOf<UUID>()

    private fun collageGroup(size: Int, index: Int): PhotoGroup =
        groupOf((0 until size).map { photo(index = index * 10 + it, takenAt = base.plusSeconds(index * 600L + it)) })

    private fun soloGroup(index: Int, people: Boolean = false): PhotoGroup {
        val p = photo(
            index = index,
            takenAt = base.plusSeconds(index * 600L),
            subjectType = if (people) "group" else "food",
            faces = if (people) 2 else 0,
            shotDistance = "medium",
            settingScope = "subject",
            mood = "joyful"
        )
        if (people) peopleIds.add(p.id!!)
        return groupOf(listOf(p))
    }

    private fun groupOf(photos: List<Photo>): PhotoGroup {
        val scores = photos.mapNotNull { it.id?.let { id -> id to 0.5 } }.toMap()
        return PhotoGroup(
            photos = photos,
            representativeTime = photos.firstNotNullOfOrNull { it.metadata.takenAt },
            standaloneScores = scores,
            isHero = false
        )
    }

    private fun photo(
        index: Int,
        takenAt: Instant?,
        subjectType: String = "landscape",
        faces: Int = 0,
        colors: List<String> = listOf("#3366cc"),
        shotDistance: String = "wide",
        settingScope: String = "environment",
        mood: String = "serene",
        width: Int = 4000,
        height: Int = 3000
    ): Photo {
        val id = UUID.randomUUID()
        return Photo(
            id = id,
            trip = trip,
            storageKey = "test/$id.jpg",
            originalFilename = "IMG_$index.jpg",
            contentType = "image/jpeg",
            fileSize = 2_000_000,
            metadata = PhotoMetadata(width = width, height = height, takenAt = takenAt, orientation = 1),
            signals = PhotoSignals(
                sceneType = subjectType,
                aestheticScore = 0.6,
                blurScore = 0.2,
                facesCount = faces,
                subjectType = subjectType,
                shotDistance = shotDistance,
                settingScope = settingScope,
                mood = mood,
                dominantColors = colors
            )
        )
    }
}
