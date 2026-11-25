package com.atlaso.repository

import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import jakarta.persistence.LockModeType
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.time.Instant
import java.util.Optional
import java.util.UUID

interface TripRepository : JpaRepository<Trip, UUID> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<Trip>
    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<Trip>

    /** Unclaimed (guest) trips older than [cutoff] — cleanup candidates. Paged + oldest-first so a
     *  run touches a bounded batch and whatever it doesn't reach is picked up next time. */
    fun findByUserIsNullAndCreatedAtBefore(cutoff: Instant, pageable: Pageable): List<Trip>

    /** Owned trips still in [status] (CREATED = no photo ever confirmed) older than [cutoff]. A
     *  signed-in user owns a trip from creation, so abandoned starts would otherwise pile up. */
    fun findByUserIsNotNullAndStatusAndCreatedAtBefore(status: TripStatus, cutoff: Instant, pageable: Pageable): List<Trip>

    /** Row-locks the trip so per-trip upload reservation (count/bytes) is atomic under concurrency. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    fun findByIdForUpdate(id: UUID): Optional<Trip>
}
