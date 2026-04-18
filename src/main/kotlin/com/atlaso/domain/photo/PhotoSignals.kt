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
    val depthOfField: String = "deep"
) : Serializable
