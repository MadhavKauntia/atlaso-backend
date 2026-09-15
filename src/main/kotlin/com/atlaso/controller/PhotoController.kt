package com.atlaso.controller

import com.atlaso.controller.dto.ConfirmUploadRequest
import com.atlaso.controller.dto.InitiateUploadRequest
import com.atlaso.controller.dto.InitiateUploadResponse
import com.atlaso.controller.dto.PhotoResponse
import com.atlaso.service.PhotoUploadService
import com.atlaso.service.StorageService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/trips/{tripId}/photos")
class PhotoController(
    private val photoUploadService: PhotoUploadService,
    private val storageService: StorageService
) {

    /** Builds a PhotoResponse including direct (presigned) image + thumbnail URLs. */
    private fun toResponse(photo: com.atlaso.domain.photo.Photo): PhotoResponse {
        val url = runCatching { storageService.getAccessUrl(photo.storageKey, photo.contentType) }.getOrNull()
        val thumbUrl = photo.thumbnailKey?.let {
            runCatching { storageService.getAccessUrl(it, "image/jpeg") }.getOrNull()
        }
        return PhotoResponse.from(photo, url, thumbUrl)
    }

    // Photo creation is exclusively via the presigned initiate/confirm flow below, which enforces
    // the trip lock, count/byte quota, one-time grant binding, and real-image validation. The old
    // direct multipart routes bypassed all of that, so they were removed.

    // Guest-readable with the trip's guest token; owner JWT after claim.
    @GetMapping
    fun getPhotos(
        @PathVariable tripId: UUID,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<List<PhotoResponse>> {
        val photos = photoUploadService.getPhotosForTrip(tripId, jwt?.subject?.let(UUID::fromString), guestToken)
            .map { toResponse(it) }
        return ResponseEntity.ok(photos)
    }

    @PutMapping("/{photoId}/rotate")
    fun rotatePhoto(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID,
        @RequestParam("degrees", defaultValue = "90") degrees: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<PhotoResponse> {
        val userId = UUID.fromString(jwt.subject)
        val photo = photoUploadService.rotatePhoto(photoId, degrees, tripId, userId)
        return ResponseEntity.ok(toResponse(photo))
    }

    @DeleteMapping("/{photoId}")
    fun deletePhoto(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        val userId = UUID.fromString(jwt.subject)
        photoUploadService.deletePhoto(photoId, tripId, userId)
        return ResponseEntity.noContent().build()
    }

    // Public — guest uploads before login
    // Guest token (unclaimed) or owner JWT (claimed).
    @PostMapping("/initiate")
    fun initiateUploads(
        @PathVariable tripId: UUID,
        @RequestBody requests: List<InitiateUploadRequest>,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<List<InitiateUploadResponse>> {
        val responses = photoUploadService.initiateUploads(tripId, requests, jwt?.subject?.let(UUID::fromString), guestToken)
        return ResponseEntity.ok(responses)
    }

    // Guest token (unclaimed) or owner JWT (claimed).
    @PostMapping("/confirm")
    fun confirmUploads(
        @PathVariable tripId: UUID,
        @RequestBody confirmations: List<ConfirmUploadRequest>,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<List<PhotoResponse>> {
        val photos = photoUploadService.confirmUploads(tripId, confirmations, jwt?.subject?.let(UUID::fromString), guestToken)
        return ResponseEntity.status(HttpStatus.CREATED).body(photos.map { toResponse(it) })
    }

    @GetMapping("/{photoId}/image")
    fun getPhotoImage(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID,
        @RequestHeader(value = "X-Guest-Token", required = false) guestToken: String?,
        @AuthenticationPrincipal jwt: Jwt?
    ): ResponseEntity<Void> {
        val photo = photoUploadService.getPhotoByTripAndId(photoId, tripId, jwt?.subject?.let(UUID::fromString), guestToken)
        val url = storageService.getAccessUrl(photo.storageKey, photo.contentType)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, url)
            .build()
    }
}
