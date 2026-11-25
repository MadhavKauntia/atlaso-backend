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
    val coverCountry: String? = null,
    val coverPaletteId: String? = null,
    val coverSubtitle: String? = null,
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
            updatedAt = trip.updatedAt,
            coverCountry = trip.coverCountry,
            coverPaletteId = trip.coverPaletteId,
            coverSubtitle = trip.coverSubtitle
        )

        fun from(trip: Trip, guestToken: String?): TripResponse = from(trip).copy(guestToken = guestToken)
    }
}

/** The cover step's choices for a trip that doesn't have a book yet. */
data class UpdateTripCoverRequest(
    val title: String? = null,
    val country: String? = null,
    val subtitle: String? = null,
    val paletteId: String? = null
)
