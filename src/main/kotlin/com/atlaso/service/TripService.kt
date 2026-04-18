package com.atlaso.service

import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.TripRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class TripNotFoundException(id: UUID) : RuntimeException("Trip not found: $id")

@Service
@Transactional
class TripService(
    private val tripRepository: TripRepository
) {
    private val logger = LoggerFactory.getLogger(TripService::class.java)

    fun createTrip(name: String, destination: String?): Trip {
        val trip = Trip(
            name = name,
            destination = destination,
            status = TripStatus.CREATED
        )
        val saved = tripRepository.save(trip)
        logger.info("Created trip: {} ({})", saved.id, saved.name)
        return saved
    }

    @Transactional(readOnly = true)
    fun getAllTrips(): List<Trip> {
        return tripRepository.findAllByOrderByCreatedAtDesc()
    }

    @Transactional(readOnly = true)
    fun getTrip(id: UUID): Trip {
        return tripRepository.findById(id)
            .orElseThrow { TripNotFoundException(id) }
    }

    fun updateStatus(id: UUID, status: TripStatus): Trip {
        val trip = getTrip(id)
        trip.status = status
        return tripRepository.save(trip)
    }
}
