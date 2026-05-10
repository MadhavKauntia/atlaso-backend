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

    // Guest trip — no user yet; claimed later via claimTrip()
    fun createTrip(name: String, destination: String?): Trip {
        val trip = Trip(name = name, destination = destination, user = null)
        val saved = tripRepository.save(trip)
        logger.info("Created guest trip: {} ({})", saved.id, saved.name)
        return saved
    }

    // Public lookup by ID — no ownership check (used by guest flow and public endpoints)
    fun getTrip(id: UUID): Trip {
        return tripRepository.findById(id).orElseThrow { TripNotFoundException(id) }
    }

    // Ownership-verified lookup — used by authenticated endpoints after claim
    fun getTrip(id: UUID, userId: UUID): Trip {
        return tripRepository.findByIdAndUserId(id, userId)
            .orElseThrow { TripNotFoundException(id) }
    }

    // Associate a guest trip with a logged-in user (idempotent)
    fun claimTrip(tripId: UUID, userId: UUID): Trip {
        val trip = tripRepository.findById(tripId).orElseThrow { TripNotFoundException(tripId) }
        val existingUser = trip.user
        if (existingUser != null) {
            if (existingUser.id != userId) throw RuntimeException("Trip already belongs to another user")
            return trip // already claimed by this user
        }
        val user = userRepository.findById(userId).orElseThrow { RuntimeException("User not found") }
        trip.user = user
        val saved = tripRepository.save(trip)
        logger.info("Claimed trip: {} for user: {}", tripId, userId)
        return saved
    }

    @Transactional(readOnly = true)
    fun getAllTrips(userId: UUID): List<Trip> {
        return tripRepository.findAllByUserIdOrderByCreatedAtDesc(userId)
    }

    internal fun getTripById(id: UUID): Trip {
        return tripRepository.findById(id).orElseThrow { TripNotFoundException(id) }
    }

    fun updateStatus(id: UUID, status: TripStatus): Trip {
        val trip = getTripById(id)
        trip.status = status
        return tripRepository.save(trip)
    }

    fun updateTrip(id: UUID, userId: UUID, name: String?, destination: String?): Trip {
        val trip = getTrip(id, userId)
        name?.let { trip.name = it }
        destination?.let { trip.destination = it }
        return tripRepository.save(trip)
    }

    fun deleteTrip(id: UUID, userId: UUID) {
        val trip = getTrip(id, userId)
        tripRepository.delete(trip)
        logger.info("Deleted trip: {} for user: {}", id, userId)
    }
}
