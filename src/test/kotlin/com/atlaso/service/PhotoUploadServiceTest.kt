package com.atlaso.service

import com.atlaso.controller.dto.ConfirmUploadRequest
import com.atlaso.controller.dto.InitiateUploadRequest
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.UploadGrant
import com.atlaso.domain.trip.Trip
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.TripRepository
import com.atlaso.repository.UploadGrantRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.util.Optional
import java.util.UUID
import javax.imageio.ImageIO

class PhotoUploadServiceTest {

    private val photoRepo = mock<PhotoRepository>()
    private val storage = mock<StorageService>()
    private val tripService = mock<TripService>()
    private val grantRepo = mock<UploadGrantRepository>()
    private val tripRepo = mock<TripRepository>()
    private val svc = PhotoUploadService(photoRepo, storage, tripService, grantRepo, tripRepo)

    private val tripId = UUID.randomUUID()
    private val photoId = UUID.randomUUID()
    private val key = "$tripId/$photoId.png"

    private fun png(w: Int = 10, h: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "png", out)
        return out.toByteArray()
    }

    private fun jpeg(w: Int = 10, h: Int = 10): ByteArray {
        val out = ByteArrayOutputStream()
        ImageIO.write(BufferedImage(w, h, BufferedImage.TYPE_INT_RGB), "jpeg", out)
        return out.toByteArray()
    }

    private fun grant(
        createdAt: Instant = Instant.now(),
        size: Long = 1000,
        thumb: String? = null,
        thumbSize: Long? = null,
    ) = UploadGrant(
        id = UUID.randomUUID(), tripId = tripId, photoId = photoId, storageKey = key,
        thumbnailKey = thumb, contentType = "image/png", maxSizeBytes = size,
        thumbnailMaxSizeBytes = thumbSize, createdAt = createdAt
    )

    private fun conf(storageKey: String = key, thumb: String? = null) = ConfirmUploadRequest(
        photoId = photoId, storageKey = storageKey, originalFilename = "p.png", contentType = "image/png",
        fileSize = 1000, width = 10, height = 10, takenAt = null, latitude = null, longitude = null, thumbnailStorageKey = thumb
    )

    @BeforeEach
    fun setup() {
        whenever(tripService.getTrip(tripId)).thenReturn(Trip(id = tripId, name = "T", status = TripStatus.UPLOADING_PHOTOS))
        // confirmUploads row-locks the trip before converting reservations.
        whenever(tripRepo.findByIdForUpdate(tripId)).thenReturn(Optional.of(Trip(id = tripId, name = "T", status = TripStatus.UPLOADING_PHOTOS)))
        // Defaults for the happy path — individual tests override to trigger a specific rejection.
        whenever(storage.head(key)).thenReturn(ObjectHead(contentLength = 1000, contentType = "image/png"))
        whenever(storage.load(key)).thenReturn(png())
        whenever(grantRepo.markConsumed(any())).thenReturn(1)
        whenever(photoRepo.saveAll(any<List<Photo>>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `confirm rejects a photo that was never initiated`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(null)
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects an expired grant`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(createdAt = Instant.now().minusSeconds(48 * 3600)))
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a mismatched storage key`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant())
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf(storageKey = "$tripId/other.png"))) }
    }

    @Test
    fun `confirm rejects when the object size doesn't match the reservation`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(size = 1000))
        whenever(storage.head(key)).thenReturn(ObjectHead(contentLength = 999, contentType = "image/png"))
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a content-type mismatch`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant())
        whenever(storage.head(key)).thenReturn(ObjectHead(contentLength = 1000, contentType = "application/pdf"))
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a thumbnail whose size doesn't match`() {
        val thumbKey = "$tripId/${photoId}_thumb.jpg"
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(thumb = thumbKey, thumbSize = 200))
        whenever(storage.head(thumbKey)).thenReturn(ObjectHead(contentLength = 199, contentType = "image/jpeg"))
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf(thumb = thumbKey))) }
    }

    @Test
    fun `confirm rejects bytes that aren't a real image`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant())
        whenever(storage.load(key)).thenReturn("this is not an image".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a grant already consumed by a concurrent request`() {
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant())
        whenever(grantRepo.markConsumed(any())).thenReturn(0) // lost the atomic consume race
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects when the object's actual format differs from the declared type`() {
        // Grant + HEAD say PNG, but the stored bytes are really a JPEG — reject the mismatch.
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant())
        whenever(storage.load(key)).thenReturn(jpeg())
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf())) }
    }

    @Test
    fun `confirm rejects a thumbnail whose bytes aren't a real image`() {
        val thumbKey = "$tripId/${photoId}_thumb.jpg"
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(thumb = thumbKey, thumbSize = 200))
        whenever(storage.head(thumbKey)).thenReturn(ObjectHead(contentLength = 200, contentType = "image/jpeg"))
        whenever(storage.load(thumbKey)).thenReturn("not an image".toByteArray())
        assertThrows(IllegalArgumentException::class.java) { svc.confirmUploads(tripId, listOf(conf(thumb = thumbKey))) }
    }

    @Test
    fun `confirm records the thumbnail size toward the trip byte quota`() {
        val thumbKey = "$tripId/${photoId}_thumb.jpg"
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(grant(thumb = thumbKey, thumbSize = 200))
        whenever(storage.head(thumbKey)).thenReturn(ObjectHead(contentLength = 200, contentType = "image/jpeg"))
        whenever(storage.load(thumbKey)).thenReturn(jpeg())

        val result = svc.confirmUploads(tripId, listOf(conf(thumb = thumbKey)))

        assertEquals(200L, result[0].thumbnailSizeBytes)
    }

    @Test
    fun `confirm accepts a valid upload, consumes the grant, uses server-validated dimensions`() {
        val g = grant(size = 1000)
        whenever(grantRepo.findByPhotoIdAndTripId(photoId, tripId)).thenReturn(g)
        whenever(storage.load(key)).thenReturn(png(w = 12, h = 8))

        val result = svc.confirmUploads(tripId, listOf(conf()))

        assertEquals(1, result.size)
        assertEquals(12, result[0].metadata.width)
        assertEquals(8, result[0].metadata.height)
        verify(grantRepo).markConsumed(g.id!!)
    }

    @Test
    fun `initiate counts outstanding grants toward the photo quota`() {
        whenever(tripRepo.findByIdForUpdate(tripId)).thenReturn(Optional.of(Trip(id = tripId, name = "T", status = TripStatus.CREATED)))
        whenever(photoRepo.countByTripId(tripId)).thenReturn(0)
        whenever(grantRepo.countByTripIdAndConsumedFalseAndCreatedAtAfter(any(), any())).thenReturn(PhotoUploadService.MAX_PHOTOS_PER_TRIP.toLong())
        val req = InitiateUploadRequest(filename = "p.jpg", contentType = "image/jpeg", fileSize = 1000)
        assertThrows(IllegalArgumentException::class.java) { svc.initiateUploads(tripId, listOf(req)) }
    }

    @Test
    fun `initiate enforces the cumulative byte quota`() {
        whenever(tripRepo.findByIdForUpdate(tripId)).thenReturn(Optional.of(Trip(id = tripId, name = "T", status = TripStatus.CREATED)))
        whenever(photoRepo.countByTripId(tripId)).thenReturn(0)
        whenever(grantRepo.countByTripIdAndConsumedFalseAndCreatedAtAfter(any(), any())).thenReturn(0)
        whenever(photoRepo.sumFileSizeByTripId(tripId)).thenReturn(0)
        whenever(grantRepo.sumReservedBytes(any(), any())).thenReturn(PhotoUploadService.MAX_BYTES_PER_TRIP) // already at the cap
        val req = InitiateUploadRequest(filename = "p.jpg", contentType = "image/jpeg", fileSize = 1000)
        assertThrows(IllegalArgumentException::class.java) { svc.initiateUploads(tripId, listOf(req)) }
    }
}
