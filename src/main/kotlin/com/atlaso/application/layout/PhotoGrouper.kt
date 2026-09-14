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

        // A page holds 1 to 4 photos. Every chunk / merge / split below preserves this.
        private const val MAX_PHOTOS_PER_PAGE = 4
        private val LEGAL_PAGE_SIZES = (1..MAX_PHOTOS_PER_PAGE).toSet()

        // A gap between two photos starts a new episode when its boundary score clears
        // this. ~0.55 needs a real time gap plus a location or scene change, so photos
        // within one activity stay together while day/place changes split.
        private const val EPISODE_THRESHOLD = 0.55

        // standalone_score at/above this reserves a photo as a solo page. Slightly
        // lower still qualifies for the single best photo of a decent episode.
        private const val HERO_THRESHOLD = StandaloneScorer.STRONG_THRESHOLD // 0.75
        private const val HERO_PROMOTE_THRESHOLD = 0.68

        // Near-duplicate curation (req: no more than 2 similar shots per event).
        // Within an episode, photos that share subject + people + composition + place
        // collapse to their single strongest frame, and keep a 2nd only when it is
        // nearly as strong (within this fraction of the best).
        private const val DUP_KEEP_SECOND_RATIO = 0.85

        // Book length is fixed at TARGET_PAGE_COUNT. When aggressive dedup + coverage
        // leave too few photos to build a good book, we relax dedup (pull duplicates
        // back) toward this many photos rather than padding with weak images.
        private const val FILL_RATIO = 1.6
    }

    /** A page under construction (before it becomes an immutable PhotoGroup). */
    private data class DraftPage(val photos: MutableList<Photo>, var isHero: Boolean) {
        val size: Int get() = photos.size
    }

    /** Result of the curation pass: kept photos per episode + the coverage-protected ids. */
    private data class Curation(val episodes: List<List<Photo>>, val protectedIds: Set<UUID>)

    /**
     * Groups photos into exactly [targetPages] page groups (or fewer when there are
     * genuinely too few photos to fill the book).
     *
     * 1. Sort chronologically, split into natural episodes.
     * 2. Curate each episode: guarantee coverage (keep every event's strongest photo),
     *    cap near-duplicates at 2, and — only if that leaves too few photos — relax
     *    dedup to fill the book instead of padding with weak images.
     * 3. Per episode: reserve strong standalone photos as solo pages, chunk the rest
     *    into pages of up to 4 photos.
     * 4. Normalize to exactly [targetPages], always keeping pages within 1–4 photos and
     *    never dropping a coverage-protected photo.
     */
    fun group(photos: List<Photo>, targetPages: Int = TARGET_PAGE_COUNT): List<PhotoGroup> {
        if (photos.isEmpty()) return emptyList()

        // Primary: takenAt ascending, nulls last. Secondary: filename (IMG_NNNN order
        // approximates shooting order when EXIF timestamps are absent).
        val sorted = photos
            .sortedBy { it.originalFilename }
            .sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })

        val episodes = detectEpisodes(sorted)

        // Episode-relative standalone scores for every photo (used for dedup, heroes, layout).
        val standaloneById = HashMap<UUID, Double>()
        episodes.forEach { ep -> standaloneById.putAll(StandaloneScorer.scoreEpisode(ep)) }

        // Coverage-first curation: dedup near-duplicates, protect one photo per episode,
        // relax dedup to fill the book if needed.
        val curation = curate(episodes, standaloneById, targetPages)

        // Draft pages, episode by episode, in chronological order (each page 1–4 photos).
        val draft = mutableListOf<DraftPage>()
        curation.episodes.forEach { ep -> if (ep.isNotEmpty()) draft.addAll(buildEpisodePages(ep, standaloneById)) }

        // Normalize to exactly the target page count, preserving legal sizes + coverage.
        while (draft.size > targetPages) reduceOnePage(draft, standaloneById, curation.protectedIds)
        while (draft.size < targetPages) { if (!expandOnePage(draft, standaloneById)) break }

        require(draft.all { it.size in LEGAL_PAGE_SIZES }) {
            "Illegal page size produced: ${draft.map { it.size }.filter { it !in LEGAL_PAGE_SIZES }}"
        }

        logger.info(
            "Grouped {} photos into {} pages across {} episodes ({} hero pages, {} kept after curation)",
            sorted.size, draft.size, episodes.size, draft.count { it.isHero },
            curation.episodes.sumOf { it.size }
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
     * Coverage-first curation.
     *
     * Per episode: cluster near-duplicate shots (same subject + people + composition +
     * place) and keep the strongest of each cluster — plus a 2nd only when it is nearly
     * as strong. The episode's overall best photo is *protected* so normalization can
     * never drop an event entirely (req: reserve ≥1 usable photo from every event).
     *
     * The rest become "surplus". Book length is fixed, so if kept photos fall short of
     * what a good book needs we relax dedup: pull the highest-scoring surplus (i.e. the
     * best near-duplicates) back in — never low-quality padding.
     */
    private fun curate(
        episodes: List<List<Photo>>,
        scores: Map<UUID, Double>,
        targetPages: Int
    ): Curation {
        val protectedIds = HashSet<UUID>()
        val keptByEpisode = ArrayList<MutableList<Photo>>(episodes.size)
        val surplusIds = HashSet<UUID>()
        var available = 0

        for (ep in episodes) {
            available += ep.size
            // Protect the episode's strongest photo — its guaranteed representative.
            ep.maxByOrNull { scores[it.id] ?: 0.0 }?.id?.let { protectedIds.add(it) }

            val kept = mutableListOf<Photo>()
            ep.groupBy { dupSignature(it) }.forEach { (_, cluster) ->
                val ranked = cluster.sortedByDescending { scores[it.id] ?: 0.0 }
                kept.add(ranked[0])
                if (ranked.size >= 2) {
                    val best = scores[ranked[0].id] ?: 0.0
                    val second = scores[ranked[1].id] ?: 0.0
                    if (second >= best * DUP_KEEP_SECOND_RATIO) {
                        kept.add(ranked[1])
                        ranked.drop(2).forEach { it.id?.let(surplusIds::add) }
                    } else {
                        ranked.drop(1).forEach { it.id?.let(surplusIds::add) }
                    }
                }
            }
            keptByEpisode.add(kept)
        }

        val keptCount = keptByEpisode.sumOf { it.size }
        val desired = minOf(available, (targetPages * FILL_RATIO).toInt())
        if (keptCount < desired && surplusIds.isNotEmpty()) {
            val need = desired - keptCount
            val addBack = episodes.flatten()
                .filter { it.id in surplusIds }
                .sortedByDescending { scores[it.id] ?: 0.0 }
                .take(need)
                .mapNotNull { it.id }
                .toHashSet()
            if (addBack.isNotEmpty()) {
                for (i in episodes.indices) {
                    val extra = episodes[i].filter { it.id in addBack }
                    if (extra.isNotEmpty()) keptByEpisode[i].addAll(extra)
                }
            }
        }

        val curated = keptByEpisode.map { it.sortedWith(compareBy(nullsLast()) { p -> p.metadata.takenAt }) }
        return Curation(curated, protectedIds)
    }

    /** Signature identifying near-duplicate shots: same subject, framing, people count, place. */
    private fun dupSignature(p: Photo): String {
        val s = p.signals ?: return "unique-${p.id}"
        val faces = when {
            s.facesCount <= 0 -> "0"
            s.facesCount == 1 -> "1"
            s.facesCount <= 3 -> "2-3"
            else -> "4+"
        }
        val place = s.locationTag ?: s.sceneType ?: "?"
        return "${s.subjectType}|${s.shotDistance}|$faces|$place"
    }

    /**
     * Reserves strong standalone photos as solo pages (inline, preserving chronology)
     * and chunks the remaining photos into pages of up to [MAX_PHOTOS_PER_PAGE].
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

    /**
     * Removes one page to hit the target count. Merges the two adjacent pages whose
     * combination loses the fewest photos (prefer non-heroes, then the weakest region),
     * keeping up to [MAX_PHOTOS_PER_PAGE] and never dropping a coverage-protected photo.
     */
    private fun reduceOnePage(draft: MutableList<DraftPage>, scores: Map<UUID, Double>, protectedIds: Set<UUID>) {
        if (draft.size < 2) return

        data class Merge(val i: Int, val drops: Int, val heroPenalty: Int, val combinedScore: Double)

        val best = (0 until draft.size - 1).map { i ->
            val a = draft[i]; val b = draft[i + 1]
            val sum = a.size + b.size
            val drops = (sum - MAX_PHOTOS_PER_PAGE).coerceAtLeast(0)
            val heroPenalty = (if (a.isHero) 1 else 0) + (if (b.isHero) 1 else 0)
            val combined = a.photos.sumOf { scores[it.id] ?: 0.0 } + b.photos.sumOf { scores[it.id] ?: 0.0 }
            Merge(i, drops, heroPenalty, combined)
        }.minWithOrNull(compareBy({ it.drops }, { it.heroPenalty }, { it.combinedScore }))!!

        val i = best.i
        val all = draft[i].photos + draft[i + 1].photos
        val (protectedPhotos, others) = all.partition { it.id in protectedIds }
        // Keep as many as fit (up to the max), coverage-protected photos first.
        val keepCount = minOf(all.size, MAX_PHOTOS_PER_PAGE)
        val kept = (protectedPhotos + others.sortedByDescending { scores[it.id] ?: 0.0 })
            .take(keepCount)
            .toMutableList()

        draft[i] = DraftPage(kept, isHero = keepCount == 1 && (draft[i].isHero || draft[i + 1].isHero))
        draft.removeAt(i + 1)
    }

    /**
     * Adds one page to hit the target count by splitting the densest page in half
     * (4 → 2+2, 3 → 1+2, 2 → 1+1). Returns false when no page can be split (all singles),
     * so the caller stops instead of looping forever.
     */
    private fun expandOnePage(draft: MutableList<DraftPage>, scores: Map<UUID, Double>): Boolean {
        val idx = draft.indices.filter { draft[it].size >= 2 }.maxByOrNull { draft[it].size } ?: return false

        val byTime = draft[idx].photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val mid = byTime.size / 2
        val left = byTime.subList(0, mid).toMutableList()
        val right = byTime.subList(mid, byTime.size).toMutableList()

        draft[idx] = DraftPage(left, isHero = isSoloHero(left, scores))
        draft.add(idx + 1, DraftPage(right, isHero = isSoloHero(right, scores)))
        return true
    }

    private fun isSoloHero(photos: List<Photo>, scores: Map<UUID, Double>): Boolean =
        photos.size == 1 && (scores[photos[0].id] ?: 0.0) >= HERO_PROMOTE_THRESHOLD

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
