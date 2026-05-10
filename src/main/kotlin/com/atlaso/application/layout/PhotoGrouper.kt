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

        // Book physical layout (cover is not counted):
        //   Page 1        — isolated (right-hand page, back of cover on left)
        //   Pages 2–3     — spread 1
        //   Pages 4–5     — spread 2
        //   …
        //   Pages 22–23   — spread 11
        //   Page 24       — isolated (left-hand page, right = back cover)
        //
        // This gives 13 semantic episodes:  1 isolated + 11 spreads + 1 isolated = 24 pages.
        private const val SPREAD_COUNT = 11
        private const val TARGET_EPISODES = SPREAD_COUNT + 2  // 13
        private const val MAX_PHOTOS_PER_PAGE = 4
    }

    /**
     * Groups photos into page groups that respect the book's spread layout.
     *
     * Produces (up to) 24 page groups:
     *   - Episode 0  → 1 page group  (page 1, isolated)
     *   - Episodes 1–11 → 2 page groups each (pages 2–23, 11 spreads)
     *   - Episode 12 → 1 page group  (page 24, isolated)
     *
     * Episodes are delimited by the 12 highest-scoring semantic boundaries in the
     * chronologically sorted photo sequence. A boundary scores high when adjacent
     * photos differ in location, scene type, detected objects, or have a large time gap.
     * Photos within a spread therefore always come from the same semantic episode, so
     * they look coherent when two pages are viewed side by side.
     *
     * Within each page group, photos are sorted by aesthetic score so the LayoutEngine
     * assigns the best photo to the featured slot.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val n = sorted.size

        // Not enough photos to fill the episode structure — fall back to one photo per group
        if (n < TARGET_EPISODES) {
            return sorted.map { photo ->
                PhotoGroup(photos = listOf(photo), representativeTime = photo.metadata.takenAt)
            }
        }

        // Score each gap between adjacent photos — higher = stronger episode boundary
        val splitScores = (0 until n - 1).map { i -> computeSplitScore(sorted[i], sorted[i + 1]) }

        // Choose the (TARGET_EPISODES - 1) = 12 highest-scoring gaps as split points
        val splitPoints = splitScores
            .mapIndexed { idx, score -> idx to score }
            .sortedByDescending { it.second }
            .take(TARGET_EPISODES - 1)
            .map { it.first }
            .sorted()
            .toSet()

        // Build 13 episodes from split points
        val episodes = mutableListOf<List<Photo>>()
        var start = 0
        for (i in 0 until n - 1) {
            if (i in splitPoints) {
                episodes.add(sorted.subList(start, i + 1))
                start = i + 1
            }
        }
        episodes.add(sorted.subList(start, n))

        // Convert episodes to page groups
        val groups = mutableListOf<PhotoGroup>()
        for ((index, episode) in episodes.withIndex()) {
            // Episodes 1 through (size-2) are spread episodes; first and last are isolated
            val isSpread = index in 1..(episodes.size - 2)

            if (isSpread && episode.size >= 2) {
                // Split chronologically into left page and right page of the spread
                val mid = episode.size / 2
                groups.add(buildGroup(episode.subList(0, mid).take(MAX_PHOTOS_PER_PAGE)))
                groups.add(buildGroup(episode.subList(mid, episode.size).take(MAX_PHOTOS_PER_PAGE)))
            } else {
                // Isolated page, or a spread episode that only got 1 photo (rare)
                groups.add(buildGroup(episode.take(MAX_PHOTOS_PER_PAGE)))
            }
        }

        logger.info(
            "Grouped {} photos into {} episodes → {} pages ({} spreads + 2 isolated)",
            n, episodes.size, groups.size, SPREAD_COUNT
        )
        return groups
    }

    /**
     * Scores how strongly a page boundary should be placed between [a] and [b].
     * Higher = more likely a new semantic episode starts at [b].
     *
     * Weights:
     *   Temporal gap          0.40  — large gap means different activity or day
     *   Location tag mismatch 0.25  — e.g. beach → restaurant = strong break
     *   Scene type mismatch   0.20  — people / landscape / food / city / misc
     *   Object dissimilarity  0.10  — Jaccard distance on detected object labels
     *   Color temp shift       0.05  — warm→cool often signals a different setting
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
