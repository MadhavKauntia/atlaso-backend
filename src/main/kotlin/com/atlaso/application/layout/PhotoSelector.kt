package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant

/**
 * Selects the best photos for a photobook using quality filtering,
 * burst detection, weighted scoring, and diversity selection.
 *
 * Algorithm is deterministic and explainable.
 */
@Component
class PhotoSelector(
    private val qualityThresholds: QualityThresholds = QualityThresholds(),
    private val burstConfig: BurstConfig = BurstConfig(),
    private val scoringWeights: ScoringWeights = ScoringWeights(),
    private val diversityConfig: DiversityConfig = DiversityConfig(),
    private val coverageConfig: CoverageConfig = CoverageConfig()
) {
    private val logger = LoggerFactory.getLogger(PhotoSelector::class.java)

    /**
     * Main entry point: selects photos for a photobook.
     *
     * @param photos All photos from the trip (must have signals populated)
     * @return Result with selected photos, logs, and statistics
     */
    fun selectPhotosForBook(photos: List<Photo>): PhotoSelectionResult {
        val log = mutableListOf<String>()

        // Phase 1: Quality Filtering
        logger.info("Starting photo selection with ${photos.size} photos")
        log.add("Starting with ${photos.size} photos")

        val qualityPhotos = filterLowQuality(photos, qualityThresholds)
        logger.info("After quality filter: ${qualityPhotos.size} photos")
        log.add("After quality filter: ${qualityPhotos.size} photos (removed ${photos.size - qualityPhotos.size})")

        // Phase 2: Burst Detection
        val dedupedPhotos = dedupeBursts(qualityPhotos, burstConfig)
        logger.info("After burst deduplication: ${dedupedPhotos.size} photos")
        log.add("After burst deduplication: ${dedupedPhotos.size} photos (removed ${qualityPhotos.size - dedupedPhotos.size})")

        // Phase 3: Adaptive selection — keep all photos up to 200 (50 pages × 4 per page max).
        // Above 200, apply diversity scoring to pick the best spread.
        // If quality filter + dedup dropped us below 50, relax: skip quality filter and dedup again.
        val maxPhotos = 200
        val minPhotos = 50
        val selectedPhotos = when {
            dedupedPhotos.size >= maxPhotos ->
                selectPhotos(dedupedPhotos, scoringWeights, diversityConfig.copy(targetPhotos = maxPhotos))
            dedupedPhotos.size >= minPhotos ->
                dedupedPhotos
            else -> {
                log.add("Only ${dedupedPhotos.size} photos after quality filter; relaxing constraints to reach $minPhotos pages")
                logger.info("Below minimum page count; falling back to dedup-only selection")
                val fallback = dedupeBursts(photos, burstConfig)
                val fallbackPhotos = if (fallback.size >= minPhotos) {
                    fallback
                } else {
                    log.add("Only ${fallback.size} photos after burst dedup; using all uploaded photos")
                    logger.info("Still below minimum after dedup-only fallback; using all uploaded photos")
                    photos
                }
                if (fallbackPhotos.size <= maxPhotos) fallbackPhotos
                else selectPhotos(fallbackPhotos, scoringWeights, diversityConfig.copy(targetPhotos = maxPhotos))
            }
        }
        logger.info("Final selection: ${selectedPhotos.size} photos")
        log.add("Final selection: ${selectedPhotos.size} photos")

        // Sort chronologically for natural story flow
        val chronological = selectedPhotos.sortedBy { it.metadata.takenAt ?: Instant.MIN }

        return PhotoSelectionResult(
            photos = chronological,
            logs = log,
            stats = calculateStats(chronological)
        )
    }

    /**
     * Phase 1: Remove photos that don't meet minimum quality standards.
     */
    private fun filterLowQuality(photos: List<Photo>, thresholds: QualityThresholds): List<Photo> {
        return photos.filter { photo ->
            val signals = photo.signals
            val metadata = photo.metadata

            // Must have AI analysis
            if (signals == null) {
                logger.debug("Filtered ${photo.id}: no AI signals")
                return@filter false
            }

            // Quality checks
            val passesAesthetic = signals.aestheticScore >= thresholds.minAestheticScore
            val passesBlur = signals.blurScore <= thresholds.maxBlurScore
            val passesDimension = metadata.width >= thresholds.minDimension &&
                                 metadata.height >= thresholds.minDimension

            if (!passesAesthetic) {
                logger.debug("Filtered ${photo.id}: aesthetic ${signals.aestheticScore} < ${thresholds.minAestheticScore}")
            }
            if (!passesBlur) {
                logger.debug("Filtered ${photo.id}: blur ${signals.blurScore} > ${thresholds.maxBlurScore}")
            }
            if (!passesDimension) {
                logger.debug("Filtered ${photo.id}: dimensions ${metadata.width}x${metadata.height}")
            }

            passesAesthetic && passesBlur && passesDimension
        }
    }

    /**
     * Phase 2: Detect bursts (rapid-fire shots) and keep only the best from each.
     */
    private fun dedupeBursts(photos: List<Photo>, config: BurstConfig): List<Photo> {
        val bursts = detectBursts(photos, config)

        logger.debug("Detected ${bursts.size} time-bursts")

        val burstPhotoIds = bursts.flatMap { it.photos.map { p -> p.id } }.toSet()
        val nonBurstPhotos = photos.filter { it.id !in burstPhotoIds }
        // Only collapse frames within a burst that are ALSO semantically similar, so
        // e.g. a wide beach shot and a close couple portrait taken seconds apart both
        // survive, while three near-identical selfies collapse to the best one.
        val keptFromBursts = bursts.flatMap { keepSemanticallyDistinct(it.photos) }

        return nonBurstPhotos + keptFromBursts
    }

    /** Within a time-burst, keep the best frame of each run of semantically-similar shots. */
    private fun keepSemanticallyDistinct(burstPhotos: List<Photo>): List<Photo> {
        val sorted = burstPhotos.sortedBy { it.metadata.takenAt }
        val kept = mutableListOf<Photo>()
        var cluster = mutableListOf<Photo>()
        fun flush() {
            if (cluster.isEmpty()) return
            kept.add(cluster.maxByOrNull { it.signals?.aestheticScore ?: 0.0 } ?: cluster.first())
            cluster = mutableListOf()
        }
        for (photo in sorted) {
            // Compare against the cluster's anchor so a drifting sequence splits.
            if (cluster.isEmpty() || isSemanticallySimilar(cluster.first(), photo)) {
                cluster.add(photo)
            } else {
                flush()
                cluster.add(photo)
            }
        }
        flush()
        return kept
    }

    private fun isSemanticallySimilar(a: Photo, b: Photo): Boolean {
        val sa = a.signals ?: return false
        val sb = b.signals ?: return false
        val sameLocation = sa.locationTag == sb.locationTag || sa.locationTag == null || sb.locationTag == null
        return sa.subjectType == sb.subjectType &&
            sa.shotDistance == sb.shotDistance &&
            sa.facesCount == sb.facesCount &&
            sameLocation &&
            objectJaccard(sa.detectedObjects, sb.detectedObjects) >= 0.4
    }

    private fun objectJaccard(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else sa.intersect(sb).size / union
    }

    /**
     * Detects photo bursts (groups taken within timeWindowSeconds).
     */
    private fun detectBursts(photos: List<Photo>, config: BurstConfig): List<Burst> {
        // Sort by capture time
        val sorted = photos
            .filter { it.metadata.takenAt != null }
            .sortedBy { it.metadata.takenAt }

        val bursts = mutableListOf<Burst>()
        var currentBurst = mutableListOf<Photo>()
        var burstStartTime: Instant? = null

        sorted.forEach { photo ->
            val photoTime = photo.metadata.takenAt!!

            if (burstStartTime == null) {
                // Start first burst
                burstStartTime = photoTime
                currentBurst.add(photo)
            } else {
                val timeSinceStart = Duration.between(burstStartTime, photoTime).seconds

                if (timeSinceStart <= config.timeWindowSeconds) {
                    // Same burst
                    currentBurst.add(photo)
                } else {
                    // New burst - save previous if it has multiple photos
                    if (currentBurst.size > 1) {
                        bursts.add(Burst(
                            photos = currentBurst.toList(),
                            startTime = burstStartTime!!,
                            endTime = currentBurst.last().metadata.takenAt!!
                        ))
                    }

                    // Start new burst
                    burstStartTime = photoTime
                    currentBurst = mutableListOf(photo)
                }
            }
        }

        // Add final burst
        if (currentBurst.size > 1) {
            bursts.add(Burst(
                photos = currentBurst.toList(),
                startTime = burstStartTime!!,
                endTime = currentBurst.last().metadata.takenAt!!
            ))
        }

        return bursts
    }

    /**
     * Phase 3 & 4: Score photos and select with diversity constraints.
     */
    private fun selectPhotos(
        photos: List<Photo>,
        weights: ScoringWeights,
        diversityConfig: DiversityConfig
    ): List<Photo> {
        val target = diversityConfig.targetPhotos

        // Coverage guard: before any competition, reserve the strongest usable photo of
        // every event so no activity is dropped just because it scored low. Only the
        // remaining slots are then filled by the diversity-weighted competition below.
        val reserved = if (coverageConfig.enabled) reserveCoveragePhotos(photos) else emptyList()
        if (reserved.size >= target) {
            logger.info("Coverage reservation ({}) meets/exceeds target ({}); keeping strongest per event", reserved.size, target)
            return reserved.sortedByDescending { it.signals?.aestheticScore ?: 0.0 }.take(target)
        }

        val selected = reserved.toMutableList()
        val reservedIds = reserved.mapNotNull { it.id }.toHashSet()
        val sceneTypeCounts = mutableMapOf<String?, Int>()
        val timeOfDayCounts = mutableMapOf<String?, Int>()
        // Seed diversity counters with the reserved photos so quotas account for them.
        reserved.forEach { p ->
            sceneTypeCounts[p.signals?.sceneType] = (sceneTypeCounts[p.signals?.sceneType] ?: 0) + 1
            timeOfDayCounts[p.signals?.timeOfDay] = (timeOfDayCounts[p.signals?.timeOfDay] ?: 0) + 1
        }
        logger.info("Coverage reserved {} photos across events before competition", reserved.size)

        // Calculate target counts per category
        val sceneTargets = diversityConfig.sceneTypeTargets.mapValues { (_, ratio) ->
            (target * ratio).toInt()
        }

        logger.debug("Scene targets: $sceneTargets")

        // Score the remaining (non-reserved) photos initially
        var scoredPhotos = photos.filter { it.id !in reservedIds }.map { photo ->
            scorePhoto(photo, weights, sceneTypeCounts, timeOfDayCounts)
        }.sortedByDescending { it.totalScore }

        // Pass 1: diversity-constrained selection
        val skippedByQuota = mutableListOf<Photo>()
        while (selected.size < diversityConfig.targetPhotos && scoredPhotos.isNotEmpty()) {
            val nextPhoto = scoredPhotos.first()
            val signals = nextPhoto.photo.signals!!

            val sceneCount = sceneTypeCounts[signals.sceneType] ?: 0
            val sceneTarget = sceneTargets[signals.sceneType] ?: 3

            if (sceneCount < sceneTarget) {
                selected.add(nextPhoto.photo)
                sceneTypeCounts[signals.sceneType] = sceneCount + 1
                timeOfDayCounts[signals.timeOfDay] = (timeOfDayCounts[signals.timeOfDay] ?: 0) + 1

                logger.debug("Selected photo ${nextPhoto.photo.id} (score: ${nextPhoto.totalScore}, scene: ${signals.sceneType})")

                scoredPhotos = scoredPhotos.drop(1).map { photoScore ->
                    scorePhoto(photoScore.photo, weights, sceneTypeCounts, timeOfDayCounts)
                }.sortedByDescending { it.totalScore }
            } else {
                logger.debug("Deferred photo ${nextPhoto.photo.id} (quota reached for ${signals.sceneType})")
                skippedByQuota.add(nextPhoto.photo)
                scoredPhotos = scoredPhotos.drop(1)
            }
        }

        // Pass 2: fill remaining slots with best skipped photos when target scene types were absent
        if (selected.size < diversityConfig.targetPhotos && skippedByQuota.isNotEmpty()) {
            val remaining = diversityConfig.targetPhotos - selected.size
            val fillPhotos = skippedByQuota
                .map { scorePhoto(it, weights, sceneTypeCounts, timeOfDayCounts) }
                .sortedByDescending { it.totalScore }
                .take(remaining)
            fillPhotos.forEach { logger.debug("Fill-selected photo ${it.photo.id} (scene: ${it.photo.signals?.sceneType})") }
            selected.addAll(fillPhotos.map { it.photo })
        }

        return selected
    }

    /**
     * Clusters photos into events (activities) and returns the single strongest usable
     * photo from each — the coverage set. An event boundary is a large time gap or a
     * location change, a lightweight proxy for the grouper's episode detection. This runs
     * only on large trips (inside [selectPhotos]); smaller trips keep every photo anyway.
     */
    private fun reserveCoveragePhotos(photos: List<Photo>): List<Photo> {
        if (photos.isEmpty()) return emptyList()
        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val events = mutableListOf<MutableList<Photo>>()
        var current = mutableListOf<Photo>()
        for (photo in sorted) {
            if (current.isEmpty() || !isNewEvent(current.last(), photo)) {
                current.add(photo)
            } else {
                events.add(current)
                current = mutableListOf(photo)
            }
        }
        if (current.isNotEmpty()) events.add(current)
        return events.mapNotNull { ev -> ev.maxByOrNull { it.signals?.aestheticScore ?: 0.0 } }
    }

    /** True when [b] begins a new event relative to [a]: a big time gap or a place change. */
    private fun isNewEvent(a: Photo, b: Photo): Boolean {
        val ta = a.metadata.takenAt
        val tb = b.metadata.takenAt
        val gapExceeded = ta != null && tb != null &&
            Duration.between(ta, tb).toMinutes() >= coverageConfig.eventGapMinutes
        val la = a.signals?.locationTag
        val lb = b.signals?.locationTag
        val locationChanged = la != null && lb != null && la != lb
        return gapExceeded || locationChanged
    }

    /**
     * Scores a single photo based on weighted factors.
     */
    private fun scorePhoto(
        photo: Photo,
        weights: ScoringWeights,
        sceneTypeCounts: Map<String?, Int>,
        timeOfDayCounts: Map<String?, Int>
    ): PhotoScore {
        val signals = photo.signals ?: return PhotoScore(
            photo = photo,
            totalScore = 0.0,
            breakdown = ScoreBreakdown(0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        )
        val metadata = photo.metadata

        // 1. Aesthetic Score (0.0 - 1.0)
        val aestheticScore = signals.aestheticScore * weights.aestheticWeight

        // 2. Sharpness Score (inverse of blur)
        val sharpnessScore = (1.0 - signals.blurScore) * weights.sharpnessWeight

        // 3. Scene Variety Bonus
        val sceneCount = sceneTypeCounts[signals.sceneType] ?: 0
        val sceneVarietyScore = calculateVarietyBonus(sceneCount) * weights.sceneVarietyBonus

        // 4. Time Variety Bonus
        val timeCount = timeOfDayCounts[signals.timeOfDay] ?: 0
        val timeVarietyScore = calculateVarietyBonus(timeCount) * weights.timeVarietyBonus

        // 5. Orientation Preference
        val orientation = Orientation.from(metadata.width, metadata.height)
        val orientationScore = when (orientation) {
            Orientation.LANDSCAPE -> 1.0
            Orientation.PORTRAIT -> 0.7
            Orientation.SQUARE -> 0.5
        } * weights.orientationPreference

        // 6. Lighting Preference
        val lightingScore = when (signals.timeOfDay) {
            "golden_hour" -> 1.0
            "day" -> 0.8
            "night" -> 0.6
            else -> 0.5
        } * weights.lightingPreference

        val totalScore = aestheticScore + sharpnessScore + sceneVarietyScore +
                        timeVarietyScore + orientationScore + lightingScore

        return PhotoScore(
            photo = photo,
            totalScore = totalScore,
            breakdown = ScoreBreakdown(
                aestheticScore = aestheticScore,
                sharpnessScore = sharpnessScore,
                sceneVarietyScore = sceneVarietyScore,
                timeVarietyScore = timeVarietyScore,
                orientationScore = orientationScore,
                lightingScore = lightingScore
            )
        )
    }

    /**
     * Calculates variety bonus inversely proportional to count.
     * More of a category → lower bonus.
     */
    private fun calculateVarietyBonus(count: Int): Double {
        return when {
            count == 0 -> 1.0
            count <= 2 -> 0.9
            count <= 5 -> 0.7
            count <= 10 -> 0.4
            else -> 0.0
        }
    }

    /**
     * Calculates statistics for the final selection.
     */
    private fun calculateStats(photos: List<Photo>): SelectionStats {
        val sceneTypeCounts = photos.groupingBy { it.signals?.sceneType }.eachCount()
        val timeOfDayCounts = photos.groupingBy { it.signals?.timeOfDay }.eachCount()
        val avgAesthetic = photos.mapNotNull { it.signals?.aestheticScore }.average()
        val avgBlur = photos.mapNotNull { it.signals?.blurScore }.average()

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
 * Configuration for burst detection.
 */
data class BurstConfig(
    val timeWindowSeconds: Long = 10
)

/**
 * Configuration for scoring weights.
 * All weights should sum to ~1.0 for normalized scores.
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
 * Configuration for diversity-aware selection.
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
 * Configuration for the coverage guard applied on large trips (>200 photos), where the
 * diversity competition would otherwise be free to drop an entire low-scoring event.
 */
data class CoverageConfig(
    val enabled: Boolean = true,
    // Consecutive photos more than this many minutes apart start a new event.
    val eventGapMinutes: Long = 60
)

/**
 * A burst of photos taken in rapid succession.
 */
data class Burst(
    val photos: List<Photo>,
    val startTime: Instant,
    val endTime: Instant
) {
    fun getBestPhoto(): Photo {
        return photos.maxByOrNull { it.signals?.aestheticScore ?: 0.0 }
            ?: photos.first()
    }
}

/**
 * Photo with computed score and breakdown.
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
