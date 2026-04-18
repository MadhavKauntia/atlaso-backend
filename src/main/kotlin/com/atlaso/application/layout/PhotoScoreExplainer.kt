package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import org.springframework.stereotype.Component

/**
 * Utility for explaining why a photo received a particular score.
 * Useful for debugging and understanding selection decisions.
 */
@Component
class PhotoScoreExplainer {

    /**
     * Generates a detailed explanation of a photo's score.
     */
    fun explainScore(
        photo: Photo,
        photoScore: PhotoScore,
        weights: ScoringWeights = ScoringWeights(),
        rank: Int? = null
    ): String {
        val signals = photo.signals!!
        val metadata = photo.metadata
        val breakdown = photoScore.breakdown

        val rankText = rank?.let { "Rank: #$it\n" } ?: ""

        return """
        |═══════════════════════════════════════════════════
        |Photo: ${photo.originalFilename}
        |${rankText}Total Score: ${photoScore.totalScore.format(3)} / 1.000
        |═══════════════════════════════════════════════════
        |
        |SCORE BREAKDOWN:
        |
        |1. Aesthetic Quality
        |   Raw Score:    ${signals.aestheticScore.format(3)}
        |   Weight:       ${weights.aestheticWeight.format(3)} (50%)
        |   Contribution: ${breakdown.aestheticScore.format(3)}
        |   └─ ${getAestheticAssessment(signals.aestheticScore)}
        |
        |2. Sharpness
        |   Blur Score:   ${signals.blurScore.format(3)}
        |   Sharp Score:  ${(1.0 - signals.blurScore).format(3)}
        |   Weight:       ${weights.sharpnessWeight.format(3)} (20%)
        |   Contribution: ${breakdown.sharpnessScore.format(3)}
        |   └─ ${getSharpnessAssessment(signals.blurScore)}
        |
        |3. Scene Variety Bonus
        |   Scene Type:   ${signals.sceneType}
        |   Weight:       ${weights.sceneVarietyBonus.format(3)} (10%)
        |   Contribution: ${breakdown.sceneVarietyScore.format(3)}
        |   └─ Encourages diversity in scene types
        |
        |4. Time of Day Variety Bonus
        |   Time:         ${signals.timeOfDay}
        |   Weight:       ${weights.timeVarietyBonus.format(3)} (5%)
        |   Contribution: ${breakdown.timeVarietyScore.format(3)}
        |   └─ Encourages mix of day/night/golden hour
        |
        |5. Orientation Preference
        |   Dimensions:   ${metadata.width} × ${metadata.height}
        |   Orientation:  ${Orientation.from(metadata.width, metadata.height)}
        |   Weight:       ${weights.orientationPreference.format(3)} (10%)
        |   Contribution: ${breakdown.orientationScore.format(3)}
        |   └─ ${getOrientationNote(Orientation.from(metadata.width, metadata.height))}
        |
        |6. Lighting Preference
        |   Time:         ${signals.timeOfDay}
        |   Weight:       ${weights.lightingPreference.format(3)} (5%)
        |   Contribution: ${breakdown.lightingScore.format(3)}
        |   └─ ${getLightingNote(signals.timeOfDay)}
        |
        |═══════════════════════════════════════════════════
        |ADDITIONAL INFO:
        |  Faces:        ${signals.facesCount}
        |  Objects:      ${signals.detectedObjects.joinToString(", ").ifEmpty { "none" }}
        |  Captured:     ${metadata.takenAt ?: "unknown"}
        |═══════════════════════════════════════════════════
        """.trimMargin()
    }

    /**
     * Generates a compact one-line explanation.
     */
    fun explainScoreCompact(photo: Photo, photoScore: PhotoScore): String {
        val signals = photo.signals!!
        val orientation = Orientation.from(photo.metadata.width, photo.metadata.height)

        return "Score: ${photoScore.totalScore.format(3)} | " +
               "Aesthetic: ${signals.aestheticScore.format(2)} | " +
               "Blur: ${signals.blurScore.format(2)} | " +
               "Scene: ${signals.sceneType} | " +
               "Time: ${signals.timeOfDay} | " +
               "Orient: $orientation | " +
               "File: ${photo.originalFilename}"
    }

