package com.atlaso.controller

import com.atlaso.controller.dto.CreateTripRequest
import com.atlaso.controller.dto.TripResponse
import com.atlaso.service.TripService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/trips")
class TripController(
    private val tripService: TripService
) {

    @PostMapping
    fun createTrip(@RequestBody request: CreateTripRequest): ResponseEntity<TripResponse> {
        val trip = tripService.createTrip(request.name, request.destination)
        return ResponseEntity.status(HttpStatus.CREATED).body(TripResponse.from(trip))
    }

    @GetMapping
    fun getAllTrips(): ResponseEntity<List<TripResponse>> {
        val trips = tripService.getAllTrips().map { TripResponse.from(it) }
        return ResponseEntity.ok(trips)
    }

    @GetMapping("/{id}")
    fun getTrip(@PathVariable id: UUID): ResponseEntity<TripResponse> {
        val trip = tripService.getTrip(id)
        return ResponseEntity.ok(TripResponse.from(trip))
    }
}
