package com.atlaso.service

import com.atlaso.domain.trip.Trip
import com.atlaso.domain.user.User
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UserRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.util.Optional
import java.util.UUID

class TripServiceTest {

    private val tripRepo = mock<TripRepository>()
    private val userRepo = mock<UserRepository>()
    private val storagePurger = mock<TripStoragePurger>()
    private val svc = TripService(tripRepo, userRepo, storagePurger)

    /** createTrip → returns a token and stores its hash; findById returns the saved trip. */
    private fun newTrip(): Pair<Trip, String> {
        whenever(tripRepo.save(any<Trip>())).thenAnswer { (it.arguments[0] as Trip).copy(id = UUID.randomUUID()) }
        val (trip, token) = svc.createTrip("Trip", null)
        whenever(tripRepo.findById(trip.id!!)).thenReturn(Optional.of(trip))
        return trip to token
    }

    @Test
    fun `guest access requires the matching token`() {
        val (trip, token) = newTrip()
        assertNotNull(trip.guestTokenHash)
        svc.assertTripAccess(trip.id!!, null, token) // correct token: no throw
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, "wrong-token") }
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, null) }
    }

    @Test
    fun `guest access is rejected on a claimed trip`() {
        val (trip, token) = newTrip()
        trip.user = User(id = UUID.randomUUID(), googleSub = "g", email = "o@x.com", name = "Owner")
        trip.guestTokenHash = null // a real claim revokes the token
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, token) }
    }

    @Test
    fun `read access needs owner JWT once claimed, guest token before`() {
        val (trip, token) = newTrip()
        // Unclaimed: guest token works, owner-less read fails.
        svc.assertTripAccess(trip.id!!, null, token)
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, null) }
        // Claimed: only the owner's user id is allowed.
        val ownerId = UUID.randomUUID()
        trip.user = User(id = ownerId, googleSub = "g", email = "o@x.com", name = "Owner")
        trip.guestTokenHash = null // a real claim revokes the token
        svc.assertTripAccess(trip.id!!, ownerId, null)
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, UUID.randomUUID(), null) }
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, token) }
    }

    @Test
    fun `claim requires the token and revokes it`() {
        val (trip, token) = newTrip()
        val userId = UUID.randomUUID()
        whenever(userRepo.findById(userId)).thenReturn(Optional.of(User(id = userId, googleSub = "g", email = "u@x.com", name = "U")))

        assertThrows(GuestTokenException::class.java) { svc.claimTrip(trip.id!!, userId, "wrong") }

        val claimed = svc.claimTrip(trip.id!!, userId, token)
        assertEquals(userId, claimed.user?.id)
        assertNull(claimed.guestTokenHash) // token revoked on claim
    }

    @Test
    fun `a signed-in creator owns the trip from the start`() {
        val userId = UUID.randomUUID()
        val user = User(id = userId, googleSub = "g", email = "u@x.com", name = "U")
        whenever(userRepo.findById(userId)).thenReturn(Optional.of(user))
        whenever(tripRepo.save(any<Trip>())).thenAnswer { (it.arguments[0] as Trip).copy(id = UUID.randomUUID()) }
        val (trip, token) = svc.createTrip("Trip", null, userId)
        whenever(tripRepo.findById(trip.id!!)).thenReturn(Optional.of(trip))

        assertEquals(userId, trip.user?.id)
        assertNotNull(trip.guestTokenHash)
        svc.assertTripAccess(trip.id!!, userId, null)  // owner JWT
        svc.assertTripAccess(trip.id!!, null, token)   // phone with the guest token, no sign-in
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, "wrong") }
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, UUID.randomUUID(), null) }

        // Build time: claim by the owner retires the guest token.
        val claimed = svc.claimTrip(trip.id!!, userId, token)
        assertEquals(userId, claimed.user?.id)
        assertNull(claimed.guestTokenHash)
        assertThrows(GuestTokenException::class.java) { svc.assertTripAccess(trip.id!!, null, token) }
        assertThrows(RuntimeException::class.java) { svc.claimTrip(trip.id!!, UUID.randomUUID(), token) }
    }

    @Test
    fun `an unknown user id falls back to a guest trip`() {
        val userId = UUID.randomUUID()
        whenever(userRepo.findById(userId)).thenReturn(Optional.empty())
        whenever(tripRepo.save(any<Trip>())).thenAnswer { (it.arguments[0] as Trip).copy(id = UUID.randomUUID()) }
        val (trip, _) = svc.createTrip("Trip", null, userId)
        assertNull(trip.user)
    }
}
