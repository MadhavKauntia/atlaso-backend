package com.atlaso.service

import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

class TripNotFoundException(id: UUID) : RuntimeException("Trip not found: $id")

@Service
@Transactional
class TripService(
    private val tripRepository: TripRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(TripService::class.java)

    fun createTrip(name: String, destination: String?, userId: UUID): Trip {
        val user = userRepository.findById(userId).orElseThrow { RuntimeException("User not found") }
        val trip = Trip(name = name, destination = destination, user = user)
        val saved = tripRepository.save(trip)
        logger.info("Created trip: {} ({}) for user: {}", saved.id, saved.name, userId)
        return saved
    }

    @Transactional(readOnly = true)
    fun getAllTrips(userId: UUID): List<Trip> {
        return tripRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
    }

    @Transactional(readOnly = true)
    fun getTrip(id: UUID, userId: UUID): Trip {
        return tripRepository.findByIdAndUserId(id, userId)
            .orElseThrow { TripNotFoundException(id) }
    }

    // Internal use only — ownership must have been verified upstream
    @Transactional(readOnly = true)
    internal fun getTripById(id: UUID): Trip {
        return tripRepository.findById(id).orElseThrow { TripNotFoundException(id) }
    }

    fun updateStatus(id: UUID, status: TripStatus): Trip {
        val trip = getTripById(id)
        trip.status = status
        return tripRepository.save(trip)
    }
}
