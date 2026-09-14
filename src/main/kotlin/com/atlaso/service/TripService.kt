package com.atlaso.service

import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.UUID

class TripNotFoundException(id: UUID) : RuntimeException("Trip not found: $id")

/** Thrown when a guest operation is attempted without the trip's capability token. */
class GuestTokenException(message: String) : RuntimeException(message)

/** A newly created guest trip plus its one-time capability token (returned to the client once). */
data class TripWithToken(val trip: Trip, val guestToken: String)

@Service
@Transactional
class TripService(
    private val tripRepository: TripRepository,
    private val userRepository: UserRepository
) {
    private val logger = LoggerFactory.getLogger(TripService::class.java)
    private val secureRandom = SecureRandom()

    // Guest trip — no user yet; claimed later via claimTrip(). Returns the capability token
    // once; only its hash is stored.
    fun createTrip(name: String, destination: String?): TripWithToken {
        val token = generateGuestToken()
        val trip = Trip(name = name, destination = destination, user = null, guestTokenHash = hashToken(token))
        val saved = tripRepository.save(trip)
        logger.info("Created guest trip: {} ({})", saved.id, saved.name)
        return TripWithToken(saved, token)
    }

    private fun generateGuestToken(): String {
        val bytes = ByteArray(32).also { secureRandom.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun hashToken(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun tokenMatches(provided: String?, storedHash: String): Boolean {
        val hashed = provided?.let { hashToken(it) } ?: return false
        return MessageDigest.isEqual(hashed.toByteArray(Charsets.UTF_8), storedHash.toByteArray(Charsets.UTF_8))
    }

    /**
     * Enforces guest capability for operations on a trip by UUID. A claimed trip must be
     * operated with the owner's JWT (authenticated endpoints), not the guest token. An
     * unclaimed trip requires its matching guest token — no grandfathering, so a bare UUID
     * (or a tokenless legacy trip) can never be used. (Clear tokenless legacy trips before
     * launch; see the PR notes.)
     */
    fun assertGuestAccess(tripId: UUID, guestToken: String?) {
        val trip = tripRepository.findById(tripId).orElseThrow { TripNotFoundException(tripId) }
        if (trip.user != null) throw GuestTokenException("This trip has been claimed — sign in to continue")
        val hash = trip.guestTokenHash ?: throw GuestTokenException("Guest access is not available for this trip")
        if (!tokenMatches(guestToken, hash)) throw GuestTokenException("Invalid or missing guest token")
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

    // Associate a guest trip with a logged-in user (idempotent). Claiming an unclaimed trip
    // requires its guest token, so a UUID alone can't be used to hijack someone's trip.
    fun claimTrip(tripId: UUID, userId: UUID, guestToken: String? = null): Trip {
        val trip = tripRepository.findById(tripId).orElseThrow { TripNotFoundException(tripId) }
        val existingUser = trip.user
        if (existingUser != null) {
            if (existingUser.id != userId) throw RuntimeException("Trip already belongs to another user")
            return trip // already claimed by this user
        }
        // Require the matching guest token to claim — a UUID alone can't hijack a trip.
        val hash = trip.guestTokenHash ?: throw GuestTokenException("Guest token required to claim this trip")
        if (!tokenMatches(guestToken, hash)) throw GuestTokenException("Invalid or missing guest token")

        val user = userRepository.findById(userId).orElseThrow { RuntimeException("User not found") }
        trip.user = user
        // Revoke the guest token on claim — subsequent access must use the owner's JWT.
        trip.guestTokenHash = null
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
