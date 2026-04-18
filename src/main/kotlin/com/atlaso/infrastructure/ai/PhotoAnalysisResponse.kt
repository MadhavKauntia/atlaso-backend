package com.atlaso.infrastructure.ai

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
    val depthOfField: String = "deep"
) {
    init {
        require(aestheticScore in 0.0..1.0) { "aesthetic_score must be between 0.0 and 1.0" }
        require(blurScore in 0.0..1.0) { "blur_score must be between 0.0 and 1.0" }
        require(facesCount >= 0) { "faces_count must be non-negative" }
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
    MISC
}

enum class TimeOfDay {
    @JsonProperty("day")
    DAY,

    @JsonProperty("golden_hour")
    GOLDEN_HOUR,

    @JsonProperty("night")
    NIGHT
}
