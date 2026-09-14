package com.atlaso.repository

import com.atlaso.domain.photo.UploadGrant
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant
import java.util.UUID

interface UploadGrantRepository : JpaRepository<UploadGrant, UUID> {
    fun findByPhotoIdAndTripId(photoId: UUID, tripId: UUID): UploadGrant?

    /** Active (unconsumed, not-yet-expired) reservations for a trip — counted toward its quota. */
    fun countByTripIdAndConsumedFalseAndCreatedAtAfter(tripId: UUID, cutoff: Instant): Long
}
