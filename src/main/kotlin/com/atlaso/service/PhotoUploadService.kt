package com.atlaso.service

import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.PhotoRepository
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class PhotoNotFoundException(id: UUID) : RuntimeException("Photo not found: $id")

@Service
@Transactional
class PhotoUploadService(
    private val photoRepository: PhotoRepository,
    private val storageService: StorageService,
    private val tripService: TripService
) {
    private val logger = LoggerFactory.getLogger(PhotoUploadService::class.java)

    companion object {
        val ALLOWED_CONTENT_TYPES = setOf(
            "image/jpeg",
            "image/png",
            "image/webp",
            "image/heic",
            "image/heif"
        )

        private val HEIC_CONTENT_TYPES = setOf("image/heic", "image/heif")
    }

    fun uploadPhoto(tripId: UUID, file: MultipartFile): Photo {
        val trip = tripService.getTrip(tripId)

        val contentType = file.contentType
            ?: throw IllegalArgumentException("File content type is required")
        if (contentType !in ALLOWED_CONTENT_TYPES) {
            throw IllegalArgumentException("Unsupported file type: $contentType. Allowed: $ALLOWED_CONTENT_TYPES")
        }

        val photoId = UUID.randomUUID()
        val isHeic = contentType in HEIC_CONTENT_TYPES

        // Convert HEIC to JPEG for browser/PDF compatibility; other formats stored as-is (EXIF preserved)
        val (storageBytes, storedContentType, extension) = if (isHeic) {
            val converted = convertToJpeg(file)
            Triple(converted, "image/jpeg", "jpg")
        } else {
            val ext = file.originalFilename?.substringAfterLast('.', "jpg") ?: "jpg"
            Triple(file.bytes, contentType, ext)
        }

        val storageKey = "$tripId/$photoId.$extension"
        storageService.store(storageKey, ByteArrayInputStream(storageBytes), storedContentType)

        val metadata = extractMetadata(storageBytes)

        val photo = Photo(
            trip = trip,
            storageKey = storageKey,
            originalFilename = file.originalFilename ?: "unknown",
            contentType = storedContentType,
            fileSize = storageBytes.size.toLong(),
            metadata = metadata
        )

        val saved = photoRepository.save(photo)
        logger.info("Uploaded photo: {} for trip: {}", saved.id, tripId)

        if (trip.status == TripStatus.CREATED) {
            tripService.updateStatus(tripId, TripStatus.UPLOADING_PHOTOS)
        }

        return saved
    }

    @Transactional(readOnly = true)
    fun getPhotosForTrip(tripId: UUID): List<Photo> {
        tripService.getTrip(tripId)
        return photoRepository.findByTripId(tripId)
    }

    @Transactional(readOnly = true)
    fun getPhoto(id: UUID): Photo {
        return photoRepository.findById(id)
            .orElseThrow { PhotoNotFoundException(id) }
    }

    fun rotatePhoto(photoId: UUID, degrees: Int): Photo {
        require(degrees in listOf(0, 90, 180, 270)) { "Rotation must be 0, 90, 180, or 270" }
        val photo = photoRepository.findById(photoId)
            .orElseThrow { PhotoNotFoundException(photoId) }
        photo.rotation = (photo.rotation + degrees) % 360
        val saved = photoRepository.save(photo)
        logger.info("Rotated photo {} to {}°", photoId, saved.rotation)
        return saved
    }

    fun deletePhoto(photoId: UUID) {
        val photo = photoRepository.findById(photoId)
            .orElseThrow { PhotoNotFoundException(photoId) }
        storageService.delete(photo.storageKey)
        photoRepository.delete(photo)
        logger.info("Deleted photo: {}", photoId)
    }

    private fun convertToJpeg(file: MultipartFile): ByteArray {
        val heicTemp = Files.createTempFile("heic-", ".heic")
        val jpegTemp = Files.createTempFile("conv-", ".jpg")
        try {
            Files.write(heicTemp, file.bytes)

            val process = ProcessBuilder("sips", "-s", "format", "jpeg", heicTemp.toString(), "--out", jpegTemp.toString())
                .redirectErrorStream(true)
                .start()
            val completed = process.waitFor(30, TimeUnit.SECONDS)
            if (!completed || process.exitValue() != 0) {
                val output = process.inputStream.bufferedReader().readText()
                throw IllegalStateException("HEIC conversion failed: $output")
            }

            val jpegBytes = Files.readAllBytes(jpegTemp)
            logger.info("Converted HEIC to JPEG: {} ({}KB → {}KB)",
                file.originalFilename, file.size / 1024, jpegBytes.size / 1024)
            return jpegBytes
        } finally {
            Files.deleteIfExists(heicTemp)
            Files.deleteIfExists(jpegTemp)
        }
    }

    private fun extractMetadata(imageBytes: ByteArray): PhotoMetadata {
        var width = 0
        var height = 0
        var takenAt: java.time.Instant? = null

        try {
            val image = ImageIO.read(ByteArrayInputStream(imageBytes))
            if (image != null) {
                width = image.width
                height = image.height
            }
        } catch (e: Exception) {
            logger.warn("Failed to read image dimensions: {}", e.message)
        }

        try {
            val exifMeta = ImageMetadataReader.readMetadata(ByteArrayInputStream(imageBytes))
            val exif = exifMeta.getFirstDirectoryOfType(ExifSubIFDDirectory::class.java)
            val date = exif?.getDate(ExifSubIFDDirectory.TAG_DATETIME_ORIGINAL)
            if (date != null) {
                takenAt = date.toInstant()
                logger.info("Extracted EXIF takenAt: {}", takenAt)
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract EXIF data: {}", e.message)
        }

        return PhotoMetadata(width = width, height = height, takenAt = takenAt)
    }
}
