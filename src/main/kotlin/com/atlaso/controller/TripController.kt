package com.atlaso.controller

import com.atlaso.controller.dto.CreateTripRequest
import com.atlaso.controller.dto.TripResponse
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

    // Public — creates a guest trip with no user attached. Returns a one-time guest token
    // the client must send (X-Guest-Token) for later guest operations on this trip.
    @PostMapping
    fun createTrip(@RequestBody request: CreateTripRequest): ResponseEntity<TripResponse> {
        val (trip, guestToken) = tripService.createTrip(request.name, request.destination)
        return ResponseEntity.status(HttpStatus.CREATED).body(TripResponse.from(trip, guestToken))
    }

    @GetMapping
    fun getAllTrips(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<List<TripResponse>> {
        val userId = UUID.fromString(jwt.subject)
        val trips = tripService.getAllTrips(userId).map { TripResponse.from(it) }
        return ResponseEntity.ok(trips)
    }

    // Public — needed by cover page and upload page before login
    @GetMapping("/{id}")
    fun getTrip(@PathVariable id: UUID): ResponseEntity<TripResponse> {
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

    @PostMapping("/{id}/order")
    fun markOrdered(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TripResponse> {
        val userId = UUID.fromString(jwt.subject)
        tripService.getTrip(id, userId) // validates ownership
        val trip = tripService.updateStatus(id, TripStatus.ORDERED)
        return ResponseEntity.ok(TripResponse.from(trip))
    }
}
