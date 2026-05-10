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
        //   Pages 4–5     — spread 2
        //   …
        //   Pages 22–23   — spread 11
        //   Page 24       — isolated (left-hand page, right = back cover)
        //
        // 1 isolated + 11 spreads + 1 isolated = 24 pages
        private const val SPREAD_COUNT = 11
        private const val MAX_PHOTOS_PER_PAGE = 4
    }

    /**
     * Divides photos into exactly 24 page groups that respect the book's spread layout.
     *
     * How sizes are determined (equal distribution — guarantees ≥2 photos per spread):
     *   - photosPerPage = floor(n / 24), clamped to [1, 4]
     *   - Page 1 and Page 24 each get photosPerPage photos
     *   - Remaining photos divided equally into 11 spread episodes
     *
     * Where to split within each spread episode (semantic split):
     *   - The highest-scoring boundary inside the episode becomes the left/right divider
     *   - Score = temporal gap + location tag change + scene type change + object dissimilarity
     *
     * Within each page group, photos are sorted by aesthetic score so the LayoutEngine
     * assigns the best photo to the featured slot.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val n = sorted.size

        if (n < TARGET_PAGE_COUNT) {
            // Fewer photos than pages — one photo per page, no spreads
            return sorted.map { PhotoGroup(photos = listOf(it), representativeTime = it.metadata.takenAt) }
        }

        // --- Size distribution ---
        val photosPerPage = (n / TARGET_PAGE_COUNT).coerceIn(1, MAX_PHOTOS_PER_PAGE)
        val photosForPage1 = photosPerPage
        val photosForPage24 = photosPerPage
        val spreadsTotal = n - photosForPage1 - photosForPage24  // guaranteed ≥ 22 when n ≥ 24
        val spreadBase = spreadsTotal / SPREAD_COUNT
        val spreadExtras = spreadsTotal % SPREAD_COUNT

        val groups = mutableListOf<PhotoGroup>()

        // Page 1 (isolated)
        groups.add(buildGroup(sorted.subList(0, photosForPage1)))

        // 11 spread episodes — equal-sized chunks, each split into 2 page groups
        var spreadStart = photosForPage1
        for (s in 0 until SPREAD_COUNT) {
            val size = spreadBase + if (s < spreadExtras) 1 else 0
            val episode = sorted.subList(spreadStart, spreadStart + size)
            spreadStart += size

            val (leftPhotos, rightPhotos) = splitEpisode(episode)
            groups.add(buildGroup(leftPhotos))
            if (rightPhotos.isNotEmpty()) groups.add(buildGroup(rightPhotos))
        }

        // Page 24 (isolated)
        groups.add(buildGroup(sorted.subList(n - photosForPage24, n)))

        logger.info(
            "Grouped {} photos into {} pages ({} spreads + 2 isolated, {} photos/page)",
            n, groups.size, SPREAD_COUNT, photosPerPage
        )
        return groups
    }

    /**
     * Splits a spread episode into left-page and right-page groups.
     *
     * Finds the highest-scoring semantic boundary inside [episode] and uses it as the
     * divider. This ensures pages within a spread break at a natural content boundary
     * (location change, scene change, time gap) rather than an arbitrary midpoint.
     */
    private fun splitEpisode(episode: List<Photo>): Pair<List<Photo>, List<Photo>> {
        if (episode.size <= 1) return Pair(episode, emptyList())
        if (episode.size == 2) return Pair(listOf(episode[0]), listOf(episode[1]))

        // Score every adjacent pair; pick the highest-scoring gap as the split point
        val bestSplit = (0 until episode.size - 1)
            .maxByOrNull { i -> computeSplitScore(episode[i], episode[i + 1]) }
            ?: (episode.size / 2 - 1)

        return Pair(
            episode.subList(0, bestSplit + 1).take(MAX_PHOTOS_PER_PAGE),
            episode.subList(bestSplit + 1, episode.size).take(MAX_PHOTOS_PER_PAGE)
        )
    }

    /**
     * Scores how strongly a page boundary belongs between [a] and [b].
     * Higher = stronger episode break.
     *
     * Weights:
     *   Temporal gap          0.40
     *   Location tag mismatch 0.25
     *   Scene type mismatch   0.20
     *   Object dissimilarity  0.10  (Jaccard distance)
     *   Color temp shift       0.05
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
