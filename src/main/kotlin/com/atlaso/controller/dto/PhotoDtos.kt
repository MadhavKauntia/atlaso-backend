package com.atlaso.controller.dto

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import java.time.Instant
import java.util.UUID

data class BulkUploadResponse(
    val uploaded: List<PhotoResponse>,
    val failed: List<BulkUploadFailure>
)

data class BulkUploadFailure(
    val filename: String,
    val error: String
)

data class PhotoResponse(
    val id: UUID,
    val tripId: UUID,
    val originalFilename: String,
    val contentType: String,
    val fileSize: Long,
    val metadata: PhotoMetadata,
    val signals: PhotoSignals?,
    val rotation: Int,
    val uploadedAt: Instant?,
    val analyzedAt: Instant?
) {
    companion object {
        fun from(photo: Photo): PhotoResponse = PhotoResponse(
            id = photo.id!!,
            tripId = photo.trip.id!!,
            originalFilename = photo.originalFilename,
            contentType = photo.contentType,
            fileSize = photo.fileSize,
            metadata = photo.metadata,
            signals = photo.signals,
            rotation = photo.rotation,
            uploadedAt = photo.uploadedAt,
            analyzedAt = photo.analyzedAt
        )
    }
}
