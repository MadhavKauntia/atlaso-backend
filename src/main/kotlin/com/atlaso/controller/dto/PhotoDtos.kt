package com.atlaso.controller.dto

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import java.time.Instant
import java.util.UUID

data class InitiateUploadRequest(
    val filename: String,
    val contentType: String,
    val fileSize: Long
)

data class InitiateUploadResponse(
    val photoId: UUID,
    val storageKey: String,
    val uploadUrl: String
)

data class ConfirmUploadRequest(
    val photoId: UUID,
    val storageKey: String,
    val originalFilename: String,
    val contentType: String,
    val fileSize: Long,
    val width: Int,
    val height: Int,
    val takenAt: Long?,
    val latitude: Double? = null,
    val longitude: Double? = null
)

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
