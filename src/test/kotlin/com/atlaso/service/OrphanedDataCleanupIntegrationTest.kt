package com.atlaso.service

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.trip.Trip
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.atLeastOnce
import org.mockito.kotlin.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Runs the cleanup against a real database. The unit test mocks the repositories, which hid a
 * Hibernate flush failure: with two or more due trips in one run, the job threw
 * TransientObjectException on the second trip and rolled everything back (after S3 deletes).
 */
@SpringBootTest
class OrphanedDataCleanupIntegrationTest {

    @Autowired lateinit var cleanup: OrphanedDataCleanupService
    @Autowired lateinit var tripRepository: TripRepository
    @Autowired lateinit var photoRepository: PhotoRepository
    @Autowired lateinit var jdbc: JdbcTemplate

    @MockitoBean lateinit var storageService: StorageService

    private val created = mutableListOf<UUID>()

    @AfterEach
    fun tearDown() {
        created.forEach { id ->
            jdbc.update("DELETE FROM photos WHERE trip_id = ?", id)
            jdbc.update("DELETE FROM trips WHERE id = ?", id)
        }
    }

    /** An unclaimed guest trip with [photos] photos, backdated by [ageDays]. */
    private fun guestTrip(photos: Int, ageDays: Int): UUID {
        val trip = tripRepository.save(Trip(name = "abandoned", user = null))
        val id = trip.id!!
        created += id
        repeat(photos) { i ->
            photoRepository.save(
                Photo(
                    trip = trip,
                    storageKey = "$id/p$i.jpg",
                    thumbnailKey = "$id/p${i}_thumb.jpg",
                    originalFilename = "p$i.jpg",
                    contentType = "image/jpeg",
                    fileSize = 100,
                    metadata = PhotoMetadata(width = 2000, height = 1500),
                )
            )
        }
        jdbc.update("UPDATE trips SET created_at = now() - make_interval(days => ?) WHERE id = ?", ageDays, id)
        return id
    }

    @Test
    fun `deletes several due guest trips with photos in one run`() {
        val a = guestTrip(photos = 3, ageDays = 30)
        val b = guestTrip(photos = 2, ageDays = 20)
        val empty = guestTrip(photos = 0, ageDays = 10)

        cleanup.cleanupOrphanedTrips()

        for (id in listOf(a, b, empty)) {
            assertFalse(tripRepository.existsById(id), "trip $id should be deleted")
            assertEquals(0L, photoRepository.countByTripId(id))
        }
        verify(storageService, atLeastOnce()).delete(any())
    }

    @Test
    fun `keeps a guest trip with photos that is not yet due`() {
        val recent = guestTrip(photos = 2, ageDays = 4) // past the 3-day empty window, inside the 7-day photo window
        cleanup.cleanupOrphanedTrips()
        assert(tripRepository.existsById(recent))
        assertEquals(2L, photoRepository.countByTripId(recent))
    }
}
