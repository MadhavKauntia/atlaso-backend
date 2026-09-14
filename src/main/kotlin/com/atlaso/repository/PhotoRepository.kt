package com.atlaso.repository

import com.atlaso.domain.photo.Photo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface PhotoRepository : JpaRepository<Photo, UUID> {
    fun findByTripId(tripId: UUID): List<Photo>
    fun findByTripIdAndSignalsIsNull(tripId: UUID): List<Photo>
    fun findByTripIdAndSignalsIsNotNull(tripId: UUID): List<Photo>
    fun countByTripId(tripId: UUID): Long
    fun findByIdAndTripId(id: UUID, tripId: UUID): Optional<Photo>

    /** Total confirmed bytes stored for a trip (for the cumulative byte quota). */
    @Query("SELECT COALESCE(SUM(p.fileSize), 0) FROM Photo p WHERE p.trip.id = :tripId")
    fun sumFileSizeByTripId(tripId: UUID): Long
}
