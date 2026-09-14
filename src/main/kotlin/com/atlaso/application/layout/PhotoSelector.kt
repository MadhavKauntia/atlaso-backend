package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Curates the photos that make it into the book. The goal is breadth of memory, not the
 * highest-scoring photos: every meaningful episode is represented first (coverage), near
 * duplicates collapse, and each additional photo from an already well-covered event is
 * worth progressively less (saturation).
 *
 * Order of operations (all deterministic):
 *   1. Quality filter (§3).
 *   2. Episodes (§4, shared [EpisodeDetector]).
 *   3. Near-duplicate clustering within each episode — keep 1, allow 2 if meaningfully
 *      different (§6).
 *   4. Coverage-first: reserve the strongest representative of every episode, then cover
 *      every trip day (§5).
 *   5. Saturation fill: add remaining photos by adjustedScore until the budget is hit or
 *      the best remaining photo is no longer worth adding (§7, §8).
 *
 * Output is chronological. Page grouping / hero reservation / layout happen later in
 * [PhotoGrouper] and [LayoutEngine].
 */
@Component
class PhotoSelector(
    private val qualityThresholds: QualityThresholds = QualityThresholds(),
    private val burstConfig: BurstConfig = BurstConfig(),
    private val scoringWeights: ScoringWeights = ScoringWeights(),
    private val diversityConfig: DiversityConfig = DiversityConfig()
) {
    private val logger = LoggerFactory.getLogger(PhotoSelector::class.java)

    private companion object {
        // Upper bound on selected photos: 50 pages × 4 photos/page.
        const val MAX_PHOTOS = 200
        const val TARGET_PAGES = PhotoGrouper.TARGET_PAGE_COUNT
        // Aim for ~2 photos per page so the book is a MIX of hero singles and supporting
        // 2-/4-photo pages — not one photo per page. Selection fills toward this when the
        // material exists; below it, everything usable is kept (§16, §17).
        const val SELECTION_TARGET = TARGET_PAGES * 2

        // Two photos are near-duplicates when they are near-identical in composition AND
        // show the same content (object overlap), or when they're within a burst window and
        // clearly the same moment (§6). Composition alone is not enough — with weak signals
        // (many photos tagged the same generic subject/shot/scope) it would over-collapse
        // unrelated photos.
        const val NEAR_DUP_THRESHOLD = 0.80
        const val NEAR_DUP_OBJECT_OVERLAP = 0.5
        const val BURST_NEAR_DUP_THRESHOLD = 0.60

        // adjustedScore weights (§7). Simple and tunable.
        const val W_QUALITY = 1.0
        const val W_UNIQUENESS = 0.5
        const val W_UNDERREP = 0.4
        const val W_SIMILARITY = 0.6
        const val EPISODE_SAT_STEP = 0.08      // penalty per photo already taken from the episode
        const val ROLE_SAT_STEP = 0.05         // penalty per photo already taken of the role
        const val ROLE_SAT_HEAVY_MULT = 1.5    // extra penalty for group/landscape/detail (§8)
        // Below this, an additional photo isn't worth adding once the book is full enough.
        const val SATURATION_FLOOR = 0.45
    }

    /**
     * Main entry point: selects the photos for a photobook. Signature unchanged.
     *
     * @param photos All photos from the trip (analyzed ones have signals populated)
     */
    fun selectPhotosForBook(photos: List<Photo>): PhotoSelectionResult {
        val log = mutableListOf<String>()
        logger.info("Starting photo selection with {} photos", photos.size)
        log.add("Starting with ${photos.size} photos")

        // Phase 1: quality filter (§3).
        val quality = filterLowQuality(photos, qualityThresholds)
        log.add("After quality filter: ${quality.size} photos (removed ${photos.size - quality.size})")
        logger.info("After quality filter: {} photos", quality.size)

        if (quality.isEmpty()) {
            // Nothing usable — keep generation alive with whatever exists, chronologically.
            val chrono = photos.sortedBy { it.metadata.takenAt ?: Instant.MIN }
            log.add("No photos passed quality filter; using all uploaded photos")
            return PhotoSelectionResult(chrono, log, calculateStats(chrono))
        }

        // Phase 2: episodes (§4).
        val sorted = EpisodeDetector.sortChronologically(quality)
        val episodes = EpisodeDetector.detect(sorted)
        log.add("Detected ${episodes.size} episodes")
        val cache = PhotoSimilarity.Cache()

        // Phase 3: near-duplicate clustering within each episode (§6).
        val episodeCandidates = episodes.map { dedupeEpisode(it, cache) }
        val allCandidates = episodeCandidates.flatten()
        val episodeOf: Map<UUID, Int> = buildMap {
            episodeCandidates.forEachIndexed { idx, cands -> cands.forEach { it.id?.let { id -> put(id, idx) } } }
        }
        log.add("After near-duplicate clustering: ${allCandidates.size} distinct candidates")
        logger.info("Near-duplicate clustering: {} -> {} candidates", quality.size, allCandidates.size)

        // Selection state. Membership is tracked by id (JPA entities aren't safe hash keys).
        val selectedIds = LinkedHashSet<UUID>()
        val selectedPhotos = mutableListOf<Photo>()
        val perEpisodeCount = IntArray(episodes.size)
        val roleCount = HashMap<VisualRole, Int>()

        fun take(photo: Photo) {
            val id = photo.id ?: return
            if (!selectedIds.add(id)) return
            selectedPhotos.add(photo)
            episodeOf[id]?.let { perEpisodeCount[it]++ }
            roleCount.merge(VisualRole.of(photo), 1, Int::plus)
        }

        // Phase 4a: coverage — strongest representative of every episode (§5).
        episodeCandidates.forEach { cands ->
            cands.maxByOrNull { StandaloneScorer.baseScore(it) }?.let { take(it) }
        }
        // Phase 4b: coverage — represent every trip day that has usable photos (§5).
        val coveredDays = selectedPhotos.mapNotNull { EpisodeDetector.dayOf(it) }.toMutableSet()
        allCandidates.groupBy { EpisodeDetector.dayOf(it) }.forEach { (day, dayPhotos) ->
            if (day != null && day !in coveredDays) {
                dayPhotos.maxByOrNull { StandaloneScorer.baseScore(it) }?.let { take(it); coveredDays.add(day) }
            }
        }
        log.add("Coverage reserved ${selectedPhotos.size} photos (${episodes.size} episodes, ${coveredDays.size} days)")

        // Phase 5: saturation fill (§7, §8).
        val budget = minOf(MAX_PHOTOS, allCandidates.size)
        val minEnough = minOf(budget, SELECTION_TARGET)
        while (selectedPhotos.size < budget) {
            var best: Photo? = null
            var bestScore = Double.NEGATIVE_INFINITY
            for (photo in allCandidates) {
                if (photo.id != null && photo.id in selectedIds) continue
                val score = adjustedScore(photo, episodeOf, selectedPhotos, perEpisodeCount, roleCount, cache)
                if (score > bestScore) { bestScore = score; best = photo }
            }
            if (best == null) break
            // Once the book is full enough, stop adding photos that no longer earn their place.
            if (selectedPhotos.size >= minEnough && bestScore < SATURATION_FLOOR) break
            take(best)
        }

        val chronological = selectedPhotos.sortedBy { it.metadata.takenAt ?: Instant.MIN }
        log.add("Final selection: ${chronological.size} photos")
        logger.info("Final selection: {} photos", chronological.size)

        return PhotoSelectionResult(
            photos = chronological,
            logs = log,
            stats = calculateStats(chronological)
        )
    }

    /**
     * Phase 1: remove photos that don't meet minimum quality standards (§3).
     */
    private fun filterLowQuality(photos: List<Photo>, thresholds: QualityThresholds): List<Photo> {
        return photos.filter { photo ->
            val signals = photo.signals ?: run {
                logger.debug("Filtered {}: no AI signals", photo.id); return@filter false
            }
            val metadata = photo.metadata
            val passesAesthetic = signals.aestheticScore >= thresholds.minAestheticScore
            val passesBlur = signals.blurScore <= thresholds.maxBlurScore
            val passesDimension = metadata.width >= thresholds.minDimension &&
                metadata.height >= thresholds.minDimension
            passesAesthetic && passesBlur && passesDimension
        }
    }

    /**
     * Near-duplicate clustering within one episode (§6). Clusters photos that are visually
     * the same moment, keeps the best of each cluster, and allows a second only when it
     * communicates meaningfully different information (e.g. wide environment + close action).
     */
    private fun dedupeEpisode(episode: List<Photo>, cache: PhotoSimilarity.Cache): List<Photo> {
        if (episode.size <= 1) return episode
        val ordered = EpisodeDetector.sortChronologically(episode)
        val clusters = mutableListOf<MutableList<Photo>>()
        for (photo in ordered) {
            val target = clusters.firstOrNull { cluster -> cluster.any { isNearDuplicate(it, photo, cache) } }
            if (target != null) target.add(photo) else clusters.add(mutableListOf(photo))
        }
        return clusters.flatMap { cluster ->
            val byQuality = cluster.sortedByDescending { StandaloneScorer.baseScore(it) }
            val kept = mutableListOf(byQuality.first())
            // Allow one extra photo per cluster if it genuinely differs and is usable.
            byQuality.drop(1)
                .firstOrNull { differsMeaningfully(it, kept.first()) && StandaloneScorer.baseScore(it) >= 0.45 }
                ?.let { kept.add(it) }
            kept
        }
    }

    private fun isNearDuplicate(a: Photo, b: Photo, cache: PhotoSimilarity.Cache): Boolean {
        val sa = a.signals
        val sb = b.signals
        // Different scene type = different content, never a near-duplicate (§6).
        if (sa?.sceneType != null && sb?.sceneType != null && sa.sceneType != sb.sceneType) return false

        val sim = cache.similarity(a, b)
        // Near-identical composition AND clearly the same content (shared objects).
        val objectOverlap = PhotoSimilarity.objectJaccard(
            sa?.detectedObjects ?: emptyList(), sb?.detectedObjects ?: emptyList()
        )
        if (sim >= NEAR_DUP_THRESHOLD && objectOverlap >= NEAR_DUP_OBJECT_OVERLAP) return true
        // Timestamp is a clustering signal: same burst + similar composition = same moment.
        val ta = a.metadata.takenAt
        val tb = b.metadata.takenAt
        val timeClose = ta != null && tb != null &&
            Duration.between(ta, tb).abs().seconds <= burstConfig.timeWindowSeconds
        val sameFaces = sa?.facesCount == sb?.facesCount
        val sameLocation = sa?.locationTag == sb?.locationTag || sa?.locationTag == null || sb?.locationTag == null
        return timeClose && sim >= BURST_NEAR_DUP_THRESHOLD && sameFaces && sameLocation
    }

    /** Whether [a] adds information over [b] worth keeping a second frame for (§6). */
    private fun differsMeaningfully(a: Photo, b: Photo): Boolean {
        val sa = a.signals ?: return false
        val sb = b.signals ?: return false
        // Clearly different content only counts when both actually list objects — an empty
        // list is "unknown", not "different".
        val bothHaveObjects = sa.detectedObjects.isNotEmpty() && sb.detectedObjects.isNotEmpty()
        return VisualRole.of(a) != VisualRole.of(b) ||
            sa.shotDistance != sb.shotDistance ||
            sa.settingScope != sb.settingScope ||
            (bothHaveObjects && PhotoSimilarity.objectJaccard(sa.detectedObjects, sb.detectedObjects) < 0.3)
    }

    /**
     * Saturation-aware value of adding [photo] given what's already selected (§7):
     *   quality + uniqueness + underrepresentationBonus
     *     − similarityPenalty − episodeSaturationPenalty − visualRoleSaturationPenalty
     */
    private fun adjustedScore(
        photo: Photo,
        episodeOf: Map<UUID, Int>,
        selected: List<Photo>,
        perEpisodeCount: IntArray,
        roleCount: Map<VisualRole, Int>,
        cache: PhotoSimilarity.Cache
    ): Double {
        val episodeIdx = photo.id?.let { episodeOf[it] } ?: -1
        val quality = StandaloneScorer.baseScore(photo)

        // Uniqueness relative to already-selected photos in the same episode.
        val selectedInEpisode = selected.filter { sel ->
            episodeIdx >= 0 && sel.id?.let { episodeOf[it] } == episodeIdx
        }
        val uniqueness = if (selectedInEpisode.isEmpty()) 1.0
        else (1.0 - selectedInEpisode.map { cache.similarity(photo, it) }.average()).coerceIn(0.0, 1.0)

        // Similarity penalty: closeness to the most-similar already-selected photo anywhere.
        val similarityPenalty = if (selected.isEmpty()) 0.0
        else selected.maxOf { cache.similarity(photo, it) }

        val epCount = if (episodeIdx in perEpisodeCount.indices) perEpisodeCount[episodeIdx] else 0
        val underrepBonus = 1.0 / (1.0 + epCount)

        val episodeSatPenalty = epCount * EPISODE_SAT_STEP
        val role = VisualRole.of(photo)
        val roleMult = if (role in VisualRole.HEAVILY_PENALIZED) ROLE_SAT_HEAVY_MULT else 1.0
        val roleSatPenalty = (roleCount[role] ?: 0) * ROLE_SAT_STEP * roleMult

        return W_QUALITY * quality +
            W_UNIQUENESS * uniqueness +
            W_UNDERREP * underrepBonus -
            W_SIMILARITY * similarityPenalty -
            episodeSatPenalty -
            roleSatPenalty
    }

    /**
     * Calculates statistics for the final selection (unchanged shape).
     */
    private fun calculateStats(photos: List<Photo>): SelectionStats {
        val sceneTypeCounts = photos.groupingBy { it.signals?.sceneType }.eachCount()
        val timeOfDayCounts = photos.groupingBy { it.signals?.timeOfDay }.eachCount()
        val avgAesthetic = photos.mapNotNull { it.signals?.aestheticScore }.average().takeIf { !it.isNaN() } ?: 0.0
        val avgBlur = photos.mapNotNull { it.signals?.blurScore }.average().takeIf { !it.isNaN() } ?: 0.0

        return SelectionStats(
            totalPhotos = photos.size,
            sceneTypeDistribution = sceneTypeCounts,
            timeOfDayDistribution = timeOfDayCounts,
            averageAestheticScore = avgAesthetic,
            averageBlurScore = avgBlur
        )
    }
}

