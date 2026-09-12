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
        const val TARGET_PAGE_COUNT = 50

        // Book physical layout (cover not counted):
        //   Page 1        — isolated (right-hand page, back of cover on left)
        //   Pages 2–3     — spread 1
        //   …
        //   Pages 48–49   — spread 24
        //   Page 50       — isolated (left-hand page, right = back cover)
        //
        // 1 isolated + 24 spreads + 1 isolated = 50 pages → 26 episodes → 25 boundaries.
        // Minimum 50 photos → each spread episode gets 2 photos → exactly 1 photo per page.
        private const val SPREAD_COUNT = 24
        private const val BOUNDARY_COUNT = 25  // TARGET_EPISODES - 1
        private const val MAX_PHOTOS_PER_PAGE = 4
    }

    /**
     * Groups photos into exactly 50 page groups using constrained semantic boundary detection.
     *
     * Algorithm:
     * 1. Sort photos chronologically.
     * 2. Score every adjacent pair — high score = strong natural episode break
     *    (large time gap, location tag change, scene type change, low object overlap).
     * 3. Greedily select the 12 highest-scoring boundaries subject to:
     *    - Each spread episode (episodes 1–11) has ≥ 2 photos (so it can produce 2 pages).
     *    - Each isolated episode (0 and 12) has ≥ 1 photo.
     *    This ensures semantically coherent spreads while guaranteeing exactly 50 pages.
     * 4. Episode 0  → page 1  (isolated page group, up to 4 photos).
     * 5. Episodes 1–24 → split at midpoint into left/right page groups per spread.
     * 6. Episode 25 → page 50 (isolated page group, up to 4 photos).
     *
     * Within each page group, photos are sorted by aesthetic score so the LayoutEngine
     * assigns the best photo to the featured slot.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        // Primary: takenAt ascending, nulls last.
        // Secondary: originalFilename — iPhone files are named IMG_NNNN sequentially,
        // so filename order approximates shooting order when EXIF timestamps are absent.
        val sorted = photos
            .sortedBy { it.originalFilename }
            .sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
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
            val isIsolated = index == 0 || index == episodes.size - 1
            val isSpread = !isIsolated  // episodes 1–11
            if (isSpread && episode.size >= 2) {
                // Split at midpoint — photos within an episode are semantically similar,
                // so both halves will show related content.
                val mid = episode.size / 2
                groups.add(buildGroup(episode.subList(0, mid).take(MAX_PHOTOS_PER_PAGE)))
                groups.add(buildGroup(episode.subList(mid, episode.size).take(MAX_PHOTOS_PER_PAGE)))
            } else {
                // Isolated pages (page 1 and page 50): use exactly 1 photo.
                // A single HERO image looks better, and avoids placing two unrelated
                // photos together when the episode's photos don't share a common theme.
                val limit = if (isIsolated) 1 else MAX_PHOTOS_PER_PAGE
                groups.add(buildGroup(episode.take(limit)))
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
     *   - The final isolated episode (page 50) has ≥ 1 photo.
     *
     * Boundaries are tried in descending score order. A candidate is accepted when:
     *   a) It is ≥ 2 positions away from any already-selected boundary on both sides
     *      (guarantees ≥ 2 photos in the episodes it creates/splits).
     *   b) It is ≤ maxAllowed, leaving enough room for the remaining boundaries.
     *
     * For n = 50 (minimum), the constraints force exactly [0, 2, 4, …, 48], which is
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

        // Safety fallback — should never trigger for n ≥ 50, but guards against edge cases.
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
