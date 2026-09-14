package com.atlaso.repository

import com.atlaso.domain.photo.UploadGrant
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface UploadGrantRepository : JpaRepository<UploadGrant, UUID> {
    fun findByPhotoIdAndTripId(photoId: UUID, tripId: UUID): UploadGrant?
}
