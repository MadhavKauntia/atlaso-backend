package com.atlaso.repository

import com.atlaso.domain.photo.UploadGrant
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant
import java.util.UUID

interface UploadGrantRepository : JpaRepository<UploadGrant, UUID> {
    fun findByPhotoIdAndTripId(photoId: UUID, tripId: UUID): UploadGrant?

    /** Active (unconsumed, not-yet-expired) reservations for a trip — counted toward its quota. */
    fun countByTripIdAndConsumedFalseAndCreatedAtAfter(tripId: UUID, cutoff: Instant): Long

    /** Bytes reserved by active (unconsumed, unexpired) grants — main + thumbnail. */
    @Query(
        "SELECT COALESCE(SUM(g.maxSizeBytes + COALESCE(g.thumbnailMaxSizeBytes, 0)), 0) FROM UploadGrant g " +
            "WHERE g.tripId = :tripId AND g.consumed = false AND g.createdAt > :cutoff"
    )
    fun sumReservedBytes(@Param("tripId") tripId: UUID, @Param("cutoff") cutoff: Instant): Long

    /** Atomically consume a grant. Returns 1 if this call won the consume, 0 if already consumed. */
    @Modifying
    @Query("UPDATE UploadGrant g SET g.consumed = true WHERE g.id = :id AND g.consumed = false")
    fun markConsumed(@Param("id") id: UUID): Int

    /** Expired, unconsumed grants — abandoned reservations whose S3 objects are orphans. Paged so
     *  a single run touches a bounded batch (rows we fail to clean stay for the next run). */
    fun findByConsumedFalseAndCreatedAtBefore(cutoff: Instant, pageable: Pageable): List<UploadGrant>

    /** Deletes the given grant rows by id in one statement (used after their objects are gone). */
    @Modifying
    @Query("DELETE FROM UploadGrant g WHERE g.id IN :ids")
    fun deleteByIdIn(@Param("ids") ids: Collection<UUID>): Int

    /** Removes old consumed grant rows (the photo owns its object; the grant record is done). */
    @Modifying
    fun deleteByConsumedTrueAndCreatedAtBefore(cutoff: Instant): Int
}
