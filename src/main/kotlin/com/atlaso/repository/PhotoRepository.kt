package com.atlaso.repository

import com.atlaso.domain.photo.Photo
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.util.Optional
import java.util.UUID

interface PhotoRepository : JpaRepository<Photo, UUID> {
    fun findByTripId(tripId: UUID): List<Photo>
    fun findByTripIdAndSignalsIsNull(tripId: UUID): List<Photo>
    fun findByTripIdAndSignalsIsNotNull(tripId: UUID): List<Photo>
    fun countByTripId(tripId: UUID): Long
    fun findByIdAndTripId(id: UUID, tripId: UUID): Optional<Photo>

    /** S3 keys of a trip's photos, without loading the entities (see [StorageKeys]). */
    @Query("SELECT p.storageKey AS storageKey, p.thumbnailKey AS thumbnailKey FROM Photo p WHERE p.trip.id = :tripId")
    fun findStorageKeysByTripId(@Param("tripId") tripId: UUID): List<StorageKeys>

    /** Bulk-remove a trip's photo rows (S3 objects are deleted separately first). */
    @Modifying
    @Query("DELETE FROM Photo p WHERE p.trip.id = :tripId")
    fun deleteByTripId(@Param("tripId") tripId: UUID): Int

    /** Total confirmed bytes stored for a trip (main + thumbnail) for the cumulative byte quota. */
    @Query("SELECT COALESCE(SUM(p.fileSize + COALESCE(p.thumbnailSizeBytes, 0)), 0) FROM Photo p WHERE p.trip.id = :tripId")
    fun sumFileSizeByTripId(tripId: UUID): Long
}
