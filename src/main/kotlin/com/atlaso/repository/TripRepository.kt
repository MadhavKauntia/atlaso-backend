package com.atlaso.repository

import com.atlaso.domain.trip.Trip
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TripRepository : JpaRepository<Trip, UUID> {
    fun findAllByOrderByCreatedAtDesc(): List<Trip>
}