    /**
     * Compares two photos and explains the difference.
     */
    fun comparePhotos(
        photo1: Photo,
        score1: PhotoScore,
        photo2: Photo,
        score2: PhotoScore
    ): String {
        val diff = score1.totalScore - score2.totalScore
        val winner = if (diff > 0) photo1 else photo2
        val loser = if (diff > 0) photo2 else photo1

        val breakdown1 = score1.breakdown
        val breakdown2 = score2.breakdown

        return """
        |═══════════════════════════════════════════════════
        |PHOTO COMPARISON
        |═══════════════════════════════════════════════════
        |
        |Winner: ${winner.originalFilename} (Δ ${kotlin.math.abs(diff).format(3)})
        |
        |Photo A: ${photo1.originalFilename}
        |  Total Score: ${score1.totalScore.format(3)}
        |
        |Photo B: ${photo2.originalFilename}
        |  Total Score: ${score2.totalScore.format(3)}
        |
        |─────────────────────────────────────────────────
        |BREAKDOWN COMPARISON:
        |
        |Component          Photo A      Photo B      Δ
        |───────────────────────────────────────────────
        |Aesthetic          ${breakdown1.aestheticScore.format(3)}      ${breakdown2.aestheticScore.format(3)}      ${(breakdown1.aestheticScore - breakdown2.aestheticScore).formatSigned(3)}
        |Sharpness          ${breakdown1.sharpnessScore.format(3)}      ${breakdown2.sharpnessScore.format(3)}      ${(breakdown1.sharpnessScore - breakdown2.sharpnessScore).formatSigned(3)}
        |Scene Variety      ${breakdown1.sceneVarietyScore.format(3)}      ${breakdown2.sceneVarietyScore.format(3)}      ${(breakdown1.sceneVarietyScore - breakdown2.sceneVarietyScore).formatSigned(3)}
        |Time Variety       ${breakdown1.timeVarietyScore.format(3)}      ${breakdown2.timeVarietyScore.format(3)}      ${(breakdown1.timeVarietyScore - breakdown2.timeVarietyScore).formatSigned(3)}
        |Orientation        ${breakdown1.orientationScore.format(3)}      ${breakdown2.orientationScore.format(3)}      ${(breakdown1.orientationScore - breakdown2.orientationScore).formatSigned(3)}
        |Lighting           ${breakdown1.lightingScore.format(3)}      ${breakdown2.lightingScore.format(3)}      ${(breakdown1.lightingScore - breakdown2.lightingScore).formatSigned(3)}
        |═══════════════════════════════════════════════════
        """.trimMargin()
    }

    /**
     * Explains why a photo was rejected during filtering.
     */
    fun explainRejection(photo: Photo, thresholds: QualityThresholds): String {
        val signals = photo.signals
        val metadata = photo.metadata

        if (signals == null) {
            return "❌ REJECTED: No AI analysis available"
        }

        val reasons = mutableListOf<String>()

        if (signals.aestheticScore < thresholds.minAestheticScore) {
            reasons.add("Aesthetic score ${signals.aestheticScore.format(3)} < threshold ${thresholds.minAestheticScore}")
        }

        if (signals.blurScore > thresholds.maxBlurScore) {
            reasons.add("Blur score ${signals.blurScore.format(3)} > threshold ${thresholds.maxBlurScore}")
        }

        if (metadata.width < thresholds.minDimension || metadata.height < thresholds.minDimension) {
            reasons.add("Dimensions ${metadata.width}×${metadata.height} < ${thresholds.minDimension}px")
        }

        return if (reasons.isEmpty()) {
            "✓ PASSED quality filters"
        } else {
            "❌ REJECTED:\n" + reasons.joinToString("\n") { "  • $it" }
        }
    }

    private fun getAestheticAssessment(score: Double): String {
        return when {
            score >= 0.9 -> "Excellent - Professional quality"
            score >= 0.7 -> "Good - Well-composed"
            score >= 0.5 -> "Average - Acceptable"
            score >= 0.3 -> "Below average - Poor quality"
            else -> "Poor - Technical issues"
        }
    }

    private fun getSharpnessAssessment(blurScore: Double): String {
        return when {
            blurScore <= 0.2 -> "Sharp - Excellent detail"
            blurScore <= 0.4 -> "Good - Acceptable sharpness"
            blurScore <= 0.6 -> "Soft - Noticeable blur"
            blurScore <= 0.8 -> "Blurry - Motion or focus issues"
            else -> "Very blurry - Unusable"
        }
    }

    private fun getOrientationNote(orientation: Orientation): String {
        return when (orientation) {
            Orientation.LANDSCAPE -> "Landscape preferred (full-page layouts)"
            Orientation.PORTRAIT -> "Portrait acceptable (vertical layouts)"
            Orientation.SQUARE -> "Square usable (grid layouts)"
        }
    }

    private fun getLightingNote(timeOfDay: String): String {
        return when (timeOfDay) {
            "golden_hour" -> "Golden hour preferred (warm, soft light)"
            "day" -> "Daylight good (clear, bright)"
            "night" -> "Night acceptable (ambient/artificial light)"
            else -> "Unknown lighting"
        }
    }

    // Extension functions for formatting
    private fun Double.format(decimals: Int): String {
        return "%.${decimals}f".format(this)
    }

    private fun Double.formatSigned(decimals: Int): String {
        val formatted = "%.${decimals}f".format(kotlin.math.abs(this))
        return if (this >= 0) "+$formatted" else "-$formatted"
    }
}
