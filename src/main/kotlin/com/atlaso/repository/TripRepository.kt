package com.atlaso.repository

import com.atlaso.domain.trip.Trip
import org.springframework.data.jpa.repository.JpaRepository
import java.util.Optional
import java.util.UUID

interface TripRepository : JpaRepository<Trip, UUID> {
    fun findAllByUserIdOrderByCreatedAtDesc(userId: UUID): List<Trip>
    fun findByIdAndUserId(id: UUID, userId: UUID): Optional<Trip>
}
