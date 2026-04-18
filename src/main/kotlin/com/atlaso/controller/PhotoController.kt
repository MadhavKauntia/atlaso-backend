package com.atlaso.controller

import com.atlaso.controller.dto.BulkUploadFailure
import com.atlaso.controller.dto.BulkUploadResponse
import com.atlaso.controller.dto.ConfirmUploadRequest
import com.atlaso.controller.dto.InitiateUploadRequest
import com.atlaso.controller.dto.InitiateUploadResponse
import com.atlaso.controller.dto.PhotoResponse
import com.atlaso.service.PhotoUploadService
import com.atlaso.service.StorageService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.*
import org.springframework.web.multipart.MultipartFile
import java.util.UUID

@RestController
@RequestMapping("/api/trips/{tripId}/photos")
class PhotoController(
    private val photoUploadService: PhotoUploadService,
    private val storageService: StorageService
) {

    @PostMapping
    fun uploadPhoto(
        @PathVariable tripId: UUID,
        @RequestParam("file") file: MultipartFile,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<PhotoResponse> {
        val userId = UUID.fromString(jwt.subject)
        val photo = photoUploadService.uploadPhoto(tripId, file, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(PhotoResponse.from(photo))
    }

    @PostMapping("/bulk")
    fun uploadPhotos(
        @PathVariable tripId: UUID,
        @RequestParam("files") files: List<MultipartFile>,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<BulkUploadResponse> {
        val userId = UUID.fromString(jwt.subject)
        val uploaded = mutableListOf<PhotoResponse>()
        val failed = mutableListOf<BulkUploadFailure>()

        for (file in files) {
            val filename = file.originalFilename ?: "unknown"
            try {
                val photo = photoUploadService.uploadPhoto(tripId, file, userId)
                uploaded.add(PhotoResponse.from(photo))
            } catch (e: Exception) {
                failed.add(BulkUploadFailure(filename = filename, error = e.message ?: "Unknown error"))
            }
        }

        val status = if (uploaded.isEmpty() && failed.isNotEmpty()) HttpStatus.BAD_REQUEST else HttpStatus.CREATED
        return ResponseEntity.status(status).body(BulkUploadResponse(uploaded = uploaded, failed = failed))
    }

    @GetMapping
    fun getPhotos(
        @PathVariable tripId: UUID,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<List<PhotoResponse>> {
        val userId = UUID.fromString(jwt.subject)
        val photos = photoUploadService.getPhotosForTrip(tripId, userId).map { PhotoResponse.from(it) }
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
        return ResponseEntity.ok(PhotoResponse.from(photo))
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

    @PostMapping("/initiate")
    fun initiateUploads(
        @PathVariable tripId: UUID,
        @RequestBody requests: List<InitiateUploadRequest>,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<List<InitiateUploadResponse>> {
        val userId = UUID.fromString(jwt.subject)
        val responses = photoUploadService.initiateUploads(tripId, requests, userId)
        return ResponseEntity.ok(responses)
    }

    @PostMapping("/confirm")
    fun confirmUploads(
        @PathVariable tripId: UUID,
        @RequestBody confirmations: List<ConfirmUploadRequest>,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<List<PhotoResponse>> {
        val userId = UUID.fromString(jwt.subject)
        val photos = photoUploadService.confirmUploads(tripId, confirmations, userId)
        return ResponseEntity.status(HttpStatus.CREATED).body(photos.map { PhotoResponse.from(it) })
    }

    @GetMapping("/{photoId}/image")
    fun getPhotoImage(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID
    ): ResponseEntity<Void> {
        val photo = photoUploadService.getPhotoByTripAndId(photoId, tripId)
        val url = storageService.getAccessUrl(photo.storageKey, photo.contentType)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, url)
            .build()
    }
}
