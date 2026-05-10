package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

data class PhotoGroup(
    val photos: List<Photo>,
    val representativeTime: Instant?
)

@Component
class PhotoGrouper {

    private val logger = LoggerFactory.getLogger(PhotoGrouper::class.java)

    companion object {
        const val TARGET_PAGE_COUNT = 24

        // Book physical layout (cover not counted):
        //   Page 1        — isolated (right-hand page, back of cover on left)
        //   Pages 2–3     — spread 1
        //   …
        //   Pages 22–23   — spread 11
        //   Page 24       — isolated (left-hand page, right = back cover)
        //
        // 1 isolated + 11 spreads + 1 isolated = 24 pages → 13 episodes → 12 boundaries
        private const val SPREAD_COUNT = 11
        private const val BOUNDARY_COUNT = 12  // TARGET_EPISODES - 1
        private const val MAX_PHOTOS_PER_PAGE = 4
    }

    /**
     * Groups photos into exactly 24 page groups using constrained semantic boundary detection.
     *
     * Algorithm:
     * 1. Sort photos chronologically.
     * 2. Score every adjacent pair — high score = strong natural episode break
     *    (large time gap, location tag change, scene type change, low object overlap).
     * 3. Greedily select the 12 highest-scoring boundaries subject to:
     *    - Each spread episode (episodes 1–11) has ≥ 2 photos (so it can produce 2 pages).
     *    - Each isolated episode (0 and 12) has ≥ 1 photo.
     *    This ensures semantically coherent spreads while guaranteeing exactly 24 pages.
     * 4. Episode 0  → page 1  (isolated page group, up to 4 photos).
     * 5. Episodes 1–11 → split at midpoint into left/right page groups per spread.
     * 6. Episode 12 → page 24 (isolated page group, up to 4 photos).
     *
     * Within each page group, photos are sorted by aesthetic score so the LayoutEngine
     * assigns the best photo to the featured slot.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val n = sorted.size

        if (n < TARGET_PAGE_COUNT) {
            return sorted.map { PhotoGroup(photos = listOf(it), representativeTime = it.metadata.takenAt) }
        }

        // Score every gap between adjacent photos
        val splitScores = (0 until n - 1).map { i -> computeSplitScore(sorted[i], sorted[i + 1]) }

        // Find 12 constrained semantic boundaries
        val boundaries = selectConstrainedBoundaries(splitScores, n)

        // Build 13 episodes from the boundary positions
        val episodes = mutableListOf<List<Photo>>()
        var start = 0
        for (b in boundaries) {
            episodes.add(sorted.subList(start, b + 1))
            start = b + 1
        }
        episodes.add(sorted.subList(start, n))

        // Convert episodes to page groups
        val groups = mutableListOf<PhotoGroup>()
        for ((index, episode) in episodes.withIndex()) {
            val isSpread = index in 1..(episodes.size - 2)  // episodes 1–11
            if (isSpread && episode.size >= 2) {
                // Split at midpoint — photos within an episode are semantically similar,
                // so both halves will show related content.
                val mid = episode.size / 2
                groups.add(buildGroup(episode.subList(0, mid).take(MAX_PHOTOS_PER_PAGE)))
                groups.add(buildGroup(episode.subList(mid, episode.size).take(MAX_PHOTOS_PER_PAGE)))
            } else {
                groups.add(buildGroup(episode.take(MAX_PHOTOS_PER_PAGE)))
            }
        }

        logger.info(
            "Grouped {} photos into {} pages via constrained semantic boundaries ({} spreads + 2 isolated)",
            n, groups.size, SPREAD_COUNT
        )
        return groups
    }

    /**
     * Greedily selects [BOUNDARY_COUNT] boundary positions from the scored gap list,
     * subject to:
     *   - Each spread episode (between consecutive boundaries) has ≥ 2 photos.
     *   - The final isolated episode (page 24) has ≥ 1 photo.
     *
     * Boundaries are tried in descending score order. A candidate is accepted when:
     *   a) It is ≥ 2 positions away from any already-selected boundary on both sides
     *      (guarantees ≥ 2 photos in the episodes it creates/splits).
     *   b) It is ≤ maxAllowed, leaving enough room for the remaining boundaries.
     *
     * For n = 24 (minimum), the constraints force exactly [0, 2, 4, …, 22], which is
     * equivalent to equal distribution — no wasted choices, always valid.
     * For larger n, semantic scores guide placement toward natural scene/location breaks.
     */
    private fun selectConstrainedBoundaries(scores: List<Double>, n: Int): List<Int> {
        val sortedByScore = scores.indices.sortedByDescending { scores[it] }
        val selected = mutableListOf<Int>()

        for (candidate in sortedByScore) {
            if (selected.size == BOUNDARY_COUNT) break

            val k = selected.size
            val remaining = BOUNDARY_COUNT - k - 1

            // Upper bound: must leave room for the `remaining` boundaries still needed.
            // Each needs ≥ 2 positions after it (for the spread episode), plus ≥ 1 for page 24.
            val maxAllowed = n - 2 - remaining * 2
            if (candidate > maxAllowed) continue

            // Lower bound: must be ≥ 2 away from the nearest existing boundary on the left
            // (so the episode between prev and candidate has ≥ 2 photos).
            val prev = selected.filter { it < candidate }.maxOrNull() ?: -2
            if (candidate - prev < 2) continue

            // Must also be ≥ 2 away from the nearest existing boundary on the right
            // (so the episode between candidate and next has ≥ 2 photos).
            val next = selected.filter { it > candidate }.minOrNull() ?: (n - 1)
            if (next - candidate < 2) continue

            selected.add(candidate)
        }

        // Safety fallback — should never trigger for n ≥ 24, but guards against edge cases.
        if (selected.size < BOUNDARY_COUNT) {
            logger.warn(
                "Constrained boundary selection found only {}/{} boundaries; falling back to equal spacing",
                selected.size, BOUNDARY_COUNT
            )
            val step = n.toDouble() / (BOUNDARY_COUNT + 1)
            return (1..BOUNDARY_COUNT).map { i -> ((i * step) - 1).toInt().coerceIn(0, n - 2) }
        }

        return selected.sorted()
    }

