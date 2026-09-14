package com.atlaso.domain.photo

import java.io.Serializable

data class PhotoSignals(
    val sceneType: String? = null,
    val aestheticScore: Double = 0.0,
    val blurScore: Double = 0.5,
    val timeOfDay: String = "day",
    val facesCount: Int = 0,
    val detectedObjects: List<String> = emptyList(),
    val isBlurry: Boolean = false,
    val dominantColors: List<String> = emptyList(),
    val colorTemperature: String = "neutral",
    val mood: String = "serene",
    val depthOfField: String = "deep",
    val locationTag: String? = null,
    // Observable composition properties (V2), used for layout decisions.
    val subjectType: String = "other",
    val shotDistance: String = "medium",
    val subjectProminence: String = "medium",
    val settingScope: String = "subject",
    val backgroundComplexity: String = "medium",
    val negativeSpace: String = "low",
    // Version of the analysis schema these signals were produced with. Lets us reuse valid
    // analyses and re-run only when the schema meaningfully changes (§19). The property
    // default is a fixed BASELINE — it must NOT track CURRENT_SCHEMA_VERSION, otherwise
    // bumping the current version would make stale records deserialize as "current" and
    // never get re-analyzed. New analyses are stamped with CURRENT_SCHEMA_VERSION in
    // PhotoAnalysisService.toPhotoSignals; records written before this field existed have no
    // key and deserialize to the baseline.
    val schemaVersion: Int = SCHEMA_BASELINE
) : Serializable {
    companion object {
        /** Fixed baseline for records that predate a given re-analysis. Never change this. */
        const val SCHEMA_BASELINE = 1

        /**
         * Bump to force re-analysis of all existing photos on their next generation.
         * v2: decisive composition-field prompt (subject_type / shot_distance / setting_scope
         * / subject_prominence / background_complexity / negative_space) — earlier analyses
         * hedged these to their neutral defaults.
         */
        const val CURRENT_SCHEMA_VERSION = 2
    }
}
