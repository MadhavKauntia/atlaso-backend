package com.atlaso.controller

import com.atlaso.controller.dto.CreateTripRequest
import com.atlaso.controller.dto.TripResponse
import com.atlaso.controller.dto.UpdateTripCoverRequest
import com.atlaso.domain.trip.TripStatus
import com.atlaso.service.TripService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

data class UpdateTripRequest(val name: String? = null, val destination: String? = null)

@RestController
@RequestMapping("/api/trips")
class TripController(
    private val tripService: TripService
) {

    // Public. Signed in: the trip is owned by the caller from the start. Guest: no user until
    // claim. Either way returns a one-time guest token the client sends (X-Guest-Token) for
    // guest operations on this trip until it's claimed.
    @PostMapping
    fun createTrip(
        @RequestBody request: CreateTripRequest,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<TripResponse> {
        val (trip, guestToken) = tripService.createTrip(request.name, request.destination, jwt?.subject?.let(UUID::fromString))
        return ResponseEntity.status(HttpStatus.CREATED).body(TripResponse.from(trip, guestToken))
    }

    @GetMapping
    fun getAllTrips(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<List<TripResponse>> {
        val userId = UUID.fromString(jwt.subject)
        val trips = tripService.getAllTrips(userId).map { TripResponse.from(it) }
        return ResponseEntity.ok(trips)
    }

    // Guest-readable before login with the guest token; owner JWT after claim.
    @GetMapping("/{id}")
    fun getTrip(
        @PathVariable id: UUID,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<TripResponse> {
        tripService.assertTripAccess(id, jwt?.subject?.let(UUID::fromString), guestToken)
        val trip = tripService.getTrip(id)
        return ResponseEntity.ok(TripResponse.from(trip))
    }

    @PatchMapping("/{id}")
    fun updateTrip(
        @PathVariable id: UUID,
        @RequestBody request: UpdateTripRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TripResponse> {
        val userId = UUID.fromString(jwt.subject)
        val trip = tripService.updateTrip(id, userId, request.name, request.destination)
        return ResponseEntity.ok(TripResponse.from(trip))
    }

    // Cover step choices, saved as they're edited. Guest token (before sign-in) or owner JWT.
    @PutMapping("/{id}/cover")
    fun updateCover(
        @PathVariable id: UUID,
        @RequestBody request: UpdateTripCoverRequest,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<TripResponse> {
        val trip = tripService.updateCover(
            id, jwt?.subject?.let(UUID::fromString), guestToken,
            request.title, request.country, request.subtitle, request.paletteId
        )
        return ResponseEntity.ok(TripResponse.from(trip))
    }

    @DeleteMapping("/{id}")
    fun deleteTrip(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        val userId = UUID.fromString(jwt.subject)
        tripService.deleteTrip(id, userId)
        return ResponseEntity.noContent().build()
    }

    // Requires auth — associates the guest trip with the authenticated user. Claiming an
    // unclaimed trip requires its guest token so a UUID alone can't hijack a trip.
    @PostMapping("/{id}/claim")
    fun claimTrip(
        @PathVariable id: UUID,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TripResponse> {
        val userId = UUID.fromString(jwt.subject)
        val trip = tripService.claimTrip(id, userId, guestToken)
        return ResponseEntity.ok(TripResponse.from(trip))
    }

    // NOTE: the old POST /{id}/order endpoint was removed — a trip may only be marked ORDERED
    // by the verified-payment transaction (PaymentController.verify → OrderService), never on
    // an unauthenticated client's say-so.
}
