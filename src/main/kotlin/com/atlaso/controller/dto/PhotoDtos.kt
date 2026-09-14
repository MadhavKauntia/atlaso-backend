package com.atlaso.controller.dto

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.PhotoSignals
import java.time.Instant
import java.util.UUID

data class InitiateUploadRequest(
    val filename: String,
    val contentType: String,
    val fileSize: Long,
    // Size of the client-generated thumbnail, if any. Present → we also hand back
    // a presigned PUT for the thumbnail (Content-Length bound to this size).
    val thumbnailFileSize: Long? = null
)

data class InitiateUploadResponse(
    val photoId: UUID,
    val storageKey: String,
    val uploadUrl: String,
    // Populated only when the client declared a thumbnail in the request.
    val thumbnailStorageKey: String? = null,
    val thumbnailUploadUrl: String? = null
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
    val longitude: Double? = null,
    val sharpness: Double? = null,
    // Echoed back from initiate once the thumbnail PUT succeeds (null if the
    // client skipped or failed the thumbnail upload).
    val thumbnailStorageKey: String? = null
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
    val analyzedAt: Instant?,
    // Direct (presigned) URL to the image so the browser skips the per-image
    // backend redirect. Null when a URL couldn't be produced.
    val imageUrl: String? = null,
    // Direct (presigned) URL to the small display thumbnail, when one exists.
    // Consumers fall back to imageUrl when null.
    val thumbnailUrl: String? = null
) {
    companion object {
        fun from(photo: Photo, imageUrl: String? = null, thumbnailUrl: String? = null): PhotoResponse = PhotoResponse(
            id = photo.id!!,
            tripId = photo.trip.id!!,
            originalFilename = photo.originalFilename,
            contentType = photo.contentType,
            fileSize = photo.fileSize,
            metadata = photo.metadata,
            signals = photo.signals,
            rotation = photo.rotation,
            uploadedAt = photo.uploadedAt,
            analyzedAt = photo.analyzedAt,
            imageUrl = imageUrl,
            thumbnailUrl = thumbnailUrl
        )
    }
}
