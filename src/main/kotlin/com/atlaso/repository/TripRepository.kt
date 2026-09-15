package com.atlaso.repository

import com.atlaso.domain.trip.Trip
import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.Optional
import java.util.UUID

interface TripRepository : JpaRepository<Trip, UUID> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<Trip>
    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<Trip>

    /** Row-locks the trip so per-trip upload reservation (count/bytes) is atomic under concurrency. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM Trip t WHERE t.id = :id")
    fun findByIdForUpdate(id: UUID): Optional<Trip>
}
