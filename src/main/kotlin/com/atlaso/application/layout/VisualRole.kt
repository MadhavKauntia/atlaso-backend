package com.atlaso.application.layout

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoSignals

/**
 * Coarse visual role of a photo, derived deterministically from observable signals
 * (never asked of the vision model). Drives event variety (§8), spread composition
 * (§12) and saturation penalties (§7): a page/spread/event reads better when it mixes
 * roles rather than repeating one.
 */
enum class VisualRole {
    LANDSCAPE_ENVIRONMENT,
    SOLO_COUPLE,
    GROUP,
    ACTION_CANDID,
    DETAIL_OBJECT;

    companion object {
        /** Roles whose repetition is especially monotonous and gets stronger saturation penalties (§8). */
        val HEAVILY_PENALIZED = setOf(GROUP, LANDSCAPE_ENVIRONMENT, DETAIL_OBJECT)

        fun of(photo: Photo): VisualRole = of(photo.signals)

        fun of(s: PhotoSignals?): VisualRole {
            if (s == null) return DETAIL_OBJECT
            // Primary: the observable subject_type the model reported.
            return when (s.subjectType.lowercase()) {
                "landscape" -> LANDSCAPE_ENVIRONMENT
                "couple", "person" -> SOLO_COUPLE
                "group" -> GROUP
                "activity" -> ACTION_CANDID
                "food", "object" -> DETAIL_OBJECT
                "architecture" ->
                    if (s.settingScope.equals("environment", true)) LANDSCAPE_ENVIRONMENT else DETAIL_OBJECT
                else -> fallbackByShape(s)
            }
        }

        /** subject_type == "other"/unknown → infer from faces, scope and framing. */
        private fun fallbackByShape(s: PhotoSignals): VisualRole = when {
            s.facesCount >= 3 -> GROUP
            s.facesCount in 1..2 -> SOLO_COUPLE
            s.settingScope.equals("environment", true) -> LANDSCAPE_ENVIRONMENT
            s.settingScope.equals("detail", true) || s.shotDistance.equals("closeup", true) -> DETAIL_OBJECT
            s.mood.equals("adventurous", true) && s.shotDistance.equals("wide", true) -> ACTION_CANDID
            else -> DETAIL_OBJECT
        }
    }
}
