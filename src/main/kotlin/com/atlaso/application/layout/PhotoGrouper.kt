package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class PhotoGroup(
    val photos: List<Photo>,
    val representativeTime: Instant?,
    /** Per-photo standalone_score, so LayoutEngine can size slots without recomputing. */
    val standaloneScores: Map<UUID, Double> = emptyMap(),
    /** True when this group is a single photo reserved for a large solo treatment. */
    val isHero: Boolean = false
)

@Component
class PhotoGrouper {

    private val logger = LoggerFactory.getLogger(PhotoGrouper::class.java)

    companion object {
        const val TARGET_PAGE_COUNT = 50
        private const val MAX_PHOTOS_PER_PAGE = 4

        // A gap between two photos starts a new episode when its boundary score clears
        // this. ~0.55 needs a real time gap plus a location or scene change, so photos
        // within one activity stay together while day/place changes split.
        private const val EPISODE_THRESHOLD = 0.55

        // standalone_score at/above this reserves a photo as a solo page. Slightly
        // lower still qualifies for the single best photo of a decent episode.
        private const val HERO_THRESHOLD = StandaloneScorer.STRONG_THRESHOLD // 0.75
        private const val HERO_PROMOTE_THRESHOLD = 0.68
    }

    /** A page under construction (before it becomes an immutable PhotoGroup). */
    private data class DraftPage(val photos: MutableList<Photo>, var isHero: Boolean)

    /**
     * Groups photos into exactly [targetPages] page groups.
     *
     * 1. Sort chronologically.
     * 2. Split into natural episodes at high-scoring boundaries (variable count).
     * 3. Per episode: reserve strong standalone photos as solo pages, chunk the rest
     *    into multi-photo pages — so episodes occupy different numbers of pages and
     *    strong images get their own page.
     * 4. Normalize the draft to exactly [targetPages] by merging weak pages (too many)
     *    or splitting dense pages / separating deserving images (too few).
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        // Primary: takenAt ascending, nulls last. Secondary: filename (IMG_NNNN order
        // approximates shooting order when EXIF timestamps are absent).
        val sorted = photos
            .sortedBy { it.originalFilename }
            .sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val n = sorted.size

        // Fewer photos than pages: one photo per page, strongest marked as heroes.
        if (n <= targetPages) {
            val scores = StandaloneScorer.scoreEpisode(sorted)
            return sorted.map { p ->
                val s = p.id?.let { scores[it] } ?: 0.0
                PhotoGroup(
                    photos = listOf(p),
                    representativeTime = p.metadata.takenAt,
                    standaloneScores = p.id?.let { mapOf(it to s) } ?: emptyMap(),
                    isHero = s >= HERO_PROMOTE_THRESHOLD
                )
            }
        }

        val episodes = detectEpisodes(sorted)

        // Episode-relative standalone scores for every photo (used for heroes + layout).
        val standaloneById = HashMap<UUID, Double>()
        episodes.forEach { ep -> standaloneById.putAll(StandaloneScorer.scoreEpisode(ep)) }

        // Draft pages, episode by episode, in chronological order.
        val draft = mutableListOf<DraftPage>()
        episodes.forEach { ep -> draft.addAll(buildEpisodePages(ep, standaloneById)) }

        // Normalize to exactly the target page count.
        while (draft.size > targetPages) reduceOnePage(draft, standaloneById)
        while (draft.size < targetPages) expandOnePage(draft, standaloneById)

        logger.info(
            "Grouped {} photos into {} pages across {} episodes ({} hero pages)",
            n, draft.size, episodes.size, draft.count { it.isHero }
        )

        return draft.map { dp -> toPhotoGroup(dp, standaloneById) }
    }

    /** Splits chronologically-sorted photos into episodes at high-scoring boundaries. */
    private fun detectEpisodes(sorted: List<Photo>): List<List<Photo>> {
        val episodes = mutableListOf<List<Photo>>()
        var start = 0
        for (i in 0 until sorted.size - 1) {
            if (computeSplitScore(sorted[i], sorted[i + 1]) >= EPISODE_THRESHOLD) {
                episodes.add(sorted.subList(start, i + 1))
                start = i + 1
            }
        }
        episodes.add(sorted.subList(start, sorted.size))
        return episodes
    }

