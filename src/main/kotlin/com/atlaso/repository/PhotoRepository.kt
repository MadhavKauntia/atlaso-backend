package com.atlaso.repository

import com.atlaso.domain.photo.Photo
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface PhotoRepository : JpaRepository<Photo, UUID> {
    fun findByTripId(tripId: UUID): List<Photo>
    fun findByTripIdAndSignalsIsNull(tripId: UUID): List<Photo>
    fun findByTripIdAndSignalsIsNotNull(tripId: UUID): List<Photo>
    fun countByTripId(tripId: UUID): Long
}
