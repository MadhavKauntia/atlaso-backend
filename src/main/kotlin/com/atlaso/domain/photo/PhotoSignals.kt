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
    // Selection signals (V3). Both default to neutral so photos analyzed before these existed
    // survive the selector unpenalised until re-analysed.
    // keepsakeInterest: how much this reads as a memorable travel keepsake (temples, vistas,
    // people, food-as-experience) vs a mundane/utility snapshot (bike lock, shoes, a receipt of
    // life). 0 = utility, 1 = strong keepsake. Drives a hard-drop + ranking penalty.
    val keepsakeInterest: Double = 0.5,
    // primarySubject: a short, stable noun-phrase naming the single main subject
    // ("coconut drink", "rice terrace", "temple gateway"). Used to cap repeats of the SAME
    // non-people subject across the book. Null when unknown; "people" for person-focused shots.
    val primarySubject: String? = null
) : Serializable
