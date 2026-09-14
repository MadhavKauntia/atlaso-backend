package com.atlaso.service

import com.atlaso.controller.dto.ConfirmUploadRequest
import com.atlaso.controller.dto.InitiateUploadRequest
import com.atlaso.controller.dto.InitiateUploadResponse
import com.atlaso.domain.photo.GeoLocation
import com.atlaso.domain.photo.Photo
import com.atlaso.domain.photo.PhotoMetadata
import com.atlaso.domain.photo.UploadGrant
import com.atlaso.domain.trip.TripStatus
import com.atlaso.repository.PhotoRepository
import com.atlaso.repository.UploadGrantRepository
import com.drew.imaging.ImageMetadataReader
import com.drew.metadata.exif.ExifSubIFDDirectory
import com.drew.metadata.exif.GpsDirectory
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.multipart.MultipartFile
import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class PhotoNotFoundException(id: UUID) : RuntimeException("Photo not found: $id")

@Service
@Transactional
class PhotoUploadService(
    private val photoRepository: PhotoRepository,
    private val storageService: StorageService,
    private val tripService: TripService,
    private val uploadGrantRepository: UploadGrantRepository
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

        /** Caps that protect storage and (mainly) per-book vision-analysis cost. */
        const val MAX_PHOTOS_PER_TRIP = 1000
        const val MAX_FILE_SIZE_BYTES = 50L * 1024 * 1024 // 50 MB
        const val MAX_IMAGE_DIMENSION = 30000 // sanity cap on client-declared width/height
    }

    fun uploadPhoto(tripId: UUID, file: MultipartFile, userId: UUID): Photo {
        val trip = tripService.getTrip(tripId, userId)

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
    fun getPhotosForTrip(tripId: UUID, userId: UUID? = null, guestToken: String? = null): List<Photo> {
        tripService.assertTripAccess(tripId, userId, guestToken)
        return photoRepository.findByTripId(tripId)
    }

    @Transactional(readOnly = true)
    fun getPhoto(photoId: UUID, tripId: UUID, userId: UUID): Photo {
        tripService.getTrip(tripId, userId)
        return photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
    }

    @Transactional(readOnly = true)
    fun getPhotoByTripAndId(photoId: UUID, tripId: UUID, userId: UUID? = null, guestToken: String? = null): Photo {
        tripService.assertTripAccess(tripId, userId, guestToken)
        return photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
    }

    fun rotatePhoto(photoId: UUID, degrees: Int, tripId: UUID, userId: UUID): Photo {
        require(degrees in listOf(0, 90, 180, 270)) { "Rotation must be 0, 90, 180, or 270" }
        tripService.getTrip(tripId, userId)
        val photo = photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
        photo.rotation = (photo.rotation + degrees) % 360
        val saved = photoRepository.save(photo)
        logger.info("Rotated photo {} to {}°", photoId, saved.rotation)
        return saved
    }

    fun initiateUploads(tripId: UUID, requests: List<InitiateUploadRequest>, userId: UUID? = null, guestToken: String? = null): List<InitiateUploadResponse> {
        tripService.assertTripAccess(tripId, userId, guestToken) // owner JWT (claimed) or guest token
        val existing = photoRepository.countByTripId(tripId)
        if (existing + requests.size > MAX_PHOTOS_PER_TRIP) {
            throw IllegalArgumentException("A book can hold at most $MAX_PHOTOS_PER_TRIP photos.")
        }
        return requests.map { req ->
            if (req.contentType !in ALLOWED_CONTENT_TYPES) {
                throw IllegalArgumentException("Unsupported file type: ${req.contentType}")
            }
            require(req.fileSize in 1..MAX_FILE_SIZE_BYTES) {
                "Each photo must be between 1 byte and ${MAX_FILE_SIZE_BYTES / (1024 * 1024)} MB."
            }
            req.thumbnailFileSize?.let { require(it in 1..MAX_FILE_SIZE_BYTES) { "Invalid thumbnail size" } }
            val ext = when (req.contentType) {
                "image/jpeg" -> "jpg"
                "image/png" -> "png"
                "image/webp" -> "webp"
                "image/heic" -> "heic"
                "image/heif" -> "heif"
                else -> req.filename.substringAfterLast('.', "jpg")
            }
            val photoId = UUID.randomUUID()
            val storageKey = "$tripId/$photoId.$ext"
            // Bind Content-Length into the presigned PUT so a stolen URL can't be
            // used to upload an object larger than the declared (capped) size.
            val uploadUrl = storageService.getUploadUrl(storageKey, req.contentType, req.fileSize)
            // If the client generated a thumbnail, also hand back a presigned PUT
            // for it (always JPEG, Content-Length bound to the declared size).
            val (thumbKey, thumbUploadUrl) = req.thumbnailFileSize?.let { size ->
                val key = "$tripId/${photoId}_thumb.jpg"
                key to storageService.getUploadUrl(key, "image/jpeg", size)
            } ?: (null to null)
            // Record the grant so confirm can only register a key we handed out, exactly once.
            uploadGrantRepository.save(
                UploadGrant(
                    tripId = tripId,
                    photoId = photoId,
                    storageKey = storageKey,
                    thumbnailKey = thumbKey,
                    contentType = req.contentType,
                    maxSizeBytes = req.fileSize,
                )
            )
            InitiateUploadResponse(
                photoId = photoId,
                storageKey = storageKey,
                uploadUrl = uploadUrl,
                thumbnailStorageKey = thumbKey,
                thumbnailUploadUrl = thumbUploadUrl
            )
        }
    }

    fun confirmUploads(tripId: UUID, confirmations: List<ConfirmUploadRequest>, userId: UUID? = null, guestToken: String? = null): List<Photo> {
        tripService.assertTripAccess(tripId, userId, guestToken) // owner JWT (claimed) or guest token
        val trip = tripService.getTrip(tripId)
        val existing = photoRepository.countByTripId(tripId)
        if (existing + confirmations.size > MAX_PHOTOS_PER_TRIP) {
            throw IllegalArgumentException("A book can hold at most $MAX_PHOTOS_PER_TRIP photos.")
        }
        val photos = confirmations.map { conf ->
            // Bind to the server-recorded initiation: only a key we handed out for THIS photo
            // on THIS trip can be confirmed, once, and the object must actually exist.
            val grant = uploadGrantRepository.findByPhotoIdAndTripId(conf.photoId, tripId)
                ?: throw IllegalArgumentException("No upload was initiated for this photo")
            require(!grant.consumed) { "This upload was already confirmed" }
            require(grant.storageKey == conf.storageKey) { "Storage key does not match the initiated upload" }
            require(grant.thumbnailKey == conf.thumbnailStorageKey) { "Thumbnail key does not match the initiated upload" }
            require(storageService.exists(grant.storageKey)) { "Uploaded object not found" }
            grant.thumbnailKey?.let { require(storageService.exists(it)) { "Thumbnail object not found" } }
            grant.consumed = true
            uploadGrantRepository.save(grant)

            val takenAt = conf.takenAt?.let { Instant.ofEpochMilli(it) }
            Photo(
                trip = trip,
                storageKey = grant.storageKey,          // server-recorded key, not the client's
                thumbnailKey = grant.thumbnailKey,
                originalFilename = conf.originalFilename,
                contentType = grant.contentType,        // server-recorded content type
                fileSize = grant.maxSizeBytes,          // presigned PUT bound Content-Length to this
                metadata = PhotoMetadata(
                    width = conf.width.coerceIn(0, MAX_IMAGE_DIMENSION),
                    height = conf.height.coerceIn(0, MAX_IMAGE_DIMENSION),
                    takenAt = takenAt,
                    location = if (conf.latitude != null && conf.longitude != null)
                        GeoLocation(conf.latitude, conf.longitude) else null,
                    sharpness = conf.sharpness
                )
            )
        }
        val saved = photoRepository.saveAll(photos)
        logger.info("Confirmed {} uploads for trip {}", saved.size, tripId)
        if (trip.status == TripStatus.CREATED) {
            tripService.updateStatus(tripId, TripStatus.UPLOADING_PHOTOS)
        }
        return saved
    }

    fun deletePhoto(photoId: UUID, tripId: UUID, userId: UUID) {
        tripService.getTrip(tripId, userId)
        val photo = photoRepository.findByIdAndTripId(photoId, tripId)
            .orElseThrow { PhotoNotFoundException(photoId) }
        storageService.delete(photo.storageKey)
        photo.thumbnailKey?.let { runCatching { storageService.delete(it) } }
        photoRepository.delete(photo)
        logger.info("Deleted photo: {}", photoId)
    }

    private fun convertToJpeg(file: MultipartFile): ByteArray {
        val heicTemp = Files.createTempFile("heic-", ".heic")
        val jpegTemp = Files.createTempFile("conv-", ".jpg")
        try {
            Files.write(heicTemp, file.bytes)

            val isMac = System.getProperty("os.name").lowercase().contains("mac")
            val cmd = if (isMac)
                listOf("sips", "-s", "format", "jpeg", heicTemp.toString(), "--out", jpegTemp.toString())
            else
                listOf("convert", heicTemp.toString(), jpegTemp.toString())
            val process = ProcessBuilder(cmd)
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
        var location: GeoLocation? = null

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
            val gps = exifMeta.getFirstDirectoryOfType(GpsDirectory::class.java)
            val rawGeo = gps?.geoLocation
            if (rawGeo != null) {
                location = GeoLocation(rawGeo.latitude, rawGeo.longitude)
                // Don't log exact coordinates — that's user PII flowing to centralized logs.
                logger.debug("Extracted GPS location for photo")
            }
        } catch (e: Exception) {
            logger.warn("Failed to extract EXIF data: {}", e.message)
        }

        return PhotoMetadata(width = width, height = height, takenAt = takenAt, location = location)
    }
}
