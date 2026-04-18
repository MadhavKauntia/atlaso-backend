package com.atlaso.domain.photo

import java.io.Serializable
import java.time.Instant

data class PhotoMetadata(
    val width: Int,
    val height: Int,
    val takenAt: Instant? = null,
    val location: GeoLocation? = null,
    val orientation: Int = 1,
    val cameraModel: String? = null
) : Serializable

data class GeoLocation(
    val latitude: Double,
    val longitude: Double
) : Serializable
