package com.atlaso.domain.book

import java.io.Serializable
import java.util.UUID

data class PhotoSlot(
    val photoId: UUID,
    val position: Position,
    val size: Size,
    val caption: String? = null,
    val rotation: Int = 0,
    val offsetX: Double? = null,  // 0.0=left, 0.5=center, 1.0=right; null defaults to 0.5
    val offsetY: Double? = null   // 0.0=top,  0.5=center, 1.0=bottom; null defaults to 0.5
) : Serializable

data class Position(
    val x: Double,
    val y: Double
) : Serializable

data class Size(
    val width: Double,
    val height: Double
) : Serializable
