package com.atlaso.service

import com.atlaso.controller.dto.ConfirmUploadRequest
import com.atlaso.controller.dto.InitiateUploadRequest
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.UploadGrant
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.UploadGrantRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.util.UUID

class PhotoUploadServiceTest {

    private val photoRepo = mock<PhotoRepository>()
    private val storage = mock<StorageService>()
    private val tripService = mock<TripService>()
    private val grantRepo = mock<UploadGrantRepository>()
    private val svc = PhotoUploadService(photoRepo, storage, tripService, grantRepo)

    private val tripId = UUID.randomUUID()
    private val photoId = UUID.randomUUID()
    private val key = "$tripId/$photoId.jpg"

    @BeforeEach
    fun setup() {
        whenever(tripService.getTrip(tripId)).thenReturn(Trip(id = tripId, name = "T", status = TripStatus.UPLOADING_PHOTOS))
        whenever(photoRepo.countByTripId(tripId)).thenReturn(0)
    }

    private fun grant(consumed: Boolean = false, size: Long = 1000, thumb: String? = null) =
        UploadGrant(tripId = tripId, photoId = photoId, storageKey = key, thumbnailKey = thumb, contentType = "image/jpeg", maxSizeBytes = size, consumed = consumed)

    private fun conf(storageKey: String = key, thumb: String? = null) = ConfirmUploadRequest(
        photoId = photoId, storageKey = storageKey, originalFilename = "p.jpg", contentType = "image/jpeg",
        fileSize = 1000, width = 100, height = 100, takenAt = null, latitude = null, longitude = null, thumbnailStorageKey = thumb
    )

    @Test
    fun `confirm rejects a photo that was never initiated`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(null)
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a consumed grant (one-time)`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(consumed = true))
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a mismatched storage key`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant())
        assertThrows(IllegalArgumentException::class.java) {
            svc.confirmUploads(tripId, listOf(conf(storageKey = "$tripId/somethingelse.jpg")))
        }
    }

    @Test
    fun `confirm rejects when the object size doesn't match the reservation`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(size = 1000))
        whenever(storage.head(key)).thenReturn(ObjectHead(contentLength = 999, contentType = "image/jpeg"))
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm consumes the grant and saves the photo on success`() {
        val g = grant(size = 1000)
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(g)
        whenever(storage.head(key)).thenReturn(ObjectHead(1000, "image/jpeg"))
        whenever(photoRepo.saveAll(any<List<Photo>>())).thenAnswer { it.arguments[0] }

        val result = svc.confirmUploads(tripId, listOf(conf()))

        assertEquals(1, result.size)
        assertTrue(g.consumed)
        verify(grantRepo).save(g)
    }

    @Test
    fun `initiate counts outstanding grants toward the trip quota`() {
        whenever(photoRepo.countByTripId(tripId)).thenReturn(0)
        // Already at the cap via unconsumed reservations → one more must be refused.
        whenever(grantRepo.countByTripIdAndConsumedFalseAndCreatedAtAfter(any(), any()))
            .thenReturn(PhotoUploadService.MAX_PHOTOS_PER_TRIP.toLong())
        val req = InitiateUploadRequest(filename = "p.jpg", contentType = "image/jpeg", fileSize = 1000)
        assertThrows(IllegalArgumentException::class.java) { svc.initiateUploads(tripId, listOf(req)) }
    }
}