/**
 * Configuration for quality filtering.
 */
data class QualityThresholds(
    val minAestheticScore: Double = 0.4,
    val maxBlurScore: Double = 0.6,
    val minDimension: Int = 1200
)

/**
 * Configuration for burst detection (near-duplicate time window).
 */
data class BurstConfig(
    val timeWindowSeconds: Long = 10
)

/**
 * Configuration for scoring weights. Retained for the score explainer / diagnostics.
 */
data class ScoringWeights(
    val aestheticWeight: Double = 0.50,
    val sharpnessWeight: Double = 0.20,
    val sceneVarietyBonus: Double = 0.10,
    val timeVarietyBonus: Double = 0.05,
    val orientationPreference: Double = 0.10,
    val lightingPreference: Double = 0.05
)

/**
 * Configuration for diversity-aware selection. Retained for diagnostics/compatibility.
 */
data class DiversityConfig(
    val targetPhotos: Int = 30,
    val sceneTypeTargets: Map<String, Double> = mapOf(
        "people" to 0.30,
        "landscape" to 0.35,
        "city" to 0.15,
        "food" to 0.10,
        "misc" to 0.10
    ),
    val timeTargets: Map<String, Double> = mapOf(
        "day" to 0.60,
        "golden_hour" to 0.25,
        "night" to 0.15
    )
)

