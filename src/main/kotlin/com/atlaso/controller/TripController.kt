package com.atlaso.controller

import com.atlaso.controller.dto.CreateTripRequest
import com.atlaso.controller.dto.TripResponse
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

    @PostMapping
    fun createTrip(
        @RequestBody request: CreateTripRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TripResponse> {
        val userId = UUID.fromString(jwt.subject)
        val trip = tripService.createTrip(request.name, request.destination, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(TripResponse.from(trip))
    }

    @GetMapping
    fun getAllTrips(@AuthenticationPrincipal jwt: Jwt): ResponseEntity<List<TripResponse>> {
        val userId = UUID.fromString(jwt.subject)
        val trips = tripService.getAllTrips(userId).map { TripResponse.from(it) }
        return ResponseEntity.ok(trips)
    }

    @GetMapping("/{id}")
    fun getTrip(
        @PathVariable id: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TripResponse> {
        val userId = UUID.fromString(jwt.subject)
        val trip = tripService.getTrip(id, userId)
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
}