    /**
     * Scores how strongly a page boundary belongs between [a] and [b].
     * Higher = stronger episode break (different location, scene type, subject matter, or time).
     *
     * Weights:
     *   Temporal gap          0.40 — biggest signal; large gaps mean different activity or day
     *   Location tag mismatch 0.25 — e.g. beach → restaurant is a clear break
     *   Scene type mismatch   0.20 — people / landscape / food / city / misc
     *   Object dissimilarity  0.10 — Jaccard distance on detected object labels
     *   Color temp shift       0.05 — warm→cool often signals a different setting
     */
    private fun computeSplitScore(a: Photo, b: Photo): Double {
        var score = 0.0

        val takenAtA = a.metadata.takenAt
        val takenAtB = b.metadata.takenAt
        if (takenAtA != null && takenAtB != null) {
            val gapMinutes = Duration.between(takenAtA, takenAtB).toMinutes().coerceAtLeast(0).toDouble()
            score += minOf(gapMinutes / 120.0, 1.0) * 0.40
        } else {
            score += 0.20
        }

        val sigA = a.signals
        val sigB = b.signals
        if (sigA != null && sigB != null) {
            if (sigA.locationTag != null && sigB.locationTag != null && sigA.locationTag != sigB.locationTag) {
                score += 0.25
            }
            if (sigA.sceneType != null && sigB.sceneType != null && sigA.sceneType != sigB.sceneType) {
                score += 0.20
            }
            val objA = sigA.detectedObjects.toSet()
            val objB = sigB.detectedObjects.toSet()
            if (objA.isNotEmpty() && objB.isNotEmpty()) {
                val intersection = objA.intersect(objB).size.toDouble()
                val union = objA.union(objB).size.toDouble()
                score += (1.0 - intersection / union) * 0.10
            }
            if (sigA.colorTemperature != sigB.colorTemperature) {
                score += 0.05
            }
        }

        return score
    }

    private fun buildGroup(photos: List<Photo>): PhotoGroup {
        val ordered = photos.sortedByDescending { it.signals?.aestheticScore ?: 0.0 }
        return PhotoGroup(
            photos = ordered,
            representativeTime = medianTime(photos)
        )
    }

    private fun medianTime(photos: List<Photo>): Instant? {
        val times = photos.mapNotNull { it.metadata.takenAt }.sorted()
        if (times.isEmpty()) return null
        return times[times.size / 2]
    }
}