    /**
     * Reserves strong standalone photos as solo pages (inline, preserving chronology)
     * and chunks the remaining photos into collage pages of up to [MAX_PHOTOS_PER_PAGE].
     */
    private fun buildEpisodePages(episode: List<Photo>, scores: Map<UUID, Double>): List<DraftPage> {
        val heroIds = episode.filter { (scores[it.id] ?: 0.0) >= HERO_THRESHOLD }.mapNotNull { it.id }.toMutableSet()
        // If nothing cleared the bar, still let a decent episode's best photo be a hero.
        if (heroIds.isEmpty() && episode.size >= 3) {
            val best = episode.maxByOrNull { scores[it.id] ?: 0.0 }
            if (best?.id != null && (scores[best.id] ?: 0.0) >= HERO_PROMOTE_THRESHOLD) heroIds.add(best.id!!)
        }

        val pages = mutableListOf<DraftPage>()
        var buffer = mutableListOf<Photo>()
        fun flush() {
            if (buffer.isEmpty()) return
            buffer.chunked(MAX_PHOTOS_PER_PAGE).forEach { pages.add(DraftPage(it.toMutableList(), isHero = false)) }
            buffer = mutableListOf()
        }
        for (photo in episode) {
            if (photo.id in heroIds) {
                flush()
                pages.add(DraftPage(mutableListOf(photo), isHero = true))
            } else {
                buffer.add(photo)
            }
        }
        flush()
        return pages
    }

    /** Removes one page: merge the weakest collage page into a neighbour (heroes last resort). */
    private fun reduceOnePage(draft: MutableList<DraftPage>, scores: Map<UUID, Double>) {
        val collageIdx = draft.indices.filter { !draft[it].isHero }
        val idx = (collageIdx.minByOrNull { pageScore(draft[it], scores) })
            ?: draft.indices.minByOrNull { pageScore(draft[it], scores) }!!

        val removed = draft.removeAt(idx)
        if (draft.isEmpty()) { draft.add(removed); return } // nothing to merge into

        // Merge into the neighbour with the fewest photos, preferring a collage.
        val candidates = listOfNotNull(
            (idx - 1).takeIf { it in draft.indices },
            idx.takeIf { it in draft.indices } // element formerly at idx+1
        )
        val target = candidates.filter { !draft[it].isHero }.minByOrNull { draft[it].photos.size }
            ?: candidates.minByOrNull { draft[it].photos.size }!!

        val merged = (draft[target].photos + removed.photos)
            .sortedByDescending { scores[it.id] ?: 0.0 }
            .take(MAX_PHOTOS_PER_PAGE)
            .toMutableList()
        draft[target] = DraftPage(merged, isHero = merged.size == 1 && draft[target].isHero)
    }

    /** Adds one page: split the densest collage page, or separate a deserving image. */
    private fun expandOnePage(draft: MutableList<DraftPage>, scores: Map<UUID, Double>) {
        val idx = draft.indices.filter { draft[it].photos.size >= 2 }
            .maxByOrNull { draft[it].photos.size } ?: return

        val byTime = draft[idx].photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val mid = byTime.size / 2
        val left = byTime.subList(0, mid).toMutableList()
        val right = byTime.subList(mid, byTime.size).toMutableList()

        draft[idx] = DraftPage(left, isHero = isSoloHero(left, scores))
        draft.add(idx + 1, DraftPage(right, isHero = isSoloHero(right, scores)))
    }

    private fun isSoloHero(photos: List<Photo>, scores: Map<UUID, Double>): Boolean =
        photos.size == 1 && (scores[photos[0].id] ?: 0.0) >= HERO_PROMOTE_THRESHOLD

    private fun pageScore(page: DraftPage, scores: Map<UUID, Double>): Double =
        page.photos.sumOf { scores[it.id] ?: 0.0 }

    private fun toPhotoGroup(dp: DraftPage, scores: Map<UUID, Double>): PhotoGroup {
        // Best photo first, so LayoutEngine puts it in the featured slot.
        val ordered = dp.photos.sortedByDescending { scores[it.id] ?: 0.0 }
        val groupScores = ordered.mapNotNull { p -> p.id?.let { it to (scores[it] ?: 0.0) } }.toMap()
        return PhotoGroup(
            photos = ordered,
            representativeTime = medianTime(dp.photos),
            standaloneScores = groupScores,
            isHero = dp.isHero && ordered.size == 1
        )
    }

    /**
     * Scores how strongly a page boundary belongs between [a] and [b].
     * Higher = stronger episode break.
     *   Temporal gap 0.40, location mismatch 0.25, scene mismatch 0.20,
     *   object dissimilarity 0.10, colour-temperature shift 0.05.
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

    private fun medianTime(photos: List<Photo>): Instant? {
        val times = photos.mapNotNull { it.metadata.takenAt }.sorted()
        if (times.isEmpty()) return null
        return times[times.size / 2]
    }
}
