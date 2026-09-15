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

    companion object {
        // subjectType values that mark a photo as people-focused (exempt from the repeat-subject cap).
        private val PEOPLE_SUBJECT_TYPES = setOf("person", "couple", "group")
    }

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

        // Phase 2b: Repeat-subject cap. Near-dup dedup relies on noisy exact-match fields, so the
        // same non-people subject shot many times across a session (e.g. 8 coconut drinks) can
        // still slip through as separate "distinct" frames. Cap each non-people primarySubject to
        // its best few. People are exempt — a recurring person across activities is desired.
        val cappedPhotos = capRepeatedSubjects(dedupedPhotos, diversityConfig.maxSameSubject)
        if (cappedPhotos.size < dedupedPhotos.size) {
            logger.info("After repeat-subject cap: ${cappedPhotos.size} photos")
            log.add("After repeat-subject cap: ${cappedPhotos.size} photos (removed ${dedupedPhotos.size - cappedPhotos.size})")
        }

        // Phase 3: Adaptive selection — keep all photos up to 150. Across the fixed 50 pages that
        // averages ~3 photos/page, deliberately leaving the grouper room for pairs and threes
        // instead of packing nearly every page into a 4-up 2×2 grid (150 ÷ 50 = 3.0, vs the old
        // 200 cap = 4.0 which forced dense-dense spreads throughout). Above 150, diversity scoring
        // picks the best spread. If quality filter + dedup dropped us below 50, relax: skip the
        // quality filter and dedup again.
        val maxPhotos = 150
        val minPhotos = 50
        val selectedPhotos = when {
            cappedPhotos.size >= maxPhotos ->
                selectPhotos(cappedPhotos, scoringWeights, diversityConfig.copy(targetPhotos = maxPhotos))
            cappedPhotos.size >= minPhotos ->
                cappedPhotos
            else -> {
                log.add("Only ${cappedPhotos.size} photos after quality filter; relaxing constraints to reach $minPhotos pages")
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
            // Drop clearly mundane/utility shots (bike lock, shoes, storefronts, a lone coffee
            // cup) that are technically fine but no one would keep. keepsakeInterest is the only
            // signal that separates these from photogenic shots of similar aesthetic score.
            val passesKeepsake = signals.keepsakeInterest >= thresholds.minKeepsakeInterest
            // Drop documentary shots (menus, receipts, tickets, signage, screenshots…) — the
            // vision model labels them a real scene (often "food"), and their sharpness can
            // otherwise ride them into the book over more photogenic images.
            val excludedObject = signals.detectedObjects.firstOrNull { matchesExcludedObject(it, thresholds.excludedObjectTags) }

            if (!passesAesthetic) {
                logger.debug("Filtered ${photo.id}: aesthetic ${signals.aestheticScore} < ${thresholds.minAestheticScore}")
            }
            if (!passesBlur) {
                logger.debug("Filtered ${photo.id}: blur ${signals.blurScore} > ${thresholds.maxBlurScore}")
            }
            if (!passesDimension) {
                logger.debug("Filtered ${photo.id}: dimensions ${metadata.width}x${metadata.height}")
            }
            if (excludedObject != null) {
                logger.debug("Filtered ${photo.id}: documentary object '$excludedObject'")
            }
            if (!passesKeepsake) {
                logger.debug("Filtered ${photo.id}: keepsakeInterest ${signals.keepsakeInterest} < ${thresholds.minKeepsakeInterest} (mundane/utility)")
            }

            passesAesthetic && passesBlur && passesDimension && excludedObject == null && passesKeepsake
        }
    }

    /**
     * True when a detected-object label is a documentary/non-photogenic subject we exclude.
     * Matches whole word-tokens for single-word tags (so "text" won't hit "texture") and
     * substring for multi-word tags (e.g. "boarding pass").
     */
    private fun matchesExcludedObject(label: String, excluded: Set<String>): Boolean {
        val tag = label.lowercase().trim()
        val tokens = tag.split(Regex("[^a-z0-9]+")).filter { it.isNotBlank() }.toHashSet()
        return excluded.any { ex -> if (ex.contains(' ')) tag.contains(ex) else tokens.contains(ex) }
    }

    /**
     * Phase 2: Near-duplicate suppression. Collapses time-proximate, content-similar frames
     * (the "let me take another" repeats — retries, sunset bursts, selfie runs) to their single
     * best frame. Unlike a fixed burst window, a cluster extends as long as consecutive frames
     * stay within [BurstConfig.timeWindowSeconds] of each other AND remain near-identical to the
     * cluster anchor, so a series of near-dupes spread over a minute collapses too. A genuinely
     * different shot (new subject/framing/objects) breaks the cluster and survives — so two
     * distinct memories from the same spot both stay; only true repeats are dropped.
     */
    private fun dedupeBursts(photos: List<Photo>, config: BurstConfig): List<Photo> {
        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val kept = mutableListOf<Photo>()
        var cluster = mutableListOf<Photo>()
        fun flush() {
            if (cluster.isEmpty()) return
            kept.add(cluster.maxByOrNull { rankScore(it) } ?: cluster.first())
            cluster = mutableListOf()
        }
        for (photo in sorted) {
            val anchor = cluster.firstOrNull()
            val prev = cluster.lastOrNull()
            val closeInTime = prev != null && withinSeconds(prev, photo, config.timeWindowSeconds)
            if (anchor == null || (closeInTime && isNearDuplicate(anchor, photo))) {
                cluster.add(photo)
            } else {
                flush()
                cluster.add(photo)
            }
        }
        flush()
        return kept
    }

    private fun withinSeconds(a: Photo, b: Photo, seconds: Long): Boolean {
        val ta = a.metadata.takenAt ?: return false
        val tb = b.metadata.takenAt ?: return false
        return kotlin.math.abs(Duration.between(ta, tb).seconds) <= seconds
    }

    /**
     * True when [b] is a near-duplicate of [a]: same subject, framing, people, place, and a
     * high overlap of detected objects. Deliberately strict so distinct shots from the same
     * setting (a wide vs a close-up, two different dishes) are NOT merged.
     */
    private fun isNearDuplicate(a: Photo, b: Photo): Boolean {
        val sa = a.signals ?: return false
        val sb = b.signals ?: return false
        val sameLocation = sa.locationTag == sb.locationTag || sa.locationTag == null || sb.locationTag == null
        return sa.subjectType == sb.subjectType &&
            sa.shotDistance == sb.shotDistance &&
            sa.facesCount == sb.facesCount &&
            sameLocation &&
            objectJaccard(sa.detectedObjects, sb.detectedObjects) >= 0.5
    }

    /** Static per-photo quality used for ranking within a dedup cluster or an event. */
    private fun rankScore(photo: Photo): Double {
        val s = photo.signals ?: return 0.0
        val m = photo.metadata
        val orientation = when (Orientation.from(m.width, m.height)) {
            Orientation.LANDSCAPE -> 1.0
            Orientation.PORTRAIT -> 0.7
            Orientation.SQUARE -> 0.5
        }
        val lighting = when (s.timeOfDay) {
            "golden_hour" -> 1.0
            "day" -> 0.8
            "night" -> 0.6
            else -> 0.5
        }
        val baseQuality = s.aestheticScore * scoringWeights.aestheticWeight +
            (1.0 - s.blurScore) * scoringWeights.sharpnessWeight +
            orientation * scoringWeights.orientationPreference +
            lighting * scoringWeights.lightingPreference
        // Memorability penalty: a low-keepsake shot that survived the filter still ranks below a
        // more memorable one within its cluster/event. Scales quality to [0.4x .. 1.0x] over
        // keepsakeInterest, so a mundane-but-crisp frame never out-ranks the real memory beside it.
        val keepsakeMultiplier = 0.4 + 0.6 * s.keepsakeInterest.coerceIn(0.0, 1.0)
        return baseQuality * keepsakeMultiplier
    }

    private fun objectJaccard(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else sa.intersect(sb).size / union
    }

    /**
     * Keeps at most [maxPerSubject] of the strongest photos for each non-people [primarySubject],
     * dropping the rest as repetitive. People are exempt (a recurring person across the trip is
     * desired, and the vision model can't tell individuals apart anyway); photos with no
     * primarySubject are left untouched. Input order is otherwise preserved.
     */
    private fun capRepeatedSubjects(photos: List<Photo>, maxPerSubject: Int): List<Photo> {
        if (maxPerSubject <= 0) return photos
        // Rank so the best few of each subject survive; the dropped set is then removed by identity.
        val counts = HashMap<String, Int>()
        val dropped = HashSet<Photo>()
        for (photo in photos.sortedByDescending { rankScore(it) }) {
            val s = photo.signals ?: continue
            val subject = s.primarySubject?.lowercase()?.trim()
            if (subject.isNullOrBlank() || isPeopleSubject(s)) continue
            val n = counts.getOrDefault(subject, 0)
            if (n >= maxPerSubject) dropped.add(photo) else counts[subject] = n + 1
        }
        return if (dropped.isEmpty()) photos else photos.filter { it !in dropped }
    }

    /** True when the photo is primarily about one or more people, which exempts it from the repeat cap. */
    private fun isPeopleSubject(s: com.atlaso.domain.photo.PhotoSignals): Boolean {
        return s.subjectType in PEOPLE_SUBJECT_TYPES ||
            s.sceneType == "people" ||
            s.primarySubject?.lowercase()?.trim() == "people"
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
     * Phase 3: trim [photos] down to [DiversityConfig.targetPhotos] by activity/event.
     *
     * The book's category mix is NOT quota-driven — it simply reflects the trip. Instead we
     * cluster into events (activities) and allocate slots **proportional to how much was shot at
     * each**, with two guards: every event keeps at least [DiversityConfig.floorPerEvent] of its
     * strongest shots (so no moment is ever dropped), and no event may exceed
     * [DiversityConfig.maxEventShare] of the book (so one burst-heavy activity can't dominate).
     * Photos are already near-dup-free from Phase 2, so an event's picks are distinct memories.
     */
    private fun selectPhotos(
        photos: List<Photo>,
        weights: ScoringWeights,
        diversityConfig: DiversityConfig
    ): List<Photo> {
        val target = diversityConfig.targetPhotos
        val events = clusterEvents(photos)
        if (events.isEmpty()) return emptyList()

        // Per-event candidate pools, strongest first.
        val pools = events.map { ev -> ev.sortedByDescending { rankScore(it) }.toMutableList() }
        val totalPhotos = pools.sumOf { it.size }.coerceAtLeast(1)
        val capAbs = (target * diversityConfig.maxEventShare).toInt().coerceAtLeast(diversityConfig.floorPerEvent)

        val allocated = IntArray(pools.size)
        val selected = mutableListOf<Photo>()
        fun take(i: Int) { selected.add(pools[i].removeAt(0)); allocated[i]++ }

        // 1. Coverage floor — guarantee each event's strongest few first (no activity dropped).
        for (i in pools.indices) {
            repeat(minOf(diversityConfig.floorPerEvent, pools[i].size)) { if (selected.size < target) take(i) }
        }
        // 2. Fill the rest proportionally: each remaining slot goes to the event that is most under
        //    its fair (proportional) share and still has distinct photos left below the cap.
        while (selected.size < target) {
            var bestI = -1
            var bestDeficit = Double.NEGATIVE_INFINITY
            for (i in pools.indices) {
                if (pools[i].isEmpty() || allocated[i] >= minOf(capAbs, events[i].size)) continue
                val fairShare = target.toDouble() * events[i].size / totalPhotos
                val deficit = (fairShare - allocated[i]) / maxOf(1.0, fairShare)
                if (deficit > bestDeficit) { bestDeficit = deficit; bestI = i }
            }
            if (bestI < 0) break // everything left is over its cap
            take(bestI)
        }

        logger.info("Event allocation: {} events, {} photos selected (target {})", events.size, selected.size, target)
        return selected
    }

    /**
     * Clusters photos into events/activities in chronological order. An event boundary is a large
     * time gap or a location change — a lightweight proxy for the grouper's episode detection.
     */
    private fun clusterEvents(photos: List<Photo>): List<List<Photo>> {
        if (photos.isEmpty()) return emptyList()
        val sorted = photos.sortedWith(compareBy(nullsLast()) { it.metadata.takenAt })
        val events = mutableListOf<MutableList<Photo>>()
        var current = mutableListOf<Photo>()
        for (photo in sorted) {
            if (current.isEmpty() || !isNewEvent(current.last(), photo)) current.add(photo)
            else { events.add(current); current = mutableListOf(photo) }
        }
        if (current.isNotEmpty()) events.add(current)
        return events
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
    val minDimension: Int = 1200,
    // Drop shots the vision model flags as clearly mundane/utility (bike lock, shoes, storefront,
    // lone coffee cup). Set low so only the obviously-unkeepable is dropped; anything ambiguous or
    // analysed before this signal existed (neutral default 0.5) survives.
    val minKeepsakeInterest: Double = 0.3,
    // Documentary / non-photogenic subjects to drop outright. Single-word entries match whole
    // tokens; multi-word entries match as a substring. Deliberately excludes bare "sign",
    // "phone", "book" — those are too often legitimate travel/candid shots.
    val excludedObjectTags: Set<String> = setOf(
        // menus & receipts
        "menu", "receipt",
        // text & documents
        "text", "screenshot", "document", "whiteboard", "qr code",
        // printed signage
        "signage", "poster", "brochure", "price tag", "label",
        // travel paperwork
        "ticket", "boarding pass", "passport", "booking"
    )
)

/**
 * Configuration for near-duplicate suppression. [timeWindowSeconds] is the max gap between
 * consecutive frames for them to stay in one near-dup cluster — wide enough to catch a series of
 * "let me take another" repeats spread over a minute or two, not just a sub-10s burst.
 */
data class BurstConfig(
    val timeWindowSeconds: Long = 90
)

/**
 * Configuration for scoring weights.
 * All weights should sum to ~1.0 for normalized scores.
 */
data class ScoringWeights(
    // Aesthetics now dominate (was 0.50) so a merely-sharp/well-oriented but plain shot can't
    // ride secondary factors into the book. Remaining weights rebalanced to keep the sum at 1.0,
    // pulling mostly from sharpness + orientation (the factors that previously over-rewarded
    // crisp documentary photos).
    val aestheticWeight: Double = 0.65,
    val sharpnessWeight: Double = 0.12,
    val sceneVarietyBonus: Double = 0.09,
    val timeVarietyBonus: Double = 0.05,
    val orientationPreference: Double = 0.05,
    val lightingPreference: Double = 0.04
)

/**
 * Configuration for event-proportional selection. There are no scene-type quotas — the category
 * mix reflects the actual trip; these only control how book slots spread across activities.
 */
data class DiversityConfig(
    val targetPhotos: Int = 30,
    // Every activity/event keeps at least this many of its strongest shots, so no moment is dropped.
    val floorPerEvent: Int = 1,
    // No single event may exceed this fraction of the book, so one burst-heavy activity can't dominate.
    val maxEventShare: Double = 0.22,
    // Max photos of the same non-people primarySubject kept across the whole book (e.g. coconut drink).
    val maxSameSubject: Int = 3
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