/**
 * Photo with computed score and breakdown (used by PhotoScoreExplainer/diagnostics).
 */
data class PhotoScore(
    val photo: Photo,
    val totalScore: Double,
    val breakdown: ScoreBreakdown
)

/**
 * Detailed score breakdown for debugging.
 */
data class ScoreBreakdown(
    val aestheticScore: Double,
    val sharpnessScore: Double,
    val sceneVarietyScore: Double,
    val timeVarietyScore: Double,
    val orientationScore: Double,
    val lightingScore: Double
)

/**
 * Photo orientation based on aspect ratio.
 */
enum class Orientation {
    LANDSCAPE,
    PORTRAIT,
    SQUARE;

    companion object {
        fun from(width: Int, height: Int): Orientation {
            val ratio = width.toDouble() / height
            return when {
                ratio > 1.1 -> LANDSCAPE
                ratio < 0.9 -> PORTRAIT
                else -> SQUARE
            }
        }
    }
}

/**
 * Result of photo selection with metadata.
 */
data class PhotoSelectionResult(
    val photos: List<Photo>,
    val logs: List<String>,
    val stats: SelectionStats
)

/**
 * Statistics about the selected photos.
 */
data class SelectionStats(
    val totalPhotos: Int,
    val sceneTypeDistribution: Map<String?, Int>,
    val timeOfDayDistribution: Map<String?, Int>,
    val averageAestheticScore: Double,
    val averageBlurScore: Double
)
