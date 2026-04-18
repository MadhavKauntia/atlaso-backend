package com.atlaso.controller

import com.atlaso.controller.dto.BulkUploadFailure
import com.atlaso.controller.dto.BulkUploadResponse
import com.atlaso.controller.dto.PhotoResponse
import com.atlaso.service.PhotoUploadService
import com.atlaso.service.StorageService
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
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
        @RequestParam("file") file: MultipartFile
    ): ResponseEntity<PhotoResponse> {
        val photo = photoUploadService.uploadPhoto(tripId, file)
        return ResponseEntity.status(HttpStatus.CREATED).body(PhotoResponse.from(photo))
    }

    @PostMapping("/bulk")
    fun uploadPhotos(
        @PathVariable tripId: UUID,
        @RequestParam("files") files: List<MultipartFile>
    ): ResponseEntity<BulkUploadResponse> {
        val uploaded = mutableListOf<PhotoResponse>()
        val failed = mutableListOf<BulkUploadFailure>()

        for (file in files) {
            val filename = file.originalFilename ?: "unknown"
            try {
                val photo = photoUploadService.uploadPhoto(tripId, file)
                uploaded.add(PhotoResponse.from(photo))
            } catch (e: Exception) {
                failed.add(BulkUploadFailure(filename = filename, error = e.message ?: "Unknown error"))
            }
        }

        val status = if (uploaded.isEmpty() && failed.isNotEmpty()) HttpStatus.BAD_REQUEST else HttpStatus.CREATED
        return ResponseEntity.status(status).body(BulkUploadResponse(uploaded = uploaded, failed = failed))
    }

    @GetMapping
    fun getPhotos(@PathVariable tripId: UUID): ResponseEntity<List<PhotoResponse>> {
        val photos = photoUploadService.getPhotosForTrip(tripId).map { PhotoResponse.from(it) }
        return ResponseEntity.ok(photos)
    }

    @PutMapping("/{photoId}/rotate")
    fun rotatePhoto(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID,
        @RequestParam("degrees", defaultValue = "90") degrees: Int
    ): ResponseEntity<PhotoResponse> {
        val photo = photoUploadService.rotatePhoto(photoId, degrees)
        return ResponseEntity.ok(PhotoResponse.from(photo))
    }

    @DeleteMapping("/{photoId}")
    fun deletePhoto(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID
    ): ResponseEntity<Void> {
        photoUploadService.deletePhoto(photoId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{photoId}/image")
    fun getPhotoImage(
        @PathVariable tripId: UUID,
        @PathVariable photoId: UUID
    ): ResponseEntity<Void> {
        val photo = photoUploadService.getPhoto(photoId)
        val url = storageService.getAccessUrl(photo.storageKey, photo.contentType)
        return ResponseEntity.status(HttpStatus.FOUND)
            .header(HttpHeaders.LOCATION, url)
            .build()
    }
}
