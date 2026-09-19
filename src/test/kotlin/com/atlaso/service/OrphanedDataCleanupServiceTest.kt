package com.atlaso.service

import com.atlaso.config.CleanupProperties
import com.atlaso.domain.trip.Trip
import com.atlaso.repository.BookRepository
import com.atlaso.repository.OrderRepository
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UploadGrantRepository
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import java.time.Duration
import java.time.Instant
import java.util.UUID

class OrphanedDataCleanupServiceTest {

    private val tripRepo = mock<TripRepository>()
    private val photoRepo = mock<PhotoRepository>()
    private val bookRepo = mock<BookRepository>()
    private val orderRepo = mock<OrderRepository>()
    private val grantRepo = mock<UploadGrantRepository>()
    private val purger = mock<TripStoragePurger>()

    private fun service(enabled: Boolean = true) = OrphanedDataCleanupService(
        CleanupProperties(enabled = enabled, photoTripCutoffHours = 168, emptyTripCutoffHours = 72, batchSize = 200),
        tripRepo, photoRepo, bookRepo, orderRepo, grantRepo, purger,
    )

    private fun trip(ageHours: Long): Trip =
        Trip(id = UUID.randomUUID(), name = "t", user = null, createdAt = Instant.now().minus(Duration.ofHours(ageHours)))

    private fun candidates(vararg trips: Trip) {
        whenever(tripRepo.findByUserIsNullAndCreatedAtBefore(any(), any<Pageable>())).thenReturn(trips.toList())
        // default: no protections, no photos, purge succeeds
        whenever(bookRepo.existsByTripId(any())).thenReturn(false)
        whenever(orderRepo.existsByTripId(any())).thenReturn(false)
        whenever(photoRepo.countByTripId(any())).thenReturn(0L)
        whenever(purger.keysForTrip(any())).thenReturn(emptySet())
        whenever(purger.purge(any(), any())).thenReturn(true)
    }

    @Test
    fun `empty trip past the empty window is deleted`() {
        val t = trip(ageHours = 100) // > 72h empty cutoff
        candidates(t)
        service().cleanupOrphanedTrips()
        verify(tripRepo).delete(t)
        verify(photoRepo).deleteByTripId(t.id!!)
        verify(grantRepo).deleteByTripId(t.id!!)
    }

    @Test
    fun `trip with photos is kept until the longer photo window`() {
        val t = trip(ageHours = 100) // past empty (72h) but not photo (168h) window
        candidates(t)
        whenever(photoRepo.countByTripId(t.id!!)).thenReturn(5L)
        service().cleanupOrphanedTrips()
        verify(tripRepo, never()).delete(any())
        verify(purger, never()).purge(any(), any())
    }

    @Test
    fun `trip with photos past the photo window is deleted`() {
        val t = trip(ageHours = 200) // > 168h photo cutoff
        candidates(t)
        whenever(photoRepo.countByTripId(t.id!!)).thenReturn(5L)
        service().cleanupOrphanedTrips()
        verify(tripRepo).delete(t)
    }

    @Test
    fun `trip with an order is never deleted`() {
        val t = trip(ageHours = 500)
        candidates(t)
        whenever(orderRepo.existsByTripId(t.id!!)).thenReturn(true)
        service().cleanupOrphanedTrips()
        verify(tripRepo, never()).delete(any())
    }

    @Test
    fun `trip with a book is never deleted`() {
        val t = trip(ageHours = 500)
        candidates(t)
        whenever(bookRepo.existsByTripId(t.id!!)).thenReturn(true)
        service().cleanupOrphanedTrips()
        verify(tripRepo, never()).delete(any())
    }

    @Test
    fun `failed object delete holds the trip for retry`() {
        val t = trip(ageHours = 100)
        candidates(t)
        whenever(purger.purge(any(), any())).thenReturn(false) // an S3 delete failed
        service().cleanupOrphanedTrips()
        verify(tripRepo, never()).delete(any())
    }

    @Test
    fun `dry-run deletes nothing`() {
        val t = trip(ageHours = 500)
        candidates(t)
        service(enabled = false).cleanupOrphanedTrips()
        verify(tripRepo, never()).delete(any())
        verify(purger, never()).purge(any(), any())
    }
}
