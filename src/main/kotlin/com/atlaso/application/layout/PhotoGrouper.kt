package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

data class PhotoGroup(
    val photos: List<Photo>,
    val representativeTime: Instant?,
    /** Per-photo standalone_score, so LayoutEngine can size slots without recomputing. */
    val standaloneScores: Map<UUID, Double> = emptyMap(),
    /** True when this group is a single photo reserved for a large solo treatment. */
    val isHero: Boolean = false,
    /** Episode index this page belongs to (for spread-composition reasoning). */
    val episodeIndex: Int = -1
)

@Component
class PhotoGrouper {

    private val logger = LoggerFactory.getLogger(PhotoGrouper::class.java)

    companion object {
        const val TARGET_PAGE_COUNT = 50

        // Normal pages carry only 1, 2 or 4 photos — never 3, 5 or 6+ (§10).
        private const val MAX_PHOTOS_PER_PAGE = 4

        // standalone_score at/above this reserves a photo as a solo page. Slightly lower
        // still qualifies for the single best photo of a decent episode.
        private const val HERO_THRESHOLD = StandaloneScorer.STRONG_THRESHOLD // 0.75
        private const val HERO_PROMOTE_THRESHOLD = 0.68

        // Within one episode, at most this many normal solo pages per visual role (§9).
        // This does NOT cap how many distinct photos of the event appear in multi-photo pages.
        private const val MAX_SOLO_PER_ROLE = 2
    }

    /** A page under construction (before it becomes an immutable PhotoGroup). */
    private data class DraftPage(val photos: MutableList<Photo>, var isHero: Boolean, val episodeIndex: Int)

    /**
     * Groups photos into exactly [targetPages] page groups.
     *
     * 1. Sort chronologically + split into natural episodes ([EpisodeDetector]).
     * 2. Per episode: reserve strong standalone photos as solo pages (capped per role),
     *    then chunk the rest into 1/2/4-photo pages that mix visual roles (§8, §9, §10).
     * 3. Normalize the draft to exactly [targetPages] (§17), always keeping page sizes valid.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        val sorted = EpisodeDetector.sortChronologically(photos)
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

        val episodes = EpisodeDetector.detect(sorted)

        // Episode-relative standalone scores for every photo (used for heroes + layout).
        val standaloneById = HashMap<UUID, Double>()
        episodes.forEach { ep -> standaloneById.putAll(StandaloneScorer.scoreEpisode(ep)) }

        // Draft pages, episode by episode, in chronological order.
        val draft = mutableListOf<DraftPage>()
        episodes.forEachIndexed { idx, ep -> draft.addAll(buildEpisodePages(ep, idx, standaloneById)) }

        // Normalize to exactly the target page count (bail out if it can't move further).
        while (draft.size > targetPages) { if (!reduceOnePage(draft, standaloneById)) break }
        while (draft.size < targetPages) { if (!expandOnePage(draft, standaloneById)) break }

        logger.info(
            "Grouped {} photos into {} pages across {} episodes ({} hero pages)",
            n, draft.size, episodes.size, draft.count { it.isHero }
        )

        return draft.map { dp -> toPhotoGroup(dp, standaloneById) }
    }

    /**
     * Reserves strong standalone photos as solo pages (inline, preserving chronology, capped
     * to [MAX_SOLO_PER_ROLE] per visual role) and chunks the remaining photos into 1/2/4-photo
     * pages that mix visual roles.
     */
    private fun buildEpisodePages(episode: List<Photo>, episodeIndex: Int, scores: Map<UUID, Double>): List<DraftPage> {
        val heroIds = pickHeroIds(episode, scores)

        val pages = mutableListOf<DraftPage>()
        var buffer = mutableListOf<Photo>()
        fun flush() {
            if (buffer.isEmpty()) return
            val ordered = interleaveByRole(buffer, scores)
            var i = 0
            for (size in pageSizes(ordered.size)) {
                pages.add(DraftPage(ordered.subList(i, i + size).toMutableList(), isHero = false, episodeIndex = episodeIndex))
                i += size
            }
            buffer = mutableListOf()
        }
        for (photo in episode) {
            if (photo.id in heroIds) {
                flush()
                pages.add(DraftPage(mutableListOf(photo), isHero = true, episodeIndex = episodeIndex))
            } else {
                buffer.add(photo)
            }
        }
        flush()
        return pages
    }

    /** Strong standalone photos to reserve as solo pages, capped per visual role (§9). */
    private fun pickHeroIds(episode: List<Photo>, scores: Map<UUID, Double>): Set<UUID> {
        val strong = episode.filter { (scores[it.id] ?: 0.0) >= HERO_THRESHOLD }
        val base = when {
            strong.isNotEmpty() -> strong
            episode.size >= 3 -> {
                val best = episode.maxByOrNull { scores[it.id] ?: 0.0 }
                if (best != null && (scores[best.id] ?: 0.0) >= HERO_PROMOTE_THRESHOLD) listOf(best) else emptyList()
            }
            else -> emptyList()
        }
        return base.groupBy { VisualRole.of(it) }
            .flatMap { (_, list) -> list.sortedByDescending { scores[it.id] ?: 0.0 }.take(MAX_SOLO_PER_ROLE) }
            .mapNotNull { it.id }
            .toSet()
    }

