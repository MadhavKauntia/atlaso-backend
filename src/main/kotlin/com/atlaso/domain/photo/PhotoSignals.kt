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
    // Version of the analysis schema these signals were produced with. Lets us reuse
    // valid analyses and re-run only when the schema meaningfully changes (§19). Records
    // written before this field existed deserialize to CURRENT (they already carry the
    // composition fields), so they are treated as current and not re-analyzed.
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION
) : Serializable {
    companion object {
        /** Bump when the vision schema changes in a way that requires re-analysis. */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
