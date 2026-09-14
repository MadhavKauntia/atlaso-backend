package com.atlaso.controller.dto

import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class CreateTripRequest(
    val name: String,
    val destination: String? = null,
    val startDate: LocalDate? = null,
    val endDate: LocalDate? = null
)

data class TripResponse(
    val id: UUID,
    val name: String,
    val destination: String?,
    val startDate: LocalDate?,
    val endDate: LocalDate?,
    val status: TripStatus,
    val createdAt: Instant?,
    val updatedAt: Instant?,
    // Only present in the create-trip response — the client keeps it and sends it as the
    // X-Guest-Token header for subsequent guest operations (upload/claim). Never re-served.
    val guestToken: String? = null
) {
    companion object {
        fun from(trip: Trip): TripResponse = TripResponse(
            id = trip.id!!,
            name = trip.name,
            destination = trip.destination,
            startDate = trip.startDate,
            endDate = trip.endDate,
            status = trip.status,
            createdAt = trip.createdAt,
            updatedAt = trip.updatedAt
        )

        fun from(trip: Trip, guestToken: String?): TripResponse = from(trip).copy(guestToken = guestToken)
    }
}