    /**
     * Reorders photos so consecutive picks rotate through visual roles — chunking this into
     * pages naturally mixes roles within and across pages (§8). Within a role, best first.
     */
    private fun interleaveByRole(photos: List<Photo>, scores: Map<UUID, Double>): List<Photo> {
        if (photos.size <= 1) return photos
        val buckets = photos.groupBy { VisualRole.of(it) }
            .mapValues { (_, v) -> v.sortedByDescending { scores[it.id] ?: 0.0 }.toMutableList() }
        val order = VisualRole.entries.filter { buckets.containsKey(it) }
        val result = mutableListOf<Photo>()
        while (result.size < photos.size) {
            for (role in order) {
                buckets[role]?.let { if (it.isNotEmpty()) result.add(it.removeAt(0)) }
            }
        }
        return result
    }

    /** Page sizes for [n] photos drawn from {4,2,1}, never 3 (§10). Prefers filling 4s. */
    private fun pageSizes(n: Int): List<Int> {
        val sizes = mutableListOf<Int>()
        var r = n
        while (r > 0) {
            when {
                r >= 4 -> { sizes.add(4); r -= 4 }
                r == 3 -> { sizes.add(2); sizes.add(1); r = 0 }
                r == 2 -> { sizes.add(2); r = 0 }
                else -> { sizes.add(1); r = 0 }
            }
        }
        return sizes
    }

    /** Removes one page: merge the weakest collage page into a neighbour (heroes last resort). */
    private fun reduceOnePage(draft: MutableList<DraftPage>, scores: Map<UUID, Double>): Boolean {
        if (draft.size <= 1) return false
        val collageIdx = draft.indices.filter { !draft[it].isHero }
        val idx = collageIdx.minByOrNull { pageScore(draft[it], scores) }
            ?: draft.indices.minByOrNull { pageScore(draft[it], scores) }
            ?: return false

        val removed = draft.removeAt(idx)

        val candidates = listOfNotNull(
            (idx - 1).takeIf { it in draft.indices },
            idx.takeIf { it in draft.indices } // element formerly at idx+1
        )
        val target = candidates.filter { !draft[it].isHero }.minByOrNull { draft[it].photos.size }
            ?: candidates.minByOrNull { draft[it].photos.size }
        if (target == null) { draft.add(idx.coerceIn(0, draft.size), removed); return false }

        val merged = snapToValidSize(
            (draft[target].photos + removed.photos).sortedByDescending { scores[it.id] ?: 0.0 }
        )
        draft[target] = DraftPage(
            merged.toMutableList(),
            isHero = merged.size == 1 && draft[target].isHero,
            episodeIndex = draft[target].episodeIndex
        )
        return true
    }

    /** Trims a merged photo list to a valid page size (1/2/4), dropping the weakest extras. */
    private fun snapToValidSize(photosByScoreDesc: List<Photo>): List<Photo> = when {
        photosByScoreDesc.size >= MAX_PHOTOS_PER_PAGE -> photosByScoreDesc.take(MAX_PHOTOS_PER_PAGE)
        photosByScoreDesc.size == 3 -> photosByScoreDesc.take(2)
        else -> photosByScoreDesc // 1 or 2 already valid
    }

    /** Adds one page: split the densest collage page (4→2+2, 2→1+1), separating deserving images. */
    private fun expandOnePage(draft: MutableList<DraftPage>, scores: Map<UUID, Double>): Boolean {
        val idx = draft.indices.filter { draft[it].photos.size >= 2 }
            .maxByOrNull { draft[it].photos.size } ?: return false

        val page = draft[idx]
        val byTime = page.photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val mid = byTime.size / 2
        val left = byTime.subList(0, mid).toMutableList()
        val right = byTime.subList(mid, byTime.size).toMutableList()

        draft[idx] = DraftPage(left, isHero = isSoloHero(left, scores), episodeIndex = page.episodeIndex)
        draft.add(idx + 1, DraftPage(right, isHero = isSoloHero(right, scores), episodeIndex = page.episodeIndex))
        return true
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
            isHero = dp.isHero && ordered.size == 1,
            episodeIndex = dp.episodeIndex
        )
    }

    private fun medianTime(photos: List<Photo>): Instant? {
        val times = photos.mapNotNull { it.metadata.takenAt }.sorted()
        if (times.isEmpty()) return null
        return times[times.size / 2]
    }
}
