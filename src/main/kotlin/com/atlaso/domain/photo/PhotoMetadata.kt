package com.atlaso.domain.photo

import java.io.Serializable
import java.time.Instant

data class PhotoMetadata(
    val width: Int,
    val height: Int,
    val takenAt: Instant? = null,
    val location: GeoLocation? = null,
    val orientation: Int = 1,
    val cameraModel: String? = null,
    /** Client-computed sharpness (Laplacian variance); higher = sharper. Used to
     *  pick the best frame of a burst before spending a vision-analysis call. */
    val sharpness: Double? = null
) : Serializable

data class GeoLocation(
    val latitude: Double,
    val longitude: Double
) : Serializable
