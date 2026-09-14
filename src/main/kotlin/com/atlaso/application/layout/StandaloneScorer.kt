package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import java.util.UUID

/**
 * Computes a per-photo `standalone_score`: how well a photo carries a page on its
 * own and therefore deserves a large visual treatment (full-bleed, framed single,
 * or the featured slot of a grid).
 *
 * Derived downstream from observable signals only. It is deliberately NOT used as
 * the sole photo-selection score — selection stays with PhotoSelector.
 *
 *   standalone_score = aesthetic*0.50
 *                    + (1 - blur)*0.15
 *                    + prominence*0.10
 *                    + background_cleanliness*0.10
 *                    + uniqueness*0.15
 *
 * `uniqueness` is relative to the photo's episode: it rises when the photo differs
 * meaningfully from its neighbours in subject, shot distance, objects, or setting.
 */
object StandaloneScorer {

    const val STRONG_THRESHOLD = 0.75

    /** standalone_score for every photo in an episode (uniqueness is episode-relative). */
    fun scoreEpisode(episode: List<Photo>): Map<UUID, Double> =
        episode.filter { it.id != null }.associate { it.id!! to score(it, episode) }

    fun score(photo: Photo, episode: List<Photo>): Double {
        val s = photo.signals ?: return 0.0
        return s.aestheticScore * 0.50 +
            (1.0 - s.blurScore) * 0.15 +
            prominenceScore(s.subjectProminence) * 0.10 +
            backgroundCleanliness(s.backgroundComplexity) * 0.10 +
            uniqueness(photo, episode) * 0.15
    }

    private fun prominenceScore(v: String): Double = when (v.lowercase()) {
        "high" -> 1.0
        "low" -> 0.4
        else -> 0.7 // medium / unknown
    }

    private fun backgroundCleanliness(complexity: String): Double = when (complexity.lowercase()) {
        "low" -> 1.0
        "high" -> 0.4
        else -> 0.7 // medium / unknown
    }

    /**
     * How distinct this photo is within its episode: 1.0 = alone or fully unique,
     * 0.0 = identical to its neighbours.
     */
    private fun uniqueness(photo: Photo, episode: List<Photo>): Double {
        val others = episode.filter { it.id != photo.id }
        if (others.isEmpty()) return 1.0
        val avgSimilarity = others.map { similarity(photo, it) }.average()
        return (1.0 - avgSimilarity).coerceIn(0.0, 1.0)
    }

    private fun similarity(a: Photo, b: Photo): Double {
        val sa = a.signals ?: return 0.0
        val sb = b.signals ?: return 0.0
        var sim = 0.0
        if (sa.subjectType == sb.subjectType) sim += 0.35
        if (sa.shotDistance == sb.shotDistance) sim += 0.25
        if (sa.settingScope == sb.settingScope) sim += 0.15
        sim += objectJaccard(sa.detectedObjects, sb.detectedObjects) * 0.25
        return sim.coerceIn(0.0, 1.0)
    }

    private fun objectJaccard(a: List<String>, b: List<String>): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        val sa = a.toSet()
        val sb = b.toSet()
        val union = sa.union(sb).size.toDouble()
        return if (union == 0.0) 0.0 else sa.intersect(sb).size / union
    }
}
