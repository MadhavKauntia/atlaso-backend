package com.atlaso.infrastructure.ai

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * Structured response from vision model photo analysis.
 * All fields are required and must be present in the JSON response.
 */
data class PhotoAnalysisResponse(
    @JsonProperty("aesthetic_score")
    val aestheticScore: Double,

    @JsonProperty("blur_score")
    val blurScore: Double,

    @JsonProperty("faces_count")
    val facesCount: Int,

    @JsonProperty("scene_type")
    val sceneType: SceneType,

    @JsonProperty("time_of_day")
    val timeOfDay: TimeOfDay,

    @JsonProperty("dominant_colors")
    val dominantColors: List<String> = emptyList(),

    @JsonProperty("detected_objects")
    val detectedObjects: List<String> = emptyList(),

    @JsonProperty("color_temperature")
    val colorTemperature: String = "neutral",

    @JsonProperty("mood")
    val mood: String = "serene",

    @JsonProperty("depth_of_field")
    val depthOfField: String = "deep",

    @JsonProperty("location_tag")
    val locationTag: String? = null,

    // Observable composition properties used for layout decisions (V2).
    @JsonProperty("subject_type")
    val subjectType: String = "other",        // person | couple | group | landscape | food | object | architecture | activity | other

    @JsonProperty("shot_distance")
    val shotDistance: String = "medium",      // closeup | medium | wide

    @JsonProperty("subject_prominence")
    val subjectProminence: String = "medium", // low | medium | high

    @JsonProperty("setting_scope")
    val settingScope: String = "subject",     // detail | subject | environment

    @JsonProperty("background_complexity")
    val backgroundComplexity: String = "medium", // low | medium | high

    @JsonProperty("negative_space")
    val negativeSpace: String = "low",        // low | medium | high

    // Selection signals (V3).
    @JsonProperty("keepsake_interest")
    val keepsakeInterest: Double = 0.5,       // 0 = mundane/utility, 1 = strong travel keepsake

    @JsonProperty("primary_subject")
    val primarySubject: String? = null        // short stable noun-phrase; "people" for person shots
) {
    init {
        require(aestheticScore in 0.0..1.0) { "aesthetic_score must be between 0.0 and 1.0" }
        require(blurScore in 0.0..1.0) { "blur_score must be between 0.0 and 1.0" }
        require(facesCount >= 0) { "faces_count must be non-negative" }
        require(keepsakeInterest in 0.0..1.0) { "keepsake_interest must be between 0.0 and 1.0" }
    }
}

enum class SceneType {
    @JsonProperty("people")
    PEOPLE,

    @JsonProperty("landscape")
    LANDSCAPE,

    @JsonProperty("food")
    FOOD,

    @JsonProperty("city")
    CITY,

    @JsonProperty("misc")
    MISC;

    companion object {
        // Lenient parse: the vision model occasionally returns a scene_type outside this set (e.g.
        // "activity", which is really a subject_type). Map anything unrecognised to MISC instead of
        // throwing — a single stray value must not discard the whole response (keepsakeInterest,
        // primarySubject, aestheticScore, …) and force an all-defaults fallback.
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String?): SceneType =
            values().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: MISC
    }
}

enum class TimeOfDay {
    @JsonProperty("day")
    DAY,

    @JsonProperty("golden_hour")
    GOLDEN_HOUR,

    @JsonProperty("night")
    NIGHT;

    companion object {
        // Lenient parse: unrecognised time_of_day degrades to DAY rather than failing the response.
        @JvmStatic
        @JsonCreator
        fun fromValue(value: String?): TimeOfDay =
            values().firstOrNull { it.name.equals(value?.trim(), ignoreCase = true) } ?: DAY
    }
}
